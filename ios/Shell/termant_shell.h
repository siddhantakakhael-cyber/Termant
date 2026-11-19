// The Linux shell inside the Termant iPhone app: the iSH engine (x86 emulation with
// Linux system calls, https://ish.app) running Alpine Linux from the app's own folder.
// Plain C, so that Swift can call it and so that it can be tried on a Linux computer too.
//
// This file and the two .c files next to it are licensed under the GNU GPL version 3,
// like iSH itself; see ios/LICENSE.md.

#ifndef TERMANT_SHELL_H
#define TERMANT_SHELL_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/// Unpacks a root file system (a .tar.gz, for example Alpine's "minirootfs") into `root`,
/// in the form the engine keeps it: `root/data` with the files and `root/meta.db` with
/// owners, modes and links. `root` must not exist yet. Returns 0, or -1 with the reason in `why`.
int tsh_import(const char *archive, const char *root, char *why, size_t why_size);

/// Starts Linux on the file system in `root` (once per process). `motd` replaces /etc/motd
/// when it is not NULL. Returns 0, or a negative number with the reason in `why`.
int tsh_boot(const char *root, const char *motd, char *why, size_t why_size);

/// 1 once tsh_boot has succeeded.
int tsh_running(void);

/// Output of a terminal. Called on a thread of the engine: copy the bytes and return quickly.
typedef void (*tsh_output_fn)(void *context, const char *bytes, size_t length);
/// The login of a terminal has ended (the user typed "exit"); `status` is what /bin/login returned.
/// Called on a thread of the engine: do not call tsh_close from inside it.
typedef void (*tsh_ended_fn)(void *context, int status);

/// Opens a terminal with a login shell in it. Returns its number (0 or more), or a negative number.
int tsh_open(int columns, int rows, tsh_output_fn output, tsh_ended_fn ended, void *context);
/// What the user typed.
void tsh_type(int terminal, const char *bytes, size_t length);
void tsh_resize(int terminal, int columns, int rows);
/// Hangs up. Nothing is called for this terminal after it returns.
void tsh_close(int terminal);

/// Runs one command line with /bin/sh, without a terminal: no input, output and errors together.
/// Waits until it has ended or `seconds` have passed (then it is killed and 1 is returned).
/// `output` gets what the command printed, piece by piece, on the calling thread.
/// `status` gets the exit code, or 128 + signal. Returns 0, 1 (killed after the time ran out),
/// 2 (killed because tsh_stop was called) or a negative number (it could not be started).
int tsh_run(const char *command, const char *folder, int seconds,
            tsh_output_fn output, void *context, int *status);

/// Ends the commands that tsh_run is waiting for right now. Terminals are left alone.
void tsh_stop(void);

#ifdef __cplusplus
}
#endif

#endif
