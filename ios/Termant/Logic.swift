import Foundation

/// Times the agent and the user write: "30m", "20h", "2d", "21:00", "7am".
enum When {
    static let nightFrom = 23, nightTo = 7

    /// Accepts 7, 07:00, 7.30, 7am, 7:30pm, 19:05. Returns hour and minute.
    static func parseTime(_ text: String) -> (hour: Int, minute: Int)? {
        let s = text.trimmingCharacters(in: .whitespaces).lowercased()
        guard let re = try? NSRegularExpression(pattern: "^(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm)?$"),
              let m = re.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)) else { return nil }
        func group(_ i: Int) -> String? {
            guard let r = Range(m.range(at: i), in: s) else { return nil }
            return String(s[r])
        }
        guard var hour = Int(group(1) ?? "") else { return nil }
        let minute = Int(group(2) ?? "0") ?? 0
        if let half = group(3) {
            if hour < 1 || hour > 12 { return nil }
            hour = hour % 12 + (half == "pm" ? 12 : 0)
        }
        if hour > 23 || minute > 59 { return nil }
        return (hour, minute)
    }

    /// A delay such as 30m, 20h or 2d in seconds; nil if it is not one.
    static func parseDelay(_ text: String) -> TimeInterval? {
        let s = text.trimmingCharacters(in: .whitespaces).lowercased()
        guard let re = try? NSRegularExpression(pattern: "^(\\d+)\\s*(m|min|h|d)$"),
              let m = re.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)),
              let nr = Range(m.range(at: 1), in: s), let ur = Range(m.range(at: 2), in: s),
              let n = Double(s[nr]) else { return nil }
        let unit = String(s[ur])
        let minutes = unit == "d" ? n * 24 * 60 : unit == "h" ? n * 60 : n
        if minutes < 1 || minutes > 30 * 24 * 60 { return nil }
        return minutes * 60
    }

    /// The moment "text" means, counted from "now": a delay, or the next time the clock shows that time.
    static func parse(_ text: String, now: Date = Date(), calendar: Calendar = .current) -> Date? {
        if let delay = parseDelay(text) { return now.addingTimeInterval(delay) }
        guard let t = parseTime(text) else { return nil }
        return next(hour: t.hour, minute: t.minute, after: now.addingTimeInterval(30), calendar: calendar)
    }

    /// The next time the clock shows hour:minute, strictly after "after".
    static func next(hour: Int, minute: Int, after: Date, calendar: Calendar = .current) -> Date {
        var parts = calendar.dateComponents([.year, .month, .day], from: after)
        parts.hour = hour
        parts.minute = minute
        parts.second = 0
        let today = calendar.date(from: parts) ?? after
        if today > after { return today }
        return calendar.date(byAdding: .day, value: 1, to: today) ?? today.addingTimeInterval(86400)
    }

    /// Nobody is woken for a question: a moment between 23:00 and 07:00 moves to 07:00.
    static func outOfTheNight(_ date: Date, calendar: Calendar = .current) -> Date {
        let hour = calendar.component(.hour, from: date)
        if hour < nightFrom && hour >= nightTo { return date }
        var parts = calendar.dateComponents([.year, .month, .day], from: date)
        parts.hour = nightTo
        parts.minute = 0
        parts.second = 0
        let morning = calendar.date(from: parts) ?? date
        if hour >= nightFrom { return calendar.date(byAdding: .day, value: 1, to: morning) ?? morning }
        return morning
    }

    /// "9:00, 21:30" or "8am 1pm" as "09:00 21:30"; "" for none; nil when it is not understood.
    static func normalizeTimes(_ typed: String, max: Int = 4) -> String? {
        var text = typed.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if text.isEmpty || text == "none" { return "" }
        text = text.replacingOccurrences(of: "\\s+(am|pm)\\b", with: "$1", options: .regularExpression)
        var clean: [String] = []
        let words = text.components(separatedBy: CharacterSet(charactersIn: " ,;\n\t")).filter { !$0.isEmpty }
        if words.count > max { return nil }
        for word in words {
            guard let t = parseTime(word) else { return nil }
            let shown = String(format: "%02d:%02d", t.hour, t.minute)
            if !clean.contains(shown) { clean.append(shown) }
        }
        return clean.joined(separator: " ")
    }

    static func times(_ stored: String) -> [(hour: Int, minute: Int)] {
        stored.split(separator: " ").compactMap { parseTime(String($0)) }
    }

    static func show(_ date: Date) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "EEE d MMM HH:mm"
        return f.string(from: date)
    }
}

/// What the agent wants done on the phone, written into its reply as [[kind: text]].
struct Directive: Equatable {
    var kind: String
    var body: String
}

enum Directives {
    private static let pattern = try! NSRegularExpression(
        pattern: "\\[\\[\\s*(note|care|remind|forget)\\s*:\\s*([^\\]]*)\\]\\]", options: [.caseInsensitive])
    private static let commandStart = try! NSRegularExpression(pattern: "\\[\\[\\s*run\\s*:", options: [.caseInsensitive])
    private static let thinking = try! NSRegularExpression(
        pattern: "<think>.*?</think>", options: [.caseInsensitive, .dotMatchesLineSeparators])

    /// The reply as the user should see it, and the directives that were in it.
    /// Works on half a reply too: a directive that is still being written is not shown.
    static func split(_ raw: String) -> (visible: String, found: [Directive]) {
        var text = raw
        // some models print their thinking between <think> tags
        text = thinking.stringByReplacingMatches(in: text, range: NSRange(text.startIndex..., in: text), withTemplate: "")
        if let open = text.range(of: "<think>", options: [.caseInsensitive, .backwards]) {
            text = String(text[..<open.lowerBound])
        }
        var found: [Directive] = []
        for m in pattern.matches(in: text, range: NSRange(text.startIndex..., in: text)) {
            guard let k = Range(m.range(at: 1), in: text), let b = Range(m.range(at: 2), in: text) else { continue }
            found.append(Directive(kind: text[k].lowercased(),
                                   body: text[b].trimmingCharacters(in: .whitespacesAndNewlines)))
        }
        text = pattern.stringByReplacingMatches(in: text, range: NSRange(text.startIndex..., in: text), withTemplate: "")
        let taken = commands(in: text)
        text = taken.rest
        for command in taken.found { found.append(Directive(kind: "run", body: command)) }
        // one that has begun but not ended yet
        if let open = text.range(of: "[[", options: .backwards), !text[open.upperBound...].contains("]]") {
            text = String(text[..<open.lowerBound])
        } else if text.hasSuffix("[") {
            text = String(text.dropLast())
        }
        text = text.replacingOccurrences(of: "[ \\t]+\\n", with: "\n", options: .regularExpression)
        text = text.replacingOccurrences(of: "\\n{3,}", with: "\n\n", options: .regularExpression)
        return (text.trimmingCharacters(in: .whitespacesAndNewlines), found)
    }

    /// Takes the [[run: ...]] lines out of a text. A command for the shell may be several lines long and
    /// hold brackets of its own, also "[[ -f x ]]". One that is still being written is left out with all after it.
    static func commands(in text: String) -> (rest: String, found: [String]) {
        var rest = ""
        var found: [String] = []
        var from = text.startIndex
        while from < text.endIndex,
              let m = commandStart.firstMatch(in: text, range: NSRange(from..<text.endIndex, in: text)),
              let start = Range(m.range, in: text) {
            rest.append(contentsOf: text[from..<start.lowerBound])
            var depth = 1
            var i = start.upperBound
            var end: String.Index?
            while i < text.endIndex {
                let next = text.index(after: i)
                if next < text.endIndex {
                    let after = text.index(after: next)
                    if text[i] == "[" && text[next] == "[" {
                        depth += 1
                        i = after
                        continue
                    }
                    if text[i] == "]" && text[next] == "]" {
                        // "x]]]": the first of the three belongs to the command
                        if depth == 1 && after < text.endIndex && text[after] == "]" {
                            i = next
                            continue
                        }
                        depth -= 1
                        if depth == 0 {
                            end = i
                            break
                        }
                        i = after
                        continue
                    }
                }
                i = next
            }
            guard let close = end else { return (rest, found) }
            let body = text[start.upperBound..<close].trimmingCharacters(in: .whitespacesAndNewlines)
            if !body.isEmpty { found.append(body) }
            from = text.index(close, offsetBy: 2)
        }
        if from < text.endIndex { rest.append(contentsOf: text[from...]) }
        return (rest, found)
    }

    /// "20h | how the exam went" as its two halves; the text alone when there is no bar.
    static func halves(_ body: String) -> (when: String, text: String) {
        guard let bar = body.firstIndex(of: "|") else { return ("", body.trimmingCharacters(in: .whitespaces)) }
        return (body[..<bar].trimmingCharacters(in: .whitespaces),
                body[body.index(after: bar)...].trimmingCharacters(in: .whitespaces))
    }
}

/// What a terminal printed, as plain text.
enum TerminalText {
    private static let escapes = try! NSRegularExpression(
        pattern: "\u{1b}\\[[0-9;?<=>]*[ -/]*[@-~]|\u{1b}\\][^\u{07}\u{1b}]*(\u{07}|\u{1b}\\\\)|\u{1b}[()][0-9A-Za-z]|\u{1b}[=>78DEHMNOZc]")

    /// Without colours, cursor movements and the other control sequences; line ends as "\n".
    static func plain(_ text: String) -> String {
        var s = escapes.stringByReplacingMatches(in: text, range: NSRange(text.startIndex..., in: text), withTemplate: "")
        s = s.replacingOccurrences(of: "\r\n", with: "\n")
        // what a shell prints to rub out the letter before the cursor
        for _ in 0..<200 {
            let shorter = s.replacingOccurrences(of: "[^\n\u{8}]\u{8} \u{8}", with: "", options: .regularExpression)
            if shorter == s { break }
            s = shorter
        }
        for junk in ["\r", "\u{8}", "\u{7}", "\u{0}"] { s = s.replacingOccurrences(of: junk, with: "") }
        return s
    }

    /// The end of a long text, cut at a line where possible.
    static func tail(_ text: String, _ limit: Int) -> String {
        guard text.count > limit else { return text }
        let cut = String(text.suffix(limit))
        if let line = cut.firstIndex(of: "\n"), cut.distance(from: cut.startIndex, to: line) < limit / 4 {
            return String(cut[cut.index(after: line)...])
        }
        return cut
    }
}

/// What the model is told about the commands it ran.
enum ShellReport {
    static let headSize = 3_000, tailSize = 5_000
    /// How long a command of the agent may run.
    static let minutes = 5

    /// Long output with its middle left out.
    static func shorten(_ output: String) -> String {
        guard output.count > headSize + tailSize + 200 else { return output }
        let left = output.count - headSize - tailSize
        return String(output.prefix(headSize)) + "\n[... \(left) letters left out ...]\n" + String(output.suffix(tailSize))
    }

    static func text(_ runs: [ShellRun]) -> String {
        var s = "Command output:"
        for run in runs {
            s += "\n$ " + run.command + "\n"
            let output = shorten(run.output).trimmingCharacters(in: .newlines)
            switch run.how {
            case "ended":
                s += (output.isEmpty ? "(it printed nothing)" : output) + "\n(exit code \(run.status))"
            case "timeout":
                s += (output.isEmpty ? "" : output + "\n") + "(stopped: it was still running after \(minutes) minutes)"
            case "stopped":
                s += (output.isEmpty ? "" : output + "\n") + "(stopped by the user)"
            case "waiting", "running":
                s += "(not run)"
            default:
                s += "(not run: " + (output.isEmpty ? "the shell is not available" : output) + ")"
            }
        }
        return s
    }
}

/// One of the services a key can be saved for. All speak the same "chat completions" protocol.
struct Provider: Identifiable, Equatable {
    let id: String
    let name: String
    let base: String
    /// Where a key is made.
    let page: String

    static let custom = "custom"
    static let all: [Provider] = [
        Provider(id: "openrouter", name: "OpenRouter", base: "https://openrouter.ai/api/v1", page: "https://openrouter.ai/keys"),
        Provider(id: "groq", name: "Groq", base: "https://api.groq.com/openai/v1", page: "https://console.groq.com/keys"),
        Provider(id: "gemini", name: "Google Gemini", base: "https://generativelanguage.googleapis.com/v1beta/openai", page: "https://aistudio.google.com/apikey"),
        Provider(id: "openai", name: "OpenAI (GPT)", base: "https://api.openai.com/v1", page: "https://platform.openai.com/api-keys"),
        Provider(id: "anthropic", name: "Anthropic (Claude)", base: "https://api.anthropic.com/v1", page: "https://console.anthropic.com/settings/keys"),
        Provider(id: "deepseek", name: "DeepSeek", base: "https://api.deepseek.com/v1", page: "https://platform.deepseek.com/api_keys"),
        Provider(id: "cerebras", name: "Cerebras", base: "https://api.cerebras.ai/v1", page: "https://cloud.cerebras.ai"),
        Provider(id: "mistral", name: "Mistral", base: "https://api.mistral.ai/v1", page: "https://console.mistral.ai/api-keys"),
        Provider(id: custom, name: "Custom (OpenAI-compatible)", base: "", page: ""),
    ]

    static func named(_ id: String) -> Provider {
        all.first { $0.id == id } ?? all[0]
    }
}

/// A moment at which the agent writes first. One without a topic is the daily question.
struct Plan: Codable, Identifiable, Equatable {
    var id: String
    var when: Date
    /// What the agent wanted to come back to; nil for the daily question.
    var topic: String?
    /// The message, written ahead of time so that it can arrive while the app is not running.
    var text: String?
    var writtenAt: Date?

    var daily: Bool { topic == nil }
}

/// The limits that keep the agent's care from becoming a nuisance. The same as in the Android app.
enum CareRules {
    static let maxPerDay = 6
    static let gap: TimeInterval = 30 * 60
    static let justWrote: TimeInterval = 3 * 60
    /// A moment this near is taken as meant, even at night.
    static let soon: TimeInterval = 60 * 60
    /// Daily questions stop after this many without an answer.
    static let maxUnanswered = 3
    static let maxFollowUps = 8
    static let maxTopic = 200

    private static func sameDay(_ a: Date, _ b: Date, _ calendar: Calendar) -> Bool {
        calendar.isDate(a, inSameDayAs: b)
    }

    /// The next "count" daily moments after "after" that keep the limits, given the moments taken already.
    static func dailySlots(times: String, after: Date, count: Int, taken: [Date],
                           calendar: Calendar = .current) -> [Date] {
        let clock = When.times(times).sorted { ($0.hour, $0.minute) < ($1.hour, $1.minute) }
        if clock.isEmpty || count <= 0 { return [] }
        var all = taken
        var found: [Date] = []
        let start = calendar.startOfDay(for: after)
        for day in 0..<14 {
            guard let base = calendar.date(byAdding: .day, value: day, to: start) else { continue }
            for t in clock {
                var parts = calendar.dateComponents([.year, .month, .day], from: base)
                parts.hour = t.hour
                parts.minute = t.minute
                guard let moment = calendar.date(from: parts), moment.timeIntervalSince(after) > 60 else { continue }
                if all.contains(where: { abs($0.timeIntervalSince(moment)) < gap }) { continue }
                if all.filter({ sameDay($0, moment, calendar) }).count >= maxPerDay { continue }
                found.append(moment)
                all.append(moment)
                if found.count == count { return found }
            }
        }
        return found
    }

    /// Where a moment the agent asked for ends up: out of the night, half an hour from the others,
    /// and on a day that is not full yet.
    static func place(_ wanted: Date, now: Date, taken: [Date], calendar: Calendar = .current) -> Date {
        var t = max(wanted, now.addingTimeInterval(60))
        for _ in 0..<200 {
            if t.timeIntervalSince(now) > soon {
                let moved = When.outOfTheNight(t, calendar: calendar)
                if moved != t { t = moved; continue }
            }
            if taken.filter({ sameDay($0, t, calendar) }).count >= maxPerDay {
                let tomorrow = calendar.date(byAdding: .day, value: 1, to: calendar.startOfDay(for: t)) ?? t.addingTimeInterval(86400)
                t = When.outOfTheNight(tomorrow, calendar: calendar)
                continue
            }
            if let near = taken.first(where: { abs($0.timeIntervalSince(t)) < gap }) {
                t = near.addingTimeInterval(gap)
                continue
            }
            break
        }
        return t
    }
}
