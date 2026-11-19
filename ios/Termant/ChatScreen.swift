import SwiftUI

struct ChatScreen: View {
    @EnvironmentObject private var prefs: Prefs
    @EnvironmentObject private var chat: ChatStore
    @State private var draft = ""
    @State private var showSettings = false
    @State private var showHistory = false
    @State private var showRemote = false
    @State private var showShell = false
    @FocusState private var typing: Bool

    private var colors: Palette { Theme.palette(prefs.theme) }

    var body: some View {
        VStack(spacing: 0) {
            bar
            if chat.chat.messages.isEmpty {
                welcome
            } else {
                messages
            }
            input
        }
        .background(colors.bg.ignoresSafeArea())
        .sheet(isPresented: $showSettings) {
            SettingsScreen(openRemote: {
                showSettings = false
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { showRemote = true }
            })
            .environmentObject(prefs)
            .environmentObject(chat)
            .environmentObject(Care.shared)
            .environmentObject(Memory.shared)
        }
        .sheet(isPresented: $showHistory) {
            HistoryScreen()
                .environmentObject(prefs)
                .environmentObject(chat)
        }
        .fullScreenCover(isPresented: $showRemote) {
            RemoteView().environmentObject(prefs)
        }
        .fullScreenCover(isPresented: $showShell) {
            ShellView(openServer: {
                // one's own server, or Settings to name it
                let named = Remote.normalize(prefs.remoteURL) != nil
                showShell = false
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) {
                    if named { showRemote = true } else { showSettings = true }
                }
            })
            .environmentObject(prefs)
        }
    }

    private var bar: some View {
        HStack(spacing: 18) {
            Text("Termant")
                .font(.system(size: 20, weight: .bold))
                .foregroundColor(colors.text)
            Spacer()
            icon("square.and.pencil", "new_chat", id: "bar.new") { chat.newChat() }
            icon("clock.arrow.circlepath", "history", id: "bar.history") { showHistory = true }
            icon("terminal", "terminal", id: "bar.terminal") {
                typing = false
                showShell = true
            }
            icon("gearshape", "settings", id: "bar.settings") { showSettings = true }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(colors.surface)
    }

    private func icon(_ symbol: String, _ label: String, id: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 19))
                .foregroundColor(colors.text)
                .frame(minWidth: 30, minHeight: 34)
        }
        .accessibilityLabel(L.t(label))
        .accessibilityIdentifier(id)
    }

    private var welcome: some View {
        VStack(spacing: 14) {
            Spacer()
            Text(">_")
                .font(.system(size: 44, weight: .bold, design: .monospaced))
                .foregroundColor(colors.accent)
            Text(L.t("welcome"))
                .font(.title2.bold())
                .foregroundColor(colors.text)
            Text(L.t("welcome_text"))
                .multilineTextAlignment(.center)
                .foregroundColor(colors.muted)
            if !prefs.ready {
                Text(L.t("need_key"))
                    .multilineTextAlignment(.center)
                    .foregroundColor(colors.text)
                    .padding(.top, 8)
                    .accessibilityIdentifier("welcome.needkey")
                Button(L.t("open_settings")) { showSettings = true }
                    .buttonStyle(.borderedProminent)
                    .accessibilityIdentifier("welcome.settings")
            }
            Spacer()
        }
        .padding(.horizontal, 28)
        .frame(maxWidth: .infinity)
        .contentShape(Rectangle())
        .onTapGesture { typing = false }
        .id(prefs.keysChanged)
    }

    private var messages: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 10) {
                    ForEach(chat.chat.messages) { message in
                        Bubble(message: message, colors: colors, busy: chat.busy && message.id == chat.chat.messages.last?.id)
                            .id(message.id)
                    }
                    Color.clear.frame(height: 1).id("end")
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 12)
            }
            .onTapGesture { typing = false }
            .onChange(of: chat.chat.messages.last?.text) { _ in proxy.scrollTo("end", anchor: .bottom) }
            .onChange(of: chat.chat.messages.last?.runs) { _ in proxy.scrollTo("end", anchor: .bottom) }
            .onChange(of: chat.chat.messages.count) { _ in
                withAnimation { proxy.scrollTo("end", anchor: .bottom) }
            }
            .onChange(of: typing) { _ in
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { proxy.scrollTo("end", anchor: .bottom) }
            }
            .onAppear { proxy.scrollTo("end", anchor: .bottom) }
        }
    }

    private var input: some View {
        HStack(alignment: .bottom, spacing: 10) {
            field
                .focused($typing)
                .disableAutocorrection(Bootstrap.testing ? true : nil) // a test types exactly what it means
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(colors.surfaceHigh)
                .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                .foregroundColor(colors.text)
                .accessibilityIdentifier("chat.input")
            Button {
                if chat.busy {
                    chat.stop()
                } else {
                    let text = draft
                    draft = ""
                    chat.send(text)
                }
            } label: {
                Image(systemName: chat.busy ? "stop.fill" : "arrow.up")
                    .font(.system(size: 17, weight: .bold))
                    .foregroundColor(colors.onAccent)
                    .frame(width: 40, height: 40)
                    .background(sendable ? colors.accent : colors.muted.opacity(0.5))
                    .clipShape(Circle())
            }
            .disabled(!sendable)
            .accessibilityLabel(L.t(chat.busy ? "stop" : "send"))
            .accessibilityIdentifier("chat.send")
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(colors.surface)
    }

    private var sendable: Bool {
        chat.busy || (prefs.ready && !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
    }

    @ViewBuilder
    private var field: some View {
        if #available(iOS 16.0, *) {
            TextField(L.t("ask_hint"), text: $draft, axis: .vertical)
                .lineLimit(1...5)
        } else {
            TextField(L.t("ask_hint"), text: $draft)
        }
    }
}

struct Bubble: View {
    let message: Message
    let colors: Palette
    let busy: Bool

    var body: some View {
        HStack {
            if message.user { Spacer(minLength: 40) }
            VStack(alignment: .leading, spacing: 6) {
                if message.first {
                    Label(L.t("care_own"), systemImage: "heart.fill")
                        .font(.caption)
                        .foregroundColor(colors.accent)
                }
                if !message.note.isEmpty {
                    Text(message.note)
                        .font(.caption)
                        .foregroundColor(colors.muted)
                }
                if message.text.isEmpty && busy {
                    ProgressView()
                } else if !message.text.isEmpty || (message.runs ?? []).isEmpty {
                    Text(message.user ? AttributedString(message.text) : Theme.rich(message.text))
                        .foregroundColor(message.failed ? colors.error : (message.user ? colors.onAccent : colors.text))
                        .textSelection(.enabled)
                        .fixedSize(horizontal: false, vertical: true)
                }
                ForEach(message.actions, id: \.self) { action in
                    Label(action, systemImage: "checkmark.circle")
                        .font(.caption)
                        .foregroundColor(colors.muted)
                }
                ForEach(message.runs ?? []) { run in
                    RunView(run: run, colors: colors)
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(message.user ? colors.accent : colors.surface)
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .contextMenu {
                Button {
                    UIPasteboard.general.string = message.text
                } label: {
                    Label("Copy", systemImage: "doc.on.doc")
                }
            }
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier(message.user ? "bubble.user" : "bubble.agent")
            if !message.user { Spacer(minLength: 40) }
        }
    }
}

/// A command the agent ran in the shell, with what it printed.
struct RunView: View {
    let run: ShellRun
    let colors: Palette
    @State private var all = false

    static let shownLines = 8

    private var state: String {
        switch run.how {
        case "waiting": return L.t("run_waiting")
        case "running": return L.t("run_running")
        case "ended": return L.t("run_exit", String(run.status))
        case "timeout": return L.t("run_timeout", String(ShellReport.minutes))
        case "stopped": return L.t("run_stopped")
        default: return L.t("run_failed")
        }
    }

    var body: some View {
        let output = run.output.trimmingCharacters(in: .whitespacesAndNewlines)
        let lines = output.split(separator: "\n", omittingEmptySubsequences: false)
        let long = lines.count > RunView.shownLines || output.count > 600
        let shown = all || !long ? output : String(lines.suffix(RunView.shownLines).joined(separator: "\n").suffix(600))
        VStack(alignment: .leading, spacing: 4) {
            Text("$ " + run.command)
                .font(.system(size: 12, weight: .semibold, design: .monospaced))
                .foregroundColor(colors.accent)
                .fixedSize(horizontal: false, vertical: true)
            if !shown.isEmpty {
                Text(shown)
                    .font(.system(size: 12, design: .monospaced))
                    .foregroundColor(run.how == "failed" ? colors.error : colors.text)
                    .fixedSize(horizontal: false, vertical: true)
            }
            HStack(spacing: 8) {
                if run.how == "running" { ProgressView().scaleEffect(0.7) }
                Text(state)
                    .font(.caption2)
                    .foregroundColor(run.how == "ended" && run.status == 0 ? colors.muted : colors.error)
                if long {
                    Spacer()
                    Button(L.t(all ? "run_less" : "run_more")) { all.toggle() }
                        .font(.caption2)
                }
            }
        }
        .textSelection(.enabled)
        .padding(8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(colors.bg)
        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
        .accessibilityIdentifier("run")
    }
}

struct HistoryScreen: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var prefs: Prefs
    @EnvironmentObject private var chat: ChatStore

    var body: some View {
        let list = chat.history()
        NavigationView {
            List {
                if list.isEmpty {
                    Text(L.t("history_none")).foregroundColor(.secondary)
                }
                ForEach(list) { item in
                    Button {
                        chat.open(item.id)
                        dismiss()
                    } label: {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(item.title.isEmpty ? "…" : item.title)
                                .lineLimit(2)
                                .foregroundColor(.primary)
                            Text(When.show(item.updated))
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    }
                    .swipeActions {
                        Button(role: .destructive) {
                            chat.delete(item.id)
                        } label: {
                            Label(L.t("delete"), systemImage: "trash")
                        }
                    }
                }
            }
            .navigationTitle(L.t("history"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L.t("close")) { dismiss() } }
            }
        }
        .navigationViewStyle(.stack)
        .preferredColorScheme(prefs.theme == "light" ? .light : .dark)
        .tint(Theme.palette(prefs.theme).accent)
    }
}
