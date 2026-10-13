/*
 * ppplink-engine - PPP Link for macOS (and Linux).
 * Copyright 2026 zedstarr. Licensed under the Apache License, Version 2.0.
 *
 * Connects a serial line (USB serial adapter) to slirp running on a pty, so a
 * retro computer can dial in over PPP and use the computer's internet
 * connection. Userspace only: no root, no ppp0, no NAT rules.
 *
 * The same job as LinkEngine.kt in the Android app:
 *  - one line reader per session; slirp is restarted when the client hangs up;
 *  - optional Hayes modem emulation (OK / CONNECT) before PPP starts;
 *  - a read-only PPP sniffer for status (handles ACFC/PFC);
 *  - data for a client that holds CTS low is dropped, not queued forever;
 *  - adapter unplug/replug is followed; a client still on the old link is reported;
 *  - automatic DNS goes through a relay that follows network changes.
 *
 * Used by the `ppplink` command and the PPP Link menu-bar app. With
 * --status machine, status goes to stdout one event per line:
 *   STATE <key> <text>   key: waiting ready negotiating connected notready stale error stopped
 *   ADAPTER <path or ->
 *   DNS <text>
 *   BYTES <from client> <to client>
 *   LOG <text>
 */
#if defined(__linux__)
#define _GNU_SOURCE
#endif

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>

#ifdef __APPLE__
#include <util.h>
#include <IOKit/serial/ioss.h>
#else
#include <pty.h>
#endif

#include "dnsrelay.h"

#ifndef PPPLINK_VERSION
#define PPPLINK_VERSION "1.0.0"
#endif

#define RESTART_DELAY 0.5        /* seconds before slirp is restarted */
#define STALL_TIMEOUT 2.0        /* seconds of no progress before data is dropped */
#define TICK_MS 250

/* ---------------------------------------------------------------- options */

static struct {
    const char *line;            /* device path, or "auto" */
    int tcp_port;                /* >0: debug bridge instead of a serial line */
    const char *tcp_host;
    const char *slirp;
    long baud;
    int flow;                    /* RTS/CTS */
    int modem;                   /* Hayes modem emulation */
    int mtu;
    const char *dns;             /* "auto" or IPv4 address */
    int machine;
    int hex;
    int wait;                    /* wait for the adapter instead of failing */
} opt = { "auto", 0, "127.0.0.1", NULL, 115200, 1, 0, 1500, "auto", 0, 0, 0 };

/* ---------------------------------------------------------------- output */

static pthread_mutex_t out_lock = PTHREAD_MUTEX_INITIALIZER;

static void out(const char *kind, const char *text)
{
    pthread_mutex_lock(&out_lock);
    if (opt.machine) {
        printf("%s %s\n", kind, text);
    } else if (strcmp(kind, "BYTES") != 0) {
        char ts[16];
        time_t t = time(NULL);
        strftime(ts, sizeof ts, "%H:%M:%S", localtime(&t));
        if (strcmp(kind, "DNS") == 0) printf("%s  DNS: %s\n", ts, text);
        else if (strcmp(kind, "ADAPTER") == 0) printf("%s  Adapter: %s\n", ts, text);
        else printf("%s  %s\n", ts, text);
    }
    fflush(stdout);
    pthread_mutex_unlock(&out_lock);
}

static void logmsg(const char *fmt, ...)
{
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof buf, fmt, ap);
    va_end(ap);
    out("LOG", buf);
}

static char state_key[16] = "";
static char state_text[256] = "";

static void set_state(const char *key, const char *fmt, ...)
{
    char text[256];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(text, sizeof text, fmt, ap);
    va_end(ap);
    if (strcmp(key, state_key) == 0 && strcmp(text, state_text) == 0) return;
    snprintf(state_key, sizeof state_key, "%s", key);
    snprintf(state_text, sizeof state_text, "%s", text);
    if (opt.machine) {
        char buf[300];
        snprintf(buf, sizeof buf, "%s %s", key, text);
        out("STATE", buf);
    } else {
        out("STATE", text);
    }
}

static double now(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec + ts.tv_nsec / 1e9;
}

/* ---------------------------------------------------------------- signals */

static int sig_pipe[2] = { -1, -1 };

static void on_signal(int sig)
{
    int saved = errno;
    unsigned char b = (unsigned char)sig;
    if (write(sig_pipe[1], &b, 1) < 0) { /* pipe full: a wakeup is already pending */ }
    errno = saved;
}

static void set_cloexec_nonblock(int fd, int nonblock)
{
    fcntl(fd, F_SETFD, FD_CLOEXEC);
    if (nonblock) fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) | O_NONBLOCK);
}

static void setup_signals(void)
{
    if (pipe(sig_pipe) < 0) { perror("pipe"); exit(1); }
    set_cloexec_nonblock(sig_pipe[0], 1);
    set_cloexec_nonblock(sig_pipe[1], 1);
    struct sigaction sa;
    memset(&sa, 0, sizeof sa);
    sa.sa_handler = on_signal;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = SA_RESTART;
    sigaction(SIGINT, &sa, NULL);
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGHUP, &sa, NULL);
    sigaction(SIGCHLD, &sa, NULL);
    sigaction(SIGUSR1, &sa, NULL);
#ifdef SIGINFO
    sigaction(SIGINFO, &sa, NULL);   /* Ctrl-T on macOS prints the byte counts */
#endif
    signal(SIGPIPE, SIG_IGN);
}

/* ---------------------------------------------------------------- PPP sniffer */

enum ppp_event { EV_NONE, EV_NEGOTIATING, EV_IP, EV_TERMINATE };

static struct {
    unsigned char frame[16];     /* only the first few bytes are needed */
    int index, in_frame, escape;
} sn;

static void sniffer_reset(void) { memset(&sn, 0, sizeof sn); }

static enum ppp_event parse_frame(void)
{
    const unsigned char *f = sn.frame;
    int len = sn.index, off = 0, protocol;
    if (len >= 2 && f[0] == 0xFF && f[1] == 0x03) off = 2;      /* ACFC: may be absent */
    if (len - off < 1) return EV_NONE;
    if (f[off] & 1) {                                           /* PFC: one-byte protocol */
        protocol = f[off];
        off += 1;
    } else {
        if (len - off < 2) return EV_NONE;
        protocol = (f[off] << 8) | f[off + 1];
        off += 2;
    }
    switch (protocol) {
    case 0xC021:                                                /* LCP */
        if (len <= off) return EV_NONE;
        if (f[off] >= 1 && f[off] <= 4) return EV_NEGOTIATING;   /* Configure-* */
        if (f[off] == 5) return EV_TERMINATE;                    /* Terminate-Request */
        return EV_NONE;                                          /* Echo, Discard... */
    case 0x8021: return EV_NEGOTIATING;                          /* IPCP */
    case 0x0021: return EV_IP;                                   /* IPv4 */
    }
    return EV_NONE;
}

static void on_ppp_event(enum ppp_event e);

static void sniff(const unsigned char *data, int len)
{
    for (int i = 0; i < len; i++) {
        unsigned char b = data[i];
        if (b == 0x7E) {
            if (sn.in_frame && sn.index > 0) on_ppp_event(parse_frame());
            sn.in_frame = 1;
            sn.index = 0;
            sn.escape = 0;
            continue;
        }
        if (!sn.in_frame) continue;
        if (b == 0x7D) { sn.escape = 1; continue; }
        if (sn.escape) { b ^= 0x20; sn.escape = 0; }
        if (sn.index < (int)sizeof sn.frame) sn.frame[sn.index++] = b;
    }
}

/* ---------------------------------------------------------------- session state */

static int line_fd = -1, listen_fd = -1, line_is_tcp = 0;
static char line_path[PATH_MAX] = "";

static pid_t slirp_pid = 0;
static int master_fd = -1, err_fd = -1;
static double slirp_started, restart_at = 0;
static int quick_failures = 0;
static int slirp_killed = 0;      /* we stopped it: not a failure */
static char slirp_home[PATH_MAX] = "";
static int dns_port = -1;

static int at_phase = 0;
static char at_buf[257];
static int at_len = 0;
static int negotiated = 0, stale_warned = 0;

static int stalled = 0;
static char before_stall_key[16], before_stall_text[256];
static unsigned char pend[16384];
static int pend_len = 0, pend_off = 0;
static double pend_since = 0;

static unsigned long long bytes_in = 0, bytes_out = 0;

static void ready_state(void)
{
    if (at_phase) set_state("ready", "Ready for client (modem emulation: waiting for dial)");
    else set_state("ready", "Ready for client");
}

static void hexlog(const char *dir, const unsigned char *d, int n)
{
    char buf[3 * 48 + 64];
    int show = n < 48 ? n : 48, p = 0;
    p += snprintf(buf + p, sizeof buf - p, "%s (%d):", dir, n);
    for (int i = 0; i < show; i++) p += snprintf(buf + p, sizeof buf - p, " %02x", d[i]);
    if (show < n) snprintf(buf + p, sizeof buf - p, " ...");
    logmsg("%s", buf);
}

/* While stalled, status changes are remembered and shown once the client is back. */
static void session_state(const char *key, const char *text)
{
    if (stalled) {
        snprintf(before_stall_key, sizeof before_stall_key, "%s", key);
        snprintf(before_stall_text, sizeof before_stall_text, "%s", text);
    } else {
        set_state(key, "%s", text);
    }
}

static void on_ppp_event(enum ppp_event e)
{
    switch (e) {
    case EV_NEGOTIATING:
        negotiated = 1;
        if (strcmp(stalled ? before_stall_key : state_key, "negotiating") != 0)
            session_state("negotiating", "Negotiating");
        break;
    case EV_IP:
        if (!negotiated) {
            /* IP from a PPP session this slirp never negotiated: the client still
             * thinks the previous link is up. slirp drops these packets. */
            if (!stale_warned) {
                stale_warned = 1;
                logmsg("Client is still using the previous PPP link - disconnect and reconnect on the client");
                session_state("stale", "Client on old link - reconnect on client");
            }
            break;
        }
        if (strcmp(stalled ? before_stall_key : state_key, "connected") != 0) {
            if (!stalled) logmsg("PPP up: client is sending IP traffic");
            session_state("connected", "Connected");
        }
        break;
    case EV_TERMINATE:
        logmsg("PPP: client sent LCP Terminate-Request");
        break;
    default:
        break;
    }
}

/* ---------------------------------------------------------------- serial line */

static int is_candidate(const char *name)
{
#ifdef __APPLE__
    static const char *prefixes[] = { "cu.usbserial", "cu.usbmodem", "cu.wchusbserial",
                                      "cu.SLAB_USBtoUART", "cu.PL2303", "cu.UC-232", NULL };
#else
    static const char *prefixes[] = { "ttyUSB", "ttyACM", NULL };
#endif
    for (int i = 0; prefixes[i]; i++)
        if (strncmp(name, prefixes[i], strlen(prefixes[i])) == 0) return i + 1;
    return 0;
}

/* Prints the USB serial ports, the one "auto" picks first. */
static void list_ports(void)
{
    DIR *d = opendir("/dev");
    if (!d) return;
    struct dirent *e;
    char names[128][256];
    int ranks[128], n = 0;
    while ((e = readdir(d)) && n < 128) {
        int r = is_candidate(e->d_name);
        if (!r) continue;
        snprintf(names[n], sizeof names[n], "%s", e->d_name);
        ranks[n++] = r;
    }
    closedir(d);
    for (int i = 1; i < n; i++)                  /* insertion sort: rank, then name */
        for (int j = i; j > 0 && (ranks[j] < ranks[j - 1] ||
                 (ranks[j] == ranks[j - 1] && strcmp(names[j], names[j - 1]) < 0)); j--) {
            char t[256];
            int tr = ranks[j];
            memcpy(t, names[j], sizeof t);
            memcpy(names[j], names[j - 1], sizeof t);
            memcpy(names[j - 1], t, sizeof t);
            ranks[j] = ranks[j - 1];
            ranks[j - 1] = tr;
        }
    for (int i = 0; i < n; i++) printf("/dev/%s\n", names[i]);
}

/* Picks the first USB serial port (by preference, then name). */
static int find_auto_line(char *out_path, size_t len)
{
    DIR *d = opendir("/dev");
    if (!d) return -1;
    struct dirent *e;
    char best[256] = "";
    int best_rank = 0;
    while ((e = readdir(d))) {
        int r = is_candidate(e->d_name);
        if (!r) continue;
        if (!best_rank || r < best_rank || (r == best_rank && strcmp(e->d_name, best) < 0)) {
            best_rank = r;
            snprintf(best, sizeof best, "%s", e->d_name);
        }
    }
    closedir(d);
    if (!best_rank) return -1;
    snprintf(out_path, len, "/dev/%s", best);
    return 0;
}

#ifndef __APPLE__
static speed_t baud_const(long baud)
{
    switch (baud) {
    case 1200: return B1200;     case 2400: return B2400;     case 4800: return B4800;
    case 9600: return B9600;     case 19200: return B19200;   case 38400: return B38400;
    case 57600: return B57600;   case 115200: return B115200; case 230400: return B230400;
#ifdef B460800
    case 460800: return B460800;
#endif
#ifdef B921600
    case 921600: return B921600;
#endif
    }
    return 0;
}
#endif

static int open_serial(const char *path)
{
    int fd = open(path, O_RDWR | O_NOCTTY | O_NONBLOCK);
    if (fd < 0) return -1;
    fcntl(fd, F_SETFD, FD_CLOEXEC);
#ifdef TIOCEXCL
    ioctl(fd, TIOCEXCL);         /* keep other programs off the port while we use it */
#endif
    struct termios t;
    if (tcgetattr(fd, &t) < 0) { int e = errno; close(fd); errno = e; return -1; }
    cfmakeraw(&t);
    t.c_cflag &= ~(CSIZE | PARENB | CSTOPB);
    t.c_cflag |= CS8 | CLOCAL | CREAD;
    if (opt.flow) t.c_cflag |= CRTSCTS; else t.c_cflag &= ~CRTSCTS;
    t.c_cc[VMIN] = 1;
    t.c_cc[VTIME] = 0;

    int ok;
#ifdef __APPLE__
    ok = cfsetspeed(&t, (speed_t)opt.baud) == 0 && tcsetattr(fd, TCSANOW, &t) == 0;
    if (!ok) {                   /* non-standard rate: set a standard one, then the real one */
        cfsetspeed(&t, B9600);
        speed_t s = (speed_t)opt.baud;
        ok = tcsetattr(fd, TCSANOW, &t) == 0 && ioctl(fd, IOSSIOSPEED, &s) == 0;
    }
#else
    speed_t s = baud_const(opt.baud);
    ok = s && cfsetispeed(&t, s) == 0 && cfsetospeed(&t, s) == 0 && tcsetattr(fd, TCSANOW, &t) == 0;
    if (!s) errno = EINVAL;
#endif
    if (!ok) { int e = errno; close(fd); errno = e; return -1; }

    int bits = TIOCM_DTR | TIOCM_RTS;
    ioctl(fd, TIOCMBIS, &bits);  /* raise DTR (and RTS) so the client sees us */
    tcflush(fd, TCIOFLUSH);
    return fd;
}

static int open_listener(void)
{
    int s = socket(AF_INET, SOCK_STREAM, 0);
    if (s < 0) return -1;
    int one = 1;
    setsockopt(s, SOL_SOCKET, SO_REUSEADDR, &one, sizeof one);
    struct sockaddr_in a;
    memset(&a, 0, sizeof a);
    a.sin_family = AF_INET;
    a.sin_port = htons(opt.tcp_port);
    if (inet_pton(AF_INET, opt.tcp_host, &a.sin_addr) != 1) { close(s); errno = EINVAL; return -1; }
    if (bind(s, (struct sockaddr *)&a, sizeof a) < 0 || listen(s, 1) < 0) {
        int e = errno; close(s); errno = e; return -1;
    }
    set_cloexec_nonblock(s, 1);
    return s;
}

/* ---------------------------------------------------------------- slirp */

static int write_slirp_config(void)
{
    char path[PATH_MAX + 16];
    snprintf(path, sizeof path, "%s/.slirprc", slirp_home);
    FILE *f = fopen(path, "w");
    if (!f) return -1;
    fprintf(f, "ppp_exit\n");
    /* No "type five zeroes to exit" / "five ones to detach": a dial-up
     * shell-account feature with no place here. */
    fprintf(f, "nozeros\n");
    if (dns_port > 0) {
        fprintf(f, "dns 127.0.0.1\n");
        fprintf(f, "dnsport %d\n", dns_port);     /* patched slirp: DNS goes to the relay */
    } else if (strcmp(opt.dns, "auto") != 0) {
        fprintf(f, "dns %s\n", opt.dns);
    }                                             /* else slirp reads /etc/resolv.conf */
    fprintf(f, "baudrate %ld\n", opt.baud);
    /* Keepalives: if the client stops answering (cable pulled, switched off),
     * slirp drops PPP after ~40-50 s, exits (ppp_exit) and is restarted. */
    fprintf(f, "lcp-echo-interval 10\n");
    fprintf(f, "lcp-echo-failure 4\n");
    if (opt.mtu != 1500) fprintf(f, "mtu %d\nmru %d\n", opt.mtu, opt.mtu);
    return fclose(f);
}

static int start_slirp(void)
{
    if (write_slirp_config() != 0) {
        set_state("error", "Cannot write slirp config: %s", strerror(errno));
        return -1;
    }
    int ep[2];
    if (pipe(ep) < 0) return -1;
    struct termios raw;
    memset(&raw, 0, sizeof raw);
    cfmakeraw(&raw);
    int master;
    pid_t pid = forkpty(&master, NULL, &raw, NULL);
    if (pid < 0) {
        set_state("error", "Cannot start slirp: %s", strerror(errno));
        close(ep[0]);
        close(ep[1]);
        return -1;
    }
    if (pid == 0) {
        sigset_t none;
        sigemptyset(&none);
        sigprocmask(SIG_SETMASK, &none, NULL);
        int sigs[] = { SIGINT, SIGTERM, SIGHUP, SIGCHLD, SIGUSR1, SIGPIPE,
#ifdef SIGINFO
                       SIGINFO,
#endif
                       0 };
        for (int i = 0; sigs[i]; i++) signal(sigs[i], SIG_DFL);
        dup2(ep[1], 2);
        long maxfd = sysconf(_SC_OPEN_MAX);
        if (maxfd < 0 || maxfd > 65536) maxfd = 65536;
        for (int fd = 3; fd < maxfd; fd++) close(fd);
        setenv("HOME", slirp_home, 1);
        execl(opt.slirp, "slirp", "ppp", (char *)NULL);
        dprintf(2, "exec %s: %s\n", opt.slirp, strerror(errno));
        _exit(127);
    }
    close(ep[1]);
    master_fd = master;
    err_fd = ep[0];
    set_cloexec_nonblock(master_fd, 1);
    set_cloexec_nonblock(err_fd, 1);
    slirp_pid = pid;
    slirp_killed = 0;
    slirp_started = now();
    restart_at = 0;
    at_phase = opt.modem;
    at_len = 0;
    negotiated = 0;
    stale_warned = 0;
    stalled = 0;
    pend_len = pend_off = 0;
    sniffer_reset();
    logmsg("slirp started (pid %d)%s", (int)pid,
           at_phase ? ", modem emulation: waiting for AT commands" : ", waiting for client");
    ready_state();
    return 0;
}

static void kill_slirp(void)
{
    if (slirp_pid > 0) { kill(slirp_pid, SIGTERM); slirp_killed = 1; }
}

static void close_slirp_fds(void)
{
    if (master_fd >= 0) { close(master_fd); master_fd = -1; }
    if (err_fd >= 0) { close(err_fd); err_fd = -1; }
}

static void on_slirp_exit(int status)
{
    /* Drain slirp's last words before closing. */
    if (err_fd >= 0) {
        char buf[1024];
        int n = read(err_fd, buf, sizeof buf - 1);
        if (n > 0) {
            buf[n] = 0;
            for (char *s = strtok(buf, "\r\n"); s; s = strtok(NULL, "\r\n"))
                if (*s) logmsg("[slirp] %s", s);
        }
    }
    close_slirp_fds();
    slirp_pid = 0;
    pend_len = pend_off = 0;
    stalled = 0;

    if (WIFSIGNALED(status)) logmsg("slirp ended by signal %d", WTERMSIG(status));
    else logmsg("slirp exited with code %d", WEXITSTATUS(status));

    if (line_fd < 0) return;      /* unplugged or client gone: restart when it's back */

    double ran = now() - slirp_started;
    quick_failures = ran < 3.0 && !slirp_killed ? quick_failures + 1 : 0;
    if (quick_failures >= 5) {
        set_state("error", "slirp keeps exiting - see the log");
        restart_at = now() + 5.0;
    } else {
        logmsg("PPP link ended - restarting slirp");
        restart_at = now() + (quick_failures >= 3 ? 2.0 : RESTART_DELAY);
    }
}

static void reap_children(void)
{
    int status;
    pid_t p;
    while ((p = waitpid(-1, &status, WNOHANG)) > 0)
        if (p == slirp_pid) on_slirp_exit(status);
}

/* ---------------------------------------------------------------- line I/O */

static void line_gone(const char *why)
{
    if (line_fd < 0) return;
    close(line_fd);
    line_fd = -1;
    pend_len = pend_off = 0;
    stalled = 0;
    restart_at = 0;
    kill_slirp();                 /* fresh PPP when the client is back */
    if (line_is_tcp) {
        logmsg("Debug client disconnected%s%s", why ? ": " : "", why ? why : "");
        set_state("waiting", "Waiting for debug client on %s:%d", opt.tcp_host, opt.tcp_port);
        return;
    }
    logmsg("Serial line lost%s%s", why ? ": " : "", why ? why : "");
    out("ADAPTER", "-");
    if (!opt.wait) {
        set_state("error", "Adapter unplugged");
        return;
    }
    set_state("waiting", "Adapter unplugged - waiting for it");
}

static void stall_begin(void)
{
    if (stalled) return;
    stalled = 1;
    snprintf(before_stall_key, sizeof before_stall_key, "%s", state_key);
    snprintf(before_stall_text, sizeof before_stall_text, "%s", state_text);
    set_state("notready", "Client not ready (asleep or busy?)");
    logmsg("Client not accepting data (CTS low) - dropping data until it is");
}

static void stall_end(void)
{
    if (!stalled) return;
    stalled = 0;
    if (strcmp(state_key, "notready") == 0 && before_stall_key[0])
        set_state(before_stall_key, "%s", before_stall_text);
    logmsg("Client accepting data again");
}

/* Writes slirp output to the line; keeps what doesn't fit (unless stalled). */
static void to_line(const unsigned char *d, int n)
{
    if (line_fd < 0 || n <= 0) return;
    int w = write(line_fd, d, n);
    if (w < 0) {
        if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) { line_gone(strerror(errno)); return; }
        w = 0;
    }
    bytes_out += w;
    if (w > 0) stall_end();
    if (w == n || stalled) return;             /* done, or dropping while stalled */
    memcpy(pend, d + w, n - w);
    pend_len = n - w;
    pend_off = 0;
    pend_since = now();
}

static void flush_pending(void)
{
    if (pend_len <= pend_off || line_fd < 0) return;
    int w = write(line_fd, pend + pend_off, pend_len - pend_off);
    if (w < 0) {
        if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) line_gone(strerror(errno));
        return;
    }
    if (w > 0) {
        bytes_out += w;
        pend_off += w;
        pend_since = now();
        stall_end();
    }
    if (pend_off >= pend_len) pend_len = pend_off = 0;
}

static void write_line_str(const char *s)
{
    to_line((const unsigned char *)s, (int)strlen(s));
}

/* Modem emulation. Returns where PPP data starts in buf, or n if all AT traffic. */
static int handle_at(const unsigned char *buf, int n)
{
    for (int i = 0; i < n && at_phase; i++) {
        unsigned char b = buf[i];
        if (b == 0x7E) {
            logmsg("Modem emulation: PPP frame seen, switching to PPP");
            at_phase = 0;
            ready_state();
            return i;                              /* include the 0x7E */
        }
        if (b == '\r') {
            char cmd[257];
            int k = 0;
            for (int j = 0; j < at_len; j++)
                if (at_buf[j] != ' ') cmd[k++] = (char)((at_buf[j] >= 'a' && at_buf[j] <= 'z') ? at_buf[j] - 32 : at_buf[j]);
            cmd[k] = 0;
            at_len = 0;
            if (strncmp(cmd, "ATD", 3) == 0) {
                char reply[48];
                snprintf(reply, sizeof reply, "\r\nCONNECT %ld\r\n", opt.baud);
                write_line_str(reply);
                logmsg("Modem emulation: %s -> CONNECT", cmd);
                at_phase = 0;
                ready_state();
                return i + 1;                      /* PPP starts after the CR */
            } else if (strncmp(cmd, "AT", 2) == 0) {
                write_line_str("\r\nOK\r\n");
                logmsg("Modem emulation: %s -> OK", cmd);
            }
            continue;
        }
        if (b == '\n') continue;
        if (at_len < 256) at_buf[at_len++] = (char)b;
    }
    return n;
}

static void read_line(void)
{
    unsigned char buf[4096];
    int n = read(line_fd, buf, sizeof buf);
    if (n < 0) {
        if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) return;
        line_gone(strerror(errno));
        return;
    }
    if (n == 0) { line_gone(line_is_tcp ? NULL : "end of file"); return; }
    bytes_in += n;
    if (slirp_pid <= 0 || master_fd < 0) return;   /* between slirp sessions */
    if (opt.hex) hexlog("client->slirp", buf, n);

    int start = 0;
    if (at_phase) {
        start = handle_at(buf, n);
        if (at_phase) return;
    }
    if (n - start <= 0) return;
    sniff(buf + start, n - start);
    /* slirp reads its pty quickly; if it ever doesn't, PPP resends. */
    if (write(master_fd, buf + start, n - start) < 0 && errno != EAGAIN && errno != EINTR) {
        /* slirp is exiting; SIGCHLD restarts it */
    }
}

static void read_slirp(void)
{
    unsigned char buf[4096];
    int n = read(master_fd, buf, sizeof buf);
    if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR)) return;
    if (n <= 0) {                                  /* EIO once slirp has exited */
        close(master_fd);
        master_fd = -1;
        return;
    }
    if (at_phase) return;                          /* hold slirp's LCP until the client dials */
    if (opt.hex) hexlog("slirp->client", buf, n);
    to_line(buf, n);
}

static void read_slirp_stderr(void)
{
    static char acc[2048];
    static int acc_len = 0;
    char buf[1024];
    int n = read(err_fd, buf, sizeof buf);
    if (n < 0 && (errno == EAGAIN || errno == EINTR)) return;
    if (n <= 0) { close(err_fd); err_fd = -1; acc_len = 0; return; }
    for (int i = 0; i < n; i++) {
        char c = buf[i];
        if (c == '\n' || c == '\r' || acc_len == (int)sizeof acc - 1) {
            acc[acc_len] = 0;
            if (acc_len > 0) logmsg("[slirp] %s", acc);
            acc_len = 0;
            if (c == '\n' || c == '\r') continue;
        }
        acc[acc_len++] = c;
    }
}

static void accept_client(void)
{
    struct sockaddr_in a;
    socklen_t alen = sizeof a;
    int c = accept(listen_fd, (struct sockaddr *)&a, &alen);
    if (c < 0) return;
    set_cloexec_nonblock(c, 1);
#ifdef SO_NOSIGPIPE
    int one = 1;
    setsockopt(c, SOL_SOCKET, SO_NOSIGPIPE, &one, sizeof one);
#endif
    char ip[INET_ADDRSTRLEN];
    inet_ntop(AF_INET, &a.sin_addr, ip, sizeof ip);
    logmsg("Debug client connected from %s", ip);
    line_fd = c;
    if (slirp_pid <= 0) start_slirp();
}

/* Opens the serial line if it isn't open. Returns 0 when open. */
static int try_open_line(int first)
{
    static char last_error[PATH_MAX + 64] = "";
    char path[PATH_MAX];
    if (strcmp(opt.line, "auto") == 0) {
        if (find_auto_line(path, sizeof path) != 0) {
            if (!opt.wait) { set_state("error", "No USB serial adapter found"); return -1; }
            (void)first;
            if (strcmp(state_key, "waiting") != 0) set_state("waiting", "Waiting for adapter");
            return -1;
        }
    } else {
        snprintf(path, sizeof path, "%s", opt.line);
        if (access(path, F_OK) != 0) {
            if (!opt.wait) { set_state("error", "%s not found", path); return -1; }
            if (strcmp(state_key, "waiting") != 0) set_state("waiting", "Waiting for adapter (%s)", path);
            return -1;
        }
    }
    int fd = open_serial(path);
    if (fd < 0) {
        char msg[PATH_MAX + 64];
        snprintf(msg, sizeof msg, "Cannot open %s: %s", path, strerror(errno));
        if (strcmp(msg, last_error) != 0) logmsg("%s", msg);
        snprintf(last_error, sizeof last_error, "%s", msg);
        set_state("error", "%s", msg);
        return -1;
    }
    last_error[0] = 0;
    line_fd = fd;
    line_is_tcp = 0;
    snprintf(line_path, sizeof line_path, "%s", path);
    out("ADAPTER", path);
    logmsg("Opened %s at %ld baud, flow control %s", path, opt.baud, opt.flow ? "RTS/CTS" : "off");
    if (slirp_pid <= 0) start_slirp();
    else restart_at = 0;          /* old slirp still exiting: SIGCHLD restarts it */
    return 0;
}

/* ---------------------------------------------------------------- main */

static void usage(FILE *f)
{
    fprintf(f,
        "ppplink-engine %s - PPP server for retro computers over a serial line\n"
        "usage: ppplink-engine --slirp PATH [options]\n"
        "  --line PATH|auto     serial device (default auto: first USB serial adapter)\n"
        "  --tcp PORT           debug bridge: accept one TCP client instead of a serial line\n"
        "  --tcp-host ADDR      address for --tcp (default 127.0.0.1)\n"
        "  --baud N             default 115200\n"
        "  --flow rtscts|none   hardware flow control (default rtscts)\n"
        "  --mode direct|modem  modem answers AT commands and CONNECT on ATD (default direct)\n"
        "  --mtu N              MTU/MRU, 296-1500 (default 1500)\n"
        "  --dns auto|A.B.C.D   auto relays to the system's DNS and follows network changes\n"
        "  --wait               wait for the adapter (and for it to come back if unplugged)\n"
        "  --status human|machine\n"
        "  --hex                log the bytes in both directions\n"
        "  --list-ports         list USB serial ports (the first is what auto picks)\n"
        "  --version\n", PPPLINK_VERSION);
}

static int valid_ipv4(const char *s)
{
    struct in_addr a;
    return inet_pton(AF_INET, s, &a) == 1;
}

static void parse_args(int argc, char **argv)
{
    for (int i = 1; i < argc; i++) {
        const char *a = argv[i];
        const char *v = i + 1 < argc ? argv[i + 1] : NULL;
#define NEED_VALUE do { if (!v) { fprintf(stderr, "%s needs a value\n", a); exit(2); } i++; } while (0)
        if (!strcmp(a, "--line")) { NEED_VALUE; opt.line = v; }
        else if (!strcmp(a, "--tcp")) { NEED_VALUE; opt.tcp_port = atoi(v); }
        else if (!strcmp(a, "--tcp-host")) { NEED_VALUE; opt.tcp_host = v; }
        else if (!strcmp(a, "--slirp")) { NEED_VALUE; opt.slirp = v; }
        else if (!strcmp(a, "--baud")) { NEED_VALUE; opt.baud = atol(v); }
        else if (!strcmp(a, "--flow")) { NEED_VALUE; opt.flow = !strcmp(v, "rtscts") || !strcmp(v, "on"); }
        else if (!strcmp(a, "--mode")) { NEED_VALUE; opt.modem = !strcmp(v, "modem"); }
        else if (!strcmp(a, "--mtu")) { NEED_VALUE; opt.mtu = atoi(v); }
        else if (!strcmp(a, "--dns")) { NEED_VALUE; opt.dns = v; }
        else if (!strcmp(a, "--status")) { NEED_VALUE; opt.machine = !strcmp(v, "machine"); }
        else if (!strcmp(a, "--wait")) opt.wait = 1;
        else if (!strcmp(a, "--hex")) opt.hex = 1;
        else if (!strcmp(a, "--version")) { printf("%s\n", PPPLINK_VERSION); exit(0); }
        else if (!strcmp(a, "--list-ports")) { list_ports(); exit(0); }
        else if (!strcmp(a, "--help") || !strcmp(a, "-h")) { usage(stdout); exit(0); }
        else { fprintf(stderr, "unknown option: %s\n", a); usage(stderr); exit(2); }
    }
    if (!opt.slirp) { fprintf(stderr, "--slirp PATH is required\n"); exit(2); }
    if (access(opt.slirp, X_OK) != 0) { fprintf(stderr, "slirp not executable: %s\n", opt.slirp); exit(2); }
    if (opt.baud < 300 || opt.baud > 4000000) { fprintf(stderr, "bad --baud\n"); exit(2); }
    if (opt.mtu < 296 || opt.mtu > 1500) { fprintf(stderr, "--mtu must be 296-1500\n"); exit(2); }
    if (opt.tcp_port < 0 || opt.tcp_port > 65535) { fprintf(stderr, "bad --tcp port\n"); exit(2); }
    if (strcmp(opt.dns, "auto") != 0 && !valid_ipv4(opt.dns)) {
        fprintf(stderr, "--dns must be auto or an IPv4 address\n");
        exit(2);
    }
}

static void print_bytes(void)
{
    char buf[64];
    snprintf(buf, sizeof buf, "%llu %llu", bytes_in, bytes_out);
    if (opt.machine) out("BYTES", buf);
    else {
        unsigned long q = 0, f = 0;
        if (dns_port > 0) dns_relay_stats(&q, &f);
        logmsg("From client %llu bytes, to client %llu bytes; DNS queries %lu (%lu failed)",
               bytes_in, bytes_out, q, f);
    }
}

static void cleanup(void)
{
    if (slirp_pid > 0) {
        kill(slirp_pid, SIGTERM);
        for (int i = 0; i < 20; i++) {
            if (waitpid(slirp_pid, NULL, WNOHANG) == slirp_pid) { slirp_pid = 0; break; }
            usleep(50000);
        }
        if (slirp_pid > 0) { kill(slirp_pid, SIGKILL); waitpid(slirp_pid, NULL, 0); }
    }
    if (slirp_home[0]) {
        char path[PATH_MAX + 16];
        snprintf(path, sizeof path, "%s/.slirprc", slirp_home);
        unlink(path);
        rmdir(slirp_home);
    }
}

int main(int argc, char **argv)
{
    parse_args(argc, argv);
    setup_signals();

    const char *tmp = getenv("TMPDIR");
    snprintf(slirp_home, sizeof slirp_home, "%s/ppplink.XXXXXX", tmp && *tmp ? tmp : "/tmp");
    for (char *p = slirp_home; *p; p++)            /* tidy a trailing slash in TMPDIR */
        if (p[0] == '/' && p[1] == '/') memmove(p, p + 1, strlen(p));
    if (!mkdtemp(slirp_home)) { perror("mkdtemp"); return 1; }

    logmsg("PPP Link engine %s", PPPLINK_VERSION);
    if (strcmp(opt.dns, "auto") == 0) {
        dns_port = dns_relay_start(logmsg);
        if (dns_port > 0) {
            char desc[160], text[220];
            logmsg("DNS relay on 127.0.0.1:%d", dns_port);
            dns_relay_describe(desc, sizeof desc);
            snprintf(text, sizeof text, "System DNS, follows network (now %s)", desc);
            out("DNS", text);
        } else {
            logmsg("DNS relay unavailable (%s) - slirp uses /etc/resolv.conf", strerror(errno));
            out("DNS", "System DNS (read when slirp starts)");
        }
    } else {
        out("DNS", opt.dns);
    }

    int rc = 0;
    if (opt.tcp_port > 0) {
        listen_fd = open_listener();
        if (listen_fd < 0) {
            set_state("error", "Cannot listen on %s:%d: %s", opt.tcp_host, opt.tcp_port, strerror(errno));
            cleanup();
            return 2;
        }
        line_is_tcp = 1;
        out("ADAPTER", "debug bridge");
        set_state("waiting", "Waiting for debug client on %s:%d", opt.tcp_host, opt.tcp_port);
    } else if (try_open_line(1) != 0 && !opt.wait) {
        cleanup();
        return 2;
    }

    /* With --status machine we're run by an app: stop if it goes away. */
    pid_t parent = getppid();
    double next_tick = now() + 1.0;
    unsigned long long last_in = ~0ULL, last_out = ~0ULL;
    int running = 1;

    while (running) {
        struct pollfd p[5];
        int np = 0, i_sig, i_line = -1, i_master = -1, i_err = -1, i_listen = -1;
        p[np].fd = sig_pipe[0]; p[np].events = POLLIN; i_sig = np++;
        if (line_fd >= 0) {
            p[np].fd = line_fd;
            p[np].events = POLLIN | (pend_len > pend_off ? POLLOUT : 0);
            i_line = np++;
        }
        if (master_fd >= 0 && (pend_len <= pend_off || stalled)) {
            p[np].fd = master_fd; p[np].events = POLLIN; i_master = np++;
        }
        if (err_fd >= 0) { p[np].fd = err_fd; p[np].events = POLLIN; i_err = np++; }
        if (listen_fd >= 0 && line_fd < 0) { p[np].fd = listen_fd; p[np].events = POLLIN; i_listen = np++; }

        int r = poll(p, np, TICK_MS);
        if (r < 0 && errno != EINTR) { perror("poll"); rc = 1; break; }

        if (r > 0 && (p[i_sig].revents & POLLIN)) {
            unsigned char sigs[32];
            int n = read(sig_pipe[0], sigs, sizeof sigs);
            for (int k = 0; k < n; k++) {
                if (sigs[k] == SIGINT || sigs[k] == SIGTERM || sigs[k] == SIGHUP) running = 0;
                else if (sigs[k] == SIGCHLD) reap_children();
                else print_bytes();
            }
        }
        if (!running) break;

        if (r > 0 && i_line >= 0 && line_fd >= 0) {
            short re = p[i_line].revents;
            if (re & POLLOUT) flush_pending();
            if (line_fd >= 0 && (re & (POLLIN | POLLHUP | POLLERR))) read_line();
            if (line_fd >= 0 && (re & POLLNVAL)) line_gone("invalid descriptor");
        }
        if (r > 0 && i_master >= 0 && master_fd >= 0 && (p[i_master].revents & (POLLIN | POLLHUP | POLLERR)))
            read_slirp();
        if (r > 0 && i_err >= 0 && err_fd >= 0 && (p[i_err].revents & (POLLIN | POLLHUP | POLLERR)))
            read_slirp_stderr();
        if (r > 0 && i_listen >= 0 && (p[i_listen].revents & POLLIN))
            accept_client();

        double t = now();

        /* Client holding CTS low for too long: drop what's queued, show "not ready". */
        if (pend_len > pend_off && t - pend_since > STALL_TIMEOUT) {
            pend_len = pend_off = 0;
            stall_begin();
        }
        if (restart_at > 0 && t >= restart_at && line_fd >= 0 && slirp_pid <= 0)
            start_slirp();

        if (t >= next_tick) {
            next_tick = t + 1.0;
            if (opt.machine && getppid() != parent) {
                logmsg("Parent process gone - stopping");
                break;
            }
            if (!line_is_tcp) {
                if (line_fd >= 0 && access(line_path, F_OK) != 0) line_gone("device removed");
                if (line_fd < 0 && opt.wait) try_open_line(0);
                if (line_fd < 0 && !opt.wait && slirp_pid <= 0) { rc = 3; break; }
            }
            if (opt.machine && (bytes_in != last_in || bytes_out != last_out)) {
                last_in = bytes_in;
                last_out = bytes_out;
                print_bytes();
            }
        }
    }

    if (line_fd >= 0) close(line_fd);
    cleanup();
    if (!opt.machine) print_bytes();
    set_state("stopped", "Stopped");
    return rc;
}
