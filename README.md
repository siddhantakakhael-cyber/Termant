# Termant

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

## Android app

[`android/Termant.apk`](android/Termant.apk) is a small Android app (Android 7.0+)
that opens your Termant server full screen, without the browser bars.

1. Start the server (see **Run it** above). The app is only a screen for the
   server; Ubuntu itself does not run on the phone.
2. Copy `Termant.apk` to the phone and open it. Android will ask you to allow
   installing from this source.
3. Enter the server address, for example `http://192.168.1.10:3000`, then log in.

Press Back inside the terminal to exit or to change the server address.

To rebuild it, run `android/build.sh` on a machine with the Android SDK
(`ANDROID_HOME` set), or use the **Android APK** workflow on GitHub. A build
signed with a different key cannot update an installed copy; uninstall first.

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
