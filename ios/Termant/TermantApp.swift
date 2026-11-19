import SwiftUI
import UserNotifications
import WebKit

final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        _ = Bootstrap.once
        UNUserNotificationCenter.current().delegate = NotificationHandler.shared
        Bootstrap.applyTestSettings()
        Care.registerCategory()
        return true
    }
}

extension Bootstrap {
    /// The UI test hands over the addresses of its stand-in services, which it could only type slowly.
    @MainActor
    static func applyTestSettings() {
        guard testing else { return }
        UNUserNotificationCenter.current().removeAllPendingNotificationRequests()
        UNUserNotificationCenter.current().removeAllDeliveredNotifications()
        WKWebsiteDataStore.default().removeData(ofTypes: WKWebsiteDataStore.allWebsiteDataTypes(),
                                                modifiedSince: .distantPast) {}
        let prefs = Prefs.shared
        if let base = environment["TERMANT_TEST_BASE"] {
            prefs.provider = Provider.custom
            prefs.customBase = base
            prefs.setKey(environment["TERMANT_TEST_KEY"] ?? "")
            prefs.model = environment["TERMANT_TEST_MODEL"] ?? ""
        }
        if let remote = environment["TERMANT_TEST_REMOTE"] {
            prefs.remoteURL = remote
            prefs.remotePassword = environment["TERMANT_TEST_REMOTE_PASSWORD"] ?? ""
        }
        if let times = environment["TERMANT_TEST_CARE_TIMES"] { prefs.careTimes = times }
        if environment["TERMANT_TEST_CARE_OFF"] == "1" { prefs.careOn = false }
    }
}

@main
struct TermantApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @Environment(\.scenePhase) private var phase
    @StateObject private var prefs = Prefs.shared
    @StateObject private var chat = ChatStore.shared
    @StateObject private var care = Care.shared
    @StateObject private var memory = Memory.shared

    var body: some Scene {
        WindowGroup {
            ChatScreen()
                .environmentObject(prefs)
                .environmentObject(chat)
                .environmentObject(care)
                .environmentObject(memory)
                .preferredColorScheme(prefs.theme == "light" ? .light : .dark)
                .tint(Theme.palette(prefs.theme).accent)
                .onAppear { care.sync() }
        }
        .onChange(of: phase) { now in
            if now == .active {
                Care.registerCategory()
                care.sync()
            }
        }
    }
}
