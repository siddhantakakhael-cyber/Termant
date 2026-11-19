import XCTest
@testable import Termant

/// The parts that need neither a network nor a screen.
final class LogicTests: XCTestCase {
    private var calendar: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "Asia/Kolkata")!
        return c
    }()

    private func date(_ day: Int, _ hour: Int, _ minute: Int = 0) -> Date {
        calendar.date(from: DateComponents(year: 2026, month: 11, day: day, hour: hour, minute: minute))!
    }

    // ------------------------------------------------------------ times

    func testParseTime() {
        XCTAssertEqual(When.parseTime("7")?.hour, 7)
        XCTAssertEqual(When.parseTime("07:05")?.minute, 5)
        XCTAssertEqual(When.parseTime("7.30")?.minute, 30)
        XCTAssertEqual(When.parseTime("7pm")?.hour, 19)
        XCTAssertEqual(When.parseTime("12am")?.hour, 0)
        XCTAssertEqual(When.parseTime("12pm")?.hour, 12)
        XCTAssertEqual(When.parseTime("7:30 PM")?.hour, 19)
        XCTAssertNil(When.parseTime("24:00"))
        XCTAssertNil(When.parseTime("13pm"))
        XCTAssertNil(When.parseTime("7:60"))
        XCTAssertNil(When.parseTime("soon"))
        XCTAssertNil(When.parseTime(""))
    }

    func testParseDelay() {
        XCTAssertEqual(When.parseDelay("30m"), 1800)
        XCTAssertEqual(When.parseDelay("45 min"), 2700)
        XCTAssertEqual(When.parseDelay("20h"), 72000)
        XCTAssertEqual(When.parseDelay("2d"), 172800)
        XCTAssertNil(When.parseDelay("0m"))
        XCTAssertNil(When.parseDelay("40d"))
        XCTAssertNil(When.parseDelay("21:00"))
        XCTAssertNil(When.parseDelay("h"))
    }

    func testParseMoment() {
        let now = date(14, 10, 0)
        XCTAssertEqual(When.parse("20h", now: now, calendar: calendar), date(15, 6, 0))
        XCTAssertEqual(When.parse("21:00", now: now, calendar: calendar), date(14, 21, 0))
        // a time that has passed today means tomorrow
        XCTAssertEqual(When.parse("7am", now: now, calendar: calendar), date(15, 7, 0))
        // "10:00" at 10:00 is tomorrow, not this very minute
        XCTAssertEqual(When.parse("10:00", now: now, calendar: calendar), date(15, 10, 0))
        XCTAssertNil(When.parse("kal", now: now, calendar: calendar))
    }

    func testOutOfTheNight() {
        XCTAssertEqual(When.outOfTheNight(date(14, 22, 59), calendar: calendar), date(14, 22, 59))
        XCTAssertEqual(When.outOfTheNight(date(14, 23, 0), calendar: calendar), date(15, 7, 0))
        XCTAssertEqual(When.outOfTheNight(date(14, 3, 30), calendar: calendar), date(14, 7, 0))
        XCTAssertEqual(When.outOfTheNight(date(14, 6, 59), calendar: calendar), date(14, 7, 0))
        XCTAssertEqual(When.outOfTheNight(date(14, 7, 0), calendar: calendar), date(14, 7, 0))
    }

    func testNormalizeTimes() {
        XCTAssertEqual(When.normalizeTimes("9:00, 21:30"), "09:00 21:30")
        XCTAssertEqual(When.normalizeTimes("8am 1pm"), "08:00 13:00")
        XCTAssertEqual(When.normalizeTimes("8 am, 9 pm"), "08:00 21:00")
        XCTAssertEqual(When.normalizeTimes("9, 9:00"), "09:00")
        XCTAssertEqual(When.normalizeTimes(""), "")
        XCTAssertEqual(When.normalizeTimes("none"), "")
        XCTAssertNil(When.normalizeTimes("morning"))
        XCTAssertNil(When.normalizeTimes("9 10 11 12 13"))
        XCTAssertNil(When.normalizeTimes("25:00"))
    }

    // ------------------------------------------------------------ what the agent asks of the phone

    func testDirectivesAreTakenOutOfTheReply() {
        let parts = Directives.split("Theek hai.\n[[note: The user is called Asha.]]\nAur kuch? [[care: 20h | how the exam went]]")
        XCTAssertEqual(parts.visible, "Theek hai.\n\nAur kuch?")
        XCTAssertEqual(parts.found, [Directive(kind: "note", body: "The user is called Asha."),
                                     Directive(kind: "care", body: "20h | how the exam went")])
    }

    func testDirectiveStillBeingWrittenIsNotShown() {
        XCTAssertEqual(Directives.split("All the best! [[care: 20h | how the ex").visible, "All the best!")
        XCTAssertEqual(Directives.split("All the best! [").visible, "All the best!")
        XCTAssertEqual(Directives.split("All the best! [[").visible, "All the best!")
        XCTAssertTrue(Directives.split("All the best! [[care: 20h | how the ex").found.isEmpty)
    }

    func testOrdinaryBracketsStay() {
        let text = "Use a[0] and [[1, 2], [3]] in Python."
        XCTAssertEqual(Directives.split(text).visible, text)
        XCTAssertTrue(Directives.split(text).found.isEmpty)
    }

    func testThinkingIsHidden() {
        XCTAssertEqual(Directives.split("<think>hmm\nwell</think>Short answer.").visible, "Short answer.")
        XCTAssertEqual(Directives.split("<think>still going").visible, "")
    }

    func testDirectiveNamesInAnyCase() {
        XCTAssertEqual(Directives.split("[[ Note : x ]]").found, [Directive(kind: "note", body: "x")])
        XCTAssertEqual(Directives.split("[[shout: x]]").found, [])
    }

    // ------------------------------------------------------------ commands for the shell

    func testCommandIsTakenOutOfTheReply() {
        let parts = Directives.split("Dekhta hoon.\n[[run: uname -a]]\nBas.")
        XCTAssertEqual(parts.visible, "Dekhta hoon.\n\nBas.")
        XCTAssertEqual(parts.found, [Directive(kind: "run", body: "uname -a")])
    }

    func testCommandMayHoldBracketsAndLines() {
        XCTAssertEqual(Directives.split("[[run: if [[ -f x ]]; then echo y; fi]] ok").found,
                       [Directive(kind: "run", body: "if [[ -f x ]]; then echo y; fi")])
        XCTAssertEqual(Directives.split("[[run: if [[ -f x ]]; then echo y; fi]] ok").visible, "ok")
        XCTAssertEqual(Directives.split("[[run: echo ${a[0]}]]").found, [Directive(kind: "run", body: "echo ${a[0]}")])
        XCTAssertEqual(Directives.split("[[run: echo $a[0]]]").found, [Directive(kind: "run", body: "echo $a[0]")])
        XCTAssertEqual(Directives.split("[[run: [[ a ]]]]").found, [Directive(kind: "run", body: "[[ a ]]")])
        XCTAssertEqual(Directives.split("[[RUN:\ncat <<EOF\nhi\nEOF\n]]z").found, [Directive(kind: "run", body: "cat <<EOF\nhi\nEOF")])
        XCTAssertEqual(Directives.split("[[RUN:\ncat <<EOF\nhi\nEOF\n]]z").visible, "z")
    }

    func testSeveralCommandsAndOtherLines() {
        let parts = Directives.split("[[run: a]][[run: b]] [[note: Likes Linux.]] done [[run:  ]]")
        XCTAssertEqual(parts.found, [Directive(kind: "note", body: "Likes Linux."),
                                     Directive(kind: "run", body: "a"), Directive(kind: "run", body: "b")])
        XCTAssertEqual(parts.visible, "done")
    }

    func testCommandStillBeingWrittenIsNotShown() {
        XCTAssertEqual(Directives.split("Dekhta hoon. [[run: echo hi").visible, "Dekhta hoon.")
        XCTAssertTrue(Directives.split("Dekhta hoon. [[run: echo hi").found.isEmpty)
        XCTAssertEqual(Directives.split("Dekhta hoon. [[run: if [[ -f x ]]; then").visible, "Dekhta hoon.")
        XCTAssertEqual(Directives.split("Dekhta hoon. [[ru").visible, "Dekhta hoon.")
    }

    func testTerminalTextAsPlainText() {
        let raw = "termant:~# \u{1b}[6necho hi\r\nhi\r\n\u{1b}[1;32mgreen\u{1b}[0m \u{1b}]0;title\u{7}x ab\u{8} \u{8}\u{8} \u{8}c \u{1b}[?2004h\u{1b}(B\u{1b}="
        XCTAssertEqual(TerminalText.plain(raw), "termant:~# echo hi\nhi\ngreen x c ")
        XCTAssertEqual(TerminalText.plain("plain\ntext\n"), "plain\ntext\n")
        XCTAssertEqual(TerminalText.tail("one\ntwo\nthree", 100), "one\ntwo\nthree")
        XCTAssertEqual(TerminalText.tail("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\ntwo\nthree", 12), "two\nthree")
        XCTAssertEqual(TerminalText.tail("abcdefghijklmnopqrstuvwxyz", 5), "vwxyz")
    }

    func testWhatTheModelIsToldAboutItsCommands() {
        var ended = ShellRun(command: "echo hi")
        ended.output = "hi\n"
        ended.status = 0
        ended.how = "ended"
        var silent = ShellRun(command: "true")
        silent.status = 0
        silent.how = "ended"
        var slow = ShellRun(command: "sleep 500")
        slow.output = "begun\n"
        slow.status = 137
        slow.how = "timeout"
        var stopped = ShellRun(command: "sleep 9")
        stopped.how = "stopped"
        var failed = ShellRun(command: "ls")
        failed.how = "failed"
        failed.output = "no space left"
        let never = ShellRun(command: "id")
        XCTAssertEqual(ShellReport.text([ended]), "Command output:\n$ echo hi\nhi\n(exit code 0)")
        XCTAssertEqual(ShellReport.text([silent, slow, stopped, failed, never]), """
        Command output:
        $ true
        (it printed nothing)
        (exit code 0)
        $ sleep 500
        begun
        (stopped: it was still running after 5 minutes)
        $ sleep 9
        (stopped by the user)
        $ ls
        (not run: no space left)
        $ id
        (not run)
        """)
    }

    func testLongOutputIsShortenedForTheModel() {
        let long = String(repeating: "0123456789", count: 2000)
        let short = ShellReport.shorten(long)
        XCTAssertLessThan(short.count, ShellReport.headSize + ShellReport.tailSize + 100)
        XCTAssertTrue(short.contains("[... 12000 letters left out ...]"))
        XCTAssertEqual(ShellReport.shorten("short"), "short")
    }

    func testOutputOfACommandIsCollectedWithinLimits() {
        let box = OutputBox()
        box.add(Data("abc\r\n".utf8))
        box.add(Data("\u{1b}[31mred\u{1b}[0m\n".utf8))
        XCTAssertEqual(box.text, "abc\nred\n")
        let big = OutputBox()
        for i in 0..<5000 { big.add(Data("line \(i)\n".utf8)) }
        XCTAssertTrue(big.text.hasPrefix("line 0\nline 1\n"))
        XCTAssertTrue(big.text.hasSuffix("line 4998\nline 4999\n"))
        XCTAssertTrue(big.text.contains("bytes left out"))
        XCTAssertLessThan(big.text.count, OutputBox.headSize + OutputBox.tailSize + 100)
    }

    func testChatOfAnEarlierVersionIsStillRead() throws {
        let old = #"{"id":"6D1E7B0C-1A0B-4E0B-9B0A-0D0C0B0A0908","user":false,"text":"Namaste","raw":"Namaste","failed":false,"first":false,"actions":[],"note":"","at":700000000}"#
        let message = try JSONDecoder().decode(Message.self, from: Data(old.utf8))
        XCTAssertEqual(message.text, "Namaste")
        XCTAssertNil(message.runs)
    }

    func testHalves() {
        XCTAssertEqual(Directives.halves("20h | how it went").when, "20h")
        XCTAssertEqual(Directives.halves("20h | how it went").text, "how it went")
        XCTAssertEqual(Directives.halves("no bar").when, "")
        XCTAssertEqual(Directives.halves("7am | a | b").text, "a | b")
    }

    // ------------------------------------------------------------ the limits of care

    func testDailySlots() {
        let slots = CareRules.dailySlots(times: "09:00 21:00", after: date(14, 10), count: 3, taken: [], calendar: calendar)
        XCTAssertEqual(slots, [date(14, 21), date(15, 9), date(15, 21)])
    }

    func testDailySlotsKeepAwayFromPlannedMoments() {
        let slots = CareRules.dailySlots(times: "09:00 21:00", after: date(14, 10), count: 2,
                                         taken: [date(14, 20, 45)], calendar: calendar)
        XCTAssertEqual(slots, [date(15, 9), date(15, 21)])
    }

    func testDailySlotsStopAtSixADay() {
        let full = (0..<6).map { date(14, 12 + $0) }
        let slots = CareRules.dailySlots(times: "21:30", after: date(14, 10), count: 1, taken: full, calendar: calendar)
        XCTAssertEqual(slots, [date(15, 21, 30)])
    }

    func testDailySlotsWithoutTimes() {
        XCTAssertTrue(CareRules.dailySlots(times: "", after: date(14, 10), count: 3, taken: [], calendar: calendar).isEmpty)
        XCTAssertTrue(CareRules.dailySlots(times: "09:00", after: date(14, 10), count: 0, taken: [], calendar: calendar).isEmpty)
    }

    func testPlaceMovesOutOfTheNight() {
        let now = date(14, 10)
        // 16 hours on is 02:00: nobody is woken for a question
        XCTAssertEqual(CareRules.place(date(15, 2), now: now, taken: [], calendar: calendar), date(15, 7))
        XCTAssertEqual(CareRules.place(date(14, 23, 30), now: now, taken: [], calendar: calendar), date(15, 7))
        XCTAssertEqual(CareRules.place(date(14, 18), now: now, taken: [], calendar: calendar), date(14, 18))
    }

    func testPlaceKeepsANearMomentEvenAtNight() {
        // "in 30 minutes", said at 23:10, is meant
        let now = date(14, 23, 10)
        XCTAssertEqual(CareRules.place(date(14, 23, 40), now: now, taken: [], calendar: calendar), date(14, 23, 40))
    }

    func testPlaceKeepsHalfAnHourApart() {
        let now = date(14, 10)
        let placed = CareRules.place(date(14, 18), now: now, taken: [date(14, 18, 10)], calendar: calendar)
        XCTAssertEqual(placed, date(14, 18, 40))
        // and after the move it is still out of the night
        let late = CareRules.place(date(14, 22, 45), now: now, taken: [date(14, 22, 40)], calendar: calendar)
        XCTAssertEqual(late, date(15, 7))
    }

    func testPlaceMovesToTheNextDayWhenTheDayIsFull() {
        let now = date(14, 8)
        let full = (0..<6).map { date(14, 9 + 2 * $0) }
        XCTAssertEqual(CareRules.place(date(14, 20), now: now, taken: full, calendar: calendar), date(15, 7))
    }

    func testPlaceNeverInThePast() {
        let now = date(14, 10)
        XCTAssertEqual(CareRules.place(date(14, 9), now: now, taken: [], calendar: calendar), now.addingTimeInterval(60))
    }

    // ------------------------------------------------------------ the model's answers

    func testDelta() {
        XCTAssertEqual(LLM.delta(fromLine: #"data: {"choices":[{"delta":{"content":"Hel"}}]}"#), "Hel")
        XCTAssertEqual(LLM.delta(fromLine: #"data:{"choices":[{"message":{"content":"whole"}}]}"#), "whole")
        XCTAssertNil(LLM.delta(fromLine: "data: [DONE]"))
        XCTAssertNil(LLM.delta(fromLine: ": keep-alive"))
        XCTAssertNil(LLM.delta(fromLine: #"data: {"choices":[{"delta":{"role":"assistant"}}]}"#))
        XCTAssertNil(LLM.delta(fromLine: #"data: {"choices":[]}"#))
        XCTAssertNil(LLM.delta(fromLine: "data: {broken"))
    }

    func testErrorMessage() {
        XCTAssertEqual(LLM.errorMessage(Data(#"{"error":{"message":"Invalid API key"}}"#.utf8), status: 401), "Invalid API key")
        XCTAssertEqual(LLM.errorMessage(Data(#"{"error":"nope"}"#.utf8), status: 400), "nope")
        XCTAssertEqual(LLM.errorMessage(Data(#"{"message":"gone"}"#.utf8), status: 404), "gone")
        XCTAssertEqual(LLM.errorMessage(Data("Bad gateway".utf8), status: 502), "Bad gateway")
        XCTAssertEqual(LLM.errorMessage(Data(), status: 503), "HTTP 503")
    }

    func testStreamError() {
        XCTAssertEqual(LLM.streamError(fromLine: #"data: {"error":{"message":"overloaded"}}"#), "overloaded")
        XCTAssertNil(LLM.streamError(fromLine: #"data: {"choices":[]}"#))
    }

    func testModelNames() {
        XCTAssertEqual(LLM.modelNames(Data(#"{"data":[{"id":"a/b:free"},{"id":"c"}]}"#.utf8)), ["a/b:free", "c"])
        // Gemini puts "models/" before its names
        XCTAssertEqual(LLM.modelNames(Data(#"{"data":[{"id":"models/gemini-x"}]}"#.utf8)), ["gemini-x"])
        XCTAssertEqual(LLM.modelNames(Data(#"[{"id":"plain"}]"#.utf8)), ["plain"])
        XCTAssertEqual(LLM.modelNames(Data("not json".utf8)), [])
    }

    func testEndpoint() {
        XCTAssertEqual(LLM.endpoint("https://openrouter.ai/api/v1", "models")?.absoluteString, "https://openrouter.ai/api/v1/models")
        XCTAssertEqual(LLM.endpoint(" http://10.0.0.2:8080/v1// ", "chat/completions")?.absoluteString,
                       "http://10.0.0.2:8080/v1/chat/completions")
        XCTAssertNil(LLM.endpoint("", "models"))
    }

    func testWhichFailuresAnotherModelMayGetPast() {
        XCTAssertTrue(LLM.Failure(status: 429, message: "").anotherModelMayWork)
        XCTAssertTrue(LLM.Failure(status: 404, message: "").anotherModelMayWork)
        XCTAssertFalse(LLM.Failure(status: 401, message: "").anotherModelMayWork)
        XCTAssertFalse(LLM.Failure(status: 0, message: "").anotherModelMayWork)
    }

    func testAnthropicGetsItsOwnHeader() {
        var request = URLRequest(url: URL(string: "https://api.anthropic.com/v1/models")!)
        LLM.authorize(&request, base: "https://api.anthropic.com/v1", key: "k")
        XCTAssertEqual(request.value(forHTTPHeaderField: "x-api-key"), "k")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer k")
        var other = URLRequest(url: URL(string: "https://openrouter.ai/api/v1/models")!)
        LLM.authorize(&other, base: "https://openrouter.ai/api/v1", key: "k")
        XCTAssertNil(other.value(forHTTPHeaderField: "x-api-key"))
    }

    // ------------------------------------------------------------ the address of the terminal

    func testRemoteAddress() {
        XCTAssertEqual(Remote.normalize("192.168.1.5:3000")?.absoluteString, "http://192.168.1.5:3000/")
        XCTAssertEqual(Remote.normalize(" https://term.example.com ")?.absoluteString, "https://term.example.com/")
        XCTAssertEqual(Remote.normalize("https://example.com/term")?.absoluteString, "https://example.com/term/")
        XCTAssertEqual(Remote.normalize("http://example.com/term/?x=1#y")?.absoluteString, "http://example.com/term/")
        XCTAssertNil(Remote.normalize(""))
        XCTAssertNil(Remote.normalize("ftp://example.com"))
        XCTAssertNil(Remote.normalize("my server"))
    }

    // ------------------------------------------------------------ texts

    func testEveryTextExistsInBothLanguages() {
        XCTAssertEqual(Set(L.en.keys), Set(L.hi.keys))
        for (key, value) in L.en {
            let placeholders = value.components(separatedBy: "%@").count
            XCTAssertEqual(L.hi[key]?.components(separatedBy: "%@").count, placeholders, key)
        }
    }

    func testProviders() {
        XCTAssertEqual(Set(Provider.all.map { $0.id }).count, Provider.all.count)
        for p in Provider.all where p.id != Provider.custom {
            XCTAssertNotNil(LLM.endpoint(p.base, "chat/completions"), p.id)
            XCTAssertTrue(p.base.hasPrefix("https://"), p.id)
        }
        XCTAssertEqual(Provider.named("nothing").id, Provider.all[0].id)
    }
}
