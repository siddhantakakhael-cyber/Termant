import Foundation
import Speech
import AVFoundation

/// Hears the user. Two ways, like the Android app: one press of the
/// microphone gives one message, and, when it is on, it waits for the words
/// "hey Termant" and takes what comes after them as the message.
///
/// All the callbacks (onMark, onCommand, onProblem) arrive on the main thread.
final class Voice {
    enum Mark {
        case idle       // hears nothing
        case listening  // the microphone is open
        case armed      // "hey Termant" was heard; the words after it are the message
    }

    var onMark: ((Mark) -> Void)?
    var onCommand: ((String) -> Void)?
    var onProblem: ((String) -> Void)?

    private(set) var mark = Mark.idle
    private(set) var wakeOn = false

    private let recognizer = SFSpeechRecognizer(locale: Locale(identifier: "en-IN"))
    private let engine = AVAudioEngine()
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private var oneShot = false     // a press of the microphone waits for one message
    private var commandText = ""    // while oneShot: the words so far
    private var tail = ""           // while armed: the words after the wake word
    private var armedAt = Date.distantPast
    private var mistakes = 0
    private var restarting = false
    private let wakePattern = try! NSRegularExpression(
        pattern: "t[ea]a?r?m[ae]?nt", options: [.caseInsensitive])

    // ------------------------------------------------------------ permissions

    /// Both questions in one; the phone shows them when needed.
    func requestAccess(_ done: @escaping (Bool) -> Void) {
        SFSpeechRecognizer.requestAuthorization { status in
            AVAudioSession.sharedInstance().requestRecordPermission { mic in
                DispatchQueue.main.async { done(status == .authorized && mic) }
            }
        }
    }

    // ------------------------------------------------------------ the two ways

    /// The microphone button: one press, one message.
    func hearOnce() {
        guard !engine.isRunning else { return } // the wake word has the microphone
        oneShot = true
        commandText = ""
        setMark(.listening)
        startEngine()
    }

    /// The wake word, on or off.
    func setWake(_ on: Bool) {
        wakeOn = on
        if on {
            oneShot = false
            tail = ""
            if !engine.isRunning {
                setMark(.listening)
                startEngine()
            }
        } else if !oneShot {
            tail = ""
            stopEngine()
            setMark(.idle)
        }
    }

    // ------------------------------------------------------------ listening

    private func setMark(_ m: Mark) {
        guard m != mark else { return }
        mark = m
        onMark?(m)
    }

    private func startEngine() {
        cancelTask()
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playAndRecord, mode: .measurement,
                                    options: [.duckOthers, .defaultToSpeaker])
            try session.setActive(true, options: [])
        } catch {
            onProblem?("The microphone could not be opened: " + error.localizedDescription)
            oneShot = false
            setMark(.idle)
            return
        }
        guard let rec = recognizer, rec.isAvailable else {
            onProblem?("Speech recognition is not available on this phone.")
            oneShot = false
            setMark(.idle)
            return
        }
        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true
        if rec.supportsOnDeviceRecognition {
            request.requiresOnDeviceRecognition = true // no internet, no account
        }
        self.request = request
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        input.removeTap(onBus: 0)
        input.installTap(onBus: 0, bufferSize: 1024, format: format) { buffer, _ in
            request.append(buffer)
        }
        engine.prepare()
        do { try engine.start() } catch {
            onProblem?("The microphone did not start: " + error.localizedDescription)
            oneShot = false
            setMark(.idle)
            return
        }
        task = rec.recognitionTask(with: request) { [weak self] result, error in
            if let result { self?.result(result) }
            if let error { self?.taskError(error) }
        }
    }

    private func stopEngine() {
        cancelTask()
        if engine.isRunning { engine.stop() }
        engine.inputNode.removeTap(onBus: 0)
        AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation])
    }

    private func cancelTask() {
        task?.cancel()
        task = nil
        request = nil
    }

    /// The task ends on silence or after a minute; while something is wanted,
    /// a new one starts a moment later.
    private func restartSoon() {
        guard !restarting else { return }
        restarting = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
            guard let self else { return }
            self.restarting = false
            if self.wakeOn || self.oneShot { self.startEngine() }
            else { self.stopEngine(); self.setMark(.idle) }
        }
    }

    private func taskError(_ error: Error) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            guard self.wakeOn || self.oneShot else { return }
            self.mistakes += 1
            if self.mistakes >= 5 {
                // like the Android app: after five in a row, the listening is off
                self.mistakes = 0
                self.wakeOn = false
                self.oneShot = false
                self.tail = ""
                self.stopEngine()
                self.setMark(.idle)
                self.onProblem?("The microphone kept failing, so the listening is off. Turn it on again from the gear.")
                return
            }
            self.restartSoon()
        }
    }

    private func result(_ r: SFSpeechRecognitionResult) {
        let text = clean(r.bestTranscription.formattedString)
        let isFinal = r.isFinal
        DispatchQueue.main.async { [weak self] in
            self?.handle(text, isFinal: isFinal)
        }
    }

    private func handle(_ text: String, isFinal: Bool) {
        // The words after the last "termant" in what was heard so far.
        if wakeOn, let after = wordsAfterWake(text) {
            tail = after
            if mark != .armed {
                armedAt = Date()
                setMark(.armed)
            }
        }
        if mark == .armed {
            if isFinal {
                let message = clean(tail)
                tail = ""
                if message.count >= 3 { onCommand?(message) }
                if wakeOn { setMark(.listening); restartSoon() }
                else { stopEngine(); setMark(.idle) }
            } else if Date().timeIntervalSince(armedAt) > 15 {
                // fifteen quiet seconds: the wake word was in the background
                tail = ""
                setMark(.listening)
            }
        } else if oneShot {
            if isFinal {
                let message = clean(commandText.isEmpty ? text : commandText)
                oneShot = false
                stopEngine()
                setMark(.idle)
                if message.count >= 3 { onCommand?(message) }
            } else {
                commandText = text
            }
        }
    }

    // ------------------------------------------------------------ words

    /// The words after the newest "termant" in the heard text, or nil.
    private func wordsAfterWake(_ text: String) -> String? {
        let ns = text as NSString
        var last: NSRange?
        for m in wakePattern.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
            last = m.range
        }
        guard let range = last, range.location + range.length <= ns.length else { return nil }
        return clean(ns.substring(from: range.location + range.length))
    }

    /// Lowercase letters and numbers, one space between words — the wake word
    /// must be found in "Hey, TERMANT?" as well as in "hey termant".
    private func clean(_ s: String) -> String {
        let letters = s.lowercased()
            .replacingOccurrences(of: #"[^\p{L}\p{N} ]"#, with: "", options: .regularExpression)
        return letters.replacingOccurrences(of: " +", with: " ")
            .trimmingCharacters(in: .whitespaces)
    }
}
