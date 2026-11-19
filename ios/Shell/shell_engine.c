// Starts Linux (the iSH engine) inside the app and gives the rest of the app two things:
// terminals with a login shell, and a way to run one command and collect what it printed.
//
// The order of the steps in tsh_boot and tsh_open follows iSH's own app (app/AppDelegate.m,
// app/TerminalViewController.m, app/Terminal.m). Licensed under the GNU GPL version 3; see ios/LICENSE.md.

#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include "kernel/calls.h"
#include "kernel/fs.h"
#include "kernel/init.h"
#include "kernel/signal.h"
#include "kernel/task.h"
#include "fs/devices.h"
#include "fs/fd.h"
#include "fs/path.h"
#include "fs/real.h"
#include "fs/sock.h"
#include "fs/tty.h"

#ifdef __APPLE__
#include <arpa/inet.h>
#include <netdb.h>
#include <netinet/in.h>
#include <resolv.h>
#endif

#include "termant_shell.h"

// in iSH's kernel/uname.c, without a header
extern const char *uname_version;
extern const char *uname_hostname_override;

#define MAX_TERMINALS 8
#define MAX_RUNS 8

struct terminal {
    int number;
    struct tty *tty;
    pid_t_ pid;
    int closed;
    tsh_output_fn output;
    tsh_ended_fn ended;
    void *context;
};

struct run {
    pid_t_ pid; // 0: free
    int ended;
    int status;
};

// Calls into the engine from the app, one at a time. Never taken by a thread of the engine.
static pthread_mutex_t api_lock = PTHREAD_MUTEX_INITIALIZER;
// The tables below and the callbacks. Threads of the engine take it last and call nothing of the engine under it.
static pthread_mutex_t table_lock = PTHREAD_MUTEX_INITIALIZER;
static struct terminal *terminals[MAX_TERMINALS];
static struct run runs[MAX_RUNS];
static struct terminal *opening; // the terminal whose tty is being made (under api_lock)
static int state; // 0 not started, 1 running, -1 failed
static volatile int stops; // counts the calls of tsh_stop

static int say(char *why, size_t why_size, const char *format, ...) {
    if (why != NULL && why_size > 0) {
        va_list args;
        va_start(args, format);
        vsnprintf(why, why_size, format, args);
        va_end(args);
    }
    return -1;
}

static int wait_status(int code) {
    if (code & 0x7f)
        return 128 + (code & 0x7f);
    return (code >> 8) & 0xff;
}

// ------------------------------------------------------------ the ends of the terminals

static int term_init(struct tty *tty) {
    tty->data = opening;
    return 0;
}

static int term_write(struct tty *tty, const void *buf, size_t len, bool blocking) {
    (void) blocking;
    struct terminal *t = tty->data;
    if (t != NULL) {
        pthread_mutex_lock(&table_lock);
        if (!t->closed && t->output != NULL)
            t->output(t->context, buf, len);
        pthread_mutex_unlock(&table_lock);
    }
    return (int) len;
}

static void term_cleanup(struct tty *tty) {
    // the last user of the tty is gone, and that is never before tsh_close
    free(tty->data);
    tty->data = NULL;
}

static const struct tty_driver_ops term_ops = {
    .init = term_init,
    .write = term_write,
    .cleanup = term_cleanup,
};
static struct tty_driver term_driver = {.ops = &term_ops};

// What init itself prints (nothing, normally) goes to the log of the app.
static int console_write(struct tty *tty, const void *buf, size_t len, bool blocking) {
    (void) tty;
    (void) blocking;
    fwrite(buf, 1, len, stderr);
    return (int) len;
}

static const struct tty_driver_ops console_ops = {.write = console_write};
DEFINE_TTY_DRIVER(console_driver, &console_ops, TTY_CONSOLE_MAJOR, 64);

// Called by the engine, on the thread of a process that has just ended.
static void process_ended(struct task *task, int code) {
    pid_t_ pid = task->tgid;
    pthread_mutex_lock(&table_lock);
    for (int i = 0; i < MAX_RUNS; i++) {
        if (runs[i].pid == pid) {
            runs[i].status = wait_status(code);
            runs[i].ended = 1;
        }
    }
    for (int i = 0; i < MAX_TERMINALS; i++) {
        struct terminal *t = terminals[i];
        if (t != NULL && t->pid == pid && !t->closed && t->ended != NULL)
            t->ended(t->context, wait_status(code));
    }
    pthread_mutex_unlock(&table_lock);
}

// ------------------------------------------------------------ starting

// Writes a file inside Linux. `current` must be a process.
// iSH wakes a thread that waits in the host's kernel by sending it the signal SIGUSR1: that is how Ctrl+C,
// a kill and a timer reach a program that sleeps or reads. A thread gets its signal mask from the thread
// that starts it, and the threads an iPhone app does its work on (Grand Central Dispatch) let no signal in.
// So a program's first thread is started with that signal let in, whatever the mask of the caller is;
// the threads of everything the program starts take it over from there.
static void start_task(struct task *task) {
    sigset_t wake, before;
    sigemptyset(&wake);
    sigaddset(&wake, SIGUSR1);
    pthread_sigmask(SIG_UNBLOCK, &wake, &before);
    task_start(task);
    pthread_sigmask(SIG_SETMASK, &before, NULL);
}

static int put_file(const char *path, const char *text, int mode) {
    struct fd *fd = generic_open(path, O_WRONLY_ | O_CREAT_ | O_TRUNC_, mode);
    if (IS_ERR(fd))
        return (int) PTR_ERR(fd);
    fd->ops->write(fd, text, strlen(text));
    fd_close(fd);
    return 0;
}

// The name servers the phone uses now, as the text of /etc/resolv.conf.
static void name_servers(char *out, size_t size) {
    size_t n = 0;
    out[0] = '\0';
#ifdef __APPLE__
    struct __res_state res;
    memset(&res, 0, sizeof(res));
    if (res_ninit(&res) == 0) {
        union res_sockaddr_union servers[8];
        int found = res_getservers(&res, servers, 8);
        for (int i = 0; i < found; i++) {
            char address[NI_MAXHOST];
            if (servers[i].sin.sin_len == 0)
                continue;
            if (getnameinfo((struct sockaddr *) &servers[i].sin, servers[i].sin.sin_len,
                            address, sizeof(address), NULL, 0, NI_NUMERICHOST) != 0)
                continue;
            int wrote = snprintf(out + n, size - n, "nameserver %s\n", address);
            if (wrote < 0 || (size_t) wrote >= size - n)
                break;
            n += (size_t) wrote;
        }
        res_ndestroy(&res);
    }
#endif
    if (n == 0)
        snprintf(out, size, "nameserver 1.1.1.1\nnameserver 8.8.8.8\n");
}

// init only has to adopt and bury the processes that lose their parent;
// what the root file system wanted to start at boot (openrc, logins on tty1..6) is not there.
static const char *inittab =
    "# Written by Termant at every start. The shells are started by the app, not from here.\n"
    "::sysinit:/bin/true\n";

int tsh_boot(const char *root, const char *motd, char *why, size_t why_size) {
    pthread_mutex_lock(&api_lock);
    int result = -1;
    if (state == 1) {
        result = 0;
        goto out;
    }
    if (state == -1) {
        say(why, why_size, "Linux did not start; close the app and open it again");
        goto out;
    }
    state = -1;

    char data[PATH_MAX];
    snprintf(data, sizeof(data), "%s/data", root);
    int err = mount_root(&fakefs, data);
    if (err < 0) {
        say(why, why_size, "cannot open the file system in %s (error %d)", root, err);
        goto out;
    }
    err = become_first_process();
    if (err < 0) {
        say(why, why_size, "cannot make the first process (error %d)", err);
        goto out;
    }
    create_some_device_nodes();
    generic_setattrat(AT_PWD, "/", (struct attr) {.type = attr_mode, .mode = 0755}, false);
    generic_mkdirat(AT_PWD, "/proc", 0555);
    do_mount(&procfs, "proc", "/proc", "", 0);
    do_mount(&devptsfs, "devpts", "/dev/pts", "", 0);

    char servers[1024];
    name_servers(servers, sizeof(servers));
    put_file("/etc/resolv.conf", servers, 0644);
    put_file("/etc/inittab", inittab, 0644);
    if (motd != NULL)
        put_file("/etc/motd", motd, 0644);

    exit_hook = process_ended;
    uname_version = "Termant";
    uname_hostname_override = "termant";
#if defined(__APPLE__)
    // sockets with a name need a real file, and only the app's own temporary folder may hold one
    const char *tmp = getenv("TMPDIR");
    if (tmp != NULL && tmp[0] != '\0') {
        size_t size = strlen(tmp) + 16;
        char *prefix = malloc(size);
        if (prefix != NULL) {
            snprintf(prefix, size, "%s%sishsock", tmp, tmp[strlen(tmp) - 1] == '/' ? "" : "/");
            sock_tmp_prefix = prefix;
        }
    }
#endif

    tty_drivers[TTY_CONSOLE_MAJOR] = &console_driver;
    set_console_device(TTY_CONSOLE_MAJOR, 1);
    err = create_stdio("/dev/console", TTY_CONSOLE_MAJOR, 1);
    if (err < 0) {
        say(why, why_size, "cannot open the console (error %d)", err);
        goto out;
    }
    err = do_execve("/sbin/init", 1, "/sbin/init\0", "TERM=xterm-256color\0");
    if (err < 0) {
        say(why, why_size, "cannot start /sbin/init (error %d)", err);
        goto out;
    }
    start_task(current);
    current = NULL;
    state = 1;
    result = 0;
out:
    pthread_mutex_unlock(&api_lock);
    return result;
}

int tsh_running(void) {
    pthread_mutex_lock(&api_lock);
    int running = state == 1;
    pthread_mutex_unlock(&api_lock);
    return running;
}

// ------------------------------------------------------------ terminals

static struct terminal *terminal_numbered(int number) {
    if (number < 0 || number >= MAX_TERMINALS)
        return NULL;
    pthread_mutex_lock(&table_lock);
    struct terminal *t = terminals[number];
    pthread_mutex_unlock(&table_lock);
    return t;
}

static void set_size(struct tty *tty, int columns, int rows) {
    if (columns < 1 || rows < 1)
        return;
    lock(&tty->lock);
    tty_set_winsize(tty, (struct winsize_) {.col = (word_t) columns, .row = (word_t) rows});
    unlock(&tty->lock);
}

int tsh_open(int columns, int rows, tsh_output_fn output, tsh_ended_fn ended, void *context) {
    pthread_mutex_lock(&api_lock);
    int result = -1;
    if (state != 1)
        goto out;

    int number = -1;
    pthread_mutex_lock(&table_lock);
    for (int i = 0; i < MAX_TERMINALS; i++) {
        if (terminals[i] == NULL) {
            number = i;
            break;
        }
    }
    pthread_mutex_unlock(&table_lock);
    if (number < 0) {
        result = _EMFILE;
        goto out;
    }
    struct terminal *t = calloc(1, sizeof(*t));
    if (t == NULL) {
        result = _ENOMEM;
        goto out;
    }
    t->number = number;
    t->output = output;
    t->ended = ended;
    t->context = context;

    int err = become_new_init_child();
    if (err < 0) {
        free(t);
        result = err;
        goto out;
    }
    opening = t;
    struct tty *tty = pty_open_fake(&term_driver);
    opening = NULL;
    if (IS_ERR(tty)) {
        free(t);
        current = NULL;
        result = (int) PTR_ERR(tty);
        goto out;
    }
    // the reference pty_open_fake gave is kept until tsh_close, so that t->tty stays good
    t->tty = tty;
    set_size(tty, columns, rows);

    pthread_mutex_lock(&table_lock);
    terminals[number] = t;
    pthread_mutex_unlock(&table_lock);

    char file[32];
    snprintf(file, sizeof(file), "/dev/pts/%d", tty->num);
    err = create_stdio(file, TTY_PSEUDO_SLAVE_MAJOR, tty->num);
    if (err >= 0)
        err = do_execve("/bin/login", 3, "/bin/login\0-f\0root\0", "TERM=xterm-256color\0");
    if (err < 0) {
        pthread_mutex_lock(&table_lock);
        terminals[number] = NULL;
        t->closed = 1;
        pthread_mutex_unlock(&table_lock);
        lock(&ttys_lock);
        tty_release(tty);
        unlock(&ttys_lock);
        current = NULL;
        result = err;
        goto out;
    }
    t->pid = current->pid;
    start_task(current);
    current = NULL;
    result = number;
out:
    pthread_mutex_unlock(&api_lock);
    return result;
}

void tsh_type(int terminal, const char *bytes, size_t length) {
    pthread_mutex_lock(&api_lock);
    struct terminal *t = terminal_numbered(terminal);
    if (t != NULL) {
        for (size_t i = 0; i < length; i++) {
            // one at a time: when the line is full, nothing of a longer piece would be taken
            for (int tries = 0; tries < 500; tries++) {
                if (tty_input(t->tty, bytes + i, 1, false) != _EAGAIN)
                    break;
                usleep(2000);
            }
        }
    }
    pthread_mutex_unlock(&api_lock);
}

void tsh_resize(int terminal, int columns, int rows) {
    pthread_mutex_lock(&api_lock);
    struct terminal *t = terminal_numbered(terminal);
    if (t != NULL)
        set_size(t->tty, columns, rows);
    pthread_mutex_unlock(&api_lock);
}

void tsh_close(int terminal) {
    pthread_mutex_lock(&api_lock);
    if (terminal >= 0 && terminal < MAX_TERMINALS) {
        pthread_mutex_lock(&table_lock);
        struct terminal *t = terminals[terminal];
        terminals[terminal] = NULL;
        if (t != NULL)
            t->closed = 1;
        pthread_mutex_unlock(&table_lock);
        if (t != NULL) {
            struct tty *tty = t->tty;
            lock(&tty->lock);
            tty_hangup(tty);
            unlock(&tty->lock);
            lock(&ttys_lock);
            tty_release(tty); // may free t
            unlock(&ttys_lock);
        }
    }
    pthread_mutex_unlock(&api_lock);
}

// ------------------------------------------------------------ one command

static long long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long) ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static const char run_environment[] =
    "HOME=/root\0USER=root\0LOGNAME=root\0SHELL=/bin/ash\0TERM=dumb\0PAGER=cat\0"
    "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\0";

int tsh_run(const char *command, const char *folder, int seconds,
            tsh_output_fn output, void *context, int *status) {
    if (status != NULL)
        *status = -1;
    int ends[2];
    if (pipe(ends) < 0)
        return -1;
    fcntl(ends[0], F_SETFD, FD_CLOEXEC);
    fcntl(ends[1], F_SETFD, FD_CLOEXEC);

    static const char head[] = "/bin/sh\0-c\0";
    size_t command_size = strlen(command) + 1;
    char *argv = malloc(sizeof(head) + command_size + 1);
    if (argv == NULL) {
        close(ends[0]);
        close(ends[1]);
        return -1;
    }
    memcpy(argv, head, sizeof(head) - 1);
    memcpy(argv + sizeof(head) - 1, command, command_size);
    argv[sizeof(head) - 1 + command_size] = '\0';

    pthread_mutex_lock(&api_lock);
    int result = -1;
    int slot = -1;
    int started = 0;
    if (state != 1)
        goto unlock;
    int err = become_new_init_child();
    if (err < 0) {
        result = err;
        goto unlock;
    }
    // no input; output and errors into our pipe
    struct fd *in = generic_open("/dev/null", O_RDONLY_, 0);
    struct fd *out = adhoc_fd_create(&realfs_fdops);
    if (IS_ERR(in) || out == NULL) {
        current = NULL;
        goto unlock;
    }
    out->real_fd = ends[1];
    out->dir = NULL;
    out->flags = O_WRONLY_;
    ends[1] = -1; // belongs to the process now
    current->files->files[0] = in;
    current->files->files[1] = out;
    current->files->files[2] = fd_retain(out);

    struct fd *pwd = generic_open(folder != NULL && folder[0] != '\0' ? folder : "/root", O_RDONLY_, 0);
    if (!IS_ERR(pwd))
        fs_chdir(current->fs, pwd);

    err = do_execve("/bin/sh", 3, argv, run_environment);
    if (err < 0) {
        // the process was never started; its files stay open with it, which closes our end of the pipe never.
        // /bin/sh is always there, so this is not worth more than giving up.
        current = NULL;
        result = err;
        goto unlock;
    }
    pid_t_ pid = current->pid;
    pthread_mutex_lock(&table_lock);
    for (int i = 0; i < MAX_RUNS; i++) {
        if (runs[i].pid == 0) {
            slot = i;
            runs[i] = (struct run) {.pid = pid};
            break;
        }
    }
    pthread_mutex_unlock(&table_lock);
    start_task(current);
    current = NULL;
    started = 1;
unlock:
    pthread_mutex_unlock(&api_lock);
    free(argv);
    if (!started) {
        close(ends[0]);
        if (ends[1] >= 0)
            close(ends[1]);
        return result;
    }

    long long deadline = now_ms() + (long long) (seconds > 0 ? seconds : 60) * 1000;
    long long give_up = 0; // after the kill, or after the command ended with something still holding the pipe
    int timed_out = 0; // 1: the time ran out, 2: the app asked to stop
    int stops_before = stops;
    int ended = 0, code = -1;
    char buffer[4096];
    for (;;) {
        struct pollfd p = {.fd = ends[0], .events = POLLIN};
        int ready = poll(&p, 1, 50);
        if (ready > 0) {
            ssize_t got = read(ends[0], buffer, sizeof(buffer));
            if (got > 0) {
                if (output != NULL)
                    output(context, buffer, (size_t) got);
                continue;
            }
            if (got == 0)
                break; // everything that could write has ended
            if (errno != EINTR && errno != EAGAIN)
                break;
        }
        long long now = now_ms();
        if (!ended && slot >= 0) {
            pthread_mutex_lock(&table_lock);
            ended = runs[slot].ended;
            code = runs[slot].status;
            pthread_mutex_unlock(&table_lock);
            if (ended && give_up == 0)
                give_up = now + 300; // something it left behind still holds the pipe: do not wait for that
        }
        if (!ended && !timed_out && (now >= deadline || stops != stops_before)) {
            timed_out = now >= deadline ? 1 : 2;
            send_group_signal((dword_t) pid, SIGKILL_, SIGINFO_NIL);
            give_up = now + 3000;
        }
        if (give_up != 0 && now >= give_up)
            break;
    }
    close(ends[0]);

    // the exit code arrives a moment after the pipe closes
    for (int i = 0; slot >= 0 && !ended && i < 100; i++) {
        pthread_mutex_lock(&table_lock);
        ended = runs[slot].ended;
        code = runs[slot].status;
        pthread_mutex_unlock(&table_lock);
        if (!ended)
            usleep(10000);
    }
    if (slot >= 0) {
        pthread_mutex_lock(&table_lock);
        runs[slot].pid = 0;
        pthread_mutex_unlock(&table_lock);
    }
    if (status != NULL)
        *status = ended ? code : -1;
    return timed_out;
}

void tsh_stop(void) {
    __sync_fetch_and_add(&stops, 1);
}
