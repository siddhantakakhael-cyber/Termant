import Foundation

/// Talks to a model over the "chat completions" protocol that OpenRouter, Groq,
/// OpenAI and most other services share, and hands the reply over word by word.
enum LLM {
    struct Failure: Error, Equatable {
        /// The HTTP status; 0 when the service was not reached at all.
        var status: Int
        var message: String

        /// Whether another model could get past this (limit used up, model gone or overloaded).
        var anotherModelMayWork: Bool {
            [402, 404, 408, 429, 500, 502, 503, 529].contains(status)
        }
    }

    static func endpoint(_ base: String, _ path: String) -> URL? {
        var b = base.trimmingCharacters(in: .whitespacesAndNewlines)
        while b.hasSuffix("/") { b.removeLast() }
        if b.isEmpty { return nil }
        return URL(string: b + "/" + path)
    }

    /// Puts the key where the service looks for it.
    static func authorize(_ request: inout URLRequest, base: String, key: String) {
        if key.isEmpty { return }
        request.setValue("Bearer " + key, forHTTPHeaderField: "Authorization")
        if base.contains("api.anthropic.com") {
            // Anthropic's own routes (the list of models) want the key under this name
            request.setValue(key, forHTTPHeaderField: "x-api-key")
            request.setValue("2023-06-01", forHTTPHeaderField: "anthropic-version")
        }
    }

    /// The text of one line of the reply stream; nil for lines that carry none.
    static func delta(fromLine line: String) -> String? {
        guard line.hasPrefix("data:") else { return nil }
        let payload = line.dropFirst(5).trimmingCharacters(in: .whitespaces)
        if payload == "[DONE]" || payload.isEmpty { return nil }
        guard let data = payload.data(using: .utf8),
              let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let choices = o["choices"] as? [[String: Any]], let first = choices.first else { return nil }
        if let d = first["delta"] as? [String: Any], let text = d["content"] as? String { return text }
        if let m = first["message"] as? [String: Any], let text = m["content"] as? String { return text }
        return nil
    }

    /// What a service said went wrong, from the body of its answer.
    static func errorMessage(_ body: Data, status: Int) -> String {
        if let o = try? JSONSerialization.jsonObject(with: body) as? [String: Any] {
            if let e = o["error"] as? [String: Any], let m = e["message"] as? String, !m.isEmpty { return m }
            if let m = o["error"] as? String, !m.isEmpty { return m }
            if let m = o["message"] as? String, !m.isEmpty { return m }
        }
        let text = String(data: body.prefix(300), encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return text.isEmpty ? "HTTP \(status)" : text
    }

    /// An error some services send inside a stream that began with 200.
    static func streamError(fromLine line: String) -> String? {
        guard line.hasPrefix("data:"), let data = line.dropFirst(5).data(using: .utf8),
              let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let e = o["error"] as? [String: Any] else { return nil }
        return (e["message"] as? String) ?? "error"
    }

    /// The reply, piece by piece. Throws Failure, or CancellationError when the task is cancelled.
    static func stream(base: String, key: String, model: String, messages: [[String: String]])
        -> AsyncThrowingStream<String, Error> {
        AsyncThrowingStream { continuation in
            let work = Task {
                do {
                    guard let url = endpoint(base, "chat/completions") else {
                        throw Failure(status: 0, message: "No address for the model's service")
                    }
                    var request = URLRequest(url: url)
                    request.httpMethod = "POST"
                    request.timeoutInterval = 90
                    request.setValue("application/json", forHTTPHeaderField: "Content-Type")
                    request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
                    authorize(&request, base: base, key: key)
                    request.setValue("Termant iOS", forHTTPHeaderField: "X-Title")
                    request.httpBody = try JSONSerialization.data(withJSONObject: [
                        "model": model, "stream": true, "messages": messages,
                    ] as [String: Any])
                    let (bytes, response) = try await URLSession.shared.bytes(for: request)
                    let status = (response as? HTTPURLResponse)?.statusCode ?? 0
                    if status < 200 || status >= 300 {
                        var body = Data()
                        for try await byte in bytes {
                            body.append(byte)
                            if body.count > 4000 { break }
                        }
                        throw Failure(status: status, message: errorMessage(body, status: status))
                    }
                    var got = false
                    for try await line in bytes.lines {
                        try Task.checkCancellation()
                        if let piece = delta(fromLine: line), !piece.isEmpty {
                            got = true
                            continuation.yield(piece)
                        } else if !got, let problem = streamError(fromLine: line) {
                            throw Failure(status: 502, message: problem)
                        }
                    }
                    continuation.finish()
                } catch let failure as Failure {
                    continuation.finish(throwing: failure)
                } catch is CancellationError {
                    continuation.finish(throwing: CancellationError())
                } catch {
                    let ns = error as NSError
                    if ns.domain == NSURLErrorDomain && ns.code == NSURLErrorCancelled {
                        continuation.finish(throwing: CancellationError())
                    } else {
                        continuation.finish(throwing: Failure(status: 0, message: error.localizedDescription))
                    }
                }
            }
            continuation.onTermination = { _ in work.cancel() }
        }
    }

    /// The whole reply at once.
    static func complete(base: String, key: String, model: String, messages: [[String: String]]) async throws -> String {
        var all = ""
        for try await piece in stream(base: base, key: key, model: model, messages: messages) { all += piece }
        return all
    }

    /// The names of the models this key can use.
    static func models(base: String, key: String) async throws -> [String] {
        guard let url = endpoint(base, "models") else { throw Failure(status: 0, message: "No address for the model's service") }
        var request = URLRequest(url: url)
        request.timeoutInterval = 30
        authorize(&request, base: base, key: key)
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            if status < 200 || status >= 300 { throw Failure(status: status, message: errorMessage(data, status: status)) }
            return modelNames(data)
        } catch let failure as Failure {
            throw failure
        } catch {
            throw Failure(status: 0, message: error.localizedDescription)
        }
    }

    static func modelNames(_ data: Data) -> [String] {
        guard let o = try? JSONSerialization.jsonObject(with: data) else { return [] }
        let list: [[String: Any]]
        if let d = o as? [String: Any] {
            list = (d["data"] as? [[String: Any]]) ?? (d["models"] as? [[String: Any]]) ?? []
        } else {
            list = (o as? [[String: Any]]) ?? []
        }
        return list.compactMap { ($0["id"] as? String) ?? ($0["name"] as? String) }
            .map { $0.hasPrefix("models/") ? String($0.dropFirst(7)) : $0 }
    }

    /// Models that cost nothing, by the way OpenRouter names them.
    static func isFree(_ model: String) -> Bool {
        model.hasSuffix(":free")
    }
}
