/*
 * PPP Link for macOS - DNS relay.
 * Copyright 2026 zedstarr. Licensed under the Apache License, Version 2.0.
 *
 * Listens on 127.0.0.1:<port> (UDP and TCP, same port number) and forwards each
 * query to the nameservers currently listed in /etc/resolv.conf. The file is
 * re-checked on every query, so lookups follow the computer when it changes
 * network, without restarting slirp. The patched slirp ("dnsport" option) sends
 * the client's DNS traffic for 10.0.2.3 here.
 */
#ifndef PPPLINK_DNSRELAY_H
#define PPPLINK_DNSRELAY_H

typedef void (*dns_log_fn)(const char *fmt, ...);

/* Starts the relay threads. Returns the port, or -1 on failure (errno set). */
int dns_relay_start(dns_log_fn log);

/* Writes a short description of the current upstream servers into buf. */
void dns_relay_describe(char *buf, int len);

/* Query counters since start. */
void dns_relay_stats(unsigned long *queries, unsigned long *failures);

#endif
