import SwiftUI
import WebKit

/// The Termant web terminal that runs on the user's own computer or server (server.js of this project).
enum Remote {
    /// "192.168.1.5:3000" as http://192.168.1.5:3000/ ; nil when it is no address.
    static func normalize(_ typed: String) -> URL? {
        var text = typed.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty || text.contains(" ") { return nil }
        if !text.contains("://") { text = "http://" + text }
        guard var parts = URLComponents(string: text), let scheme = parts.scheme?.lowercased(),
              scheme == "http" || scheme == "https", let host = parts.host, !host.isEmpty else { return nil }
        parts.scheme = scheme
        parts.query = nil
        parts.fragment = nil
        // the page asks for its files next to itself, so the address has to end like a folder
        if !parts.path.hasSuffix("/") { parts.path += "/" }
        return parts.url
    }

    enum LoginResult: Equatable {
        case cookie(HTTPCookie)
        case wrongPassword
        case failed(String)
    }

    /// Logs in with the password and returns the cookie the server gave.
    static func login(base: URL, password: String) async -> LoginResult {
        guard let url = URL(string: "api/login", relativeTo: base) else { return .failed("bad address") }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 15
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try? JSONSerialization.data(withJSONObject: ["password": password])
        let session = URLSession(configuration: .ephemeral)
        defer { session.finishTasksAndInvalidate() }
        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse else { return .failed("no answer") }
            if http.statusCode == 401 { return .wrongPassword }
            if http.statusCode != 200 { return .failed(LLM.errorMessage(data, status: http.statusCode)) }
            let headers = http.allHeaderFields.reduce(into: [String: String]()) { all, pair in
                if let k = pair.key as? String, let v = pair.value as? String { all[k] = v }
            }
            let cookies = HTTPCookie.cookies(withResponseHeaderFields: headers, for: url.absoluteURL)
            guard let cookie = cookies.first(where: { $0.name == "termant" }) else { return .failed("no cookie") }
            return .cookie(cookie)
        } catch {
            return .failed(error.localizedDescription)
        }
    }
}

/// Keeps one web view for the terminal and says how loading goes.
@MainActor
final class RemoteModel: NSObject, ObservableObject, WKNavigationDelegate {
    @Published var loading = true
    @Published var problem = ""
    let web: WKWebView

    override init() {
        let config = WKWebViewConfiguration()
        config.allowsInlineMediaPlayback = true
        web = WKWebView(frame: .zero, configuration: config)
        web.isOpaque = false
        web.backgroundColor = UIColor(red: 0x30 / 255, green: 0x0a / 255, blue: 0x24 / 255, alpha: 1)
        web.scrollView.backgroundColor = web.backgroundColor
        web.scrollView.bounces = false
        web.scrollView.contentInsetAdjustmentBehavior = .never
        web.accessibilityIdentifier = "remote.web"
        super.init()
        web.navigationDelegate = self
    }

    /// Logs in with the saved password, if there is one, and opens the page.
    func open(address: String, password: String) {
        guard let base = Remote.normalize(address) else {
            loading = false
            problem = L.t("remote_missing")
            return
        }
        loading = true
        problem = ""
        Task {
            if !password.isEmpty, case .cookie(let cookie) = await Remote.login(base: base, password: password) {
                await web.configuration.websiteDataStore.httpCookieStore.setCookie(cookie)
            }
            // a wrong password or no password: the page shows its own login form
            web.load(URLRequest(url: base, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 20))
        }
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        loading = false
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        loading = false
        problem = error.localizedDescription
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        loading = false
        problem = error.localizedDescription
    }

    /// Links in the terminal's output open in Safari, not in place of the terminal.
    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction) async -> WKNavigationActionPolicy {
        if navigationAction.navigationType == .linkActivated, let url = navigationAction.request.url {
            _ = await UIApplication.shared.open(url)
            return .cancel
        }
        return .allow
    }
}

struct WebHolder: UIViewRepresentable {
    let web: WKWebView

    func makeUIView(context: Context) -> WKWebView { web }

    func updateUIView(_ view: WKWebView, context: Context) {}
}

struct RemoteView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var prefs: Prefs
    @StateObject private var model = RemoteModel()

    var body: some View {
        let colors = Theme.palette(prefs.theme)
        VStack(spacing: 0) {
            HStack(spacing: 14) {
                Button(L.t("close")) { dismiss() }
                    .accessibilityIdentifier("remote.close")
                Spacer()
                if model.loading { ProgressView() }
                Text(Remote.normalize(prefs.remoteURL)?.host ?? "")
                    .font(.footnote)
                    .foregroundColor(colors.muted)
                    .lineLimit(1)
                Spacer()
                Button {
                    model.open(address: prefs.remoteURL, password: prefs.remotePassword)
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
                .accessibilityLabel(L.t("reload"))
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(colors.surface)
            if model.problem.isEmpty {
                WebHolder(web: model.web)
            } else {
                VStack(spacing: 16) {
                    Spacer()
                    Text(model.problem)
                        .multilineTextAlignment(.center)
                        .foregroundColor(colors.text)
                        .accessibilityIdentifier("remote.problem")
                    Button(L.t("reload")) { model.open(address: prefs.remoteURL, password: prefs.remotePassword) }
                        .buttonStyle(.borderedProminent)
                    Spacer()
                }
                .padding(24)
                .frame(maxWidth: .infinity)
            }
        }
        .background(colors.bg.ignoresSafeArea())
        .tint(colors.accent)
        .preferredColorScheme(prefs.theme == "light" ? .light : .dark)
        .onAppear { model.open(address: prefs.remoteURL, password: prefs.remotePassword) }
    }
}
