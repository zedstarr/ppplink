/* config.h.  Generated from config.h.in by configure.  */
/*
 * User definable configuration options
 */

/* Undefine if you don't want talk emulation */
#define EMULATE_TALK 1

/* Define if you want the connection to be probed */
/* XXX Not working yet, so ignore this for now */
/* #undef PROBE_CONN */

/* Define to 1 if you want KEEPALIVE timers */
#define DO_KEEPALIVE 0

/* Define to MAX interfaces you expect to use at once */
/* MAX_INTERFACES determines the max. TOTAL number of interfaces (SLIP and PPP) */
/* MAX_PPP_INTERFACES determines max. number of PPP interfaces */
#define MAX_INTERFACES 1
#define MAX_PPP_INTERFACES 1

/* Define if you want slirp's socket in /tmp */
/* XXXXXX Do this in ./configure */
/* #undef USE_TMPSOCKET */

/* Define if you want slirp to use cfsetXspeed() on the terminal */
/* On macOS, this is required and is implied */
/* #undef DO_CFSETSPEED */

/* Define this if you want slirp to write to the tty as fast as it can */
/* This should only be set if you are using load-balancing, slirp does a */
/* pretty good job on single modems already, and seting this will make */
/* interactive sessions less responsive */
/* XXXXX Talk about having fast modem as unit 0 */
/* #undef FULL_BOLT */

/*
 * Define if you want slirp to use less CPU
 * You will notice a small lag in interactive sessions, but it's not that bad
 * Things like Netscape/ftp/etc. are completely unaffected
 * This is mainly for sysadmins who have many slirp users
 */
/* #undef USE_LOWCPU */

/* Define this if your compiler doesn't like prototypes */
#ifndef __STDC__
#define NO_PROTOTYPES
#endif

#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

/*********************************************************/
/*
 * Autoconf defined configuration options
 * You shouldn't need to touch any of these
 */

/* Ignore this */
#define DUMMY_PPP 1

/* Define if you have unistd.h */
#define HAVE_UNISTD_H 1

/* Define if you have stdlib.h */
#define HAVE_STDLIB_H 1

/* Define if you have sys/ioctl.h */
#define HAVE_SYS_IOCTL_H 1

/* Define if you have sys/filio.h */
/* #undef HAVE_SYS_FILIO_H */

/* Define if you have strerror */
#define HAVE_STRERROR 1

/* Define if you have strdup() */
#define HAVE_STRDUP 1

/* Define according to how time.h should be included */
#define TIME_WITH_SYS_TIME 1
#define HAVE_SYS_TIME_H 1

/* Define if you have sys/bitypes.h (Bionic uses stdint.h instead) */
/* #undef HAVE_SYS_BITYPES_H */

/* Define if the machine is big endian */
/* #undef WORDS_BIGENDIAN */

/* Define if your sprintf returns char * instead of int */
/* #undef BAD_SPRINTF */

/* Define if you have readv */
#define HAVE_READV 1

/* Define if iovec needs to be declared */
/* #undef DECLARE_IOVEC */

/* Define if a declaration of sprintf/fprintf is needed */
/* #undef DECLARE_SPRINTF */

/* Define if you have a POSIX.1 sys/wait.h */
#define HAVE_SYS_WAIT_H 1

/* Define if you have sys/select.h */
#define HAVE_SYS_SELECT_H 1

/* Define if you have strings.h */
#define HAVE_STRING_H 1

/* Define if you have arpa/inet.h */
#define HAVE_ARPA_INET_H 1

/* Define if you have sys/signal.h */
#define HAVE_SYS_SIGNAL_H 1

/* Define if you have sys/stropts.h */
/* #undef HAVE_SYS_STROPTS_H */

/* Define to whatever your compiler thinks inline should be */
#define inline inline

/* Define to whatever your compiler thinks const should be */
#define const const

/* Define if your compiler doesn't like prototypes */
/* #undef NO_PROTOTYPES */

/* Define if you don't have u_int32_t etc. typedef'd */
/* #undef NEED_TYPEDEFS */

/* Define to sizeof(char) */
/* #undef SIZEOF_CHAR */

/* Define to sizeof(short) */
/* #undef SIZEOF_SHORT */

/* Define to sizeof(int) */
/* #undef SIZEOF_INT */

/* Define to sizeof(char *) */
#define SIZEOF_CHAR_P 8

/* Define if you have random() */
#define HAVE_RANDOM 1

/* Define if you have srandom() */
#define HAVE_SRANDOM 1

/* Define if you have inet_aton */
#define HAVE_INET_ATON 1

/* Define if you have setenv */
#define HAVE_SETENV 1

/* Define if you have index() */
#define HAVE_INDEX 1

/* Define if you have bcmp() */
#define HAVE_BCMP 1

/* Define if you have drand48 */
#define HAVE_DRAND48 1

/* Define if you have memmove */
#define HAVE_MEMMOVE 1

/* Define if you have <termios.h> */
#define HAVE_TERMIOS_H 1

/* Define if you have gethostid */
/* #undef HAVE_GETHOSTID */

/* Define if you DON'T have unix-domain sockets */
#define NO_UNIX_SOCKETS 1

/* Define if gettimeofday only takes one argument */
/* #undef GETTIMEOFDAY_ONE_ARG */

/* Define if you have revoke() */
/* #undef HAVE_REVOKE */

/* Define if you have the sysv method of opening pty's (/dev/ptmx, etc.) */
#define HAVE_GRANTPT 1

/* Define if you have fchmod */
#define HAVE_FCHMOD 1

/* Define if you have <sys/type32.h> */
/* #undef HAVE_SYS_TYPES32_H */

/* Define if you have ifaddrs.h */
#define HAVE_IFADDRS_H 1

