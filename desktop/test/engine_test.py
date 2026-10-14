#!/usr/bin/env python3
"""
End-to-end test for ppplink-engine + the patched slirp, no hardware needed.

A pty stands in for the USB serial adapter. This script plays the retro
computer: a minimal PPP client (LCP, IPCP, then raw IPv4) that looks up a name
through the DNS relay and opens a TCP connection through slirp. A fake DNS
upstream and a local echo server stand in for the internet.

usage: engine_test.py path/to/ppplink-engine path/to/slirp
"""
import os, socket, struct, subprocess, sys, tempfile, threading, time, select

ENGINE, SLIRP = sys.argv[1], sys.argv[2]
CLIENT_IP = "10.0.2.15"
failures = []


def check(cond, label):
    print(("  ok    " if cond else "  FAIL  ") + label)
    if not cond:
        failures.append(label)


# ------------------------------------------------------------------ fake internet

def fake_dns(port, host="127.0.0.1", addr=(1, 2, 3, 4)):
    """Answers every A query with addr (UDP and TCP)."""
    def answer(q):
        e = 12
        while q[e]:
            e += q[e] + 1
        return (q[:2] + b"\x81\x80" + struct.pack(">HHHH", 1, 1, 0, 0) + q[12:e + 5]
                + b"\xc0\x0c" + struct.pack(">HHIH", 1, 1, 60, 4) + bytes(addr))
    fam = socket.AF_INET6 if ":" in host else socket.AF_INET
    u = socket.socket(fam, socket.SOCK_DGRAM)
    u.bind((host, port))
    t = socket.socket(fam, socket.SOCK_STREAM)
    t.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    t.bind((host, port))
    t.listen(4)

    def udp():
        while True:
            q, a = u.recvfrom(512)
            u.sendto(answer(q), a)

    def tcp():
        while True:
            c, _ = t.accept()
            n = struct.unpack(">H", c.recv(2))[0]
            q = c.recv(n)
            r = answer(q)
            c.sendall(struct.pack(">H", len(r)) + r)
            c.close()
    threading.Thread(target=udp, daemon=True).start()
    threading.Thread(target=tcp, daemon=True).start()


def second_loopback():
    """::1 where IPv6 works (macOS has no 127.0.0.2), else 127.0.0.2 (Linux)."""
    try:
        socket.socket(socket.AF_INET6, socket.SOCK_DGRAM).bind(("::1", 0))
        return "::1"
    except OSError:
        return "127.0.0.2"


def echo_server():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    s.listen(4)

    def run():
        while True:
            c, _ = s.accept()
            data = c.recv(100)
            c.sendall(data.upper())
            c.close()
    threading.Thread(target=run, daemon=True).start()
    return s.getsockname()[1]


# ------------------------------------------------------------------ PPP framing

def fcs16(data):
    fcs = 0xFFFF
    for b in data:
        fcs ^= b
        for _ in range(8):
            fcs = (fcs >> 1) ^ 0x8408 if fcs & 1 else fcs >> 1
    return fcs ^ 0xFFFF


def hdlc(proto, payload):
    body = b"\xff\x03" + struct.pack(">H", proto) + payload
    body += struct.pack("<H", fcs16(body))
    out = bytearray(b"\x7e")
    for b in body:
        if b < 0x20 or b in (0x7D, 0x7E):
            out += bytes([0x7D, b ^ 0x20])
        else:
            out.append(b)
    out.append(0x7E)
    return bytes(out)


class Line:
    """The client's end of the serial line."""

    def __init__(self, fd=None, sock=None):
        self.fd, self.sock, self.buf = fd, sock, b""

    def write(self, data):
        if self.sock:
            self.sock.sendall(data)
        else:
            os.write(self.fd, data)

    def read_raw(self, timeout):
        fd = self.sock.fileno() if self.sock else self.fd
        r, _, _ = select.select([fd], [], [], timeout)
        if not r:
            return b""
        return self.sock.recv(4096) if self.sock else os.read(self.fd, 4096)

    def read_text(self, until, timeout=3):
        end, got = time.time() + timeout, b""
        while time.time() < end and until not in got:
            got += self.read_raw(0.2)
        return got

    def frames(self, timeout):
        """Yields (protocol, payload) for good frames until timeout."""
        end = time.time() + timeout
        while time.time() < end:
            self.buf += self.read_raw(0.1)
            while b"\x7e" in self.buf:
                raw, self.buf = self.buf.split(b"\x7e", 1)
                if not raw:
                    continue
                f, esc = bytearray(), False
                for b in raw:
                    if b == 0x7D:
                        esc = True
                    elif esc:
                        f.append(b ^ 0x20); esc = False
                    else:
                        f.append(b)
                if len(f) < 4 or fcs16(bytes(f[:-2])) != struct.unpack("<H", bytes(f[-2:]))[0]:
                    continue
                f = bytes(f[:-2])
                if f[:2] == b"\xff\x03":
                    f = f[2:]
                if f[0] & 1:
                    proto, f = f[0], f[1:]
                else:
                    proto, f = struct.unpack(">H", f[:2])[0], f[2:]
                yield proto, f

    def negotiate(self):
        """LCP then IPCP. Returns True once both are open."""
        my_ip = socket.inet_aton(CLIENT_IP)
        self.write(hdlc(0xC021, struct.pack(">BBH", 1, 1, 4)))   # LCP Conf-Req, no options
        lcp_acked = lcp_peer = ipcp_acked = ipcp_peer = False
        ipcp_sent = False
        end = time.time() + 10
        while time.time() < end:
            for proto, p in self.frames(0.5):
                code, ident = p[0], p[1]
                if proto == 0xC021 and code == 1:                  # peer Conf-Req: ack it all
                    self.write(hdlc(0xC021, bytes([2]) + p[1:]))
                    lcp_peer = True
                elif proto == 0xC021 and code == 2:
                    lcp_acked = True
                elif proto == 0xC021 and code == 9:                # echo request
                    self.write(hdlc(0xC021, bytes([10]) + p[1:4] + b"\0\0\0\0"))
                elif proto == 0x8021 and code == 1:
                    self.write(hdlc(0x8021, bytes([2]) + p[1:]))
                    ipcp_peer = True
                elif proto == 0x8021 and code == 2:
                    ipcp_acked = True
                elif proto == 0x8021 and code == 3:                # Nak: take its address
                    opts = p[4:]
                    if len(opts) >= 6 and opts[0] == 3:
                        my_ip = opts[2:6]
                    ipcp_sent = False
                if lcp_acked and lcp_peer and not ipcp_sent:
                    opt = bytes([3, 6]) + my_ip
                    self.write(hdlc(0x8021, struct.pack(">BBH", 1, 2, 4 + len(opt)) + opt))
                    ipcp_sent = True
                if lcp_acked and lcp_peer and ipcp_acked and ipcp_peer:
                    self.ip = socket.inet_ntoa(my_ip)
                    return True
        return False

    def ip_exchange(self, packet, want_proto, timeout=3):
        self.write(hdlc(0x0021, packet))
        for proto, p in self.frames(timeout):
            if proto == 0x21 and p[9] == want_proto:
                return p
        return None


# ------------------------------------------------------------------ IP helpers

def csum(b):
    if len(b) % 2:
        b += b"\0"
    t = sum(struct.unpack(f">{len(b)//2}H", b))
    t = (t >> 16) + (t & 0xFFFF)
    t += t >> 16
    return ~t & 0xFFFF


def ip_pkt(src, dst, proto, payload):
    h = struct.pack(">BBHHHBBH4s4s", 0x45, 0, 20 + len(payload), 1, 0, 64, proto, 0,
                    socket.inet_aton(src), socket.inet_aton(dst))
    return h[:10] + struct.pack(">H", csum(h)) + h[12:] + payload


def dns_query(src):
    q = struct.pack(">HHHHHH", 0xBEEF, 0x0100, 1, 0, 0, 0) + b"\x07example\x03com\x00" + struct.pack(">HH", 1, 1)
    udp = struct.pack(">HHHH", 1234, 53, 8 + len(q), 0) + q
    pseudo = socket.inet_aton(src) + socket.inet_aton("10.0.2.3") + struct.pack(">BBH", 0, 17, len(udp))
    udp = udp[:6] + struct.pack(">H", csum(pseudo + udp)) + udp[8:]
    return ip_pkt(src, "10.0.2.3", 17, udp)


def tcp_seg(src, dst, sport, dport, seq, ack, flags, data=b""):
    hdr = struct.pack(">HHIIBBHHH", sport, dport, seq, ack, 5 << 4, flags, 8192, 0, 0)
    pseudo = socket.inet_aton(src) + socket.inet_aton(dst) + struct.pack(">BBH", 0, 6, len(hdr) + len(data))
    c = csum(pseudo + hdr + data)
    return ip_pkt(src, dst, 6, hdr[:16] + struct.pack(">H", c) + hdr[18:] + data)


def tcp_echo(line, src, port):
    """Connects to 10.0.2.2:port (the computer itself), sends hello, returns the reply."""
    dst, sport, seq = "10.0.2.2", 40001, 1000
    p = line.ip_exchange(tcp_seg(src, dst, sport, port, seq, 0, 0x02), 6)
    if not p or (p[33] & 0x12) != 0x12:
        return None
    srv_seq = struct.unpack(">I", p[24:28])[0]
    seq += 1
    line.write(hdlc(0x21, tcp_seg(src, dst, sport, port, seq, srv_seq + 1, 0x10)))
    line.write(hdlc(0x21, tcp_seg(src, dst, sport, port, seq, srv_seq + 1, 0x18, b"hello")))
    text = b""
    for proto, p in line.frames(3):
        if proto == 0x21 and p[9] == 6:
            off = (p[32] >> 4) * 4
            text += p[20 + off:]
            if text:
                break
    return text


# ------------------------------------------------------------------ engine

class Engine:
    def __init__(self, args, env):
        self.lines, self.cv = [], threading.Condition()
        self.p = subprocess.Popen([ENGINE, "--slirp", SLIRP, "--status", "machine"] + args,
                                  stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=env, text=True)
        threading.Thread(target=self._read, daemon=True).start()

    def _read(self):
        for l in self.p.stdout:
            with self.cv:
                self.lines.append(l.rstrip("\n"))
                self.cv.notify_all()

    def wait_for(self, prefix, timeout=10, start=0):
        end = time.time() + timeout
        with self.cv:
            while True:
                for i, l in enumerate(self.lines[start:], start):
                    if l.startswith(prefix):
                        return i
                left = end - time.time()
                if left <= 0:
                    return None
                self.cv.wait(left)

    def mark(self):
        with self.cv:
            return len(self.lines)

    def stop(self):
        self.p.terminate()
        try:
            self.p.wait(5)
        except subprocess.TimeoutExpired:
            self.p.kill()

    def dump(self):
        print("\n".join("        | " + l for l in self.lines))


def new_pty():
    m, s = os.openpty()
    name = os.ttyname(s)
    os.close(s)
    return m, name


def main():
    global alt
    tmp = tempfile.mkdtemp()
    resolv = os.path.join(tmp, "resolv.conf")
    with open(resolv, "w") as f:
        f.write("nameserver 127.0.0.1\n")
    dns_port = 15353
    fake_dns(dns_port)
    alt = second_loopback()
    fake_dns(dns_port, alt, (5, 6, 7, 8))
    echo_port = echo_server()
    env = dict(os.environ, PPPLINK_RESOLV_CONF=resolv, PPPLINK_TEST_UPSTREAM_PORT=str(dns_port), TMPDIR=tmp)

    print("1. Direct mode over a serial line (pty)")
    m, path = new_pty()
    e = Engine(["--line", path, "--wait"], env)
    check(e.wait_for("STATE ready") is not None, "engine opens the line and starts slirp")
    line = Line(fd=m)
    check(line.negotiate(), "LCP and IPCP negotiate")
    check(e.wait_for("STATE negotiating") is not None, "status: negotiating")
    p = line.ip_exchange(dns_query(line.ip), 17)
    check(p is not None and p[-4:] == bytes([1, 2, 3, 4]), "DNS via 10.0.2.3 answered through the relay")
    check(p is not None and struct.unpack(">H", p[20:22])[0] == 53, "DNS reply comes from port 53")
    check(e.wait_for("STATE connected") is not None, "status: connected")
    check(tcp_echo(line, line.ip, echo_port) == b"HELLO", "TCP through slirp to the computer")
    check(e.wait_for("BYTES") is not None, "byte counts reported")

    print("   DNS follows a network change")
    with open(resolv + ".new", "w") as f:
        f.write("# changed network\nnameserver %s\n" % alt)
    os.rename(resolv + ".new", resolv)
    p = line.ip_exchange(dns_query(line.ip), 17)
    check(p is not None and p[-4:] == bytes([5, 6, 7, 8]), "next lookup uses the new nameserver")
    check(e.wait_for("LOG DNS: system nameservers now " + alt) is not None, "change logged")
    i = e.wait_for("LOG DNS relay on")
    relay = int(e.lines[i].rsplit(":", 1)[1])
    q = struct.pack(">HHHHHH", 7, 0x0100, 1, 0, 0, 0) + b"\x01a\x00" + struct.pack(">HH", 1, 1)
    c = socket.create_connection(("127.0.0.1", relay))
    c.sendall(struct.pack(">H", len(q)) + q)
    r = c.recv(2); r = c.recv(struct.unpack(">H", r)[0])
    c.close()
    check(r[:2] == q[:2] and r[-4:] == bytes([5, 6, 7, 8]), "relay answers DNS over TCP")

    print("2. Client hangs up, slirp restarts, stale link detected")
    mk = e.mark()
    line.write(hdlc(0xC021, struct.pack(">BBH", 5, 9, 4)))       # LCP Terminate-Request
    check(e.wait_for("LOG PPP link ended", start=mk) is not None, "slirp exits on hang-up")
    check(e.wait_for("STATE ready", start=mk) is not None, "slirp restarted, ready again")
    time.sleep(0.5)
    line.buf = b""
    line.write(hdlc(0x0021, dns_query(line.ip)))
    check(e.wait_for("STATE stale", start=mk) is not None, "IP on the old link reported")
    check(line.negotiate(), "client reconnects")
    check(line.ip_exchange(dns_query(line.ip), 17) is not None, "DNS works after reconnect")

    print("3. Adapter unplugged and replugged")
    mk = e.mark()
    os.close(m)
    check(e.wait_for("STATE waiting Adapter unplugged", start=mk) is not None, "unplug detected, waiting")
    m2, path2 = new_pty()
    if path2 == path:
        check(e.wait_for("STATE ready", start=mk, timeout=5) is not None, "replug reopens the line")
        line = Line(fd=m2)
        check(line.negotiate(), "client connects after replug")
    else:
        print("  skip  replug (the new pty got a different name)")
    e.stop()
    check(e.wait_for("STATE stopped") is not None, "clean stop")
    if failures:
        e.dump()

    print("4. Modem emulation")
    m, path = new_pty()
    e = Engine(["--line", path, "--mode", "modem", "--baud", "57600"], env)
    check(e.wait_for("STATE ready") is not None, "ready")
    line = Line(fd=m)
    line.write(b"ATZ\r")
    check(b"OK" in line.read_text(b"OK"), "AT -> OK")
    line.write(b"ATDT 123\r")
    check(b"CONNECT 57600" in line.read_text(b"CONNECT"), "ATD -> CONNECT 57600")
    check(line.negotiate(), "PPP after CONNECT")
    check(line.ip_exchange(dns_query(line.ip), 17) is not None, "DNS works")
    e.stop()
    os.close(m)

    print("5. Custom DNS and debug TCP bridge")
    e = Engine(["--tcp", "18765", "--dns", "9.9.9.9", "--mtu", "576"], env)
    check(e.wait_for("STATE waiting Waiting for debug client") is not None, "bridge listening")
    check(e.wait_for("DNS 9.9.9.9") is not None, "custom DNS reported")
    s = socket.create_connection(("127.0.0.1", 18765))
    line = Line(sock=s)
    check(e.wait_for("STATE ready") is not None, "client accepted, slirp started")
    check(line.negotiate(), "PPP over the bridge")
    s.close()
    check(e.wait_for("LOG Debug client disconnected") is not None, "disconnect noticed")
    e.stop()

    print("6. Missing adapter without --wait")
    e = Engine(["--line", "/dev/does-not-exist"], env)
    rc = e.p.wait(5)
    check(rc == 2 and e.wait_for("STATE error") is not None, "fails with an error state")

    print()
    print("FAILED: %d" % len(failures) if failures else "All tests passed.")
    sys.exit(1 if failures else 0)


main()
