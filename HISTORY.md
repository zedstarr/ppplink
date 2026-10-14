# Development history

PPP Link began as PsiLink, a PPP bridge for the Psion Series 5mx / Ericsson MC218.
The notes below record that development; names in them are historical.

# PsiLink changes: review items 1-8

1. Replug resumes the link: USB attach/detach/permission receivers moved into
   LinkService; the service stays running while unplugged ("Adapter unplugged")
   and the engine re-opens the adapter on attach. Detach only reacts to the
   adapter in use.
2. One USB reader per session that survives slirp restarts; only the pty it
   feeds changes. No more two readers on one port after a hang-up.
3. Each slirp process is tracked by identity; a stale watcher can't close or
   restart a newer session. SIGKILL is only sent while the process is unreaped.
   Also fixed: argv[0] was passed twice (slirp received its own path as an option).
4. USB permission is requested (PendingIntent to the service); a denial stops the
   link with a clear error. Duplicate dialogs suppressed.
5. Start checks for an adapter first; any start failure stops the service, frees
   the wake lock, closes the USB connection and leaves the reason on screen.
6. PppSniffer handles ACFC/PFC header compression and ignores LCP Echo/Discard,
   so the state reaches and stays at Connected.
7. USB writes retry while flow control holds the line; 10 s with no progress
   restarts slirp and shows an error instead of a silent half-dead link.
8. Status card shows the real adapter, DNS, byte counts and time since PPP up.
   Modem emulation now holds slirp's output back until the Psion dials, and
   forwards bytes that follow ATD in the same read.

New files: LinkStatus.kt, UsbAdapters.kt. Rewritten: LinkEngine.kt, LinkService.kt,
MainActivity.kt, PppSniffer.kt, LinkState.kt; StatusCardView in ui/PsiLinkApp.kt.

## Fixes after first device test

- slirp `options.c`: strip trailing newline/whitespace from config-file lines.
  Bionic's inet_aton() rejected "10.42.0.1\n", so `dns` was ignored
  ("Error: Bad IP") and slirp had no DNS server at all. Verified on Linux with a
  Bionic-strict inet_aton: unpatched reproduces the error, patched sets the DNS.
- `.slirprc` gains `lcp-echo-interval 10` / `lcp-echo-failure 3`: if the Psion
  stops answering (cable pulled), slirp drops PPP after ~30-40 s and is restarted,
  so the status leaves "Connected".
- Status only shows Connected once the Psion has negotiated PPP with the current
  slirp. IP traffic from a stale session (Psion still on the pre-unplug link)
  shows "Psion on old link - reconnect on Psion" instead of a false Connected.

# Round 3: stall handling, items 9-15

- Psion holding the line (CTS low: asleep, switched off, busy) no longer kills
  slirp. Data is dropped while stalled (PPP/TCP resend), status shows
  "Psion not ready" and returns to the previous state when it accepts data again.
  LCP keepalives (now 10 s x 4) still end a link whose Psion never returns.
- 9  Threading: done in round 1 (single-threaded control dispatcher).
- 10 JNI: child resets the signal mask and handlers (ART's blocked SIGTERM was
     inherited), closes fds up to the real limit, null/OOM checks, CLOEXEC on the
     stderr pipe and pty master. nativeWait retries on EINTR (an interrupted wait
     made the app think slirp had exited while it was still running).
     Tested on Linux via a fake-JNI harness: launches real slirp, PPP answered over
     the pty, SIGTERM delivered even with SIGTERM blocked in the parent.
- 11 Foreground service type is connectedDevice only (dataSync and its permission
     removed). If startForeground fails the service stops cleanly with an error.
- 12 Custom DNS validated (UI and engine). Default-network changes are watched:
     if the Psion isn't connected, slirp restarts at once with the new DNS;
     if it is, the new DNS applies from the next connection (logged).
- 13 Modem emulation edge cases: done in round 1.
- 14 Debug TCP bridge wired up via a SerialLine abstraction (UsbLine / TcpLine),
     with a configurable port (default 8000). A client disconnect is treated as
     the Psion hanging up.
- 15 Flow-control switch disabled when the adapter can't do RTS/CTS; battery
     exemption prompt shown once; wake lock is 2 h and renewed every 30 min
     instead of silently ending at 24 h. Help text covers the Psion's power
     switch-off and carrier-detect settings.

New: SerialLine.kt. Removed: DebugTcpBridge.kt.

# Round 4: app icon

- Original adaptive launcher icon: clamshell handheld with a serial square wave
  on its screen, charcoal (#2E3033) background. Vector-only (minSdk 26).
  res/drawable/ic_launcher_foreground.xml, res/drawable/ic_launcher_monochrome.xml
  (Android 13+ themed icons), res/mipmap-anydpi-v26/ic_launcher(_round).xml,
  res/values/ic_launcher_colors.xml. Manifest points at @mipmap/ic_launcher.
- Matching notification small icon: res/drawable/ic_stat_psilink.xml.

# Round 5: DNS follows the phone's network

- New DnsRelay.kt: UDP + TCP DNS relay on a free 127.0.0.1 port. Each query is
  answered via the phone's *current* default network (DnsResolver on Android 10+,
  honouring Private DNS; plain UDP to the network's DNS on 8/9). SERVFAIL on
  failure; TC bit for oversized UDP answers so the client retries over TCP.
- slirp patches (main.c/h, options.c/p, socket.c, tcp_subr.c, udp.c): new
  `dnsport N` option sends DNS for the 10.0.2.3 alias to port N instead of 53
  (UDP and TCP), and replies from aliased sockets now carry the port the guest
  addressed (53) rather than the relay's port. Verified end-to-end on Linux over
  SLIP: query to 10.0.2.3:53 reaches the relay, answer returns from
  10.0.2.3:53; without the udp.c fix it returns from :5353 (would be rejected).
- Automatic DNS mode uses the relay (status: "Phone's DNS (follows network)").
  Custom DNS still goes direct. If the relay can't start, falls back to direct.

# Removing dial-up-era features

- `nozeros` in the generated config: no "type five zeroes to exit" (or five
  ones to detach) keyboard escape.
- slirp `main.c`: removed the telnet shell (10.0.2.1:23, would run /bin/sh)
  and slirp.ftpd (10.0.2.1:21) hooks.
- slirp `tcp_subr.c`: disabled the remote command console on 10.0.2.0, which
  let the client change slirp settings and run commands on the phone.
- Tested on Linux over SLIP: banner no longer offers the escape; five single
  '0' bytes no longer exit (control: they do without nozeros); 10.0.2.0:23,
  10.0.2.1:23 and :21 answer "No application configured" (control: unpatched
  slirp opens a "Slirp>" prompt); the DNS relay path still answers from
  10.0.2.3:53.

# macOS version

- `macos/engine/ppplink-engine.c`: the Android LinkEngine's job in portable C:
  serial port (termios, RTS/CTS, DTR) <-> slirp on a pty, slirp restarted after
  each hang-up, modem emulation, PPP sniffer for status, stale-link warning,
  drop-not-queue when the client holds CTS low, unplug/replug, debug TCP bridge.
- `macos/engine/dnsrelay.c`: DNS relay for the `dnsport` patch, forwarding to the
  nameservers in /etc/resolv.conf, re-read when it changes (UDP and TCP).
- `macos/bin/ppplink`: command for Terminal (bash 3.2), config file, port list
  with driver hints, client setup; keeps the Mac awake with caffeinate.
- `macos/app`: SwiftUI menu-bar app (macOS 13+) running the bundled engine.
- slirp is built from `app/src/main/cpp/slirp` unchanged, so both platforms share
  the patches. Also builds and runs on Linux.
- Hosted on GitHub (github.com/zedstarr/ppplink); Homebrew tap at
  github.com/zedstarr/homebrew-ppplink. CI builds and tests on a macOS runner.
- `make -C macos test`: a pty as the serial line and a scripted PPP client:
  LCP/IPCP, DNS through the relay (and after a nameserver change), TCP through
  slirp, hang-up and restart, stale link, replug, modem mode, debug bridge.

# Linux

- `macos/` renamed `desktop/`: the command, engine and slirp build were already
  portable; Linux is now a supported target alongside macOS.
- slirp `config.h`: pointer size comes from the target instead of being fixed at
  8. On 32-bit builds (32-bit Raspberry Pi OS, i386) the old value padded slirp's
  IP overlay header wrongly, so the link came up but DNS and TCP failed (7 test
  failures on an i386 build before the fix, none after). Android is unaffected
  (64-bit only).
- Makefile: slirp objects now rebuild when its headers change.
- `ppplink` on Linux: keeps the machine awake with systemd-inhibit; warns when the
  user can't open the port (dialout group); explains brltty grabbing CH340
  adapters; lists /dev/serial/by-id names.
- `desktop/linux/ppplink.service`: always-on service as a throwaway unprivileged
  user, settings in /etc/ppplink/ppplink.conf (`make install-service`).
- `desktop/linux/70-ppplink.rules`: keeps ModemManager off USB serial adapters
  (`make install-udev`).
- CI tests Linux x86_64, Linux i386 and macOS.
