import UIKit
import WebKit

/// The one screen of the app: the PC's web chat in a browser, a microphone
/// button, and a gear that holds the address and the wake word. The page is
/// the page the phone's browser opens too, so the chat is the same chat.
final class ChatViewController: UIViewController, WKNavigationDelegate {
    private let webView = WKWebView()
    private let voice = Voice()
    private var address = ""

    private let bar = UIView()
    private let title = UILabel()
    private let gear = UIButton(type: .system)
    private let mic = UIButton(type: .system)
    private let status = UILabel()

    // the small sheet under the gear: the address and the wake word
    private var sheet: UIView?
    private var field: UITextField?
    private var wakeSwitch: UISwitch?

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        buildBar()
        buildWebView()
        buildMic()
        buildStatus()
        wireVoice()

        address = UserDefaults.standard.string(forKey: "pc.address") ?? ""
        if address.isEmpty { openSheet() }
        else { load() }
    }

    // ------------------------------------------------------------ building

    private func buildBar() {
        bar.backgroundColor = UIColor(white: 0.07, alpha: 1)
        bar.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(bar)

        title.text = "Termant"
        title.font = .systemFont(ofSize: 17, weight: .semibold)
        title.textColor = .white
        title.translatesAutoresizingMaskIntoConstraints = false

        gear.setTitle("⚙︎", for: .normal)
        gear.tintColor = .white
        gear.translatesAutoresizingMaskIntoConstraints = false
        gear.addTarget(self, action: #selector(openSheet), for: .touchUpInside)

        [title, gear].forEach { bar.addSubview($0) }
        NSLayoutConstraint.activate([
            bar.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            bar.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            bar.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            bar.heightAnchor.constraint(equalToConstant: 48),
            title.leadingAnchor.constraint(equalTo: bar.leadingAnchor, constant: 16),
            title.centerYAnchor.constraint(equalTo: bar.centerYAnchor),
            gear.trailingAnchor.constraint(equalTo: bar.trailingAnchor, constant: -12),
            gear.centerYAnchor.constraint(equalTo: bar.centerYAnchor),
            gear.widthAnchor.constraint(equalToConstant: 30),
            gear.heightAnchor.constraint(equalToConstant: 30),
        ])
    }

    private func buildWebView() {
        webView.navigationDelegate = self
        webView.allowsBackForwardNavigationGestures = false
        webView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(webView)
        NSLayoutConstraint.activate([
            webView.topAnchor.constraint(equalTo: bar.bottomAnchor),
            webView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            webView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            webView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
    }

    private func buildMic() {
        mic.setTitle("🎤", for: .normal)
        mic.titleLabel?.font = .systemFont(ofSize: 24)
        mic.backgroundColor = UIColor(white: 0.16, alpha: 1)
        mic.layer.cornerRadius = 28
        mic.translatesAutoresizingMaskIntoConstraints = false
        mic.addTarget(self, action: #selector(pressMic), for: .touchUpInside)
        view.addSubview(mic)
        NSLayoutConstraint.activate([
            mic.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -18),
            mic.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -18),
            mic.widthAnchor.constraint(equalToConstant: 56),
            mic.heightAnchor.constraint(equalToConstant: 56),
        ])
    }

    private func buildStatus() {
        status.font = .systemFont(ofSize: 13)
        status.textColor = .white
        status.backgroundColor = UIColor(white: 0.16, alpha: 0.95)
        status.textAlignment = .center
        status.layer.cornerRadius = 14
        status.numberOfLines = 2
        status.adjustsFontSizeToFitWidth = true
        status.isHidden = true
        status.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(status)
        NSLayoutConstraint.activate([
            status.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            status.bottomAnchor.constraint(equalTo: mic.topAnchor, constant: -14),
            status.leadingAnchor.constraint(greaterThanOrEqualTo: view.leadingAnchor, constant: 20),
            status.trailingAnchor.constraint(lessThanOrEqualTo: view.trailingAnchor, constant: -20),
        ])
    }

    private func wireVoice() {
        voice.onMark = { [weak self] mark in
            guard let self else { return }
            switch mark {
            case .idle: self.say("")
            case .listening: self.say(self.voice.wakeOn
                ? "Listening — say “hey Termant”"
                : "Listening — say your command")
            case .armed: self.say("“hey Termant” heard — now the command")
            }
        }
        voice.onCommand = { [weak self] text in self?.send(text) }
        voice.onProblem = { [weak self] text in self?.say(text) }
    }

    // ------------------------------------------------------------ the page

    private func load() {
        addressLabelIs(address)
        guard let url = URL(string: address) else {
            say("That address is not right")
            return
        }
        webView.load(URLRequest(url: url))
    }

    private func addressLabelIs(_ a: String) {
        title.text = "Termant" + (a.isEmpty ? "" : " · " + a)
    }

    // ------------------------------------------------------------ the gear

    @objc private func openSheet() {
        guard sheet == nil else { return }
        let s = UIView()
        s.backgroundColor = UIColor(white: 0.1, alpha: 1)
        s.layer.cornerRadius = 14
        s.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(s)
        sheet = s

        let head = UILabel()
        head.text = "The PC this phone talks to"
        head.font = .systemFont(ofSize: 15, weight: .semibold)
        head.textColor = .white
        head.translatesAutoresizingMaskIntoConstraints = false

        let f = UITextField()
        f.placeholder = "https://192.168.1.20:3000"
        f.text = address
        f.autocapitalizationType = .none
        f.autocorrectionType = .no
        f.keyboardType = .URL
        f.backgroundColor = UIColor(white: 0.18, alpha: 1)
        f.textColor = .white
        f.layer.cornerRadius = 10
        f.leftView = UIView(frame: CGRect(x: 0, y: 0, width: 10, height: 1))
        f.leftViewMode = .always
        f.translatesAutoresizingMaskIntoConstraints = false
        field = f

        let w = UILabel()
        w.text = "Listen for “hey Termant”"
        w.font = .systemFont(ofSize: 14)
        w.textColor = .white
        w.translatesAutoresizingMaskIntoConstraints = false

        let sw = UISwitch()
        sw.onTintColor = .systemGreen
        sw.setOn(UserDefaults.standard.bool(forKey: "wake.on"), animated: false)
        sw.translatesAutoresizingMaskIntoConstraints = false
        wakeSwitch = sw
        sw.addTarget(self, action: #selector(wakeChanged), for: .valueChanged)

        let done = UIButton(type: .system)
        done.setTitle("Open", for: .normal)
        done.titleLabel?.font = .systemFont(ofSize: 16, weight: .semibold)
        done.translatesAutoresizingMaskIntoConstraints = false
        done.addTarget(self, action: #selector(closeSheet), for: .touchUpInside)

        [head, f, w, sw, done].forEach { s.addSubview($0) }
        NSLayoutConstraint.activate([
            s.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 20),
            s.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -20),
            s.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -24),
            head.topAnchor.constraint(equalTo: s.topAnchor, constant: 18),
            head.leadingAnchor.constraint(equalTo: s.leadingAnchor, constant: 16),
            f.topAnchor.constraint(equalTo: head.bottomAnchor, constant: 14),
            f.leadingAnchor.constraint(equalTo: s.leadingAnchor, constant: 16),
            f.trailingAnchor.constraint(equalTo: s.trailingAnchor, constant: -16),
            f.heightAnchor.constraint(equalToConstant: 44),
            w.topAnchor.constraint(equalTo: f.bottomAnchor, constant: 18),
            w.leadingAnchor.constraint(equalTo: s.leadingAnchor, constant: 16),
            sw.centerYAnchor.constraint(equalTo: w.centerYAnchor),
            sw.trailingAnchor.constraint(equalTo: s.trailingAnchor, constant: -16),
            done.topAnchor.constraint(equalTo: w.bottomAnchor, constant: 18),
            done.centerXAnchor.constraint(equalTo: s.centerXAnchor),
            done.bottomAnchor.constraint(equalTo: s.bottomAnchor, constant: -16),
        ])
    }

    @objc private func wakeChanged() {
        voice.setWake(wakeSwitch?.isOn ?? false)
    }

    @objc private func closeSheet() {
        var a = (field?.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if !a.hasPrefix("http") { a = "https://" + a }
        while a.hasSuffix("/") { a.removeLast() }
        address = a
        UserDefaults.standard.set(address, forKey: "pc.address")
        UserDefaults.standard.set(wakeSwitch?.isOn ?? false, forKey: "wake.on")
        sheet?.removeFromSuperview()
        sheet = nil; field = nil; wakeSwitch = nil
        if address.isEmpty { say("Put the address — it is in Termant on the PC: Settings → Web chat") }
        else { load() }
    }

    // ------------------------------------------------------------ hearing

    @objc private func pressMic() {
        voice.requestAccess { [weak self] yes in
            guard let self else { return }
            if yes { self.voice.hearOnce() }
            else { self.say("Allow the microphone (and speech) in Settings, then try again") }
        }
    }

    private func send(_ text: String) {
        say("Sent: " + text)
        guard let url = URL(string: address + "/api/message") else { return }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try? JSONSerialization.data(withJSONObject: ["text": text], options: [])
        // the same door the page logged in with
        webView.configuration.websiteDataStore.httpCookieStore.getAllCookies { [weak self] cookies in
            guard let self else { return }
            if let door = cookies.first(where: { $0.name == "termant" }) {
                req.setValue("termant=" + door.value, forHTTPHeaderField: "Cookie")
            }
            URLSession.shared.dataTask(with: req) { data, resp, err in
                DispatchQueue.main.async {
                    if let err = err {
                        self.say("The PC did not answer: " + err.localizedDescription)
                    } else {
                        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
                        if code != 200 {
                            self.say("The door said no (" + String(code) + "): log in on the page first")
                        }
                    }
                }
            }.resume()
        }
    }

    private func say(_ text: String) {
        if text.isEmpty { status.isHidden = true; return }
        status.isHidden = false
        status.text = text
        DispatchQueue.main.asyncAfter(deadline: .now() + 4) { [weak self] in
            self?.status.isHidden = true
        }
    }

    // ------------------------------------------------------------ the page's problems

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        say("The PC did not answer: " + error.localizedDescription)
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!,
                 withError error: Error) {
        say("Could not open the PC: " + error.localizedDescription)
    }

    // The certificate is made by the PC's Termant itself (see the web chat):
    // over a home network that is the door, so it is accepted.
    func webView(_ webView: WKWebView, didReceive challenge: URLAuthenticationChallenge,
                 completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        let sp = challenge.protectionSpace
        if sp.authenticationMethod == NSURLAuthenticationMethodServerTrust,
           let trust = sp.serverTrust {
            completionHandler(.useCredential, URLCredential(trust: trust))
        } else {
            completionHandler(.performDefaultHandling, nil)
        }
    }
}
