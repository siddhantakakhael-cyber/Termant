// Unpacks a root file system archive into the form iSH's "fakefs" keeps it in:
// the files themselves under <root>/data, and their Linux owner, mode and device number
// in the SQLite database <root>/meta.db (the iPhone's own file system cannot hold those).
// Symbolic links and device files are ordinary files there; a link's content is its target.
//
// It does what iSH's tools/fakefs.c does, with zlib and a small tar reader in place of libarchive.
// Licensed under the GNU GPL version 3; see ios/LICENSE.md.

#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <unistd.h>
#include <sqlite3.h>
#include <zlib.h>
#include "termant_shell.h"

// the row format of the "stats" table; must stay the same as struct ish_stat in iSH's fs/fake-db.h
struct stat_row {
    uint32_t mode;
    uint32_t uid;
    uint32_t gid;
    uint32_t rdev;
};

// Linux file types, as iSH expects them in `mode`
enum {
    T_FIFO = 0010000, T_CHR = 0020000, T_DIR = 0040000, T_BLK = 0060000,
    T_REG = 0100000, T_LNK = 0120000,
};

// the same tables iSH's importer makes (schema version 3)
static const char *schema =
    "create table meta (id integer unique default 0, db_inode integer);"
    "insert into meta (db_inode) values (0);"
    "create table stats (inode integer primary key, stat blob);"
    "create table paths (path blob primary key, inode integer references stats(inode));"
    "create index inode_to_path on paths (inode, path);"
    "pragma user_version=3;";

#define NAME_MAX_ 4096

struct import {
    gzFile in;
    sqlite3 *db;
    sqlite3_stmt *add_stat, *add_path, *add_link;
    int root; // <root>/data
    char data_dir[PATH_MAX];
    char *why;
    size_t why_size;
};

static int fail(struct import *im, const char *format, ...) {
    if (im->why != NULL && im->why_size > 0) {
        va_list args;
        va_start(args, format);
        vsnprintf(im->why, im->why_size, format, args);
        va_end(args);
    }
    return -1;
}

// "./etc//passwd" as "/etc/passwd", the root itself as "". 0 if the path tries to leave the root.
static int normalize(const char *path, char *out, size_t out_size) {
    size_t n = 0;
    while (*path != '\0') {
        while (*path == '/')
            path++;
        if (*path == '\0')
            break;
        size_t len = strcspn(path, "/");
        if (len == 2 && path[0] == '.' && path[1] == '.')
            return 0;
        if (!(len == 1 && path[0] == '.')) {
            if (n + len + 2 > out_size)
                return 0;
            out[n++] = '/';
            memcpy(out + n, path, len);
            n += len;
        }
        path += len;
    }
    out[n] = '\0';
    return 1;
}

// the path as the *at() calls want it, relative to <root>/data
static const char *rel(const char *path) {
    if (path[0] == '\0')
        return ".";
    return path + 1;
}

// a number field of a tar header: octal text, or binary when the first bit is set
static uint64_t number(const unsigned char *field, size_t size) {
    uint64_t value = 0;
    if (field[0] & 0x80) {
        value = field[0] & 0x7f;
        for (size_t i = 1; i < size; i++)
            value = (value << 8) | field[i];
        return value;
    }
    size_t i = 0;
    while (i < size && field[i] == ' ')
        i++;
    for (; i < size && field[i] >= '0' && field[i] <= '7'; i++)
        value = (value << 3) | (uint64_t) (field[i] - '0');
    return value;
}

static int read_block(struct import *im, unsigned char *block) {
    int got = gzread(im->in, block, 512);
    if (got == 512)
        return 1;
    if (got == 0)
        return 0;
    return fail(im, "the archive ends in the middle");
}

// the content of an entry that is kept in memory (a long name), at most `limit` bytes
static char *read_text(struct import *im, uint64_t size, uint64_t limit) {
    if (size > limit) {
        fail(im, "a name in the archive is too long");
        return NULL;
    }
    uint64_t padded = (size + 511) / 512 * 512;
    char *text = malloc(padded + 1);
    if (text == NULL) {
        fail(im, "out of memory");
        return NULL;
    }
    if (padded > 0 && gzread(im->in, text, (unsigned) padded) != (int) padded) {
        free(text);
        fail(im, "the archive ends in the middle");
        return NULL;
    }
    text[size] = '\0';
    return text;
}

static int copy_data(struct import *im, int fd, uint64_t size, const char *path) {
    unsigned char buffer[64 * 512];
    uint64_t left = (size + 511) / 512 * 512;
    uint64_t wanted = size;
    while (left > 0) {
        unsigned chunk = left > sizeof(buffer) ? (unsigned) sizeof(buffer) : (unsigned) left;
        if (gzread(im->in, buffer, chunk) != (int) chunk)
            return fail(im, "the archive ends in the middle of %s", path);
        left -= chunk;
        size_t keep = wanted < chunk ? (size_t) wanted : chunk;
        wanted -= keep;
        size_t done = 0;
        while (fd >= 0 && done < keep) {
            ssize_t wrote = write(fd, buffer + done, keep - done);
            if (wrote < 0) {
                if (errno == EINTR)
                    continue;
                return fail(im, "cannot write %s: %s", path, strerror(errno));
            }
            done += (size_t) wrote;
        }
    }
    return 0;
}

static int sql(struct import *im, const char *statement) {
    if (sqlite3_exec(im->db, statement, NULL, NULL, NULL) != SQLITE_OK)
        return fail(im, "database: %s", sqlite3_errmsg(im->db));
    return 0;
}

static int step(struct import *im, sqlite3_stmt *statement) {
    int err = sqlite3_step(statement);
    if (err != SQLITE_DONE && err != SQLITE_ROW)
        return fail(im, "database: %s", sqlite3_errmsg(im->db));
    sqlite3_reset(statement);
    return 0;
}

static int record(struct import *im, const char *path, struct stat_row row) {
    sqlite3_bind_blob(im->add_stat, 1, &row, sizeof(row), SQLITE_TRANSIENT);
    if (step(im, im->add_stat) < 0)
        return -1;
    sqlite3_bind_blob(im->add_path, 1, path, (int) strlen(path), SQLITE_TRANSIENT);
    sqlite3_bind_int64(im->add_path, 2, sqlite3_last_insert_rowid(im->db));
    return step(im, im->add_path);
}

// mkdir -p for the folders above `path`
static int make_parents(struct import *im, const char *path) {
    char copy[NAME_MAX_];
    snprintf(copy, sizeof(copy), "%s", path);
    for (char *slash = strchr(copy + 1, '/'); slash != NULL; slash = strchr(slash + 1, '/')) {
        *slash = '\0';
        if (mkdirat(im->root, rel(copy), 0777) < 0 && errno != EEXIST)
            return fail(im, "cannot make the folder %s: %s", copy, strerror(errno));
        *slash = '/';
    }
    return 0;
}

// The value of one key in the records of a pax header ("27 path=some/long/name\n"), or NULL.
static char *pax_value(const char *records, const char *key) {
    size_t key_len = strlen(key);
    const char *p = records;
    while (*p != '\0') {
        char *end;
        unsigned long len = strtoul(p, &end, 10);
        if (len == 0 || *end != ' ' || strlen(p) < len)
            break;
        const char *field = end + 1;
        const char *stop = p + len - 1; // the newline
        if ((size_t) (stop - field) > key_len && strncmp(field, key, key_len) == 0 && field[key_len] == '=') {
            size_t n = (size_t) (stop - field) - key_len - 1;
            char *value = malloc(n + 1);
            if (value != NULL) {
                memcpy(value, field + key_len + 1, n);
                value[n] = '\0';
            }
            return value;
        }
        p += len;
    }
    return NULL;
}

static int unpack(struct import *im) {
    unsigned char h[512];
    char *long_name = NULL, *long_link = NULL;
    int has_root = 0;
    int result = -1;
    int more;

    while ((more = read_block(im, h)) == 1) {
        int empty = 1;
        for (int i = 0; i < 512; i++) {
            if (h[i] != 0) {
                empty = 0;
                break;
            }
        }
        if (empty) {
            more = 0; // the end of the archive
            break;
        }

        unsigned sum = 0;
        for (int i = 0; i < 512; i++)
            sum += (i >= 148 && i < 156) ? ' ' : h[i];
        if (sum != (unsigned) number(h + 148, 8)) {
            fail(im, "the archive is damaged (a header does not add up)");
            goto out;
        }

        uint64_t size = number(h + 124, 12);
        char type = (char) h[156];

        // entries that only carry a name for the entry after them
        if (type == 'L' || type == 'K' || type == 'x') {
            char *text = read_text(im, size, 1 << 20);
            if (text == NULL)
                goto out;
            if (type == 'L') {
                free(long_name);
                long_name = text;
            } else if (type == 'K') {
                free(long_link);
                long_link = text;
            } else {
                char *value;
                if ((value = pax_value(text, "path")) != NULL) {
                    free(long_name);
                    long_name = value;
                }
                if ((value = pax_value(text, "linkpath")) != NULL) {
                    free(long_link);
                    long_link = value;
                }
                free(text);
            }
            continue;
        }
        if (type == 'g') { // settings for the whole archive: nothing we use
            if (copy_data(im, -1, size, "") < 0)
                goto out;
            continue;
        }

        char name[NAME_MAX_], link[NAME_MAX_], path[NAME_MAX_];
        if (long_name != NULL) {
            snprintf(name, sizeof(name), "%s", long_name);
        } else if (h[345] != '\0' && memcmp(h + 257, "ustar\0", 6) == 0) {
            snprintf(name, sizeof(name), "%.155s/%.100s", (const char *) h + 345, (const char *) h);
        } else {
            snprintf(name, sizeof(name), "%.100s", (const char *) h);
        }
        if (long_link != NULL)
            snprintf(link, sizeof(link), "%s", long_link);
        else
            snprintf(link, sizeof(link), "%.100s", (const char *) h + 157);
        free(long_name);
        free(long_link);
        long_name = long_link = NULL;

        uint32_t kind;
        switch (type) {
            case '0': case '\0': case '7': kind = T_REG; break;
            case '1': kind = 0; break; // a second name for a file that came before
            case '2': kind = T_LNK; break;
            case '3': kind = T_CHR; break;
            case '4': kind = T_BLK; break;
            case '5': kind = T_DIR; break;
            case '6': kind = T_FIFO; break;
            default: // something this reader does not know: leave it out
                if (copy_data(im, -1, size, name) < 0)
                    goto out;
                continue;
        }
        if (kind != T_REG)
            size = 0; // only files have content in the archive

        if (!normalize(name, path, sizeof(path))) {
            // would land outside the root
            if (copy_data(im, -1, size, name) < 0)
                goto out;
            continue;
        }
        if (path[0] == '\0')
            has_root = 1;
        if (make_parents(im, path) < 0)
            goto out;

        if (type == '1') {
            char target[NAME_MAX_];
            if (!normalize(link, target, sizeof(target)) || target[0] == '\0' || path[0] == '\0')
                continue;
            if (linkat(im->root, rel(target), im->root, rel(path), 0) < 0 && errno != EEXIST) {
                fail(im, "cannot link %s: %s", path, strerror(errno));
                goto out;
            }
            sqlite3_bind_blob(im->add_link, 1, path, (int) strlen(path), SQLITE_TRANSIENT);
            sqlite3_bind_blob(im->add_link, 2, target, (int) strlen(target), SQLITE_TRANSIENT);
            if (step(im, im->add_link) < 0)
                goto out;
            continue;
        }

        if (kind == T_DIR) {
            if (mkdirat(im->root, rel(path), 0777) < 0 && errno != EEXIST) {
                fail(im, "cannot make the folder %s: %s", path, strerror(errno));
                goto out;
            }
        } else if (kind == T_FIFO) {
            char full[PATH_MAX];
            snprintf(full, sizeof(full), "%s%s", im->data_dir, path);
            if (mkfifo(full, 0666) < 0 && errno != EEXIST) {
                fail(im, "cannot make %s: %s", path, strerror(errno));
                goto out;
            }
        } else {
            int fd = openat(im->root, rel(path), O_WRONLY | O_CREAT | O_TRUNC, 0666);
            if (fd < 0) {
                if (errno == EISDIR) {
                    // a file system that does not tell capital letters apart already has a folder of this name
                    if (copy_data(im, -1, size, path) < 0)
                        goto out;
                    continue;
                }
                fail(im, "cannot make %s: %s", path, strerror(errno));
                goto out;
            }
            int err = 0;
            if (kind == T_REG) {
                err = copy_data(im, fd, size, path);
            } else if (kind == T_LNK) {
                size_t len = strlen(link);
                if (write(fd, link, len) != (ssize_t) len)
                    err = fail(im, "cannot write %s: %s", path, strerror(errno));
            }
            close(fd);
            if (err < 0)
                goto out;
        }

        struct timespec times[2];
        times[0].tv_sec = times[1].tv_sec = (time_t) number(h + 136, 12);
        times[0].tv_nsec = times[1].tv_nsec = 0;
        utimensat(im->root, rel(path), times, 0); // the time is only a nicety

        struct stat_row row = {
            .mode = kind | ((uint32_t) number(h + 100, 8) & 07777),
            .uid = (uint32_t) number(h + 108, 8),
            .gid = (uint32_t) number(h + 116, 8),
            .rdev = 0,
        };
        if (kind == T_CHR || kind == T_BLK) {
            // the way iSH writes a device number (dev_make in fs/dev.h)
            uint32_t major = (uint32_t) number(h + 329, 8), minor = (uint32_t) number(h + 337, 8);
            row.rdev = ((minor & 0xfff00) << 12) | (major << 8) | (minor & 0xff);
        }
        if (record(im, path, row) < 0)
            goto out;
    }
    if (more < 0)
        goto out;

    if (!has_root) {
        struct stat_row row = {.mode = T_DIR | 0755};
        if (record(im, "", row) < 0)
            goto out;
    }
    result = 0;
out:
    free(long_name);
    free(long_link);
    return result;
}

int tsh_import(const char *archive, const char *root, char *why, size_t why_size) {
    struct import im = {.root = -1, .why = why, .why_size = why_size};
    char path[PATH_MAX];
    int result = -1;
    if (why != NULL && why_size > 0)
        why[0] = '\0';

    if (mkdir(root, 0777) < 0)
        return fail(&im, "cannot make %s: %s", root, strerror(errno));
    snprintf(im.data_dir, sizeof(im.data_dir), "%s/data", root);
    if (mkdir(im.data_dir, 0777) < 0)
        return fail(&im, "cannot make %s: %s", im.data_dir, strerror(errno));
    im.root = open(im.data_dir, O_RDONLY);
    if (im.root < 0)
        return fail(&im, "cannot open %s: %s", im.data_dir, strerror(errno));

    snprintf(path, sizeof(path), "%s/meta.db", root);
    if (sqlite3_open_v2(path, &im.db, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE, NULL) != SQLITE_OK) {
        fail(&im, "database: %s", im.db != NULL ? sqlite3_errmsg(im.db) : "cannot open");
        goto out;
    }
    if (sql(&im, "pragma journal_mode=wal") < 0 || sql(&im, "begin") < 0 || sql(&im, schema) < 0)
        goto out;
    if (sqlite3_prepare_v2(im.db, "insert into stats (stat) values (?)", -1, &im.add_stat, NULL) != SQLITE_OK
            || sqlite3_prepare_v2(im.db, "insert or replace into paths values (?, ?)", -1, &im.add_path, NULL) != SQLITE_OK
            || sqlite3_prepare_v2(im.db, "insert or replace into paths values (?, (select inode from paths where path = ? limit 1))",
                                  -1, &im.add_link, NULL) != SQLITE_OK) {
        fail(&im, "database: %s", sqlite3_errmsg(im.db));
        goto out;
    }

    im.in = gzopen(archive, "rb");
    if (im.in == NULL) {
        fail(&im, "cannot open %s: %s", archive, strerror(errno));
        goto out;
    }
    if (unpack(&im) < 0)
        goto out;
    if (sql(&im, "commit") < 0)
        goto out;
    result = 0;

out:
    if (im.in != NULL)
        gzclose(im.in);
    sqlite3_finalize(im.add_stat);
    sqlite3_finalize(im.add_path);
    sqlite3_finalize(im.add_link);
    if (im.db != NULL)
        sqlite3_close(im.db);
    if (im.root >= 0)
        close(im.root);
    return result;
}
