/*
 * PPP Link for macOS - DNS relay. See dnsrelay.h.
 * Copyright 2026 zedstarr. Licensed under the Apache License, Version 2.0.
 */
#if defined(__linux__)
#define _GNU_SOURCE
#endif
#include "dnsrelay.h"

#include <errno.h>
#include <fcntl.h>
#include <netdb.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <poll.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

#define MAX_SERVERS 3
#define MAX_INFLIGHT 64
#define UDP_TIMEOUT_MS 2000
#define TCP_TIMEOUT_S 5
#define MAX_MSG 65535

static dns_log_fn logf_;
static int udp_fd = -1, tcp_fd = -1;

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static struct sockaddr_storage servers[MAX_SERVERS];
static socklen_t server_lens[MAX_SERVERS];
static int nservers;
static char server_desc[160] = "none";
static struct stat resolv_st;
static int resolv_loaded;
static int inflight;
static unsigned long n_queries, n_failures;

static const char *resolv_path(void)
{
    const char *p = getenv("PPPLINK_RESOLV_CONF");   /* testing hook */
    return p && *p ? p : "/etc/resolv.conf";
}

/* Testing hook: send upstream queries to this port instead of 53. */
static int upstream_port(void)
{
    const char *p = getenv("PPPLINK_TEST_UPSTREAM_PORT");
    int v = p ? atoi(p) : 0;
    return v > 0 && v < 65536 ? v : 53;
}

/* Reloads the server list if resolv.conf changed. Called with lock held. */
static void refresh_servers(void)
{
    struct stat st;
    const char *path = resolv_path();
    int missing = stat(path, &st) != 0;

    if (resolv_loaded && !missing && st.st_mtime == resolv_st.st_mtime &&
        st.st_size == resolv_st.st_size && st.st_ino == resolv_st.st_ino)
        return;
    if (resolv_loaded && missing && nservers == 0)
        return;

    struct sockaddr_storage found[MAX_SERVERS];
    socklen_t lens[MAX_SERVERS];
    char desc[160] = "";
    int n = 0;

    FILE *f = missing ? NULL : fopen(path, "r");
    if (f) {
        char line[256];
        while (n < MAX_SERVERS && fgets(line, sizeof line, f)) {
            char addr[128];
            if (sscanf(line, " nameserver %127s", addr) != 1)
                continue;
            struct addrinfo hints, *res = NULL;
            char port[8];
            memset(&hints, 0, sizeof hints);
            hints.ai_flags = AI_NUMERICHOST | AI_NUMERICSERV;
            hints.ai_socktype = SOCK_DGRAM;
            snprintf(port, sizeof port, "%d", upstream_port());
            if (getaddrinfo(addr, port, &hints, &res) != 0 || !res)
                continue;
            memcpy(&found[n], res->ai_addr, res->ai_addrlen);
            lens[n] = res->ai_addrlen;
            freeaddrinfo(res);
            if (strlen(desc) + strlen(addr) + 3 < sizeof desc) {
                if (*desc) strcat(desc, ", ");
                strcat(desc, addr);
            }
            n++;
        }
        fclose(f);
    }

    const char *newdesc = n ? desc : "none";
    int changed = strcmp(server_desc, newdesc) != 0;
    memcpy(servers, found, sizeof(found[0]) * n);
    memcpy(server_lens, lens, sizeof(lens[0]) * n);
    nservers = n;
    snprintf(server_desc, sizeof server_desc, "%s", newdesc);
    if (!missing) resolv_st = st;
    else memset(&resolv_st, 0, sizeof resolv_st);
    if (resolv_loaded && changed && logf_)
        logf_("DNS: system nameservers now %s", server_desc);
    resolv_loaded = 1;
}

static int snapshot_servers(struct sockaddr_storage *out, socklen_t *lens)
{
    pthread_mutex_lock(&lock);
    refresh_servers();
    int n = nservers;
    memcpy(out, servers, sizeof(servers[0]) * n);
    memcpy(lens, server_lens, sizeof(server_lens[0]) * n);
    pthread_mutex_unlock(&lock);
    return n;
}

void dns_relay_describe(char *buf, int len)
{
    pthread_mutex_lock(&lock);
    refresh_servers();
    snprintf(buf, len, "%s", server_desc);
    pthread_mutex_unlock(&lock);
}

void dns_relay_stats(unsigned long *queries, unsigned long *failures)
{
    pthread_mutex_lock(&lock);
    *queries = n_queries;
    *failures = n_failures;
    pthread_mutex_unlock(&lock);
}

static void count(int failed)
{
    pthread_mutex_lock(&lock);
    n_queries++;
    if (failed) n_failures++;
    pthread_mutex_unlock(&lock);
}

/* Builds a SERVFAIL answer from the query (header + question echoed back). */
static int servfail(const unsigned char *q, int qlen, unsigned char *out)
{
    if (qlen < 12) return -1;
    memcpy(out, q, qlen);
    out[2] = 0x80 | (q[2] & 0x79);      /* QR=1, keep opcode and RD */
    out[3] = 0x80 | 2;                  /* RA=1, RCODE=SERVFAIL */
    /* No answer/authority records; keep question and any OPT record. */
    out[6] = out[7] = out[8] = out[9] = 0;
    return qlen;
}

static long long now_ms(void)
{
    struct timeval tv;
    gettimeofday(&tv, NULL);
    return (long long)tv.tv_sec * 1000 + tv.tv_usec / 1000;
}

/* One UDP exchange with the current servers. Returns answer length or -1. */
static int forward_udp(const unsigned char *q, int qlen, unsigned char *ans, int anslen)
{
    struct sockaddr_storage srv[MAX_SERVERS];
    socklen_t lens[MAX_SERVERS];
    int n = snapshot_servers(srv, lens);

    for (int i = 0; i < n; i++) {
        int s = socket(srv[i].ss_family, SOCK_DGRAM, 0);
        if (s < 0) continue;
        fcntl(s, F_SETFD, FD_CLOEXEC);
        if (connect(s, (struct sockaddr *)&srv[i], lens[i]) < 0 || send(s, q, qlen, 0) != qlen) {
            close(s);
            continue;
        }
        long long deadline = now_ms() + UDP_TIMEOUT_MS;
        for (;;) {
            int left = (int)(deadline - now_ms());
            if (left <= 0) break;
            struct pollfd p = { s, POLLIN, 0 };
            if (poll(&p, 1, left) <= 0) break;
            int r = recv(s, ans, anslen, 0);
            if (r >= 12 && ans[0] == q[0] && ans[1] == q[1] && (ans[2] & 0x80)) {
                close(s);
                return r;
            }
            if (r < 0 && errno != EINTR) break;   /* e.g. ICMP port unreachable */
        }
        close(s);
    }
    return -1;
}

static int read_full(int fd, unsigned char *buf, int len)
{
    int got = 0;
    while (got < len) {
        int r = read(fd, buf + got, len - got);
        if (r < 0 && errno == EINTR) continue;
        if (r <= 0) return -1;
        got += r;
    }
    return got;
}

static int write_full(int fd, const unsigned char *buf, int len)
{
    int sent = 0;
    while (sent < len) {
        int w = write(fd, buf + sent, len - sent);
        if (w < 0 && errno == EINTR) continue;
        if (w <= 0) return -1;
        sent += w;
    }
    return sent;
}

static void set_timeouts(int s, int secs)
{
    struct timeval tv = { secs, 0 };
    setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
    setsockopt(s, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof tv);
#ifdef SO_NOSIGPIPE
    int one = 1;
    setsockopt(s, SOL_SOCKET, SO_NOSIGPIPE, &one, sizeof one);
#endif
}

static int connect_timeout(int s, const struct sockaddr *a, socklen_t alen, int ms)
{
    int fl = fcntl(s, F_GETFL);
    fcntl(s, F_SETFL, fl | O_NONBLOCK);
    int r = connect(s, a, alen);
    if (r < 0 && errno == EINPROGRESS) {
        struct pollfd p = { s, POLLOUT, 0 };
        int err = 0;
        socklen_t el = sizeof err;
        if (poll(&p, 1, ms) == 1 && getsockopt(s, SOL_SOCKET, SO_ERROR, &err, &el) == 0 && err == 0)
            r = 0;
        else
            r = -1;
    }
    fcntl(s, F_SETFL, fl);
    return r;
}

/* One TCP exchange (2-byte length framing). Returns answer length or -1. */
static int forward_tcp(const unsigned char *q, int qlen, unsigned char *ans, int anslen)
{
    struct sockaddr_storage srv[MAX_SERVERS];
    socklen_t lens[MAX_SERVERS];
    int n = snapshot_servers(srv, lens);

    for (int i = 0; i < n; i++) {
        int s = socket(srv[i].ss_family, SOCK_STREAM, 0);
        if (s < 0) continue;
        fcntl(s, F_SETFD, FD_CLOEXEC);
        set_timeouts(s, TCP_TIMEOUT_S);
        unsigned char hdr[2] = { (unsigned char)(qlen >> 8), (unsigned char)qlen };
        unsigned char lenbuf[2];
        if (connect_timeout(s, (struct sockaddr *)&srv[i], lens[i], 3000) == 0 &&
            write_full(s, hdr, 2) == 2 && write_full(s, q, qlen) == qlen &&
            read_full(s, lenbuf, 2) == 2) {
            int rlen = (lenbuf[0] << 8) | lenbuf[1];
            if (rlen >= 12 && rlen <= anslen && read_full(s, ans, rlen) == rlen) {
                close(s);
                return rlen;
            }
        }
        close(s);
    }
    return -1;
}

static int take_slot(void)
{
    pthread_mutex_lock(&lock);
    int ok = inflight < MAX_INFLIGHT;
    if (ok) inflight++;
    pthread_mutex_unlock(&lock);
    return ok;
}

static void give_slot(void)
{
    pthread_mutex_lock(&lock);
    inflight--;
    pthread_mutex_unlock(&lock);
}

static int spawn(void *(*fn)(void *), void *arg)
{
    pthread_t t;
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    int r = pthread_create(&t, &attr, fn, arg);
    pthread_attr_destroy(&attr);
    return r;
}

/* ---------------------------------------------------------------- UDP */

struct udp_job {
    struct sockaddr_storage from;
    socklen_t fromlen;
    int len;
    unsigned char q[4096];
};

static void *udp_worker(void *arg)
{
    struct udp_job *j = arg;
    unsigned char *ans = malloc(4096);
    int r = ans ? forward_udp(j->q, j->len, ans, 4096) : -1;
    count(r < 0);
    if (r < 0 && ans) r = servfail(j->q, j->len, ans);
    if (r > 0) sendto(udp_fd, ans, r, 0, (struct sockaddr *)&j->from, j->fromlen);
    free(ans);
    free(j);
    give_slot();
    return NULL;
}

static void *udp_loop(void *arg)
{
    (void)arg;
    for (;;) {
        struct udp_job *j = malloc(sizeof *j);
        if (!j) { sleep(1); continue; }
        j->fromlen = sizeof j->from;
        j->len = recvfrom(udp_fd, j->q, sizeof j->q, 0, (struct sockaddr *)&j->from, &j->fromlen);
        if (j->len < 12) { free(j); continue; }
        if (!take_slot()) {
            unsigned char ans[4096];
            int r = servfail(j->q, j->len, ans);
            if (r > 0) sendto(udp_fd, ans, r, 0, (struct sockaddr *)&j->from, j->fromlen);
            count(1);
            free(j);
            continue;
        }
        if (spawn(udp_worker, j) != 0) { give_slot(); free(j); }
    }
    return NULL;
}

/* ---------------------------------------------------------------- TCP */

static void *tcp_conn(void *arg)
{
    int c = (int)(long)arg;
    unsigned char *q = malloc(MAX_MSG), *ans = malloc(MAX_MSG);
    set_timeouts(c, 10);
    while (q && ans) {
        unsigned char lenbuf[2];
        if (read_full(c, lenbuf, 2) != 2) break;
        int qlen = (lenbuf[0] << 8) | lenbuf[1];
        if (qlen < 12 || read_full(c, q, qlen) != qlen) break;
        int r = forward_tcp(q, qlen, ans, MAX_MSG);
        if (r < 0) r = forward_udp(q, qlen, ans, MAX_MSG);
        count(r < 0);
        if (r < 0) r = servfail(q, qlen, ans);
        unsigned char hdr[2] = { (unsigned char)(r >> 8), (unsigned char)r };
        if (write_full(c, hdr, 2) != 2 || write_full(c, ans, r) != r) break;
    }
    free(q);
    free(ans);
    close(c);
    give_slot();
    return NULL;
}

static void *tcp_loop(void *arg)
{
    (void)arg;
    for (;;) {
        int c = accept(tcp_fd, NULL, NULL);
        if (c < 0) { if (errno != EINTR) usleep(100000); continue; }
        fcntl(c, F_SETFD, FD_CLOEXEC);
        if (!take_slot()) { close(c); continue; }
        if (spawn(tcp_conn, (void *)(long)c) != 0) { give_slot(); close(c); }
    }
    return NULL;
}

/* ---------------------------------------------------------------- start */

int dns_relay_start(dns_log_fn log)
{
    logf_ = log;
    for (int attempt = 0; attempt < 20; attempt++) {
        struct sockaddr_in a;
        socklen_t alen = sizeof a;
        memset(&a, 0, sizeof a);
        a.sin_family = AF_INET;
        a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);

        int u = socket(AF_INET, SOCK_DGRAM, 0);
        if (u < 0) return -1;
        fcntl(u, F_SETFD, FD_CLOEXEC);
        if (bind(u, (struct sockaddr *)&a, sizeof a) < 0 ||
            getsockname(u, (struct sockaddr *)&a, &alen) < 0) {
            close(u);
            return -1;
        }
        int t = socket(AF_INET, SOCK_STREAM, 0);
        if (t < 0) { close(u); return -1; }
        fcntl(t, F_SETFD, FD_CLOEXEC);
        if (bind(t, (struct sockaddr *)&a, sizeof a) < 0 || listen(t, 16) < 0) {
            close(u);
            close(t);
            continue;                 /* TCP port taken: try another pair */
        }
        udp_fd = u;
        tcp_fd = t;
        pthread_mutex_lock(&lock);
        refresh_servers();
        pthread_mutex_unlock(&lock);
        if (spawn(udp_loop, NULL) != 0 || spawn(tcp_loop, NULL) != 0) return -1;
        return ntohs(a.sin_port);
    }
    errno = EADDRINUSE;
    return -1;
}
