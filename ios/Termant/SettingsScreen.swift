import SwiftUI

struct SettingsScreen: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var prefs: Prefs
    @EnvironmentObject private var chat: ChatStore
    @EnvironmentObject private var care: Care
    @EnvironmentObject private var memory: Memory
    let openRemote: () -> Void

    @State private var keyDraft = ""
    @State private var keyNote = ""
    @State private var timesDraft = ""
    @State private var timesWrong = false
    @State private var remotePassword = ""
    @State private var remoteNote = ""
    @State private var reminders: [Reminder] = []

    var body: some View {
        NavigationView {
            Form {
                look
                model
                caring
                learned
                if !reminders.isEmpty { reminding }
                remote
                about
            }
            .navigationTitle(L.t("settings"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L.t("close")) { dismiss() }.accessibilityIdentifier("settings.close")
                }
            }
        }
        .navigationViewStyle(.stack)
        .preferredColorScheme(prefs.theme == "light" ? .light : .dark)
        .tint(Theme.palette(prefs.theme).accent)
        .onAppear {
            timesDraft = prefs.careTimes.replacingOccurrences(of: " ", with: ", ")
            remotePassword = prefs.remotePassword
            reminders = Reminders.all
        }
        .onDisappear { care.sync() }
    }

    // ------------------------------------------------------------ language and look

    private var look: some View {
        Section(header: Text(L.t("look"))) {
            Picker(L.t("language"), selection: $prefs.language) {
                Text("Hindi (Hinglish)").tag("hi")
                Text("English").tag("en")
            }
            .pickerStyle(.segmented)
            Picker(L.t("theme"), selection: $prefs.theme) {
                Text(L.t("dark")).tag("dark")
                Text(L.t("light")).tag("light")
            }
            .pickerStyle(.segmented)
        }
    }

    // ------------------------------------------------------------ model

    private var model: some View {
        Section(header: Text(L.t("model_section")), footer: Text(L.t("model_note"))) {
            Picker(L.t("provider"), selection: $prefs.provider) {
                ForEach(Provider.all) { p in Text(p.name).tag(p.id) }
            }
            .accessibilityIdentifier("settings.provider")
            if prefs.provider == Provider.custom {
                TextField(L.t("base_url"), text: $prefs.customBase)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .disableAutocorrection(true)
                    .accessibilityIdentifier("settings.base")
            }
            HStack {
                SecureField(L.t("key_hint"), text: $keyDraft)
                    .textInputAutocapitalization(.never)
                    .disableAutocorrection(true)
                    .accessibilityIdentifier("settings.key")
                Button(L.t("save")) {
                    prefs.setKey(keyDraft)
                    keyNote = Secrets.inKeychain("key_" + prefs.provider) ? "" : L.t("key_weak")
                    keyDraft = ""
                }
                .buttonStyle(.borderless)
                .disabled(keyDraft.trimmingCharacters(in: .whitespaces).isEmpty)
                .accessibilityIdentifier("settings.key.save")
            }
            Text(prefs.keyShown)
                .font(.footnote)
                .foregroundColor(.secondary)
                .id(prefs.keysChanged)
                .accessibilityIdentifier("settings.key.state")
            if !keyNote.isEmpty {
                Text(keyNote).font(.footnote).foregroundColor(.secondary)
            }
            if !prefs.key.isEmpty {
                Button(L.t("key_remove"), role: .destructive) { prefs.setKey("") }
                    .id(prefs.keysChanged)
            }
            let page = Provider.named(prefs.provider).page
            if let url = URL(string: page), !page.isEmpty {
                Link(L.t("key_get"), destination: url)
            }
            NavigationLink {
                ModelPicker()
            } label: {
                HStack {
                    Text(L.t("model"))
                    Spacer()
                    Text(prefs.model.isEmpty ? L.t("model_auto") : prefs.model)
                        .foregroundColor(.secondary)
                        .lineLimit(1)
                }
            }
            .accessibilityIdentifier("settings.model")
            Toggle(L.t("auto_switch"), isOn: $prefs.autoSwitch)
        }
    }

    // ------------------------------------------------------------ looking after the user

    private var caring: some View {
        Section(header: Text(L.t("care_section")), footer: Text(L.t("care_note"))) {
            Toggle(L.t("care_on"), isOn: $prefs.careOn)
                .accessibilityIdentifier("settings.care.on")
                .onChange(of: prefs.careOn) { _ in care.sync() }
            if prefs.careOn {
                HStack {
                    Text(L.t("care_times"))
                    TextField(L.t("care_times_hint"), text: $timesDraft)
                        .multilineTextAlignment(.trailing)
                        .keyboardType(.numbersAndPunctuation)
                        .accessibilityIdentifier("settings.care.times")
                        .onSubmit { saveTimes() }
                    Button(L.t("save")) { saveTimes() }
                        .buttonStyle(.borderless)
                        .accessibilityIdentifier("settings.care.times.save")
                }
                if timesWrong {
                    Text(L.t("care_times_wrong")).font(.footnote).foregroundColor(.red)
                        .accessibilityIdentifier("settings.care.times.wrong")
                }
                if !care.allowed {
                    Text(L.t("care_denied")).font(.footnote).foregroundColor(.red)
                }
                Button(L.t("care_now")) {
                    dismiss()
                    Task {
                        if await chat.checkIn(topic: nil) != nil { care.sync() }
                    }
                }
                .disabled(!prefs.ready || chat.busy)
                .accessibilityIdentifier("settings.care.now")
                ForEach(care.dailies) { plan in
                    planRow(plan, title: L.t("care_daily"))
                }
                if !care.followUps.isEmpty {
                    Text(L.t("care_planned")).font(.footnote).foregroundColor(.secondary)
                    ForEach(care.followUps) { plan in
                        planRow(plan, title: plan.topic ?? "")
                            .swipeActions {
                                Button(role: .destructive) {
                                    care.remove(plan.id)
                                } label: {
                                    Label(L.t("remove"), systemImage: "trash")
                                }
                            }
                    }
                }
            }
        }
    }

    private func planRow(_ plan: Plan, title: String) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(When.show(plan.when) + " · " + title)
                .font(.subheadline)
            Text((plan.text ?? "").isEmpty ? L.t("care_not_ready") : (plan.text ?? ""))
                .font(.footnote)
                .foregroundColor(.secondary)
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier(plan.daily ? "care.daily" : "care.followup")
    }

    private func saveTimes() {
        guard let clean = When.normalizeTimes(timesDraft) else {
            timesWrong = true
            return
        }
        timesWrong = false
        prefs.careTimes = clean
        timesDraft = clean.replacingOccurrences(of: " ", with: ", ")
        care.sync()
    }

    // ------------------------------------------------------------ notes

    private var learned: some View {
        Section(header: Text(L.t("learn_section")), footer: Text(L.t("learn_note"))) {
            Toggle(L.t("learn_on"), isOn: $prefs.learnOn)
            if memory.notes.isEmpty {
                Text(L.t("learn_none")).foregroundColor(.secondary)
            }
            ForEach(memory.notes) { note in
                Text(note.text)
                    .font(.subheadline)
                    .accessibilityIdentifier("note")
            }
            .onDelete { offsets in
                for i in offsets.sorted(by: >) { memory.forget(number: i + 1) }
            }
        }
    }

    private var reminding: some View {
        Section(header: Text(L.t("reminders"))) {
            ForEach(reminders) { reminder in
                Text(When.show(reminder.when) + " · " + reminder.text)
                    .font(.subheadline)
                    .accessibilityIdentifier("reminder")
            }
            .onDelete { offsets in
                for i in offsets { Reminders.remove(reminders[i].id) }
                reminders = Reminders.all
            }
        }
    }

    // ------------------------------------------------------------ the terminal of one's own server

    private var remote: some View {
        Section(header: Text(L.t("remote_section")), footer: Text(L.t("remote_note"))) {
            TextField(L.t("remote_url"), text: $prefs.remoteURL)
                .keyboardType(.URL)
                .textInputAutocapitalization(.never)
                .disableAutocorrection(true)
                .accessibilityIdentifier("settings.remote.url")
            SecureField(L.t("remote_password"), text: $remotePassword)
                .textInputAutocapitalization(.never)
                .disableAutocorrection(true)
                .accessibilityIdentifier("settings.remote.password")
                .onChange(of: remotePassword) { value in
                    if value != prefs.remotePassword { prefs.remotePassword = value }
                }
            Button(L.t("remote_open")) {
                if Remote.normalize(prefs.remoteURL) == nil {
                    remoteNote = L.t("remote_missing")
                } else {
                    remoteNote = ""
                    openRemote()
                }
            }
            .accessibilityIdentifier("settings.remote.open")
            if !remoteNote.isEmpty {
                Text(remoteNote).font(.footnote).foregroundColor(.red)
                    .accessibilityIdentifier("settings.remote.note")
            }
        }
    }

    private var about: some View {
        Section(header: Text(L.t("about")), footer: Text(L.t("about_sidestore"))) {
            HStack {
                Text(L.t("version"))
                Spacer()
                Text(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "")
                    .foregroundColor(.secondary)
            }
            if let url = URL(string: "https://github.com/siddhantakakhael-cyber/Termant") {
                Link("GitHub", destination: url)
            }
        }
    }
}

/// The models the chosen service offers, to pick one.
struct ModelPicker: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var prefs: Prefs
    @State private var all: [String] = []
    @State private var loading = false
    @State private var problem = ""
    @State private var search = ""
    @State private var freeOnly = false
    @State private var typed = ""

    private var shown: [String] {
        let words = search.lowercased().split(separator: " ")
        return all.filter { name in
            if freeOnly && !LLM.isFree(name) { return false }
            let lower = name.lowercased()
            return words.allSatisfy { lower.contains($0) }
        }
    }

    var body: some View {
        List {
            Section(footer: Text(L.t("model_hint"))) {
                HStack {
                    TextField(L.t("model"), text: $typed)
                        .textInputAutocapitalization(.never)
                        .disableAutocorrection(true)
                        .accessibilityIdentifier("model.typed")
                    Button(L.t("save")) { choose(typed.trimmingCharacters(in: .whitespaces)) }
                        .buttonStyle(.borderless)
                }
                Button {
                    choose("")
                } label: {
                    row(L.t("model_auto"), chosen: prefs.model.isEmpty)
                }
            }
            Section(header: Text(L.t("model_list"))) {
                if all.contains(where: LLM.isFree) {
                    Toggle(L.t("model_free_only"), isOn: $freeOnly)
                }
                if loading {
                    HStack {
                        ProgressView()
                        Text(L.t("model_loading")).foregroundColor(.secondary)
                    }
                }
                if !problem.isEmpty {
                    Text(problem).font(.footnote).foregroundColor(.red)
                        .accessibilityIdentifier("model.problem")
                }
                ForEach(shown, id: \.self) { name in
                    Button {
                        choose(name)
                    } label: {
                        row(name, chosen: prefs.model == name)
                    }
                    .accessibilityIdentifier("model.row")
                }
            }
        }
        .searchable(text: $search, prompt: Text(L.t("model_search")))
        .navigationTitle(L.t("model"))
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
    }

    private func row(_ name: String, chosen: Bool) -> some View {
        HStack {
            Text(name).foregroundColor(.primary)
            Spacer()
            if chosen { Image(systemName: "checkmark").foregroundColor(.accentColor) }
        }
    }

    private func choose(_ name: String) {
        prefs.model = name
        dismiss()
    }

    private func load() async {
        typed = prefs.model
        all = prefs.cachedModels.sorted()
        freeOnly = all.contains(where: LLM.isFree)
        guard prefs.ready else { return }
        loading = all.isEmpty
        do {
            let fresh = try await LLM.models(base: prefs.base, key: prefs.key)
            let first = all.isEmpty
            all = fresh.sorted()
            prefs.cachedModels = fresh // in the service's own order: "automatic" takes its first free one
            if first { freeOnly = fresh.contains(where: LLM.isFree) }
            problem = ""
        } catch let failure as LLM.Failure {
            if all.isEmpty { problem = ChatStore.explain(failure) }
        } catch {
            if all.isEmpty { problem = error.localizedDescription }
        }
        loading = false
    }
}
