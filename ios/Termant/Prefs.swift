import Foundation
import Security
import SwiftUI

/// Where the app keeps its files.
enum Files {
    static var support: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base
    }

    static var chats: URL {
        let dir = support.appendingPathComponent("chats", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }
}

/// Keys and passwords: in the Keychain, or in the app's own data if the Keychain refuses.
enum Secrets {
    private static let service = "com.termant.app"

    private static func query(_ name: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: name]
    }

    static func get(_ name: String) -> String {
        var q = query(name)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        if SecItemCopyMatching(q as CFDictionary, &item) == errSecSuccess,
           let data = item as? Data, let text = String(data: data, encoding: .utf8) {
            return text
        }
        return UserDefaults.standard.string(forKey: "secret_" + name) ?? ""
    }

    /// Returns false when the Keychain refused and the value went to the app's own data instead.
    @discardableResult
    static func set(_ name: String, _ value: String) -> Bool {
        SecItemDelete(query(name) as CFDictionary)
        UserDefaults.standard.removeObject(forKey: "secret_" + name)
        if value.isEmpty { return true }
        var add = query(name)
        add[kSecValueData as String] = Data(value.utf8)
        // readable while the phone is locked, so an answer typed into a notification can be sent
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        if SecItemAdd(add as CFDictionary, nil) == errSecSuccess { return true }
        UserDefaults.standard.set(value, forKey: "secret_" + name)
        return false
    }

    static func inKeychain(_ name: String) -> Bool {
        UserDefaults.standard.string(forKey: "secret_" + name) == nil
    }
}

/// Start-up work that must be done before anything is read.
enum Bootstrap {
    static let environment = ProcessInfo.processInfo.environment
    /// The UI test starts the app with nothing left over from before.
    static let testing = environment["TERMANT_UITEST"] == "1"
    /// True in the unit tests too, which run inside the app.
    static let underTest = testing || environment["XCTestConfigurationFilePath"] != nil
    /// A test that wants the real question about notifications says so.
    static let askForNotifications = !underTest || environment["TERMANT_TEST_NOTIFICATIONS"] == "1"

    static let once: Void = {
        guard testing else { return }
        if let id = Bundle.main.bundleIdentifier { UserDefaults.standard.removePersistentDomain(forName: id) }
        for p in Provider.all { Secrets.set("key_" + p.id, "") }
        Secrets.set("remote_password", "")
        try? FileManager.default.removeItem(at: Files.chats)
        if let lang = environment["TERMANT_LANG"] { UserDefaults.standard.set(lang, forKey: "lang") }
    }()
}

/// Everything chosen in Settings.
@MainActor
final class Prefs: ObservableObject {
    static let shared = Prefs()
    private let d = UserDefaults.standard

    @Published var language: String { didSet { d.set(language, forKey: "lang"); L.lang = language } }
    @Published var theme: String { didSet { d.set(theme, forKey: "theme") } }
    @Published var provider: String { didSet { d.set(provider, forKey: "provider"); model = d.string(forKey: "model_" + provider) ?? "" } }
    /// Only for the "custom" service.
    @Published var customBase: String { didSet { d.set(customBase, forKey: "custom_base") } }
    /// The model of the chosen service; empty means "choose one for me".
    @Published var model: String { didSet { d.set(model, forKey: "model_" + provider) } }
    @Published var autoSwitch: Bool { didSet { d.set(autoSwitch, forKey: "auto_switch") } }
    @Published var careOn: Bool { didSet { d.set(careOn, forKey: "care_on") } }
    /// "09:00 21:00"
    @Published var careTimes: String { didSet { d.set(careTimes, forKey: "care_times") } }
    @Published var learnOn: Bool { didSet { d.set(learnOn, forKey: "learn_on") } }
    @Published var remoteURL: String { didSet { d.set(remoteURL, forKey: "remote_url") } }
    /// Counts up when a key changes, so screens showing it are drawn again.
    @Published var keysChanged = 0

    private init() {
        _ = Bootstrap.once
        let d = UserDefaults.standard
        let lang = d.string(forKey: "lang") ?? "hi"
        language = lang == "en" ? "en" : "hi"
        theme = d.string(forKey: "theme") == "light" ? "light" : "dark"
        let p = d.string(forKey: "provider") ?? Provider.all[0].id
        provider = p
        customBase = d.string(forKey: "custom_base") ?? ""
        model = d.string(forKey: "model_" + p) ?? ""
        autoSwitch = d.object(forKey: "auto_switch") as? Bool ?? true
        careOn = d.object(forKey: "care_on") as? Bool ?? true
        careTimes = d.string(forKey: "care_times") ?? "09:00 21:00"
        learnOn = d.object(forKey: "learn_on") as? Bool ?? true
        remoteURL = d.string(forKey: "remote_url") ?? ""
        L.lang = language
    }

    var base: String {
        provider == Provider.custom ? customBase : Provider.named(provider).base
    }

    var key: String {
        Secrets.get("key_" + provider)
    }

    func setKey(_ value: String) {
        Secrets.set("key_" + provider, value.trimmingCharacters(in: .whitespacesAndNewlines))
        cachedModels = []
        keysChanged += 1
    }

    /// The service can be asked: it has an address and, unless it is one's own, a key.
    var ready: Bool {
        !base.trimmingCharacters(in: .whitespaces).isEmpty && (!key.isEmpty || provider == Provider.custom)
    }

    var keyShown: String {
        let k = key
        if k.isEmpty { return L.t("key_none") }
        return L.t("key_saved", "•••• " + (k.count > 8 ? String(k.suffix(4)) : ""))
    }

    var remotePassword: String {
        get { Secrets.get("remote_password") }
        set { Secrets.set("remote_password", newValue); keysChanged += 1 }
    }

    /// The models the chosen service offered when it was last asked.
    var cachedModels: [String] {
        get { d.stringArray(forKey: "models_" + provider) ?? [] }
        set {
            d.set(newValue, forKey: "models_" + provider)
            d.set(Date().timeIntervalSince1970, forKey: "models_at_" + provider)
        }
    }

    var cachedModelsAreOld: Bool {
        Date().timeIntervalSince1970 - d.double(forKey: "models_at_" + provider) > 24 * 3600
    }

    /// When the user last wrote to the agent.
    var lastWrote: Date {
        get { Date(timeIntervalSince1970: d.double(forKey: "last_wrote")) }
        set { d.set(newValue.timeIntervalSince1970, forKey: "last_wrote") }
    }

    var hasWritten: Bool {
        d.double(forKey: "last_wrote") > 0
    }
}

/// Short sentences the agent keeps about the user, for every chat.
struct Note: Codable, Equatable, Identifiable {
    var id = UUID()
    var date = Date()
    var text: String
}

@MainActor
final class Memory: ObservableObject {
    static let shared = Memory()
    static let maxNotes = 40, maxLength = 240

    @Published private(set) var notes: [Note] = []

    enum Outcome: Equatable {
        case saved, known
        case refused(String)
    }

    private init() {
        _ = Bootstrap.once
        if let data = UserDefaults.standard.data(forKey: "notes"),
           let saved = try? JSONDecoder().decode([Note].self, from: data) {
            notes = saved
        }
    }

    private func save() {
        if let data = try? JSONEncoder().encode(notes) { UserDefaults.standard.set(data, forKey: "notes") }
    }

    func add(_ raw: String, secret: String = "") -> Outcome {
        let text = raw.replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespaces)
        if !Prefs.shared.learnOn { return .refused("learning is turned off in Settings") }
        if text.isEmpty { return .refused("nothing to remember") }
        if text.count > Memory.maxLength { return .refused("too long (at most \(Memory.maxLength) letters)") }
        if secret.count >= 8 && text.contains(secret) { return .refused("that holds a key") }
        if notes.contains(where: { $0.text.lowercased() == text.lowercased() }) { return .known }
        if notes.count >= Memory.maxNotes { return .refused("\(Memory.maxNotes) notes already; one has to go first") }
        notes.append(Note(text: text))
        save()
        return .saved
    }

    /// Removes the note with this number (counted from 1). False if there is none.
    @discardableResult
    func forget(number: Int) -> Bool {
        guard number >= 1 && number <= notes.count else { return false }
        notes.remove(at: number - 1)
        save()
        return true
    }

    func removeAll() {
        notes = []
        save()
    }
}
