# Termant for iOS (0.1.0)

A small app for the iPhone: the chat of the PC — the same page the browser
opens — with a real microphone on top of it. The Android app has the same two
ways of speaking, and this one is smaller on purpose: the PC is the brain, the
phone is the voice.

- **Press the microphone** (the round button, bottom right): speak the
  command, it is sent to the PC, and the answer appears on the page.
- **The gear** holds the address of the PC and the switch for the wake word.
  With **Listen for “hey Termant”** on, the app waits for those words — like
  the Android app — and takes what comes after them as the command. Fifteen
  quiet seconds and the waiting ends; five microphone failures in a row and
  the listening turns itself off (the gear turns it on again).
- The address is the one the PC shows under **Settings → Web chat**. The first
  time, a security notice appears: go on to the site, and put in the password
  (it is in the same Settings of the PC).
- Recognition runs on the phone where the phone allows it (English, Indian),
  with the internet as a fallback. What is said is sent as plain text; the
  words do not go to a model, only to the PC.

## Install it

The app is **signed by nobody** — it is built on a machine that has no Apple
account, and a free account cannot sign an app for other phones. Two free
programs sign it for your own phone instead:

1. Download **[Termant.ipa](https://github.com/siddhantakakhael-cyber/Termant/releases/download/ios-v0.1.0/Termant.ipa)**
   from the [release](https://github.com/siddhantakakhael-cyber/Termant/releases/tag/ios-v0.1.0).
2. On a PC, open **Sideloadly** (free), put in any Apple ID, plug the phone in
   by cable, and drop the file on it. On a Mac, the same with **AltStore**.
3. On the phone, set the app to be trusted: Settings → General → VPN & Device
   Management, then open it.
4. First message: put in the address, allow the microphone, and the chat of
   the PC is on the screen.

The app stays installed for seven days with a free Apple ID, and the program
that signed it renews it when the phone is near the PC (or the Mac).

## What it is not

The page it opens is the page: the microphone of the page itself does not work
on the iPhone (the browser there does not listen), so the button of the app is
the microphone. The app cannot send files, open a terminal or change rules —
that is done on the PC, and it cannot see the chat when the PC is asleep.

## How it is tested

The build is tested on a Mac in the cloud: the app is made, its permissions
are checked, it is packed, and it is installed on a simulator, launched, and
must stay up. **Not tested:** a real phone on a real network, and the
microphone itself (a simulator has none). What a real voice does on a real
phone is what the first messages of the app are for.
