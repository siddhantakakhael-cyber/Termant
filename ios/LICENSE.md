# Licence of the Termant app for iPhone

Since version 0.2 the iPhone app contains a Linux shell. The shell is not Termant's own work:

- **iSH** (https://ish.app, https://github.com/ish-app/ish) runs the Linux programs: it emulates
  an x86 processor and answers the programs' requests to the Linux kernel. iSH is licensed under
  the GNU General Public License, version 3, with the additional terms of its `LICENSE.IOS`.
  The app is built from iSH's sources as its authors published them, at the commit named in
  `ios/ish/versions.sh`; one line of its build files is changed while building (the path of the
  compiler for the "vdso", see `ios/ish/build.sh`).
- **Alpine Linux** (https://alpinelinux.org) is the Linux that runs in it: the unchanged
  "minirootfs" for 32-bit x86, version 3.19, as published at
  https://github.com/alpinelinux/docker-alpine (the exact file is named in `ios/ish/versions.sh`).
  Its programs have their own licences: BusyBox the GNU GPL version 2, musl and apk-tools the
  MIT licence and the GPL version 2, and so on. Their sources are published by Alpine Linux at
  https://git.alpinelinux.org/aports (branch `3.19-stable`) and https://distfiles.alpinelinux.org.
- **xterm.js** (https://xtermjs.org) draws the terminal. MIT licence.

Because the app contains iSH, **the iPhone app as a whole (`ios/` of this repository, and the
IPA built from it) is distributed under the GNU General Public License, version 3**. Its full
text is in the file `COPYING` next to this one, and inside the app under Settings > About.
Termant's own files in `ios/` may also be used under the MIT licence of the rest of this
repository, as long as they are used without iSH.

The complete source of the app, with the scripts that fetch iSH and Alpine Linux and build
everything (`ios/ish/fetch.sh`, `ios/ish/build.sh`, `.github/workflows/ios.yml`), is at

    https://github.com/siddhantakakhael-cyber/Termant

There is no warranty for this program, to the extent permitted by law; see sections 15 and 16
of the licence.
