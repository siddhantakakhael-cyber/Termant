# Public test key

`testkey.pk8` is **public on purpose** (like Termux's GitHub builds): it lets
every build from this repository update an installed copy without uninstalling.
Because anyone can sign with it, only install APKs you got from this
repository's Releases page. For your own distribution, replace it with a
private key via `SIGN_KEY` / `SIGN_CERT` when running `build.sh`.
