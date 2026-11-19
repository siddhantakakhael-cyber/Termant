import XCTest

/// The app as a person uses it, on the simulator: the screens, a conversation with the
/// stand-in model service, a check-in that arrives as a notification, and the terminal
/// of a real Termant server. ci/test.sh starts the two services.
final class TermantUITests: XCTestCase {
    private let llm = "http://127.0.0.1:8787/v1"
    private let server = "http://127.0.0.1:3111"
    private let serverPassword = "ci-password"
    private let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")

    override func setUp() {
        continueAfterFailure = false
    }

    private func launch(_ extra: [String: String] = [:], model: Bool = true) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchEnvironment["TERMANT_UITEST"] = "1"
        if model {
            app.launchEnvironment["TERMANT_TEST_BASE"] = llm
            app.launchEnvironment["TERMANT_TEST_KEY"] = "test-key"
            app.launchEnvironment["TERMANT_TEST_MODEL"] = "test/beta:free"
        }
        for (key, value) in extra { app.launchEnvironment[key] = value }
        app.launch()
        return app
    }

    private func shot(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    /// Any element whose text contains this.
    private func text(_ app: XCUIApplication, _ part: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", part)).firstMatch
    }

    private func say(_ app: XCUIApplication, _ message: String) {
        let input = app.descendants(matching: .any)["chat.input"]
        XCTAssertTrue(input.waitForExistence(timeout: 10))
        input.tap()
        input.typeText(message)
        app.buttons["chat.send"].tap()
    }

    // ------------------------------------------------------------ first start

    func testFirstStartAsksForAKey() {
        // check-ins off: with a made-up key nothing is to be asked of a real service
        let app = launch(["TERMANT_TEST_CARE_OFF": "1"], model: false)
        XCTAssertTrue(app.staticTexts["Namaste"].waitForExistence(timeout: 20))
        XCTAssertTrue(app.staticTexts["welcome.needkey"].exists)
        XCTAssertFalse(app.buttons["chat.send"].isEnabled)
        shot("01-first-start")

        app.buttons["welcome.settings"].tap()
        XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 10))
        XCTAssertTrue(text(app, "Koi key save nahi hai").exists)
        shot("02-settings")

        // a key typed here is kept, and shown only by its last letters
        let key = app.secureTextFields["settings.key"]
        key.tap()
        key.typeText("sk-or-v1-abcdefgh1234")
        app.buttons["settings.key.save"].tap()
        XCTAssertTrue(text(app, "Save hai: •••• 1234").waitForExistence(timeout: 5))
        shot("03-key-saved")

        // English
        app.buttons["English"].tap()
        XCTAssertTrue(text(app, "Saved: •••• 1234").waitForExistence(timeout: 5))
        app.buttons["settings.close"].tap()
        XCTAssertTrue(app.staticTexts["Namaste"].waitForExistence(timeout: 5))
        // with a key the hint is gone
        XCTAssertFalse(app.staticTexts["welcome.needkey"].exists)
    }

    // ------------------------------------------------------------ a conversation

    func testConversationAndMemory() {
        let app = launch(["TERMANT_TEST_CARE_OFF": "1"])
        say(app, "Mera naam Asha hai, yaad rakho")
        XCTAssertTrue(text(app, "Theek hai, yaad rakhunga.").waitForExistence(timeout: 20))
        XCTAssertTrue(text(app, "Yaad rakha: The user is called Asha.").waitForExistence(timeout: 5))
        // the line the agent wrote for the app is not shown
        XCTAssertFalse(text(app, "[[note").exists)
        shot("04-remembered")

        // a new chat still knows it
        app.buttons["bar.new"].tap()
        say(app, "Mera naam kya hai?")
        XCTAssertTrue(text(app, "Aapka naam Asha hai.").waitForExistence(timeout: 20))
        shot("05-new-chat-knows")

        // the note is listed in Settings, and the earlier chat in the history
        app.buttons["bar.settings"].tap()
        XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 10))
        let note = text(app, "The user is called Asha.")
        for _ in 0..<8 where !note.isHittable { app.swipeUp() }
        XCTAssertTrue(note.exists)
        shot("06-settings-notes")
        for _ in 0..<8 where !app.buttons["settings.close"].isHittable { app.swipeDown() }
        app.buttons["settings.close"].tap()

        app.buttons["bar.history"].tap()
        let earlier = text(app, "Mera naam Asha hai, yaad rakho")
        XCTAssertTrue(earlier.waitForExistence(timeout: 10))
        shot("07-history")
        earlier.tap()
        XCTAssertTrue(text(app, "Theek hai, yaad rakhunga.").waitForExistence(timeout: 10))
    }

    func testMovesToAnotherModelAndShowsMarkup() {
        let app = launch(["TERMANT_TEST_CARE_OFF": "1", "TERMANT_TEST_MODEL": "test/alpha:free"])
        say(app, "limit check")
        XCTAssertTrue(text(app, "Echo from test/beta:free: limit check").waitForExistence(timeout: 20))
        XCTAssertTrue(text(app, "beta:free par chala gaya").exists)
        say(app, "bold please")
        XCTAssertTrue(text(app, "This is bold and code.").waitForExistence(timeout: 20))
        shot("08-switched-model")
    }

    // ------------------------------------------------------------ looking after the user

    func testAskMeNow() {
        let app = launch()
        XCTAssertTrue(app.staticTexts["Namaste"].waitForExistence(timeout: 20))
        app.buttons["bar.settings"].tap()
        XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 10))
        let now = app.buttons["settings.care.now"]
        for _ in 0..<8 where !now.isHittable { app.swipeUp() }
        // the daily questions are planned and written ahead of time
        XCTAssertTrue(text(app, "Namaste! Din kaisa ja raha hai?").waitForExistence(timeout: 20))
        shot("09-care-settings")
        now.tap()
        XCTAssertTrue(text(app, "Namaste! Aaj aap kaise hain?").waitForExistence(timeout: 20))
        XCTAssertTrue(text(app, "Termant ne khud poochha").exists)
        shot("10-asked-first")
        say(app, "theek hoon")
        XCTAssertTrue(text(app, "Echo from test/beta:free: theek hoon").waitForExistence(timeout: 20))
    }

    /// The whole way on an iPhone: the agent plans to come back, the message is written ahead,
    /// iOS shows it as a notification while the app is not in front, and a tap leads to it in the chat.
    func testCheckInArrivesAsNotification() {
        let app = launch(["TERMANT_TEST_NOTIFICATIONS": "1", "TERMANT_TEST_CARE_TIMES": ""])
        say(app, "kal mera exam hai")
        XCTAssertTrue(text(app, "All the best for the exam!").waitForExistence(timeout: 20))
        XCTAssertTrue(text(app, "Baad mein poochhega").waitForExistence(timeout: 5))

        // iOS asks once whether the app may notify
        let allow = springboard.buttons["Allow"]
        XCTAssertTrue(allow.waitForExistence(timeout: 20), "iOS did not ask about notifications")
        shot("11-permission")
        allow.tap()
        sleep(2) // the notification is handed to iOS right after the answer
        shot("12-planned")

        XCUIDevice.shared.press(.home)
        let banner = springboard.descendants(matching: .any)
            .matching(NSPredicate(format: "label CONTAINS %@", "how the exam went")).firstMatch
        XCTAssertTrue(banner.waitForExistence(timeout: 150), "no notification within two and a half minutes")
        shot("13-notification")
        banner.tap()

        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 20))
        XCTAssertTrue(text(app, "Arre, how the exam went? Batao na, kaisa raha?").waitForExistence(timeout: 20))
        XCTAssertTrue(text(app, "Termant ne khud poochha").exists)
        shot("14-check-in-in-chat")
    }

    // ------------------------------------------------------------ the terminal of one's own server

    func testTerminalOfOwnServer() {
        let app = launch(["TERMANT_TEST_REMOTE": server, "TERMANT_TEST_REMOTE_PASSWORD": serverPassword,
                          "TERMANT_TEST_CARE_OFF": "1"])
        app.buttons["bar.terminal"].tap()
        // logged in with the saved password: the terminal's own keys are there, the login form is not
        let esc = app.webViews.buttons["Esc"]
        XCTAssertTrue(esc.waitForExistence(timeout: 40), "the terminal page did not open")
        XCTAssertFalse(app.webViews.buttons["Open terminal"].exists)
        XCTAssertTrue(app.webViews.buttons["Log out"].exists)
        sleep(3) // the shell's prompt
        shot("15-terminal")

        // typing reaches the shell on the server: it writes a file there (the simulator shares the Mac's /tmp)
        let proof = "/tmp/termant-ios-typed.txt"
        try? FileManager.default.removeItem(atPath: proof)
        app.webViews.firstMatch.tap()
        app.typeText("echo typed-on-the-iphone > \(proof)\n")
        var written = ""
        for _ in 0..<20 where written.isEmpty {
            sleep(1)
            written = (try? String(contentsOfFile: proof, encoding: .utf8)) ?? ""
        }
        shot("16-terminal-typed")
        XCTAssertEqual(written.trimmingCharacters(in: .whitespacesAndNewlines), "typed-on-the-iphone")
        app.buttons["remote.close"].tap()
        XCTAssertTrue(app.buttons["bar.terminal"].waitForExistence(timeout: 10))
    }

    func testTerminalWithWrongPasswordShowsTheLoginForm() {
        let app = launch(["TERMANT_TEST_REMOTE": server, "TERMANT_TEST_REMOTE_PASSWORD": "wrong",
                          "TERMANT_TEST_CARE_OFF": "1"])
        app.buttons["bar.terminal"].tap()
        XCTAssertTrue(app.webViews.buttons["Open terminal"].waitForExistence(timeout: 40))
        shot("17-terminal-login")
    }

    func testTerminalWithoutServerSaysSo() {
        let app = launch(["TERMANT_TEST_REMOTE": "http://127.0.0.1:9", "TERMANT_TEST_CARE_OFF": "1"])
        app.buttons["bar.terminal"].tap()
        XCTAssertTrue(app.staticTexts["remote.problem"].waitForExistence(timeout: 40))
        shot("18-terminal-unreachable")
    }

    func testTerminalButtonLeadsToSettingsWhenNoServerIsNamed() {
        let app = launch(["TERMANT_TEST_CARE_OFF": "1"])
        app.buttons["bar.terminal"].tap()
        XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 10))
    }
}
