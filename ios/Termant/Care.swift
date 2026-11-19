import Foundation
import UIKit
import UserNotifications

/// The agent looks after the user by itself: every day at the chosen times, and at moments
/// it planned on its own ("ask tomorrow how the exam went").
///
/// An iPhone does not let an app run at a time of its choosing, so the message is written by
/// the model ahead of time, while the app is in use, and handed to iOS as a notification for
/// its moment. When the app is opened again, what arrived meanwhile is put into the chat.
@MainActor
final class Care: ObservableObject {
    static let shared = Care()
    static let category = "care"
    static let replyAction = "care.reply"
    private static let prefix = "care-"

    @Published private(set) var plans: [Plan] = []
    /// When the check-ins of the last days reached the user.
    private var delivered: [Date] = []
    /// False after iOS said the user turned notifications off.
    @Published private(set) var allowed = true
    private var syncing = false
    private var again = false
    private var asked = false

    enum PlanResult: Equatable {
        case planned(Plan)
        case refused(String)
    }

    private init() {
        _ = Bootstrap.once
        let d = UserDefaults.standard
        if let data = d.data(forKey: "care_plans"), let saved = try? JSONDecoder().decode([Plan].self, from: data) {
            plans = saved
        }
        delivered = (d.array(forKey: "care_delivered") as? [Double] ?? []).map { Date(timeIntervalSince1970: $0) }
    }

    private func save() {
        let d = UserDefaults.standard
        if let data = try? JSONEncoder().encode(plans) { d.set(data, forKey: "care_plans") }
        let week = Date().addingTimeInterval(-7 * 86400)
        delivered = delivered.filter { $0 > week }
        d.set(delivered.map { $0.timeIntervalSince1970 }, forKey: "care_delivered")
    }

    var followUps: [Plan] {
        plans.filter { !$0.daily }.sorted { $0.when < $1.when }
    }

    var dailies: [Plan] {
        plans.filter { $0.daily }.sorted { $0.when < $1.when }
    }

    /// Check-ins that reached the user since they last wrote.
    var unanswered: Int {
        let since = Prefs.shared.lastWrote
        return delivered.filter { $0 > since }.count
    }

    private var taken: [Date] {
        plans.map { $0.when } + delivered
    }

    // ------------------------------------------------------------ planning

    /// The agent wants to come back to something. Returns what became of it.
    func plan(when wanted: Date, topic raw: String, now: Date = Date()) -> PlanResult {
        let topic = raw.replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespaces)
        if !Prefs.shared.careOn { return .refused("the user turned check-ins off in Settings") }
        if topic.isEmpty { return .refused("nothing to ask about") }
        if topic.count > CareRules.maxTopic { return .refused("too long") }
        plans.removeAll { !$0.daily && $0.topic?.lowercased() == topic.lowercased() }
        if followUps.count >= CareRules.maxFollowUps { return .refused("\(CareRules.maxFollowUps) are planned already") }
        let when = CareRules.place(wanted, now: now, taken: taken)
        let plan = Plan(id: "f\(Int(now.timeIntervalSince1970 * 1000))-\(plans.count)", when: when, topic: topic)
        plans.append(plan)
        save()
        return .planned(plan)
    }

    func remove(_ id: String) {
        plans.removeAll { $0.id == id }
        save()
        sync()
    }

    /// Keeps as many daily questions planned as may still go unanswered.
    private func planDailies(now: Date) {
        let prefs = Prefs.shared
        let times = prefs.careTimes
        let clock = Set(When.times(times).map { $0.hour * 60 + $0.minute })
        let calendar = Calendar.current
        // ones planned for a time of day that is no longer chosen
        plans.removeAll { p in
            guard p.daily else { return false }
            let c = calendar.dateComponents([.hour, .minute], from: p.when)
            return !clock.contains((c.hour ?? 0) * 60 + (c.minute ?? 0))
        }
        let want = prefs.careOn && prefs.ready ? max(0, CareRules.maxUnanswered - unanswered) : 0
        var have = dailies
        while have.count > want, let last = have.popLast() { plans.removeAll { $0.id == last.id } }
        if have.count < want {
            let after = max(now, have.last?.when ?? now)
            let slots = CareRules.dailySlots(times: times, after: after, count: want - have.count, taken: taken)
            for slot in slots {
                plans.append(Plan(id: "d\(Int(slot.timeIntervalSince1970))", when: slot, topic: nil))
            }
        }
    }

    // ------------------------------------------------------------ delivering

    /// Puts check-ins whose time has come into the chat, and says whether there were any.
    /// "inFront" says that the moment came while the user has the app open: then one does not
    /// break into a conversation that is going on.
    @discardableResult
    func deliverDue(now: Date = Date(), inFront: Bool = false) -> Bool {
        let chat = ChatStore.shared
        guard !chat.busy else { return false } // the chat calls again when the reply is done
        let due = plans.filter { $0.when <= now }.sorted { $0.when < $1.when }
        guard !due.isEmpty else { return false }
        let justWrote = inFront && now.timeIntervalSince(Prefs.shared.lastWrote) < CareRules.justWrote
        var any = false
        for plan in due {
            guard let text = plan.text, !text.isEmpty else { continue } // never written: sync decides
            if justWrote && now.timeIntervalSince(plan.when) < 60 {
                // a planned one waits a little, a daily one is left out
                if plan.daily {
                    plans.removeAll { $0.id == plan.id }
                } else if let i = plans.firstIndex(where: { $0.id == plan.id }) {
                    plans[i].when = now.addingTimeInterval(15 * 60)
                }
                continue
            }
            plans.removeAll { $0.id == plan.id }
            delivered.append(plan.when)
            chat.insertCheckIn(text: text, at: plan.when)
            any = true
        }
        save()
        Care.clearBadge()
        return any
    }

    /// Brings plans, written messages and iOS notifications in line with the settings.
    /// Called when the app comes to the front, after every reply, and when a setting changes.
    func sync() {
        if syncing {
            again = true
            return
        }
        syncing = true
        Task {
            repeat {
                again = false
                await syncOnce()
            } while again
            syncing = false
        }
    }

    private func syncOnce() async {
        let prefs = Prefs.shared
        let now = Date()
        if !prefs.careOn { plans.removeAll() }
        deliverDue(now: now)
        // Due but never written (no connection then). Old ones are dropped; a fresh one the agent asks now.
        let unwritten = plans.filter { $0.when <= now && ($0.text ?? "").isEmpty }
        for plan in unwritten {
            plans.removeAll { $0.id == plan.id }
            let fresh = now.timeIntervalSince(plan.when) < 12 * 3600
            let quiet = now.timeIntervalSince(prefs.lastWrote) >= CareRules.justWrote
            if !plan.daily && fresh && quiet && prefs.ready && UIApplication.shared.applicationState != .background,
               await ChatStore.shared.checkIn(topic: plan.topic) != nil {
                delivered.append(now)
            }
        }
        planDailies(now: now)
        save()
        if prefs.ready && !plans.isEmpty { await write() }
        await schedule()
    }

    /// Lets the model write the messages that are not written yet, the nearest first.
    private func write() async {
        let prefs = Prefs.shared
        guard UIApplication.shared.applicationState != .background else { return }
        let order = plans.sorted { $0.when < $1.when }
        for (n, plan) in order.enumerated() {
            guard plans.contains(where: { $0.id == plan.id }) else { continue }
            var needs = (plan.text ?? "").isEmpty
            if !needs, n == 0, let written = plan.writtenAt {
                // the next one is written again when the user has said more since, but not all the time
                needs = prefs.lastWrote > written && Date().timeIntervalSince(written) > 4 * 3600
                    && plan.when.timeIntervalSinceNow > 10 * 60
            }
            if !needs { continue }
            if ChatStore.shared.busy { return }
            // what the ones before it ask, those written a moment ago too
            let earlier = plans.filter { $0.when < plan.when }.sorted { $0.when < $1.when }.compactMap { $0.text }
            guard let text = await ChatStore.shared.write(prompt: Prompt.prepared(plan, earlier: earlier)) else {
                return // no connection or no model: the next sync tries again
            }
            if let j = plans.firstIndex(where: { $0.id == plan.id }) {
                plans[j].text = String(text.prefix(600))
                plans[j].writtenAt = Date()
                save()
            }
        }
    }

    // ------------------------------------------------------------ notifications

    static func registerCategory() {
        let reply = UNTextInputNotificationAction(identifier: replyAction, title: L.t("reply"), options: [],
                                                  textInputButtonTitle: L.t("send"),
                                                  textInputPlaceholder: L.t("reply_hint"))
        let care = UNNotificationCategory(identifier: category, actions: [reply], intentIdentifiers: [], options: [])
        let plain = UNNotificationCategory(identifier: Reminders.category, actions: [], intentIdentifiers: [], options: [])
        UNUserNotificationCenter.current().setNotificationCategories([care, plain])
    }

    /// Asks iOS for leave to notify, once it is needed. Returns whether notifications may be shown.
    @discardableResult
    func permission() async -> Bool {
        let center = UNUserNotificationCenter.current()
        let settings = await center.notificationSettings()
        switch settings.authorizationStatus {
        case .authorized, .provisional, .ephemeral:
            allowed = true
        case .denied:
            allowed = false
        default:
            if !Bootstrap.askForNotifications || asked { return false } // the question would cover the screen under test
            asked = true
            allowed = (try? await center.requestAuthorization(options: [.alert, .sound, .badge])) ?? false
        }
        return allowed
    }

    private func schedule() async {
        let center = UNUserNotificationCenter.current()
        let now = Date()
        let ready = plans.filter { $0.when > now && !($0.text ?? "").isEmpty }
        let pending = await center.pendingNotificationRequests().map { $0.identifier }.filter { $0.hasPrefix(Care.prefix) }
        let wanted = Set(ready.map { Care.prefix + $0.id })
        let gone = pending.filter { !wanted.contains($0) }
        if !gone.isEmpty { center.removePendingNotificationRequests(withIdentifiers: gone) }
        guard !ready.isEmpty, await permission() else { return }
        for plan in ready {
            let content = UNMutableNotificationContent()
            content.title = "Termant"
            content.body = plan.text ?? ""
            content.sound = .default
            content.categoryIdentifier = Care.category
            content.userInfo = ["plan": plan.id]
            let parts = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute, .second], from: plan.when)
            let trigger = UNCalendarNotificationTrigger(dateMatching: parts, repeats: false)
            try? await center.add(UNNotificationRequest(identifier: Care.prefix + plan.id, content: content, trigger: trigger))
        }
    }

    /// Shows the agent's answer to what the user typed into a notification.
    func notify(reply: String) async {
        guard await permission() else { return }
        let content = UNMutableNotificationContent()
        content.title = "Termant"
        content.body = String(reply.prefix(600))
        content.sound = .default
        content.categoryIdentifier = Care.category
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: 1, repeats: false)
        try? await UNUserNotificationCenter.current().add(
            UNNotificationRequest(identifier: "reply-\(Int(Date().timeIntervalSince1970))", content: content, trigger: trigger))
    }

    /// Clears the number on the app's icon.
    static func clearBadge() {
        if #available(iOS 16.0, *) {
            UNUserNotificationCenter.current().setBadgeCount(0) { _ in }
        } else {
            UIApplication.shared.applicationIconBadgeNumber = 0
        }
    }

    /// Whether plans and notifications are being brought in line right now.
    var isSyncing: Bool { syncing }

    /// For tests: nothing planned, nothing delivered.
    func resetForTests() {
        plans = []
        delivered = []
        save()
    }

    /// For tests: a plan as if it had been written already.
    func addForTests(_ plan: Plan) {
        plans.append(plan)
        save()
    }
}

/// Reminders the user asked the agent for: a notification with a sound at a moment.
struct Reminder: Codable, Identifiable, Equatable {
    var id: String
    var when: Date
    var text: String
}

@MainActor
enum Reminders {
    static let category = "remind"
    private static let prefix = "remind-"

    static var all: [Reminder] {
        guard let data = UserDefaults.standard.data(forKey: "reminders"),
              let saved = try? JSONDecoder().decode([Reminder].self, from: data) else { return [] }
        return saved.filter { $0.when > Date() }.sorted { $0.when < $1.when }
    }

    private static func save(_ list: [Reminder]) {
        if let data = try? JSONEncoder().encode(list) { UserDefaults.standard.set(data, forKey: "reminders") }
    }

    static func add(at when: Date, text: String) {
        let reminder = Reminder(id: "\(Int(Date().timeIntervalSince1970 * 1000))", when: when, text: String(text.prefix(300)))
        save(all + [reminder])
        Task {
            guard await Care.shared.permission() else { return }
            let content = UNMutableNotificationContent()
            content.title = "Termant"
            content.body = reminder.text
            content.sound = .default
            content.categoryIdentifier = category
            let parts = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute, .second], from: when)
            let trigger = UNCalendarNotificationTrigger(dateMatching: parts, repeats: false)
            try? await UNUserNotificationCenter.current().add(
                UNNotificationRequest(identifier: prefix + reminder.id, content: content, trigger: trigger))
        }
    }

    static func remove(_ id: String) {
        save(all.filter { $0.id != id })
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [prefix + id])
    }
}

/// What iOS tells the app about its notifications.
final class NotificationHandler: NSObject, UNUserNotificationCenterDelegate {
    static let shared = NotificationHandler()

    /// A notification's time came while the app is in front.
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        let category = notification.request.content.categoryIdentifier
        Task { @MainActor in
            var show = true
            if category == Care.category {
                // in the chat it appears by itself; one that was put off or left out is not shown at all
                show = Care.shared.deliverDue(inFront: true) || ChatStore.shared.busy
                Care.shared.sync()
            }
            completionHandler(show ? [.banner, .sound, .list] : [])
        }
    }

    /// The user tapped a notification or answered in it.
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        let category = response.notification.request.content.categoryIdentifier
        let typed = (response as? UNTextInputNotificationResponse)?.userText ?? ""
        Task { @MainActor in
            defer { completionHandler() }
            guard category == Care.category else { return }
            Care.shared.deliverDue()
            let text = typed.trimmingCharacters(in: .whitespacesAndNewlines)
            guard response.actionIdentifier == Care.replyAction, !text.isEmpty else { return }
            // iOS gives an app in the background little time: ask for as much as it allows
            let time = BackgroundTime()
            time.begin()
            if let reply = await ChatStore.shared.sendAndWait(text), UIApplication.shared.applicationState != .active {
                await Care.shared.notify(reply: reply.text)
            }
            Care.shared.sync()
            time.end()
        }
    }
}

/// Time to finish something after the app has left the screen.
@MainActor
final class BackgroundTime {
    private var id = UIBackgroundTaskIdentifier.invalid

    func begin() {
        id = UIApplication.shared.beginBackgroundTask { [weak self] in
            Task { @MainActor in self?.end() }
        }
    }

    func end() {
        if id != .invalid { UIApplication.shared.endBackgroundTask(id) }
        id = .invalid
    }
}
