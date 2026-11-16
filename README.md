# Termant

Ubuntu in a terminal, two ways:

- **[Android app](#android-app)**: a real Ubuntu 24.04 system on your phone, no root, no server.
- **[Web terminal](#web-terminal)**: an Ubuntu terminal in the browser, hosted on your own server.

## Android app

**Download:** [Termant.apk](https://github.com/siddhantakakhael-cyber/Termant/releases/latest/download/Termant.apk) (Android 7.0 or newer, under 1 MB)

Open the APK on your phone and allow the install. The first start downloads
the official Ubuntu base image from Canonical (about 30 MB) and checks its
checksum. After that you are `root` in Ubuntu:

```sh
apt update
apt install python3 git nano
```

- **AI agent chat is the home screen.** It uses [OpenCode](https://github.com/sst/opencode), an open-source agent that writes, edits and runs code for you. It comes with free models, so it works without an account or API key. First use downloads it (about 100 MB).
- **Real Ubuntu with apt.** The same packages as on a PC, for ARM64, 32-bit ARM and x86-64 phones.
- **Everything stays installed.** Ubuntu lives in the app's storage. Packages and files survive closing the app, reboots and app updates. Only uninstalling the app, clearing its data, or "Reinstall Ubuntu" removes them.
- **Sessions keep running** in the background (see the notification), up to 8 tabs.
- **Extra keys:** ESC, TAB, CTRL, ALT, arrows, HOME, END, PGUP, PGDN.
- **Pinch** to resize text, **long-press** to select, copy and paste.
- **Phone storage** appears at `/sdcard` after you allow it from the menu.

### AI agent

The chat screen talks to the agent for you. For the full OpenCode interface, open the terminal and choose **AI agent (OpenCode)** in the menu (or run `termant-agent` in any tab). The agent works in `~/workspace`;
`cd` to another folder first to use it there. Inside OpenCode, `/models` switches model and
`/connect` adds your own provider key if you want a paid model.

- The free models are run by OpenCode, not by Termant. Your prompts and the files the agent reads are sent to their service, and the list of free models changes over time.
- The agent runs as root inside Ubuntu and can change or delete anything there, and in `/sdcard` if you allowed storage. It cannot touch the rest of the phone.
- OpenCode has no 32-bit build, so the agent needs a 64-bit phone.

### Limits

- No root means no `systemd`, no `mount`, no Docker, and ports below 1024 cannot be opened. Start services directly (for example `nginx` on port 8080, `sshd -p 8022`).
- Programs run through [PRoot](https://proot-me.github.io/), which is slower than native, mostly for work with many small files.
- Android 12 and newer may stop background programs that use many processes ("signal 9"). This is an Android limit that also affects Termux.
- Every build is tested automatically on an Android 14 x86-64 emulator (set up Ubuntu, `apt install`, restart, package still there, install OpenCode and get a reply from a free model). ARM phones use the same code with ARM builds of the same components, but are not covered by that automatic test.

### How it works

| Part | From |
| --- | --- |
| Terminal emulator and view | [Termux](https://github.com/termux/termux-app) `terminal-emulator` / `terminal-view`, vendored in `android/vendor/termux` |
| Running Linux programs without root | Termux's build of PRoot, fetched and checksum-verified at build time |
| AI agent | [OpenCode](https://github.com/sst/opencode), installed inside Ubuntu on first use with its official installer |
| The system | Ubuntu Base 24.04 from `cdimage.ubuntu.com`, downloaded by the app |

Build it yourself with `android/build.sh` (Android SDK + NDK, JDK 17; no Gradle), or let the
**Android APK** GitHub workflow do it. The app is licensed GPLv3 (`android/LICENSE`).
Release APKs are signed with a public test key (`android/keystore/README.md`), so only
install APKs from this repository's Releases page.

## Chat first

Termant opens as a chat, not a terminal. Type or speak what you want in Hindi,
Hinglish or English; the agent answers in a chat bubble and lists the commands
it ran above its answer. **New chat** starts a fresh conversation.

The answer appears while it is being written, word by word, instead of all at
once at the end. The microphone button next to the message box takes
dictation inside the app when the phone has a speech service (Google's, on
most phones); what you say lands in the message box so you can correct it
before sending.

**What it is doing:** while the agent works, the bubble names the current
step and what it is about: *Thinking* (with the sentence being thought, when
the model shows it), *Running a command* with the command, *Reading a file*
with its path, *Searching the web* with the search words, *Working on the
screen*, *Working with Git / GitHub*, *Installing*, *Writing the answer*, and
so on, with the time waited. While there is nothing to add it shows a tip.

**Language and look:** the app's own buttons and messages are in Hindi
(Hinglish) by default. Settings → *Bhasha (Language)* switches to English and
back, and *Theme* switches between dark and light, the terminal included. Both
apply at once.

Earlier chats are kept: they are listed on the new-chat screen and under the
**☰** button, and opening one continues it where it stopped (it carries on in
the same OpenCode session, so the agent still knows what was said).

The real Ubuntu terminal is one tap away: **>_** at the top. **Chat**
(or Back) in the terminal returns to the chat. The first chat message downloads
the agent (OpenCode, about 100 MB).

## Automation rules

Ask once, and the app keeps doing it by itself:

| You say | Rule the agent creates |
| --- | --- |
| `roz raat 11 baje WiFi band karo` | `phone rule add at 23:00 -- phone wifi off` |
| `jab charging lage toh backup chalao` | `phone rule add charging -- bash /root/backup.sh` |
| `WiFi se judte hi mummy ko message bhejo` | `phone rule add wifi-connected -- phone sms +91… "…"` |

Triggers: `at HH:MM` (every day), `charging`, `unplugged`, `battery-below N`,
`wifi-connected`, `wifi-lost`, `boot`. The action is any command in Ubuntu.
Rules are listed in **Settings** (switch off or delete there) and with
`phone rule list`. A rule that fails shows a notification.

Limits: there is no trigger for a place, a weekday or a date; "when I reach
home" can only be approximated by `wifi-connected`, which fires on any Wi-Fi.
Rules stop if Termant is force-stopped or closed with **Exit**; after a phone
restart they come back by themselves. Some phones kill background apps
aggressively: exclude Termant from battery optimisation there. Sending SMS
needs the SMS permission, which Android asks for the first time.

## Settings: model, workspace, your own API key

**Auto-switch** (on by default): when a model ends with an error or says
nothing, usually because its free limit is used up, Termant tries the next
backup model by itself (up to 5 per message) and says so under the reply. It
stays on the model that worked for 3 hours. Backups are all free models unless
you pick your own in Settings. It never retries once the agent has already run
a command, so nothing is done twice.

The **Arena ↗** chip opens the arena.ai website in your browser, where you
sign in and use it. Arena.ai offers no way for other apps to use its agent, so
it is not connected to the chat and cannot control the phone or Ubuntu.

Under the chat are two chips: the **workspace folder** the agent works in and
the **model** that answers. The gear button opens Settings, where you can also
enter your own API key (Anthropic, OpenAI, Google Gemini, OpenRouter, Groq,
DeepSeek, Cerebras, Mistral). With a key, that provider's models appear in the model list and are
billed to your account; without one, free models are used. Keys are stored only
on the phone, in Ubuntu at `~/.config/termant/keys.env`.

**MCP servers:** in Settings → MCP servers you can add servers (name + command)
that OpenCode connects to and uses as tools, for example a filesystem server
so the agent can read files in phone storage. Termant writes them to
`~/.config/opencode/opencode.jsonc` inside Ubuntu before each agent run, under
`mcp.servers`. The command is run by `bash -lc`, so it can be typed as in a
terminal, for example `npx -y @modelcontextprotocol/server-filesystem /sdcard`
(that one needs `apt install nodejs npm` first). The automated test checks
that OpenCode starts such a server and asks it for its tools; no published
MCP server has been tried on a real phone yet.

## Tools for the agent: GitHub, web search, screen control

All three are in Settings → *Tools for the agent*.

**GitHub.** Paste a personal access token (the dialog links to the GitHub page
that makes one; tick `repo`, and `workflow` if the agent should change
workflow files). Termant asks GitHub whose token it is, refuses one that
GitHub rejects, and shows the account name. From then on `git clone`, `pull`
and `push` to `https://github.com/…` work in the chat and in the terminal,
private repos included, and `gh` (`apt install gh`) is signed in. The token is
stored only on the phone, in `~/.config/termant/keys.env` inside Ubuntu, as
`GH_TOKEN`; git gets it through a credential helper that answers for
github.com only. Commits use the account's name and its GitHub no-reply
address unless git already has settings. Use https addresses: there is no SSH
key.

**Web search.** On by default. The agent uses OpenCode's `websearch` and
`webfetch` tools for anything recent and names its source. Searches go to Exa,
which needs no key; with your own Exa or Tavily key (optional rows in Settings)
that service is used with your account. Turning the row off removes the tool.

**Screen control.** Off until you turn it on in Android's Accessibility
settings (the row takes you there; Android 13 and newer may first want *Allow
restricted settings* in Termant's App info, as the dialog explains). With it
on, the agent can open any app, read what it shows and tap, type and swipe
there:

```
phone apps                    phone app YouTube         phone screen
phone tap 7 | "Search" | X Y  phone type "lofi music"   phone swipe up|down|left|right
phone key back|home|recents|notifications|enter
```

`phone screen` lists what is on the screen as numbered lines, and every action
answers with the new screen. It acts only when a `phone` command asks it to,
keeps no record, and refuses to type into password boxes. The agent is told to
ask before anything that spends money, sends a message, deletes something or
changes an account. Limits: games and other screens drawn as one picture have
nothing to read; Android's own permission questions ("Allow … to send
notifications?") could not be read in the automated test, so answer those
yourself; `phone key enter` needs Android 11 or newer.

## Over other apps: what the agent is doing, and its questions

When the agent is at work and Termant is not on the screen (the agent opened
another app, or you went elsewhere), a small card at the top of the screen
says what it is doing, the same words as in the chat, with a **■** button
that stops it. A tap on the card brings Termant back.

When the agent needs something from you to go on, it asks right there
instead of stopping:

```
phone ask "Kaunsa size order karoon?" "Small" "Medium" "Large"    buttons to choose from
phone ask "Kis naam se save karoon?"                               a box to type in
```

With choices you tap one (or *Type something else*); without, you type. **✕**
skips the question, and the agent is told not to do the step it asked about.
The command waits up to 4 minutes and prints the answer. Inside Termant the
same question comes as a dialog. The agent is told to use this for the yes it
needs before sending, paying, deleting or changing an account.

When the agent is done, the card shows the answer and stays for 10 seconds (a
line on it runs out with the time). *Type the next message here* on that card
opens a box and the keyboard, so the conversation can go on without opening
Termant; a card you are typing on stays until the message is sent or the card
is closed. While the answer is being written, the card shows its latest words.
Swiping any card up sends it away: the working card for that turn, the answer
at once, and a question to a notification (it is still waiting).

The card needs one of two things: Screen control (it then comes along, with
nothing else to allow), or Settings → *Show over other apps*, which opens
Android's "display over other apps" page for Termant. With neither, a
question arrives as a notification that opens Termant. While the agent itself
taps or swipes, the card lets the touch through to the app under it, and
`phone screen` leaves the card out.

## Phone control

The agent can use the phone, not only write code. In the chat, type (or
tap the microphone and say) what you want in Hindi, Hinglish or English:

| You say | What happens |
| --- | --- |
| `mujhe subah 7 baje utha dena` | an alarm for 07:00 is set in the phone's Clock app |
| `3 minute baad chai ka timer bajana` | Termant rings after 3 minutes |
| `torch on karo` | the flashlight turns on |

It does this with the `phone` command, which also works by hand in any tab:

```
phone alarm 07:00 Wake up     phone timer 10m Tea      phone ring / phone stop
phone notify "Title" "Text"   phone say "hello"        phone torch on|off
phone battery                 phone vibrate 500        phone clipboard get|set
phone open https://…          phone wake on|off        phone help
```

If the Clock app cannot be used (for example Termant is not on screen),
Termant sets its own alarm and rings itself. If `phone alarm` fails
altogether, the agent is told to write a small Python script that waits until
the time and rings, and to run it; that only works while Termant stays running.

Limits: an alarm kept by Termant itself is lost if the phone restarts or the
app is force-stopped; alarms in the Clock app are not. The request is sent to
OpenCode's free model service, so it needs internet.

## Web terminal

A real Ubuntu terminal in your browser. Packages you install stay installed.

- **Real Ubuntu 24.04** root shell: `apt`, `pip`, `git`, `vim`, `htop`, everything.
- **Packages and files persist.** The whole system (`/usr`, `/etc`, `/var`, `/opt`,
  `/root`, `/home`) lives on Docker volumes, so nothing is lost on restart,
  rebuild or upgrade.
- **Sessions persist.** Shells run on the server. Close the browser, come back
  later from another device, and your running programs and screen are still there.
- **Tabs**, full colour, resize, clickable links, copy/paste.
- **Phone friendly:** extra key row with Esc, Tab, Ctrl, Alt and arrows.
- **Password protected.**

## Run it

You need a machine with Docker (any VPS, or your own PC).

```sh
git clone https://github.com/siddhantakakhael-cyber/Termant.git
cd Termant
echo "TERMANT_PASSWORD=choose-a-long-password" > .env
docker compose up -d --build
```

Open `http://<your-server>:3000` and log in.

Try it:

```sh
apt update && apt install -y neofetch
neofetch
```

Then run `docker compose down && docker compose up -d --build` and `neofetch` is still there.

### What deletes your data

Only removing the volumes: `docker compose down -v` or `docker volume rm`.
Never run those unless you want a fresh Ubuntu.

Hosts without persistent disks (for example free tiers that give you a
temporary filesystem) cannot keep packages, whatever software you run on them.
Use a host where Docker volumes are stored on a real disk.

## Security

The password gives a **root shell**. Use a long password and put Termant behind
HTTPS (Caddy, nginx, Cloudflare Tunnel) before exposing it to the internet;
over plain HTTP the password and everything you type can be read on the network.

## Settings

| Variable | Default | Meaning |
| --- | --- | --- |
| `TERMANT_PASSWORD` | random, printed in the log | Login password |
| `PORT` | `3000` | Port to listen on |
| `TERMANT_SHELL` | `/bin/bash` | Shell to start |
| `TERMANT_HOME` | `$HOME` | Starting directory |
| `TERMANT_MAX_SESSIONS` | `8` | Maximum number of tabs |

## Run without Docker

```sh
npm install
TERMANT_PASSWORD=secret npm start
```

This gives a shell on whatever system you run it on, as the user who started it.
