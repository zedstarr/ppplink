# PPP Link for macOS: command-line tool and shared engine.
# Copyright 2026 zedstarr. Licensed under the Apache License, Version 2.0.
#
#   make                 build slirp and ppplink-engine into build/
#   make install         install to $(PREFIX) (default /usr/local)
#   make test            end-to-end test over a pty (needs python3)
#   make app             build "PPP Link.app" (needs Swift / Xcode command line tools)
#
# slirp is built from the Android app's sources (app/src/main/cpp/slirp), so both
# apps carry exactly the same patches. Also builds on Linux.

VERSION    ?= 1.0.0
PREFIX     ?= /usr/local
BINDIR     ?= $(PREFIX)/bin
LIBEXECDIR ?= $(PREFIX)/libexec/ppplink
DOCDIR     ?= $(PREFIX)/share/doc/ppplink
SLIRP_SRC  ?= ../app/src/main/cpp/slirp
BUILD      ?= build

CC        ?= cc
ARCHFLAGS ?=
UNAME     := $(shell uname -s)

# slirp is 1990s C: build it as gnu89 and keep modern compilers' new errors as warnings.
SLIRP_CFLAGS = -std=gnu89 -fcommon -O1 -w \
	-Wno-implicit-function-declaration -Wno-int-conversion \
	-Wno-incompatible-pointer-types \
	-DUSE_PPP -DUSE_MS_DNS -I$(SLIRP_SRC) -I$(SLIRP_SRC)/ppp

ENGINE_CFLAGS = -std=gnu99 -O2 -Wall -Wextra -Wno-unused-parameter \
	-DPPPLINK_VERSION=\"$(VERSION)\"

ifeq ($(UNAME),Darwin)
  ENGINE_LIBS =
  SLIRP_EXTRA =
  ifneq ($(findstring clang,$(shell $(CC) --version 2>/dev/null)),)
    SLIRP_CFLAGS += -Wno-incompatible-function-pointer-types
  endif
else
  ENGINE_LIBS = -lutil -lpthread
  SLIRP_EXTRA = engine/crypt_stub.c     # glibc keeps crypt() in a separate library
endif

SLIRP_FILES = ppp/auth.c ppp/bsd-comp.c ppp/ccp.c ppp/chap.c ppp/fsm.c ppp/ipcp.c \
	ppp/lcp.c ppp/magic.c ppp/md5.c ppp/pppdfncs.c ppp/upap.c ppp.c cksum.c debug.c \
	if.c ip_icmp.c ip_input.c ip_output.c main.c mbuf.c misc.c options.c sbuf.c sl.c \
	slcompress.c socket.c tcp_input.c tcp_output.c tcp_subr.c tcp_timer.c terminal.c \
	ttys.c udp.c
SLIRP_OBJS = $(addprefix $(BUILD)/slirp/,$(SLIRP_FILES:.c=.o))

ENGINE_SRCS = engine/ppplink-engine.c engine/dnsrelay.c

all: $(BUILD)/slirp-bin $(BUILD)/ppplink-engine $(BUILD)/ppplink

$(BUILD)/slirp/%.o: $(SLIRP_SRC)/%.c
	@mkdir -p $(dir $@)
	$(CC) $(ARCHFLAGS) $(SLIRP_CFLAGS) -c $< -o $@

$(BUILD)/slirp-bin: $(SLIRP_OBJS) $(SLIRP_EXTRA)
	$(CC) $(ARCHFLAGS) -o $@ $(SLIRP_OBJS) $(SLIRP_EXTRA)

$(BUILD)/ppplink-engine: $(ENGINE_SRCS) engine/dnsrelay.h
	@mkdir -p $(BUILD)
	$(CC) $(ARCHFLAGS) $(ENGINE_CFLAGS) -o $@ $(ENGINE_SRCS) $(ENGINE_LIBS)

# The script, pointed at the build directory so it runs uninstalled.
$(BUILD)/ppplink: bin/ppplink
	@mkdir -p $(BUILD)
	sed -e 's|@LIBEXECDIR@|$(abspath $(BUILD))|g' -e 's|@SLIRP_NAME@|slirp-bin|g' \
	    -e 's|@VERSION@|$(VERSION)|g' -e 's|@DOCDIR@|$(abspath ..)|g' $< > $@
	chmod 755 $@

install: $(BUILD)/slirp-bin $(BUILD)/ppplink-engine
	install -d $(DESTDIR)$(BINDIR) $(DESTDIR)$(LIBEXECDIR) $(DESTDIR)$(DOCDIR)
	install -m 755 $(BUILD)/slirp-bin $(DESTDIR)$(LIBEXECDIR)/slirp
	install -m 755 $(BUILD)/ppplink-engine $(DESTDIR)$(LIBEXECDIR)/ppplink-engine
	sed -e 's|@LIBEXECDIR@|$(LIBEXECDIR)|g' -e 's|@SLIRP_NAME@|slirp|g' \
	    -e 's|@VERSION@|$(VERSION)|g' -e 's|@DOCDIR@|$(DOCDIR)|g' bin/ppplink > $(DESTDIR)$(BINDIR)/ppplink
	chmod 755 $(DESTDIR)$(BINDIR)/ppplink
	install -m 644 ../LICENSE ../NOTICE ../THIRD_PARTY_NOTICES.txt README.md $(DESTDIR)$(DOCDIR)/

uninstall:
	rm -f $(DESTDIR)$(BINDIR)/ppplink
	rm -rf $(DESTDIR)$(LIBEXECDIR) $(DESTDIR)$(DOCDIR)

test: all
	python3 test/engine_test.py $(BUILD)/ppplink-engine $(BUILD)/slirp-bin

app: all
	VERSION=$(VERSION) bash app/make-app.sh

clean:
	rm -rf $(BUILD) app/.build

.PHONY: all install uninstall test app clean
