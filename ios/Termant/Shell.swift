import Foundation

/// One command the agent ran in the shell, kept with the reply that asked for it.
struct ShellRun: Codable, Equatable, Identifiable {
    var id = UUID()
    var command: String
    var output = ""
    /// The exit code; -1 when there is none.
    var status = -1
    /// "waiting", "running", "ended", "timeout", "stopped" or "failed".
    var how = "waiting"
}

/// Collects what a command prints: the beginning and the end, when it is more than fits.
final class OutputBox: @unchecked Sendable {
    static let headSize = 8_000, tailSize = 16_000
    private let lock = NSLock()
    private var head = Data()
    private var tail = Data()
    private var dropped = 0

    func add(_ bytes: UnsafePointer<CChar>, _ length: Int) {
        add(Data(bytes: bytes, count: length))
    }

    func add(_ piece: Data) {
        lock.lock()
        defer { lock.unlock() }
        var rest = piece
        if head.count < OutputBox.headSize {
            let take = min(OutputBox.headSize - head.count, rest.count)
            head.append(rest.prefix(take))
            rest = Data(rest.dropFirst(take))
        }
        tail.append(rest)
        if tail.count > OutputBox.tailSize {
            let extra = tail.count - OutputBox.tailSize
            dropped += extra
            tail = Data(tail.dropFirst(extra))
        }
    }

    var text: String {
        lock.lock()
        defer { lock.unlock() }
        var s = String(decoding: head, as: UTF8.self)
        if dropped > 0 { s += "\n[... \(dropped) bytes left out ...]\n" }
        s += String(decoding: tail, as: UTF8.self)
        return TerminalText.plain(s)
    }
}

/// Says across threads that the user does not want a command any more.
final class StopFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var raised = false

    func raise() {
        lock.lock()
        raised = true
        lock.unlock()
    }

    var isRaised: Bool {
        lock.lock()
        defer { lock.unlock() }
        return raised
    }
}

/// The Linux inside the app: the iSH engine (github.com/ish-app/ish) running Alpine Linux from
/// the app's own folder. Gets it ready, and runs commands for the agent. The C side is in ios/Shell.
@MainActor
final class Shell: ObservableObject {
    static let shared = Shell()

    enum State: Equatable {
        case off, preparing, ready
        case failed(String)
    }

    @Published private(set) var state: State = .off
    private var waiting: [CheckedContinuation<Bool, Never>] = []
    private let starting = DispatchQueue(label: "termant.shell.start")
    private static let running = DispatchQueue(label: "termant.shell.run", attributes: .concurrent)

    /// Where Linux keeps its files.
    nonisolated static var folder: URL {
        Files.support.appendingPathComponent("linux", isDirectory: true)
    }

    private init() {}

    /// Unpacks Alpine the first time and starts Linux. Returns at once; `state` tells how it went.
    func start() {
        guard state == .off else { return }
        state = .preparing
        let motd = L.t("shell_motd") + "\n"
        starting.async {
            let problem = Shell.prepare(motd: motd)
            Task { @MainActor in
                let shell = Shell.shared
                shell.state = problem.map { State.failed($0) } ?? .ready
                let all = shell.waiting
                shell.waiting = []
                for one in all { one.resume(returning: problem == nil) }
            }
        }
    }

    /// Waits until Linux runs. False if it could not be started.
    func ready() async -> Bool {
        start()
        switch state {
        case .ready: return true
        case .failed: return false
        default: return await withCheckedContinuation { waiting.append($0) }
        }
    }

    /// Runs one command line with /bin/sh in /root and returns what it printed.
    func run(_ command: String, seconds: Int = ShellReport.minutes * 60) async -> ShellRun {
        var record = ShellRun(command: command)
        guard await ready() else {
            record.how = "failed"
            if case .failed(let why) = state { record.output = why }
            return record
        }
        if Task.isCancelled {
            record.how = "stopped"
            return record
        }
        let stop = StopFlag()
        let result: (Int32, Int32, String) = await withTaskCancellationHandler {
            await withCheckedContinuation { (done: CheckedContinuation<(Int32, Int32, String), Never>) in
                Shell.running.async {
                    // stopped before it began: tsh_stop() only ends what runs already
                    if stop.isRaised {
                        done.resume(returning: (2, -1, ""))
                        return
                    }
                    let box = OutputBox()
                    var status: Int32 = -1
                    let how = tsh_run(command, nil, Int32(seconds), { context, bytes, length in
                        guard let context = context, let bytes = bytes else { return }
                        Unmanaged<OutputBox>.fromOpaque(context).takeUnretainedValue().add(bytes, length)
                    }, Unmanaged.passUnretained(box).toOpaque(), &status)
                    done.resume(returning: (how, status, box.text))
                }
            }
        } onCancel: {
            stop.raise()
            tsh_stop()
        }
        record.output = result.2
        record.status = Int(result.1)
        switch result.0 {
        case 0: record.how = "ended"
        case 1: record.how = "timeout"
        case 2: record.how = "stopped"
        default:
            record.how = "failed"
            record.output = "error \(result.0)"
        }
        return record
    }

    /// Has Linux's files thrown away before it starts the next time, so that a fresh Alpine is unpacked.
    /// Linux cannot be taken away from under running programs, so this returns false when it has
    /// started already: then it happens when the app is opened the next time.
    func reset() -> Bool {
        UserDefaults.standard.set(true, forKey: "shell_reset")
        return state == .off
    }

    /// The reason it could not be done, or nil.
    nonisolated private static func prepare(motd: String) -> String? {
        let files = FileManager.default
        let root = folder
        let defaults = UserDefaults.standard
        if defaults.bool(forKey: "shell_reset") {
            try? files.removeItem(at: root)
            defaults.set(false, forKey: "shell_reset")
        }
        if !files.fileExists(atPath: root.appendingPathComponent("meta.db").path) {
            guard let archive = Bundle.main.url(forResource: "alpine", withExtension: "tar.gz") else {
                return "alpine.tar.gz is missing from the app"
            }
            // unpacked next to its place and moved there when complete, so that half of it is never taken for all of it
            let part = root.deletingLastPathComponent().appendingPathComponent("linux.part", isDirectory: true)
            try? files.removeItem(at: root)
            try? files.removeItem(at: part)
            var why = [CChar](repeating: 0, count: 512)
            if tsh_import(archive.path, part.path, &why, why.count) != 0 {
                try? files.removeItem(at: part)
                return String(cString: why)
            }
            do {
                try files.moveItem(at: part, to: root)
            } catch {
                return error.localizedDescription
            }
        }
        var why = [CChar](repeating: 0, count: 512)
        if tsh_boot(root.path, motd, &why, why.count) != 0 { return String(cString: why) }
        return nil
    }
}

/// One terminal of the shell: bytes in, bytes out. Used from the main thread.
final class ShellSession: @unchecked Sendable {
    private var number: Int32 = -1
    private let typing = DispatchQueue(label: "termant.shell.type")
    private let lock = NSLock()
    private var pending = Data()
    private var scheduled = false
    /// What the shell printed, on the main thread.
    var onOutput: (Data) -> Void = { _ in }
    /// The user left the shell ("exit"), on the main thread.
    var onEnded: () -> Void = {}

    var isOpen: Bool { number >= 0 }

    func open(columns: Int, rows: Int) -> Bool {
        guard number < 0 else { return true }
        // the C side holds on to this object until close() has let go of it
        let context = Unmanaged.passRetained(self).toOpaque()
        let opened = tsh_open(Int32(columns), Int32(rows), { context, bytes, length in
            guard let context = context, let bytes = bytes else { return }
            Unmanaged<ShellSession>.fromOpaque(context).takeUnretainedValue().received(Data(bytes: bytes, count: length))
        }, { context, _ in
            guard let context = context else { return }
            let session = Unmanaged<ShellSession>.fromOpaque(context).takeUnretainedValue()
            DispatchQueue.main.async { session.onEnded() }
        }, context)
        if opened < 0 {
            Unmanaged<ShellSession>.fromOpaque(context).release()
            return false
        }
        number = opened
        return true
    }

    /// On a thread of the engine: keep the bytes and hand them over in one go.
    private func received(_ data: Data) {
        lock.lock()
        pending.append(data)
        let schedule = !scheduled
        scheduled = true
        lock.unlock()
        if schedule {
            DispatchQueue.main.async { self.flush() }
        }
    }

    private func flush() {
        lock.lock()
        let data = pending
        pending = Data()
        scheduled = false
        lock.unlock()
        if !data.isEmpty { onOutput(data) }
    }

    func type(_ data: Data) {
        let number = self.number
        guard number >= 0, !data.isEmpty else { return }
        typing.async {
            data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) -> Void in
                tsh_type(number, raw.bindMemory(to: CChar.self).baseAddress, raw.count)
            }
        }
    }

    func resize(columns: Int, rows: Int) {
        let number = self.number
        guard number >= 0 else { return }
        typing.async { tsh_resize(number, Int32(columns), Int32(rows)) }
    }

    func close() {
        let number = self.number
        guard number >= 0 else { return }
        self.number = -1
        onOutput = { _ in }
        onEnded = {}
        typing.async {
            tsh_close(number)
            Unmanaged.passUnretained(self).release() // what open() retained
        }
    }
}
