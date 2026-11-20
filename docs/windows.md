# Termant for Windows

[← Back to the front page](../README.md)

A desktop app for Windows 10 and 11 (64-bit): the same AI agent as on Android,
but here it works on your PC. You write what you want in Hindi, Hinglish or
English; it runs commands, writes files, opens programs, types, clicks, sets
alarms and keeps rules.

> **First version (0.1.0).** It is built and tested automatically on a Windows
> machine of GitHub (the test results are listed [below](#how-it-is-tested)),
> but it has not yet been used on many real PCs. Please report what breaks.

## Install

1. Download **[Termant-Setup.exe](https://github.com/siddhantakakhael-cyber/Termant/releases/download/windows-v0.1.0/Termant-Setup.exe)**.
2. Open it. Windows shows a blue box, **"Windows protected your PC"**, because
   the installer is not signed (a signing certificate costs money). Choose
   **More info**, then **Run anyway**.
3. Termant installs for your user only (no administrator needed), puts an icon
   on the desktop and in the Start menu, and starts.
4. Send a message. The first one downloads the agent (OpenCode, about 120 MB).

To remove it: Windows **Settings → Apps → Installed apps → Termant → Uninstall**.

## What it does

| | |
| --- | --- |
| **Chat** | Ask for anything; the answer shows each command the agent ran. Earlier chats are kept in the list on the left. |
| **Models** | Free models work with no account. Pick one from the chip at the top right, or leave it on automatic. If a model does not answer, Termant tries other free ones. With your own API key (Settings), that company's models appear too. |
| **PC control** | The agent uses the `pc` command: open programs and links, list and switch windows, type, press keys, click, read what a window shows, take screenshots, volume, brightness, clipboard, lock, sleep, shut down. |
| **Terminal** | Up to 8 tabs of PowerShell, Command Prompt, or Ubuntu if you have WSL. The `pc` command works there too. |
| **Rules** | "Every day at 11 pm turn the volume to zero." Rules run at a time of day, every so often, once after a delay, or when Termant starts. |
| **Alarms and timers** | They ring in Termant, with a notification. |
| **Notes** | Tell it something about you and it remembers in later chats. |
| **Questions** | When the agent needs a decision, a card with choices appears and it waits. |
| **Check-ins** | If you turn it on, the agent writes first at the times of day you choose. |
| **Tools** | Web search, GitHub token for `git push`, MCP servers. |
| **Look** | Hindi or English, dark or light. |
| **Voice** | The microphone button starts Windows dictation (the same as pressing Win+H). |

Closing the window keeps Termant running in the tray next to the clock, because
alarms, timers and rules need it. To stop it fully: right-click the tray icon → **Quit**.

## The `pc` command

The agent uses it by itself; you can also type it in Termant's terminal.

```
pc open <link | file | folder | program>     pc app <name>        pc apps [filter]
pc windows        pc focus <title>           pc close <title>
pc processes [filter]                        pc kill <name | id>

pc type <text>    pc key <enter | ctrl+s | alt+f4 | win+d ...>
pc click <x> <y> [right|double]              pc move <x> <y>      pc scroll <lines>
pc screen         what the window in front shows, with the point to click for each thing
pc screenshot [file.png]

pc volume [0-100] pc mute [on|off]           pc brightness [0-100]
pc say <text>     pc battery                 pc wifi [on|off]     pc info
pc lock           pc sleep                   pc shutdown [minutes]  pc restart [minutes]  pc cancel-shutdown
pc clipboard get  pc clipboard set <text>

pc notify <title> [text]
pc alarm <HH:MM> [label]    pc timer <10m> [label]    pc alarms    pc alarms remove <id>
pc ring [label]             pc stop
pc ask <question> [choices ...]
pc rule add <at HH:MM | every 30m | startup | in 10m> do <command>
pc rule list                pc rule remove <id>
pc note add <text>          pc note list     pc note remove <number>
```

## Good to know

- **The agent works on your real PC.** Nothing is sandboxed. It is told to ask
  before anything that cannot be undone, but an AI can make mistakes. Keep
  copies of files that matter. In Settings you can turn off **Do it without asking**.
- **Your messages go over the internet** to the AI model you chose.
- **Alarms, timers, rules and check-ins work only while Termant is running** and
  the PC is awake. Turn on **Start with Windows** in Settings if you rely on them.
- **Things that need administrator rights** (turning Wi-Fi off, some settings)
  fail unless Termant itself was started as administrator.
- **Brightness** can be set only on screens that Windows can control (laptops, usually not desktop monitors).
- **Different from the Android app:** there is no Ubuntu inside the app (the
  terminal is the PC's own; Ubuntu appears if you have WSL), the agent's
  questions and status are shown in Termant's window and not over other apps,
  the agent does not plan its own follow-up questions, and there are no rules
  for charging, battery level or Wi-Fi.

## How it is tested

Every change is built and tested by the **Windows app** workflow on a Windows
Server 2025 machine with a real desktop:

1. The `pc` command opens Notepad, brings it to the front, types into it, reads
   the text back, clicks its menu, takes a screenshot and closes it; and checks
   the clipboard, the program list, system information and the error messages.
2. The installer is built, then run silently, as a user would install it; the
   test checks that the program and its shortcuts are there.
3. The installed program tests itself: first-start notice, notifications,
   notes, rules that really run, an alarm and a timer that rings, a question
   card, a terminal tab that runs commands, settings in Hindi and light theme.
4. It downloads the agent, loads the model list, sends real messages to a free
   model and asks it to control the PC.

What the test machine cannot check: sound (it has no speakers, so volume, mute
and `pc say` are only tried), brightness, battery, Wi-Fi, voice dictation, and
the blue SmartScreen box.

## Build from source

Node.js 22 or newer, on Windows:

```sh
cd windows
npm ci
npm start          # run it
npm run dist       # writes windows/dist/Termant-Setup.exe
```

The app is in `windows/`: `main.js` (window, chats, the door for `pc`),
`src/agent.js` (runs OpenCode), `src/schedule.js` (rules, alarms),
`resources/termant-pc.ps1` (the `pc` command), `ui/` (what you see).
