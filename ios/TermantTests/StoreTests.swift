import XCTest
@testable import Termant

/// The app's own parts working together, against the stand-in model service (ci/fake-llm.js)
/// and the real Termant terminal server (server.js). ci/test.sh starts both.
@MainActor
final class StoreTests: XCTestCase {
    static let llm = "http://127.0.0.1:8787/v1"
    static let server = "http://127.0.0.1:3111"
    static let serverPassword = "ci-password"

    private var prefs: Prefs { Prefs.shared }
    private var chat: ChatStore { ChatStore.shared }
    private var care: Care { Care.shared }
    private var memory: Memory { Memory.shared }

    /// Waits until the care planner has nothing left to do.
    private func settle(_ seconds: Double = 20) async {
        let end = Date().addingTimeInterval(seconds)
        try? await Task.sleep(nanoseconds: 50_000_000)
        while (care.isSyncing || chat.busy) && Date() < end {
            try? await Task.sleep(nanoseconds: 50_000_000)
        }
    }

    override func setUp() async throws {
        continueAfterFailure = false
        await settle()
        // without the stand-in service these tests say nothing
        let (_, response) = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:8787/healthz")!)
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200, "start ios/ci/fake-llm.js first (ci/test.sh does)")
        prefs.language = "hi"
        prefs.provider = Provider.custom
        prefs.customBase = StoreTests.llm
        prefs.setKey("test-key")
        prefs.model = "test/beta:free"
        prefs.cachedModels = ["test/alpha:free", "test/beta:free", "test/paid"]
        prefs.autoSwitch = true
        prefs.learnOn = true
        prefs.careOn = true
        prefs.careTimes = "09:00 21:00"
        prefs.lastWrote = Date(timeIntervalSince1970: 0)
        memory.removeAll()
        care.resetForTests()
        chat.newChat()
    }

    override func tearDown() async throws {
        await settle()
        prefs.careOn = false // leaves nothing planned behind for the tests of the screens
        care.resetForTests()
    }

    // ------------------------------------------------------------ notes

    func testMemoryKeepsNotesWithinLimits() {
        XCTAssertEqual(memory.add("The user  is called\nAsha."), .saved)
        XCTAssertEqual(memory.notes.first?.text, "The user is called Asha.")
        XCTAssertEqual(memory.add("the user is called asha."), .known)
        XCTAssertNotEqual(memory.add("   "), .saved)
        XCTAssertNotEqual(memory.add(String(repeating: "x", count: Memory.maxLength + 1)), .saved)
        XCTAssertNotEqual(memory.add("my key is sk-secret-12345", secret: "sk-secret-12345"), .saved)
        XCTAssertEqual(memory.notes.count, 1)
        prefs.learnOn = false
        XCTAssertNotEqual(memory.add("Likes tea."), .saved)
        prefs.learnOn = true
        for i in 0..<(Memory.maxNotes - 1) { XCTAssertEqual(memory.add("Fact number \(i)."), .saved) }
        XCTAssertNotEqual(memory.add("One too many."), .saved)
        XCTAssertEqual(memory.notes.count, Memory.maxNotes)
        XCTAssertTrue(memory.forget(number: 1))
        XCTAssertEqual(memory.notes.first?.text, "Fact number 0.")
        XCTAssertFalse(memory.forget(number: 99))
        XCTAssertFalse(memory.forget(number: 0))
    }

    // ------------------------------------------------------------ the model's service

    func testReplyArrivesPieceByPiece() async throws {
        var pieces: [String] = []
        for try await piece in LLM.stream(base: StoreTests.llm, key: "k", model: "test/paid",
                                          messages: [["role": "user", "content": "hello there"]]) {
            pieces.append(piece)
        }
        XCTAssertGreaterThan(pieces.count, 2)
        XCTAssertEqual(pieces.joined(), "Echo from test/paid: hello there")
    }

    func testListOfModels() async throws {
        let names = try await LLM.models(base: StoreTests.llm, key: "k")
        XCTAssertEqual(names, ["test/alpha:free", "test/beta:free", "test/paid"])
        do {
            _ = try await LLM.models(base: StoreTests.llm, key: "wrong-key")
            XCTFail("a wrong key was accepted")
        } catch let failure as LLM.Failure {
            XCTAssertEqual(failure, LLM.Failure(status: 401, message: "Invalid API key"))
        }
    }

    func testServiceThatIsNotThere() async {
        do {
            _ = try await LLM.complete(base: "http://127.0.0.1:9", key: "k", model: "m", messages: [])
            XCTFail("no error from a service that is not there")
        } catch let failure as LLM.Failure {
            XCTAssertEqual(failure.status, 0)
        } catch {
            XCTFail("unexpected \(error)")
        }
    }

    // ------------------------------------------------------------ a conversation

    func testAgentRemembersWhatItIsTold() async {
        let reply = await chat.sendAndWait("Mera naam Asha hai, yaad rakho")
        XCTAssertEqual(reply?.text, "Theek hai, yaad rakhunga.")
        XCTAssertEqual(reply?.actions, ["Yaad rakha: The user is called Asha."])
        XCTAssertEqual(memory.notes.map { $0.text }, ["The user is called Asha."])
        // the note travels with the next message, in a new chat too
        chat.newChat()
        let second = await chat.sendAndWait("Mera naam kya hai?")
        XCTAssertEqual(second?.text, "Aapka naam Asha hai.")
        XCTAssertEqual(chat.chat.messages.count, 2)
    }

    func testChatIsKeptAndFoundAgain() async {
        _ = await chat.sendAndWait("pehli baat")
        let id = chat.chat.id
        chat.newChat()
        XCTAssertTrue(chat.chat.messages.isEmpty)
        let found = chat.history().first { $0.id == id }
        XCTAssertEqual(found?.title, "pehli baat")
        chat.open(id)
        XCTAssertEqual(chat.chat.messages.map { $0.text }, ["pehli baat", "Echo from test/beta:free: pehli baat"])
        chat.delete(id)
        XCTAssertNil(chat.history().first { $0.id == id })
        XCTAssertTrue(chat.chat.messages.isEmpty)
    }

    func testMovesToAnotherFreeModelWhenOneIsUsedUp() async {
        prefs.model = "test/alpha:free"
        let reply = await chat.sendAndWait("limit check")
        XCTAssertEqual(reply?.text, "Echo from test/beta:free: limit check")
        XCTAssertEqual(reply?.failed, false)
        XCTAssertEqual(reply?.note, "beta:free par chala gaya: pehle wale model ne jawab nahi diya.")
    }

    func testSaysSoWhenItMayNotMove() async {
        prefs.model = "test/alpha:free"
        prefs.autoSwitch = false
        let reply = await chat.sendAndWait("limit check")
        XCTAssertEqual(reply?.failed, true)
        XCTAssertTrue(reply?.text.contains("Rate limit exceeded") ?? false, reply?.text ?? "")
    }

    func testChoosesAFreeModelByItself() async {
        prefs.model = ""
        prefs.cachedModels = []
        let reply = await chat.sendAndWait("hi")
        XCTAssertEqual(reply?.text, "Echo from test/alpha:free: hi")
        XCTAssertEqual(prefs.cachedModels.count, 3)
    }

    func testWrongKeyIsExplained() async {
        prefs.setKey("wrong-key")
        let reply = await chat.sendAndWait("hi")
        XCTAssertEqual(reply?.failed, true)
        XCTAssertEqual(reply?.text, "Service ne key nahi maani (Invalid API key). Settings mein key dekhein.")
    }

    func testEmptyAnswerIsNotShownAsDone() async {
        let reply = await chat.sendAndWait("stay silent")
        XCTAssertEqual(reply?.failed, true)
        XCTAssertEqual(reply?.text, L.t("no_answer"))
    }

    func testThinkingAndMarkup() async {
        let reply = await chat.sendAndWait("think first")
        XCTAssertEqual(reply?.text, "Short answer.")
        let rich = Theme.rich("This is **bold** and `code`.")
        XCTAssertEqual(String(rich.characters), "This is bold and code.")
    }

    func testStopKeepsWhatCame() async {
        chat.send("slow answer please")
        try? await Task.sleep(nanoseconds: 1_500_000_000)
        XCTAssertTrue(chat.busy)
        chat.stop()
        await settle()
        XCTAssertFalse(chat.busy)
        let text = chat.chat.messages.last?.text ?? ""
        XCTAssertTrue("Echo from test/beta:free: slow answer please".hasPrefix(text) && !text.isEmpty, text)
        XCTAssertLessThan(text.count, 40)
    }

    func testFailedMessagesAreNotSentAgain() async {
        prefs.setKey("wrong-key")
        _ = await chat.sendAndWait("first")
        prefs.setKey("test-key")
        _ = await chat.sendAndWait("second")
        let wire = Prompt.wire(chat.chat.messages, extra: nil)
        XCTAssertEqual(wire.map { $0["role"] ?? "" }, ["system", "user", "assistant"])
        // two messages of the user in a row are joined: services want the roles to take turns
        XCTAssertEqual(wire[1]["content"], "first\n\nsecond")
    }

    // ------------------------------------------------------------ reminders

    func testReminder() async {
        let reply = await chat.sendAndWait("subah 7 baje jagana")
        XCTAssertEqual(reply?.text, "Theek hai, subah 7 baje yaad dilaunga.")
        XCTAssertEqual(Reminders.all.count, 1)
        let reminder = Reminders.all[0]
        XCTAssertEqual(reminder.text, "Uthiye, subah ho gayi")
        XCTAssertEqual(Calendar.current.component(.hour, from: reminder.when), 7)
        XCTAssertGreaterThan(reminder.when, Date())
        XCTAssertTrue(reply?.actions.first?.hasPrefix("Yaad dilayega: ") ?? false)
        Reminders.remove(reminder.id)
        XCTAssertTrue(Reminders.all.isEmpty)
    }

    // ------------------------------------------------------------ looking after the user

    func testAgentPlansToComeBack() async {
        let before = Date()
        let reply = await chat.sendAndWait("kal doctor ke paas jana hai")
        XCTAssertEqual(reply?.text, "Apna khayal rakhna.")
        XCTAssertEqual(care.followUps.count, 1)
        let plan = care.followUps[0]
        XCTAssertEqual(plan.topic, "how the visit to the doctor went")
        XCTAssertGreaterThanOrEqual(plan.when, before.addingTimeInterval(20 * 3600 - 5))
        let hour = Calendar.current.component(.hour, from: plan.when)
        XCTAssertTrue(hour >= When.nightTo && hour < When.nightFrom, "planned for \(hour) o'clock")
        XCTAssertTrue(reply?.actions.first?.hasPrefix("Baad mein poochhega: ") ?? false)

        // the messages are written ahead of time, so that they can arrive while the app is closed
        await settle()
        XCTAssertEqual(care.followUps.first?.text, "Arre, how the visit to the doctor went? Batao na, kaisa raha?")
        let dailies = care.dailies
        XCTAssertEqual(dailies.count, CareRules.maxUnanswered)
        XCTAssertTrue(dailies.allSatisfy { ($0.text ?? "").hasPrefix("Namaste! Din kaisa ja raha hai?") })
        // each knows what the ones before it asked, so they need not repeat themselves
        XCTAssertEqual(Set(dailies.compactMap { $0.text }).count, dailies.count)
        for p in dailies {
            let c = Calendar.current.dateComponents([.hour, .minute], from: p.when)
            XCTAssertTrue((c.hour == 9 || c.hour == 21) && c.minute == 0)
        }

        // the same topic again replaces the plan
        if case .planned(let again) = care.plan(when: Date().addingTimeInterval(3 * 3600), topic: "How the visit to the doctor went") {
            XCTAssertEqual(care.followUps.map { $0.id }, [again.id])
        } else {
            XCTFail("not planned")
        }
    }

    func testPlanLimits() {
        XCTAssertEqual(care.plan(when: Date().addingTimeInterval(3600), topic: "  "), .refused("nothing to ask about"))
        for i in 0..<CareRules.maxFollowUps {
            guard case .planned = care.plan(when: Date().addingTimeInterval(Double(i + 1) * 86400), topic: "topic \(i)") else {
                return XCTFail("plan \(i) refused")
            }
        }
        guard case .refused = care.plan(when: Date().addingTimeInterval(3600), topic: "one more") else {
            return XCTFail("a ninth plan was accepted")
        }
        prefs.careOn = false
        guard case .refused = care.plan(when: Date().addingTimeInterval(3600), topic: "topic 0") else {
            return XCTFail("planned although check-ins are off")
        }
    }

    func testCheckInNow() async {
        let message = await chat.checkIn(topic: nil)
        XCTAssertEqual(message?.text, "Namaste! Aaj aap kaise hain?")
        XCTAssertEqual(message?.first, true)
        XCTAssertEqual(chat.chat.messages.count, 1)
        // the user's answer goes out as a conversation whose roles take turns
        let answer = await chat.sendAndWait("theek hoon")
        XCTAssertEqual(answer?.text, "Echo from test/beta:free: theek hoon")
        let roles = Prompt.wire(chat.chat.messages, extra: nil).map { $0["role"] ?? "" }
        XCTAssertEqual(roles, ["system", "user", "assistant", "user", "assistant"])
    }

    func testCheckInThatFailsLeavesNothing() async {
        prefs.setKey("wrong-key")
        let message = await chat.checkIn(topic: "how the exam went")
        XCTAssertNil(message)
        XCTAssertTrue(chat.chat.messages.isEmpty)
    }

    func testCheckInWhoseTimeCameIsPutIntoTheChat() {
        let now = Date()
        prefs.lastWrote = now.addingTimeInterval(-3600)
        care.addForTests(Plan(id: "t1", when: now.addingTimeInterval(-5), topic: "x", text: "Kaisa raha?", writtenAt: now))
        care.addForTests(Plan(id: "t2", when: now.addingTimeInterval(600), topic: "y", text: "Later", writtenAt: now))
        care.deliverDue(now: now)
        XCTAssertEqual(chat.chat.messages.last?.text, "Kaisa raha?")
        XCTAssertEqual(chat.chat.messages.last?.first, true)
        XCTAssertEqual(chat.chat.messages.count, 1)
        XCTAssertEqual(care.plans.map { $0.id }, ["t2"])
        XCTAssertEqual(care.unanswered, 1)
    }

    func testNotInTheMiddleOfAConversation() {
        let now = Date()
        prefs.lastWrote = now.addingTimeInterval(-20)
        care.addForTests(Plan(id: "d", when: now.addingTimeInterval(-2), topic: nil, text: "Daily", writtenAt: now))
        care.addForTests(Plan(id: "f", when: now.addingTimeInterval(-1), topic: "x", text: "Follow", writtenAt: now))
        care.deliverDue(now: now, inFront: true)
        XCTAssertTrue(chat.chat.messages.isEmpty)
        XCTAssertEqual(care.plans.map { $0.id }, ["f"])
        XCTAssertEqual(care.plans.first?.when, now.addingTimeInterval(15 * 60))
        // when the app was not open, the notification has been seen already: it belongs in the chat
        care.addForTests(Plan(id: "g", when: now.addingTimeInterval(-1), topic: "z", text: "Seen", writtenAt: now))
        XCTAssertTrue(care.deliverDue(now: now))
        XCTAssertEqual(chat.chat.messages.map { $0.text }, ["Seen"])
    }

    func testDailyQuestionsStopWhenNobodyAnswers() async {
        let now = Date()
        prefs.lastWrote = now.addingTimeInterval(-86400)
        for i in 0..<3 {
            care.addForTests(Plan(id: "d\(i)", when: now.addingTimeInterval(Double(i - 3) * 3600), topic: nil,
                                  text: "Question \(i)", writtenAt: now))
        }
        care.sync()
        await settle()
        XCTAssertEqual(chat.chat.messages.map { $0.text }, ["Question 0", "Question 1", "Question 2"])
        XCTAssertEqual(care.unanswered, 3)
        XCTAssertTrue(care.dailies.isEmpty, "still planned: \(care.dailies)")

        // one word from the user and they begin again
        _ = await chat.sendAndWait("theek hoon")
        await settle()
        XCTAssertEqual(care.unanswered, 0)
        XCTAssertEqual(care.dailies.count, 3)
    }

    func testTurningOffRemovesEveryPlan() async {
        _ = await chat.sendAndWait("kal doctor ke paas jana hai")
        await settle()
        XCTAssertFalse(care.plans.isEmpty)
        prefs.careOn = false
        care.sync()
        await settle()
        XCTAssertTrue(care.plans.isEmpty)
    }

    func testNoDailyQuestionsWithoutTimes() async {
        prefs.careTimes = ""
        _ = await chat.sendAndWait("hello")
        await settle()
        XCTAssertTrue(care.dailies.isEmpty)
    }

    // ------------------------------------------------------------ the terminal of one's own server

    func testLoginToTheTerminalServer() async throws {
        let base = try XCTUnwrap(Remote.normalize(StoreTests.server))
        let wrong = await Remote.login(base: base, password: "not-the-password")
        XCTAssertEqual(wrong, .wrongPassword)
        guard case .cookie(let cookie) = await Remote.login(base: base, password: StoreTests.serverPassword) else {
            return XCTFail("no cookie from \(base): start server.js first (ci/test.sh does)")
        }
        XCTAssertEqual(cookie.name, "termant")
        // with the cookie the server lets the app in; without it, it does not
        var request = URLRequest(url: URL(string: "api/sessions", relativeTo: base)!)
        request.httpShouldHandleCookies = false
        let (_, refused) = try await URLSession.shared.data(for: request)
        XCTAssertEqual((refused as? HTTPURLResponse)?.statusCode, 401)
        request.setValue("termant=\(cookie.value)", forHTTPHeaderField: "Cookie")
        let (data, response) = try await URLSession.shared.data(for: request)
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
        XCTAssertNotNil((try JSONSerialization.jsonObject(with: data) as? [String: Any])?["sessions"])
    }

    func testServerThatIsNotThere() async throws {
        let base = try XCTUnwrap(Remote.normalize("127.0.0.1:9"))
        let why = await Remote.unreachable(base)
        XCTAssertNotNil(why)
        let there = await Remote.unreachable(try XCTUnwrap(Remote.normalize(StoreTests.server)))
        XCTAssertNil(there)
        guard case .failed = await Remote.login(base: base, password: "x") else {
            return XCTFail("a login to nothing did not fail")
        }
    }
}
