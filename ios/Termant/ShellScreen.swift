import SwiftUI
import WebKit

/// The terminal of the Linux inside the app: a page with xterm.js (Web/terminal.html) for the
/// screen and the keys, and one shell behind it. It lives as long as the app, so that closing
/// the screen does not end what runs in the shell.
@MainActor
final class ShellModel: NSObject, ObservableObject, WKNavigationDelegate {
    static let shared = ShellModel()

    enum Phase: Equatable {
        case starting, running, ended
        case failed(String)
    }

    @Published private(set) var phase: Phase = .starting
    /// The end of what the shell printed, as plain text. Kept for the tests only.
    @Published private(set) var tail = ""
    let web: WKWebView

    private var session: ShellSession?
    private var pageReady = false
    private var linuxReady = false
    private var columns = 80, rows = 24
    private var look = ""

    /// Hands the page's messages on without keeping the model alive.
    @MainActor
    private final class Ear: NSObject, WKScriptMessageHandler {
        weak var model: ShellModel?

        func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
            guard let body = message.body as? [String: Any] else { return }
            model?.heard(body)
        }
    }

    private override init() {
        let prefs = Prefs.shared
        let config = WKWebViewConfiguration()
        let ear = Ear()
        config.userContentController.add(ear, name: "termant")
        let start = "window.TERMANT_LOOK = { theme: '\(prefs.theme == "light" ? "light" : "dark")', font: \(prefs.shellFont) };"
        config.userContentController.addUserScript(WKUserScript(source: start, injectionTime: .atDocumentStart, forMainFrameOnly: true))
        web = WKWebView(frame: .zero, configuration: config)
        web.isOpaque = false
        web.scrollView.bounces = false
        web.scrollView.isScrollEnabled = false
        web.scrollView.contentInsetAdjustmentBehavior = .never
        web.accessibilityIdentifier = "shell.web"
        super.init()
        ear.model = self
        look = lookNow
        web.navigationDelegate = self
        paintBehind()
        if let page = Bundle.main.url(forResource: "terminal", withExtension: "html") {
            web.loadFileURL(page, allowingReadAccessTo: page.deletingLastPathComponent())
        } else {
            phase = .failed("terminal.html is missing from the app")
        }
        Task {
            if await Shell.shared.ready() {
                linuxReady = true
                begin()
            } else if case .failed(let why) = Shell.shared.state {
                phase = .failed(why)
            }
        }
    }

    private var lookNow: String {
        "'\(Prefs.shared.theme == "light" ? "light" : "dark")', \(Prefs.shared.shellFont)"
    }

    private func paintBehind() {
        let light = Prefs.shared.theme == "light"
        web.backgroundColor = light ? .white : UIColor(red: 0x0d / 255, green: 0x11 / 255, blue: 0x17 / 255, alpha: 1)
        web.scrollView.backgroundColor = web.backgroundColor
    }

    /// The theme or the size of the letters was changed in Settings.
    func refreshLook() {
        guard look != lookNow else { return }
        look = lookNow
        paintBehind()
        web.evaluateJavaScript("termantLook(\(look))")
    }

    fileprivate func heard(_ message: [String: Any]) {
        switch message["kind"] as? String {
        case "ready", "size":
            columns = max(10, message["columns"] as? Int ?? 80)
            rows = max(4, message["rows"] as? Int ?? 24)
            if message["kind"] as? String == "ready" {
                pageReady = true
                begin()
            } else {
                session?.resize(columns: columns, rows: rows)
            }
        case "typed":
            if let text = message["text"] as? String { session?.type(Data(text.utf8)) }
        case "bytes":
            // one letter for one byte
            if let text = message["text"] as? String { session?.type(Data(text.unicodeScalars.map { UInt8(truncatingIfNeeded: $0.value) })) }
        default:
            break
        }
    }

    /// Opens a shell once the page can show it and Linux runs.
    private func begin() {
        guard pageReady, linuxReady, session == nil else { return }
        let next = ShellSession()
        next.onOutput = { [weak self] data in self?.show(data) }
        next.onEnded = { [weak self, weak next] in
            guard let self = self, let next = next, self.session === next else { return }
            next.close()
            self.session = nil
            self.phase = .ended
        }
        guard next.open(columns: columns, rows: rows) else {
            phase = .failed(L.t("shell_full"))
            return
        }
        session = next
        phase = .running
    }

    private func show(_ data: Data) {
        web.evaluateJavaScript("termantWrite('\(data.base64EncodedString())')")
        if Bootstrap.testing {
            tail = TerminalText.tail(tail + TerminalText.plain(String(decoding: data, as: UTF8.self)), 600)
        }
    }

    /// Ends the shell and starts a new one on an empty screen.
    func restart() {
        session?.close()
        session = nil
        tail = ""
        web.evaluateJavaScript("termantReset()")
        if linuxReady {
            phase = .starting
            begin()
        }
    }

    func paste() {
        guard let text = UIPasteboard.general.string, !text.isEmpty,
              let json = try? JSONSerialization.data(withJSONObject: [text]),
              let list = String(data: json, encoding: .utf8) else { return }
        web.evaluateJavaScript("termantPaste(\(list)[0])")
    }

    func copyAll() {
        web.evaluateJavaScript("termantText()") { result, _ in
            if let text = result as? String { UIPasteboard.general.string = text }
        }
    }

    func focus() {
        web.evaluateJavaScript("termantFocus()")
    }

    /// The page's process was ended by the system (too little memory): load it again; the shell is still there.
    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        pageReady = false
        if let page = Bundle.main.url(forResource: "terminal", withExtension: "html") {
            web.loadFileURL(page, allowingReadAccessTo: page.deletingLastPathComponent())
        }
    }

    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction) async -> WKNavigationActionPolicy {
        navigationAction.request.url?.isFileURL == true ? .allow : .cancel
    }
}

struct ShellView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var prefs: Prefs
    @ObservedObject private var model = ShellModel.shared
    @ObservedObject private var shell = Shell.shared
    /// The user wants the terminal of their own server instead.
    let openServer: () -> Void

    var body: some View {
        let colors = Theme.palette(prefs.theme)
        VStack(spacing: 0) {
            HStack(spacing: 16) {
                Button(L.t("close")) { dismiss() }
                    .accessibilityIdentifier("shell.close")
                Spacer()
                Text("Alpine Linux")
                    .font(.footnote)
                    .foregroundColor(colors.muted)
                    .lineLimit(1)
                Spacer()
                Button { model.paste() } label: { Image(systemName: "doc.on.clipboard") }
                    .accessibilityLabel(L.t("shell_paste"))
                    .accessibilityIdentifier("shell.paste")
                Button(action: openServer) { Image(systemName: "server.rack") }
                    .accessibilityLabel(L.t("terminal_server"))
                    .accessibilityIdentifier("shell.server")
                Menu {
                    Button { model.copyAll() } label: { Label(L.t("shell_copy"), systemImage: "doc.on.doc") }
                    Button { prefs.shellFont = min(24, prefs.shellFont + 1) } label: {
                        Label(L.t("shell_bigger"), systemImage: "textformat.size.larger")
                    }
                    Button { prefs.shellFont = max(9, prefs.shellFont - 1) } label: {
                        Label(L.t("shell_smaller"), systemImage: "textformat.size.smaller")
                    }
                    Button(role: .destructive) { model.restart() } label: {
                        Label(L.t("shell_restart"), systemImage: "arrow.counterclockwise")
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
                .accessibilityIdentifier("shell.more")
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(colors.surface)
            content(colors)
            // the same text for the tests, which cannot read the page's letters
            if Bootstrap.testing {
                Text(model.tail.isEmpty ? "-" : model.tail)
                    .font(.system(size: 1))
                    .frame(height: 1)
                    .accessibilityIdentifier("shell.tail")
            }
        }
        .background(colors.bg.ignoresSafeArea())
        .tint(colors.accent)
        .preferredColorScheme(prefs.theme == "light" ? .light : .dark)
        .onAppear { model.refreshLook() }
        .onChange(of: prefs.shellFont) { _ in model.refreshLook() }
        .onChange(of: prefs.theme) { _ in model.refreshLook() }
    }

    @ViewBuilder
    private func content(_ colors: Palette) -> some View {
        switch model.phase {
        case .failed(let why):
            VStack(spacing: 16) {
                Spacer()
                Text(L.t("shell_failed", why))
                    .multilineTextAlignment(.center)
                    .foregroundColor(colors.text)
                    .accessibilityIdentifier("shell.problem")
                Spacer()
            }
            .padding(24)
            .frame(maxWidth: .infinity)
        default:
            ZStack(alignment: .bottom) {
                WebHolder(web: model.web)
                if model.phase == .starting {
                    VStack(spacing: 12) {
                        ProgressView()
                        Text(L.t(shell.state == .preparing ? "shell_preparing" : "shell_starting"))
                            .font(.footnote)
                            .foregroundColor(colors.muted)
                            .multilineTextAlignment(.center)
                    }
                    .padding(24)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(colors.bg)
                    .accessibilityIdentifier("shell.starting")
                }
                if model.phase == .ended {
                    HStack(spacing: 12) {
                        Text(L.t("shell_ended"))
                            .font(.footnote)
                            .foregroundColor(colors.text)
                        Spacer()
                        Button(L.t("shell_again")) { model.restart() }
                            .buttonStyle(.borderedProminent)
                            .accessibilityIdentifier("shell.again")
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(colors.surfaceHigh)
                }
            }
        }
    }
}
