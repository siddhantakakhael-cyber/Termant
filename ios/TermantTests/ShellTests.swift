import XCTest
@testable import Termant

/// The Linux inside the app (iSH and Alpine Linux, see ios/ish and ios/Shell): commands, a terminal,
/// and the agent using it. The stand-in model service (ci/fake-llm.js) plays the agent.
@MainActor
final class ShellTests: XCTestCase {
    private var prefs: Prefs { Prefs.shared }
    private var chat: ChatStore { ChatStore.shared }
    private var shell: Shell { Shell.shared }

    private func settle(_ seconds: Double = 30) async {
        let end = Date().addingTimeInterval(seconds)
        try? await Task.sleep(nanoseconds: 50_000_000)
        while (Care.shared.isSyncing || chat.busy) && Date() < end {
            try? await Task.sleep(nanoseconds: 50_000_000)
        }
    }

    /// Waits until the condition holds. False if it did not within the time.
    private func eventually(_ seconds: Double, _ condition: () -> Bool) async -> Bool {
        let end = Date().addingTimeInterval(seconds)
        while !condition() {
            if Date() > end { return false }
            try? await Task.sleep(nanoseconds: 50_000_000)
        }
        return true
    }

    override func setUp() async throws {
        continueAfterFailure = false
        await settle()
        prefs.language = "hi"
        prefs.provider = Provider.custom
        prefs.customBase = StoreTests.llm
        prefs.setKey("test-key")
        prefs.model = "test/beta:free"
        prefs.cachedModels = ["test/alpha:free", "test/beta:free", "test/paid"]
        prefs.autoSwitch = true
        prefs.careOn = false
        prefs.shellAgent = true
        Care.shared.resetForTests()
        chat.newChat()
        let started = await shell.ready()
        XCTAssertTrue(started, "Linux did not start: \(shell.state)")
    }

    override func tearDown() async throws {
        await settle()
        prefs.shellAgent = true
    }

    // ------------------------------------------------------------ commands

    func testCommandGivesOutputAndExitCode() async {
        let run = await shell.run("echo hello; echo oops >&2; exit 3")
        XCTAssertEqual(run.how, "ended")
        XCTAssertEqual(run.status, 3)
        XCTAssertEqual(run.output, "hello\noops\n")
        XCTAssertEqual(run.command, "echo hello; echo oops >&2; exit 3")
    }

    func testItIsAlpineLinuxForX86() async {
        let run = await shell.run("uname -m; cat /etc/alpine-release; id -u; pwd; hostname; echo $((6 * 7))")
        XCTAssertEqual(run.output, "i686\n3.19.9\n0\n/root\ntermant\n42\n")
        XCTAssertEqual(run.status, 0)
    }

    func testFilesStayBetweenCommands() async {
        let name = "kept-\(Int(Date().timeIntervalSince1970)).txt"
        let made = await shell.run("mkdir -p work && cd work && printf 'line one\\nline two\\n' > \(name) && ls | wc -l")
        XCTAssertEqual(made.status, 0, made.output)
        let read = await shell.run("grep -c line work/\(name) && rm work/\(name) && echo gone")
        XCTAssertEqual(read.output, "2\ngone\n")
    }

    func testSeveralLinesAndAPipe() async {
        let script = """
        total=0
        for n in 1 2 3 4; do
          total=$((total + n))
        done
        echo "sum $total" | tr a-z A-Z
        if [[ -d /etc ]]; then echo yes; fi
        """
        let run = await shell.run(script)
        XCTAssertEqual(run.output, "SUM 10\nyes\n")
    }

    func testProgramThatIsNotThere() async {
        let run = await shell.run("no-such-program --version")
        XCTAssertEqual(run.how, "ended")
        XCTAssertEqual(run.status, 127)
        XCTAssertTrue(run.output.contains("not found"), run.output)
    }

    func testCommandThatTakesTooLongIsEnded() async {
        let began = Date()
        let run = await shell.run("echo before; sleep 60; echo after", seconds: 2)
        XCTAssertEqual(run.how, "timeout")
        XCTAssertEqual(run.output, "before\n")
        XCTAssertEqual(run.status, 128 + 9)
        XCTAssertLessThan(Date().timeIntervalSince(began), 4.5, "it was not ended at once")
        // nothing of it is left behind
        let left = await shell.run("pidof sleep; echo end")
        XCTAssertEqual(left.output, "end\n")
        // and the shell is as before
        let next = await shell.run("echo fine")
        XCTAssertEqual(next.output, "fine\n")
    }

    func testCommandCanBeStopped() async {
        let task = Task { await Shell.shared.run("echo started; sleep 60; echo finished") }
        try? await Task.sleep(nanoseconds: 2_000_000_000)
        let began = Date()
        task.cancel()
        let run = await task.value
        XCTAssertEqual(run.how, "stopped")
        XCTAssertEqual(run.output, "started\n")
        XCTAssertLessThan(Date().timeIntervalSince(began), 2.5, "it was not ended at once")
        let left = await shell.run("pidof sleep; echo end")
        XCTAssertEqual(left.output, "end\n")
    }

    func testLongOutputKeepsItsBeginningAndItsEnd() async {
        let run = await shell.run("seq 1 20000")
        XCTAssertEqual(run.status, 0)
        XCTAssertTrue(run.output.hasPrefix("1\n2\n3\n"), String(run.output.prefix(40)))
        XCTAssertTrue(run.output.hasSuffix("19999\n20000\n"), String(run.output.suffix(40)))
        XCTAssertTrue(run.output.contains("bytes left out"))
        XCTAssertLessThan(run.output.count, OutputBox.headSize + OutputBox.tailSize + 100)
    }

    func testLinuxReachesTheNetwork() async {
        // the stand-in model service of the tests, asked from inside Linux
        let run = await shell.run("wget -q -O - http://127.0.0.1:8787/healthz")
        XCTAssertEqual(run.status, 0, run.output)
        XCTAssertTrue(run.output.contains("\"ok\":true"), run.output)
    }

    /// Not a check of this app: it writes down whether Alpine's own servers can be reached from here,
    /// which is what "apk add" needs. Read the result in the log of the test run.
    func testPackageListReport() async {
        let run = await shell.run("apk update 2>&1 | tail -5", seconds: 100)
        print("APK-REPORT how=\(run.how) status=\(run.status) output=\(run.output.replacingOccurrences(of: "\n", with: " | "))")
        XCTAssertTrue(["ended", "timeout"].contains(run.how), run.how)
        let next = await shell.run("echo fine")
        XCTAssertEqual(next.output, "fine\n")
    }

    // ------------------------------------------------------------ a terminal

    func testTerminal() async {
        let session = ShellSession()
        var seen = ""
        var ended = false
        session.onOutput = { seen += TerminalText.plain(String(decoding: $0, as: UTF8.self)) }
        session.onEnded = { ended = true }
        XCTAssertTrue(session.open(columns: 80, rows: 24))
        XCTAssertTrue(session.isOpen)
        let prompt = await eventually(30) { seen.contains("termant:~#") }
        XCTAssertTrue(prompt, seen)
        XCTAssertTrue(seen.contains("Alpine Linux"), seen) // the greeting

        session.type(Data("echo typed-$((6 * 7)); stty size\n".utf8))
        let answered = await eventually(30) { seen.contains("typed-42\n") && seen.contains("24 80\n") }
        XCTAssertTrue(answered, seen)

        session.resize(columns: 100, rows: 30)
        session.type(Data("stty size\n".utf8))
        let resized = await eventually(30) { seen.contains("30 100\n") }
        XCTAssertTrue(resized, seen)

        // Ctrl+C ends what runs in front
        session.type(Data("sleep 40\n".utf8))
        try? await Task.sleep(nanoseconds: 1_000_000_000)
        let began = Date()
        session.type(Data([3]))
        session.type(Data("echo back-$((40 + 2))\n".utf8))
        let back = await eventually(30) { seen.contains("back-42\n") }
        XCTAssertTrue(back, seen)
        XCTAssertLessThan(Date().timeIntervalSince(began), 20, "Ctrl+C did not end the sleep")

        session.type(Data("exit\n".utf8))
        let left = await eventually(30) { ended }
        XCTAssertTrue(left, seen)
        session.close()
        XCTAssertFalse(session.isOpen)

        // a file made in the terminal is there for the agent's commands, and the other way round
        let other = ShellSession()
        var second = ""
        other.onOutput = { second += TerminalText.plain(String(decoding: $0, as: UTF8.self)) }
        XCTAssertTrue(other.open(columns: 80, rows: 24))
        other.type(Data("echo from-the-terminal > /root/shared.txt; echo written-$((20 + 3))\n".utf8))
        let written = await eventually(30) { second.contains("written-23\n") }
        XCTAssertTrue(written, second)
        other.close()
        let run = await shell.run("cat /root/shared.txt; rm /root/shared.txt")
        XCTAssertEqual(run.output, "from-the-terminal\n")
    }

    // ------------------------------------------------------------ the agent

    func testAgentRunsACommandAndSaysWhatCameOfIt() async {
        let reply = await chat.sendAndWait("chalao: echo hello-linux")
        let messages = chat.chat.messages
        XCTAssertEqual(messages.count, 3)
        guard messages.count == 3 else { return }
        XCTAssertEqual(messages[1].text, "Chalata hoon.")
        XCTAssertEqual(messages[1].runs?.count, 1)
        XCTAssertEqual(messages[1].runs?.first?.command, "echo hello-linux")
        XCTAssertEqual(messages[1].runs?.first?.output, "hello-linux\n")
        XCTAssertEqual(messages[1].runs?.first?.status, 0)
        XCTAssertEqual(messages[1].runs?.first?.how, "ended")
        XCTAssertEqual(messages[2].text, "Shell ne kaha: hello-linux / (exit code 0)")
        XCTAssertEqual(reply?.id, messages[2].id)
        XCTAssertNil(messages[2].runs)
        XCTAssertFalse(chat.busy)

        // what the model was sent: its own line, and the output as the next message
        let wire = Prompt.wire(messages, extra: nil)
        XCTAssertEqual(wire.map { $0["role"] ?? "" }, ["system", "user", "assistant", "user", "assistant"])
        XCTAssertEqual(wire[2]["content"], "Chalata hoon.\n[[run: echo hello-linux]]")
        XCTAssertEqual(wire[3]["content"], "Command output:\n$ echo hello-linux\nhello-linux\n(exit code 0)")
        XCTAssertTrue((wire[0]["content"] ?? "").contains("[[run: uname -a]]"))

        // the chat is kept with the commands
        let id = chat.chat.id
        chat.newChat()
        chat.open(id)
        XCTAssertEqual(chat.chat.messages.count, 3)
        XCTAssertEqual(chat.chat.messages[1].runs?.first?.output, "hello-linux\n")
    }

    func testAgentGoesOnStepByStep() async {
        _ = await chat.sendAndWait("chalao: echo step-one")
        let messages = chat.chat.messages
        XCTAssertEqual(messages.count, 4)
        guard messages.count == 4 else { return }
        XCTAssertEqual(messages[1].runs?.first?.output, "step-one\n")
        XCTAssertEqual(messages[2].text, "Ek aur.")
        XCTAssertEqual(messages[2].runs?.first?.command, "echo step-two; exit 3")
        XCTAssertEqual(messages[2].runs?.first?.status, 3)
        XCTAssertEqual(messages[3].text, "Shell ne kaha: step-two / (exit code 3)")
    }

    func testAgentIsHeldBackWhenItNeverStops() async {
        _ = await chat.sendAndWait("chalao: echo again")
        let messages = chat.chat.messages
        // the user's message, then one reply for every round, and the one that was not carried out any more
        XCTAssertEqual(messages.count, 1 + ChatStore.maxRounds + 1)
        XCTAssertEqual(messages.last?.runs?.first?.how, "failed")
        XCTAssertEqual(messages.last?.runs?.first?.output, L.t("run_limit"))
        XCTAssertEqual(messages.dropLast().last?.runs?.first?.output, "again\n")
        XCTAssertFalse(chat.busy)
    }

    func testAgentWithoutTheShell() async {
        prefs.shellAgent = false
        let reply = await chat.sendAndWait("chalao: echo not-this-time")
        XCTAssertEqual(chat.chat.messages.count, 2)
        XCTAssertEqual(reply?.text, "Chalata hoon.")
        XCTAssertNil(reply?.runs)
        XCTAssertEqual(reply?.actions, [L.t("did_run_off")])
        let system = Prompt.system()
        XCTAssertFalse(system.contains("[[run:"))
        XCTAssertTrue(system.contains("switched the app's Linux shell off"))
    }

    func testStopEndsTheAgentsCommand() async {
        chat.send("chalao: echo begun; sleep 60; echo ended")
        let running = await eventually(30) { self.chat.chat.messages.last?.runs?.first?.how == "running" }
        XCTAssertTrue(running)
        try? await Task.sleep(nanoseconds: 1_500_000_000)
        let began = Date()
        chat.stop()
        let over = await eventually(20) { !self.chat.busy }
        XCTAssertTrue(over)
        XCTAssertLessThan(Date().timeIntervalSince(began), 2.5, "the command was not ended at once")
        XCTAssertEqual(chat.chat.messages.count, 2) // nothing more was asked of the model
        XCTAssertEqual(chat.chat.messages.last?.runs?.first?.how, "stopped")
        XCTAssertEqual(chat.chat.messages.last?.runs?.first?.output, "begun\n")
        // the next message is answered as usual
        let reply = await chat.sendAndWait("chalao: echo after-the-stop")
        XCTAssertEqual(reply?.text, "Shell ne kaha: after-the-stop / (exit code 0)")
    }
}
