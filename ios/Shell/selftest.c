// Tries the shell on a Linux computer, where the same engine runs:
//   selftest <alpine-minirootfs.tar.gz> <empty folder to work in>
// Prints one line per check and ends with "RESULT: PASS" or "RESULT: FAIL".
// Licensed under the GNU GPL version 3; see ios/LICENSE.md.

#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include "termant_shell.h"

static int failures;

static void check(int ok, const char *what, const char *detail) {
    printf("%s  %s%s%s\n", ok ? "ok  " : "FAIL", what, detail != NULL && !ok ? "\n      got: " : "", detail != NULL && !ok ? detail : "");
    if (!ok)
        failures++;
}

struct text {
    pthread_mutex_t lock;
    char bytes[1 << 16];
    size_t length;
    int ended, status;
};

static void collect(void *context, const char *bytes, size_t length) {
    struct text *t = context;
    pthread_mutex_lock(&t->lock);
    if (length > sizeof(t->bytes) - 1 - t->length)
        length = sizeof(t->bytes) - 1 - t->length;
    memcpy(t->bytes + t->length, bytes, length);
    t->length += length;
    t->bytes[t->length] = '\0';
    pthread_mutex_unlock(&t->lock);
}

static void ended(void *context, int status) {
    struct text *t = context;
    pthread_mutex_lock(&t->lock);
    t->ended = 1;
    t->status = status;
    pthread_mutex_unlock(&t->lock);
}

static long long now_ms(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (long long) t.tv_sec * 1000 + t.tv_nsec / 1000000;
}

static void *stop_soon(void *unused) {
    (void) unused;
    sleep(1);
    tsh_stop();
    return NULL;
}

static int run(const char *command, int seconds, struct text *t, int *status) {
    memset(t->bytes, 0, sizeof(t->bytes));
    t->length = 0;
    return tsh_run(command, NULL, seconds, collect, t, status);
}

// waits until the terminal has printed `needle`
static int shows(struct text *t, const char *needle, int seconds) {
    for (int i = 0; i < seconds * 20; i++) {
        pthread_mutex_lock(&t->lock);
        int found = strstr(t->bytes, needle) != NULL;
        pthread_mutex_unlock(&t->lock);
        if (found)
            return 1;
        usleep(50000);
    }
    return 0;
}

int main(int argc, char **argv) {
    if (argc != 3) {
        fprintf(stderr, "usage: selftest <rootfs.tar.gz> <work folder>\n");
        return 2;
    }
    setvbuf(stdout, NULL, _IOLBF, 0);
    // An iPhone app does its work on threads that let no signal in, and a thread hands that on to the
    // threads it starts. The engine needs one signal to wake its threads, so the test begins as the app does.
    sigset_t all;
    sigfillset(&all);
    pthread_sigmask(SIG_BLOCK, &all, NULL);
    char root[4096], why[512] = "";
    snprintf(root, sizeof(root), "%s/root", argv[2]);
    static struct text t = {.lock = PTHREAD_MUTEX_INITIALIZER};
    int status = 0;

    check(tsh_import(argv[1], root, why, sizeof(why)) == 0, "the root file system unpacks", why);
    check(tsh_import(argv[1], root, why, sizeof(why)) == -1, "a second unpacking into the same folder is refused", NULL);
    check(tsh_run("true", NULL, 5, NULL, NULL, &status) < 0, "nothing runs before Linux has started", NULL);
    check(tsh_boot(root, "Termant test\n", why, sizeof(why)) == 0, "Linux starts", why);
    check(tsh_boot(root, NULL, why, sizeof(why)) == 0 && tsh_running(), "starting twice is harmless", why);

    check(run("echo hello; echo problem >&2; exit 3", 20, &t, &status) == 0 && status == 3
          && strcmp(t.bytes, "hello\nproblem\n") == 0, "a command gives its output, its errors and its exit code", t.bytes);
    check(run("cat /etc/alpine-release && uname -m && id -u && pwd && echo $HOME $PATH", 20, &t, &status) == 0 && status == 0
          && strstr(t.bytes, "3.19") != NULL && strstr(t.bytes, "i686\n0\n/root\n/root /usr/local/sbin") != NULL,
          "it is Alpine on a 32-bit x86, as root, in /root", t.bytes);
    check(run("ls -l /bin/sh /etc/shadow /tmp -d && readlink /bin/sh", 20, &t, &status) == 0 && status == 0
          && strstr(t.bytes, "lrwxrwxrwx") != NULL && strstr(t.bytes, "-rw-r-----") != NULL
          && strstr(t.bytes, "drwxrwxrwt") != NULL && strstr(t.bytes, "root     shadow") != NULL
          && strstr(t.bytes, "/bin/busybox\n") != NULL, "links, modes and owners came through the unpacking", t.bytes);
    check(run("cat /etc/inittab /etc/resolv.conf /etc/motd", 20, &t, &status) == 0
          && strstr(t.bytes, "::sysinit:/bin/true") != NULL && strstr(t.bytes, "nameserver ") != NULL
          && strstr(t.bytes, "Termant test") != NULL, "the files the app writes at start are there", t.bytes);
    check(run("echo kept > /root/note.txt && mkdir -p /root/a/b && ln -s note.txt /root/link && cat /root/link", 20, &t, &status) == 0
          && strcmp(t.bytes, "kept\n") == 0, "files can be written and linked", t.bytes);
    check(run("i=0; while [ $i -lt 2000 ]; do i=$((i+1)); done; echo $i; seq 1 3000 | wc -l", 60, &t, &status) == 0
          && strcmp(t.bytes, "2000\n3000\n") == 0, "loops and pipes work", t.bytes);
    long long began = now_ms();
    check(run("echo before; sleep 30; echo after", 2, &t, &status) == 1 && strcmp(t.bytes, "before\n") == 0
          && status == 128 + 9, "a command that takes too long is stopped", t.bytes);
    check(now_ms() - began < 2900, "and that at once, when its time is over", NULL);
    pthread_t stopper;
    pthread_create(&stopper, NULL, stop_soon, NULL);
    began = now_ms();
    check(run("echo one; sleep 30; echo two", 60, &t, &status) == 2 && strcmp(t.bytes, "one\n") == 0,
          "the app can stop a command", t.bytes);
    check(now_ms() - began < 1900, "and that at once", NULL);
    pthread_join(stopper, NULL);
    check(run("pidof sleep; echo end", 20, &t, &status) == 0 && strcmp(t.bytes, "end\n") == 0,
          "what a stopped command had started is gone as well", t.bytes);
    check(run("uname -n -v", 20, &t, &status) == 0 && strncmp(t.bytes, "termant Termant ", 16) == 0,
          "the computer is called termant", t.bytes);
    check(run("sleep 20 & echo started", 10, &t, &status) == 0 && status == 0 && strcmp(t.bytes, "started\n") == 0,
          "a command that leaves something running in the background still returns", t.bytes);
    check(run("nosuchprogram", 10, &t, &status) == 0 && status == 127 && strstr(t.bytes, "not found") != NULL,
          "a program that does not exist is reported", t.bytes);
    check(run("kill -9 $(pidof sleep) 2>/dev/null; sleep 1; ps -o pid,stat,comm | grep -c ' Z '", 20, &t, &status) == 0
          && strcmp(t.bytes, "0\n") == 0, "ended processes do not pile up", t.bytes);

    memset(t.bytes, 0, sizeof(t.bytes));
    t.length = 0;
    int terminal = tsh_open(80, 24, collect, ended, &t);
    check(terminal >= 0, "a terminal opens", NULL);
    check(shows(&t, "Termant test", 20) && shows(&t, "# ", 20), "the login shell greets and prompts", t.bytes);
    tsh_type(terminal, "stty size; cat /root/note.txt; tty\r", 35);
    check(shows(&t, "24 80", 20) && shows(&t, "kept", 20) && shows(&t, "/dev/pts/", 20), "typing reaches the shell, which sees a terminal of 80 x 24", t.bytes);
    tsh_resize(terminal, 100, 30);
    tsh_type(terminal, "stty size\r", 10);
    check(shows(&t, "30 100", 20), "a new size reaches the shell", t.bytes);
    tsh_type(terminal, "sleep 100\r", 10);
    usleep(500000);
    tsh_type(terminal, "\x03", 1);
    tsh_type(terminal, "echo back-$((40+2))\r", 20);
    check(shows(&t, "back-42", 20), "Ctrl+C stops what runs in the terminal", t.bytes);
    int second = tsh_open(80, 24, collect, ended, &t);
    check(second >= 0 && second != terminal, "a second terminal opens next to it", NULL);
    tsh_close(second);
    tsh_type(terminal, "exit 7\r", 7);
    for (int i = 0; i < 200 && !t.ended; i++)
        usleep(50000);
    check(t.ended, "the app is told when the shell ends", NULL);
    tsh_close(terminal);
    int again = tsh_open(80, 24, collect, ended, &t);
    check(again >= 0, "a new terminal opens after the old one was closed", NULL);
    tsh_close(again);
    check(run("cat /root/note.txt", 20, &t, &status) == 0 && strcmp(t.bytes, "kept\n") == 0, "commands still run afterwards", t.bytes);

    printf("RESULT: %s\n", failures == 0 ? "PASS" : "FAIL");
    fflush(stdout);
    _exit(failures == 0 ? 0 : 1); // Linux keeps running on its own threads; there is nothing to wait for
}
