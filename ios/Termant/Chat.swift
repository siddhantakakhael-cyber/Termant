import Foundation
import SwiftUI

struct Message: Codable, Identifiable, Equatable {
    var id = UUID()
    var user: Bool
    /// What is shown.
    var text = ""
    /// What the model wrote, with the lines the app carried out and hid.
    var raw = ""
    var failed = false
    /// The agent spoke first: a check-in nobody asked for.
    var first = false
    /// What the app did for this reply ("Remembered: ...").
    var actions: [String] = []
    /// Set when the app moved to another model to get this reply.
    var note = ""
    var at = Date()
    /// The commands this reply asked the shell to run, with what they printed. (Not in chats of version 0.1.)
    var runs: [ShellRun]?
}

struct Conversation: Codable, Identifiable, Equatable {
    var id: String
    var title = ""
    var updated = Date()
    var messages: [Message] = []

    static func fresh() -> Conversation {
        Conversation(id: "c\(Int(Date().timeIntervalSince1970 * 1000))")
    }
}

/// The chat with the agent: sends messages, shows the reply while it is written,
/// carries out what the agent asks of the phone, and keeps the conversations.
@MainActor
final class ChatStore: ObservableObject {
    static let shared = ChatStore()

    @Published private(set) var chat: Conversation
    @Published private(set) var busy = false
    private var work: Task<Message?, Never>?

    private init() {
        _ = Bootstrap.once
        let current = UserDefaults.standard.string(forKey: "current_chat") ?? ""
        chat = ChatStore.read(current) ?? Conversation.fresh()
    }

    // ------------------------------------------------------------ talking

    func send(_ text: String) {
        let request = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !request.isEmpty, !busy else { return }
        work = Task { await self.turn(prompt: request, hidden: false) }
    }

    /// The same, for code that needs the reply (an answer typed into a notification).
    func sendAndWait(_ text: String) async -> Message? {
        let request = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !request.isEmpty, !busy else { return nil }
        let task = Task { await self.turn(prompt: request, hidden: false) }
        work = task
        return await task.value
    }

    /// The agent speaks first, now. Nil when it is busy or the model gave nothing; then the chat is left as it was.
    func checkIn(topic: String?) async -> Message? {
        guard !busy else { return nil }
        let task = Task { await self.turn(prompt: Prompt.checkIn(topic: topic), hidden: true) }
        work = task
        return await task.value
    }

    func stop() {
        work?.cancel()
    }

    /// How often in a row the agent may run commands and read their output before it has to answer.
    static let maxRounds = 8
    /// How many commands of one reply are run.
    static let maxCommands = 4

    private func turn(prompt: String, hidden: Bool) async -> Message? {
        busy = true
        if !hidden {
            chat.messages.append(Message(user: true, text: prompt, raw: prompt))
            Prefs.shared.lastWrote = Date()
        }
        var result: Message?
        var round = 0
        while true {
            let (id, ok) = await reply(extra: hidden ? prompt : nil, hidden: hidden)
            result = chat.messages.first { $0.id == id }
            if hidden, let r = result, !ok || r.failed || r.text.isEmpty {
                // a check-in that did not work out is not worth an error in the chat
                chat.messages.removeAll { $0.id == id }
                result = nil
            }
            // the agent asked for commands: run them, and let it go on with what they printed
            guard ok, let runs = result?.runs, !runs.isEmpty else { break }
            if round >= ChatStore.maxRounds {
                change(id) { message in
                    for i in (message.runs ?? []).indices {
                        message.runs?[i].how = "failed"
                        message.runs?[i].output = L.t("run_limit")
                    }
                }
                break
            }
            round += 1
            let finished = await runCommands(of: id)
            result = chat.messages.first { $0.id == id }
            save()
            if !finished { break }
        }
        busy = false
        save()
        Care.shared.deliverDue() // one whose time came while the agent was writing
        if !hidden { Care.shared.sync() } // the user answered: plan and write what comes next
        return result
    }

    /// Runs the commands of one reply in the shell, one after another. False when the user stopped it.
    private func runCommands(of id: UUID) async -> Bool {
        let count = chat.messages.first(where: { $0.id == id })?.runs?.count ?? 0
        for i in 0..<count {
            guard let command = chat.messages.first(where: { $0.id == id })?.runs?[i].command else { break }
            if Task.isCancelled {
                change(id) { $0.runs?[i].how = "stopped" }
                continue
            }
            change(id) { $0.runs?[i].how = "running" }
            let done = await Shell.shared.run(command)
            change(id) {
                $0.runs?[i].output = done.output
                $0.runs?[i].status = done.status
                $0.runs?[i].how = done.how
            }
        }
        return !Task.isCancelled
    }

    /// Asks the model for the next message of the chat and shows it while it is written.
    /// Returns which message it became, and whether it arrived whole.
    private func reply(extra: String?, hidden: Bool) async -> (UUID, Bool) {
        let prefs = Prefs.shared
        let wire = Prompt.wire(chat.messages, extra: extra)
        let reply = Message(user: false, first: hidden)
        let id = reply.id
        chat.messages.append(reply)
        save()
        var ok = false
        do {
            let models = try await candidates()
            var attempt = 0
            while true {
                var raw = ""
                do {
                    for try await piece in LLM.stream(base: prefs.base, key: prefs.key, model: models[attempt], messages: wire) {
                        raw += piece
                        let shown = Directives.split(raw).visible
                        change(id) { $0.raw = raw; $0.text = shown }
                    }
                    try Task.checkCancellation() // a stopped stream just ends: what came is kept, nothing is carried out
                    finish(id, raw: raw, hidden: hidden)
                    ok = true
                    break
                } catch let failure as LLM.Failure {
                    if raw.isEmpty && failure.anotherModelMayWork && attempt + 1 < models.count {
                        attempt += 1
                        let name = ChatStore.short(models[attempt])
                        change(id) { $0.note = L.t("switched", name) }
                        continue
                    }
                    if raw.isEmpty { throw failure }
                    finish(id, raw: raw, hidden: hidden) // the connection broke off: what came is kept
                    ok = true
                    break
                }
            }
        } catch is CancellationError {
            change(id) { if $0.text.isEmpty { $0.text = L.t("stopped") } }
        } catch let failure as LLM.Failure {
            change(id) { $0.failed = true; $0.text = ChatStore.explain(failure) }
        } catch {
            change(id) { $0.failed = true; $0.text = L.t("error_other", error.localizedDescription) }
        }
        if chat.messages.first(where: { $0.id == id })?.failed == true { ok = false }
        return (id, ok)
    }

    /// The model to ask, followed by the ones to move to when it does not answer.
    private func candidates() async throws -> [String] {
        let prefs = Prefs.shared
        var list = prefs.cachedModels
        var chosen = prefs.model.trimmingCharacters(in: .whitespaces)
        // the list is needed to choose a model, and to know where to move when a free one does not answer
        let needed = chosen.isEmpty || (prefs.autoSwitch && LLM.isFree(chosen))
        if needed && (list.isEmpty || prefs.cachedModelsAreOld) {
            do {
                list = try await LLM.models(base: prefs.base, key: prefs.key)
                prefs.cachedModels = list
            } catch let failure as LLM.Failure {
                if chosen.isEmpty {
                    if failure.status == 401 || failure.status == 403 { throw failure }
                    // an older list will do; without any there is nothing to choose from
                    if list.isEmpty { throw LLM.Failure(status: -1, message: L.t("error_no_model", failure.message)) }
                }
            }
        }
        if chosen.isEmpty {
            // one that costs nothing if there is one; a service of one's own may offer anything
            chosen = list.first(where: LLM.isFree) ?? (prefs.provider == Provider.custom ? list.first : nil) ?? ""
            if chosen.isEmpty { throw LLM.Failure(status: -1, message: L.t("error_choose_model")) }
        }
        var all = [chosen]
        if prefs.autoSwitch && LLM.isFree(chosen) {
            all += list.filter { LLM.isFree($0) && $0 != chosen }.prefix(3)
        }
        return all
    }

    /// The whole reply to a prompt that is not part of the chat (a check-in written ahead of time). Nil if it failed.
    func write(prompt: String) async -> String? {
        let prefs = Prefs.shared
        guard let models = try? await candidates() else { return nil }
        let wire = Prompt.wire(chat.messages, extra: prompt)
        for model in models {
            if let raw = try? await LLM.complete(base: prefs.base, key: prefs.key, model: model, messages: wire) {
                let text = Directives.split(raw).visible
                if !text.isEmpty { return text }
            }
        }
        return nil
    }

    private func finish(_ id: UUID, raw: String, hidden: Bool) {
        let parts = Directives.split(raw)
        var actions: [String] = []
        var runs: [ShellRun] = []
        for directive in parts.found {
            if directive.kind == "run" {
                // a check-in runs nothing, and the user can switch the shell off for the agent
                if hidden || !Prefs.shared.shellAgent {
                    if !hidden && actions.last != L.t("did_run_off") { actions.append(L.t("did_run_off")) }
                } else if runs.count < ChatStore.maxCommands {
                    runs.append(ShellRun(command: directive.body))
                }
            } else if let done = carryOut(directive, hidden: hidden) {
                actions.append(done)
            }
        }
        change(id) {
            $0.raw = raw
            $0.actions = actions
            $0.runs = runs.isEmpty ? nil : runs
            $0.text = parts.visible
            if parts.visible.isEmpty && runs.isEmpty {
                if actions.isEmpty {
                    $0.failed = true
                    $0.text = L.t("no_answer")
                } else {
                    $0.text = L.t("done")
                }
            }
        }
    }

    /// Does what one [[...]] line asks for and says what happened, for the list under the reply.
    private func carryOut(_ directive: Directive, hidden: Bool) -> String? {
        switch directive.kind {
        case "note":
            switch Memory.shared.add(directive.body, secret: Prefs.shared.key) {
            case .saved: return L.t("did_note", directive.body)
            case .known: return L.t("did_note_known", directive.body)
            case .refused(let why): return L.t("did_note_refused", why)
            }
        case "forget":
            guard let number = Int(directive.body.trimmingCharacters(in: .whitespaces)),
                  Memory.shared.forget(number: number) else { return nil }
            return L.t("did_forget", String(number))
        case "care":
            if hidden { return nil } // a check-in does not plan the next one before the user has answered
            let halves = Directives.halves(directive.body)
            guard let when = When.parse(halves.when) else { return L.t("did_care_refused", directive.body) }
            switch Care.shared.plan(when: when, topic: halves.text) {
            case .planned(let plan): return L.t("did_care", When.show(plan.when) + " · " + halves.text)
            case .refused(let why): return L.t("did_care_refused", why)
            }
        case "remind":
            let halves = Directives.halves(directive.body)
            guard let when = When.parse(halves.when), !halves.text.isEmpty else {
                return L.t("did_remind_refused", directive.body)
            }
            Reminders.add(at: when, text: halves.text)
            return L.t("did_remind", When.show(when) + " · " + halves.text)
        default:
            return nil
        }
    }

    private func change(_ id: UUID, _ edit: (inout Message) -> Void) {
        guard let i = chat.messages.lastIndex(where: { $0.id == id }) else { return }
        edit(&chat.messages[i])
    }

    static func short(_ model: String) -> String {
        String(model.split(separator: "/").last ?? Substring(model))
    }

    static func explain(_ failure: LLM.Failure) -> String {
        switch failure.status {
        case -1: return failure.message
        case 0: return L.t("error_network", failure.message)
        case 401, 403: return L.t("error_key", failure.message)
        case 402, 408, 429, 500, 502, 503, 529: return L.t("error_limit", failure.message)
        default: return L.t("error_other", failure.message)
        }
    }

    // ------------------------------------------------------------ check-ins that were written ahead

    /// Puts a check-in whose time has come into the chat, as the agent's own message.
    func insertCheckIn(text: String, at: Date) {
        chat.messages.append(Message(user: false, text: text, raw: text, first: true, at: at))
        save()
    }

    // ------------------------------------------------------------ conversations

    func newChat() {
        guard !busy else { return }
        save()
        chat = Conversation.fresh()
        UserDefaults.standard.set(chat.id, forKey: "current_chat")
    }

    func open(_ id: String) {
        guard !busy, let found = ChatStore.read(id) else { return }
        save()
        chat = found
        UserDefaults.standard.set(chat.id, forKey: "current_chat")
    }

    func delete(_ id: String) {
        try? FileManager.default.removeItem(at: ChatStore.file(id))
        if id == chat.id && !busy {
            chat = Conversation.fresh()
            UserDefaults.standard.set(chat.id, forKey: "current_chat")
        }
        objectWillChange.send()
    }

    /// Earlier chats, newest first.
    func history() -> [Conversation] {
        let files = (try? FileManager.default.contentsOfDirectory(at: Files.chats, includingPropertiesForKeys: nil)) ?? []
        return files.compactMap { ChatStore.read($0.deletingPathExtension().lastPathComponent) }
            .sorted { $0.updated > $1.updated }
    }

    private static func file(_ id: String) -> URL {
        Files.chats.appendingPathComponent(id + ".json")
    }

    private static func read(_ id: String) -> Conversation? {
        guard !id.isEmpty, let data = try? Data(contentsOf: file(id)) else { return nil }
        return try? JSONDecoder().decode(Conversation.self, from: data)
    }

    private func save() {
        guard !chat.messages.isEmpty else { return }
        let named = chat.messages.first(where: { $0.user }) ?? chat.messages[0]
        let title = named.text.replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
        chat.title = String(title.prefix(80))
        chat.updated = Date()
        UserDefaults.standard.set(chat.id, forKey: "current_chat")
        if let data = try? JSONEncoder().encode(chat) {
            try? data.write(to: ChatStore.file(chat.id), options: .atomic)
        }
    }
}

/// What the model is told.
@MainActor
enum Prompt {
    /// Stands where the app asked the agent to check in, so the conversation reads as one.
    static let checkInStandIn = "Check in on me now, the way a caring friend would."

    static func now() -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "EEEE d MMMM yyyy, HH:mm"
        return f.string(from: Date())
    }

    static func system() -> String {
        let prefs = Prefs.shared
        var s = """
        You are Termant, a personal assistant that lives in an app on the user's iPhone. Talk like a capable, \
        caring friend: brief, warm and practical. The user may write in Hindi, Hinglish or English; answer in \
        the user's language, and write Hindi in Latin letters unless they use Devanagari.

        Now: \(now()). Language of the app: \(prefs.language == "hi" ? "Hindi in Latin letters (Hinglish)" : "English").

        ## What you can do on this iPhone

        You act by writing a line of exactly this form anywhere in your reply. The app carries it out and \
        hides the line, so the user never sees it:

        [[note: The user's sister is called Meera.]]
        [[care: 20h | how the exam went]]
        [[remind: 07:00 | Uthiye, subah ho gayi]]
        [[forget: 3]]

        - note: keep one short sentence about the user for all future chats (who they are, what they like, \
        how they want to be answered, people and plans they mention). At most \(Memory.maxLength) letters. \
        Use it when the user says "yaad rakho" / remember, and by yourself when you learn something that \
        will matter later. Never a password or a key.
        - care: come back to something later by yourself, like a friend would. Before the bar: 30m, 20h, 2d \
        from now, or a clock time such as 21:00. After the bar: what to ask about.
        - remind: a notification with a sound at that moment, with the text after the bar. It is not a loud \
        alarm: an app on an iPhone cannot set the Clock's alarm. Say so if the user needs to be woken.
        - forget: remove the note with that number.

        Write such a line only when there is a reason, never explain or mention the brackets, and never say \
        you did something unless you wrote its line in the same reply.

        You cannot open or control other apps, read the phone's messages, photos or files, or change the \
        phone's settings; Apple does not allow an app that. Say so plainly when asked. (Termant's Android app can.)
        \(shell())

        ## Looking after the user

        You are not only a tool that waits. When the user mentions something a friend would ask about later \
        (an exam, an interview, a doctor's visit, being ill or sad, a trip, a deadline), wish them well now \
        and plan the moment with a care line, for after the event. Do not announce it as a reminder; a \
        friend just asks. One or two a day is plenty, and none for small talk. If the user says "mujhse mat \
        poocha karo", tell them the switch is in Settings under "\(L.t("care_section"))".

        """
        let notes = Memory.shared.notes
        if prefs.learnOn && !notes.isEmpty {
            s += "\n## What you know about the user (your notes)\n\n"
            for (i, note) in notes.enumerated() { s += "\(i + 1). \(note.text)\n" }
        }
        let planned = Care.shared.followUps
        if !planned.isEmpty {
            s += "\n## Moments you have planned already (do not plan them twice)\n\n"
            for p in planned { s += "- \(When.show(p.when)): \(p.topic ?? "")\n" }
        }
        return s
    }

    /// What the agent is told about the Linux inside the app.
    static func shell() -> String {
        guard Prefs.shared.shellAgent else {
            return """

            You cannot run commands either: the user has switched the app's Linux shell off for you \
            (Settings, "\(L.t("shell_section"))").
            """
        }
        return """

        ## A Linux shell on this iPhone

        The app has a small Linux computer inside it: Alpine Linux 3.19 for 32-bit x86, emulated by iSH. You \
        are root in it, and /bin/sh is busybox ash. To run a command there, write a line of this form:

        [[run: uname -a]]

        The app runs it in /root (no keyboard input; at most \(ShellReport.minutes) minutes) and gives you what it printed in the \
        next message, which starts with "Command output:". The user sees the command and its output under \
        your reply. Then you go on: run something else, or answer.

        - Use it when it gives a better answer than guessing: to calculate, to work with text or files, to \
        try a piece of code, to fetch something from the internet (wget is there), to check a fact about \
        the system. Do not use it for things you can simply say.
        - At most \(ChatStore.maxCommands) run lines in one reply; they run one after another. A command may be several lines \
        long (a script, a here-document).
        - More programs: "apk add python3" (or git, curl, nodejs, vim ...). It is an emulated computer, about \
        ten to a hundred times slower than a real one, so prefer small tools and small jobs.
        - Nothing that waits for input (an editor, a prompt for a password) and nothing that never ends.
        - Files stay in /root between commands and between chats. The user has the same Linux as a terminal \
        behind the >_ button, so you can prepare files there for them, and read what they made.
        - It is only this small Linux: it cannot see or change the iPhone, its apps, photos or settings.
        - When you have the output, say what it means in plain words; do not paste all of it back.
        """
    }

    /// The conversation as the service wants it: roles taking turns, the newest 40 messages.
    static func wire(_ messages: [Message], extra: String?) -> [[String: String]] {
        var out: [[String: String]] = [["role": "system", "content": system()]]
        func add(_ role: String, _ content: String) {
            if out.count > 1, out[out.count - 1]["role"] == role {
                out[out.count - 1]["content"] = (out[out.count - 1]["content"] ?? "") + "\n\n" + content
            } else {
                out.append(["role": role, "content": content])
            }
        }
        for m in messages.suffix(40) where !m.failed {
            let content = m.user ? m.text : (m.raw.isEmpty ? m.text : m.raw)
            if content.isEmpty { continue }
            if m.first { add("user", checkInStandIn) }
            add(m.user ? "user" : "assistant", content)
            // what its commands printed comes back to the model as the next message
            if let runs = m.runs, !runs.isEmpty { add("user", ShellReport.text(runs)) }
        }
        if let extra = extra { add("user", extra) }
        return out
    }

    private static func facts(at moment: Date?) -> String {
        let prefs = Prefs.shared
        var s = ""
        if let moment = moment {
            let f = DateFormatter()
            f.locale = Locale(identifier: "en_US_POSIX")
            f.dateFormat = "EEEE d MMMM yyyy, HH:mm"
            s += "- The user will read it at: " + f.string(from: moment) + "\n"
        } else {
            s += "- Now: " + now() + "\n"
        }
        if prefs.hasWritten {
            let minutes = Int(max(0, (moment ?? Date()).timeIntervalSince(prefs.lastWrote)) / 60)
            var ago = "\(minutes) minutes"
            if minutes >= 48 * 60 {
                ago = "\(minutes / (60 * 24)) days"
            } else if minutes >= 90 {
                ago = "\(minutes / 60) hours"
            }
            s += "- By then the user last wrote to you " + ago + " before\n"
        } else {
            s += "- The user has not written to you yet\n"
        }
        return s
    }

    private static let rules = """
    Write one short, warm message of at most three sentences that ends with ONE question for the user.
    - Make it personal. Use what you know about them from your notes and from this conversation: how \
    something they mentioned went, a plan, their health, their work, the people they talked about.
    - Fit the time of day (morning, afternoon, evening, night).
    - Do not ask something you already asked in this conversation, and do not list what you can do.
    - Write in the language the user writes to you in; if you cannot tell, use the app's language.
    - Write only the message itself: no [[...]] line, no greeting formula before it, no explanation after it.
    """

    private static func occasion(_ topic: String?) -> String {
        guard let topic = topic else { return "It is one of the moments at which you check in on them." }
        return "You planned to come back to this: \"" + topic + "\"."
    }

    private static func lastRule(_ topic: String?) -> String {
        if topic == nil {
            return "- If you know nothing about them yet, ask how they are and one thing that helps you get to know them."
        }
        return "- Ask about the thing you planned to come back to."
    }

    /// Lets the agent speak first, now.
    static func checkIn(topic: String?) -> String {
        var s = "You look after the user the way a caring friend does, and this time you speak first: "
        s += "the user has not asked anything. "
        s += occasion(topic)
        s += "\n\n" + facts(at: nil) + "\n"
        s += rules + "\n"
        s += lastRule(topic)
        return s
    }

    /// The same for a check-in that is delivered later as a notification, when the app is not running.
    static func prepared(_ plan: Plan, earlier: [String]) -> String {
        var s = "You look after the user the way a caring friend does. Write now the message you will send "
        s += "them later by yourself; the user will not have asked anything. "
        s += occasion(plan.topic)
        s += "\n\n" + facts(at: plan.when) + "\n"
        s += rules + "\n"
        s += lastRule(plan.topic)
        if !earlier.isEmpty {
            s += "\n- Before this one the user will already have got these from you, perhaps without answering; "
            s += "ask something else:\n"
            s += earlier.map { "  \"" + $0 + "\"" }.joined(separator: "\n")
        }
        return s
    }
}
