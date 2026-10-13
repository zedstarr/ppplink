cmake_minimum_required(VERSION 3.22.1)
project("ppplink" C)

# Flags as specified by build spec
set(CMAKE_C_STANDARD 90)
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -std=gnu89 -fcommon -O1 -fPIE -Wno-implicit-function-declaration -Wno-int-conversion -Wno-incompatible-pointer-types")
set(CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} -pie")

# Definitions for slirp
add_definitions(-DUSE_PPP -DUSE_MS_DNS -D_GNU_SOURCE)

# Include paths for slirp headers
include_directories(
    slirp
    slirp/ppp
)

# Slirp source files
set(SLIRP_SRCS
    slirp/ppp/auth.c
    slirp/ppp/bsd-comp.c
    slirp/ppp/ccp.c
    slirp/ppp/chap.c
    slirp/ppp/fsm.c
    slirp/ppp/ipcp.c
    slirp/ppp/lcp.c
    slirp/ppp/magic.c
    slirp/ppp/md5.c
    slirp/ppp/pppdfncs.c
    slirp/ppp/upap.c
    slirp/ppp.c
    slirp/cksum.c
    slirp/debug.c
    slirp/if.c
    slirp/ip_icmp.c
    slirp/ip_input.c
    slirp/ip_output.c
    slirp/main.c
    slirp/mbuf.c
    slirp/misc.c
    slirp/options.c
    slirp/sbuf.c
    slirp/sl.c
    slirp/slcompress.c
    slirp/socket.c
    slirp/tcp_input.c
    slirp/tcp_output.c
    slirp/tcp_subr.c
    slirp/tcp_timer.c
    slirp/terminal.c
    slirp/ttys.c
    slirp/udp.c
    android_compat.c
)

# Build slirp as an executable, packaged with name libslirp.so into lib/<abi>/
add_executable(slirp ${SLIRP_SRCS})
set_target_properties(slirp PROPERTIES
    OUTPUT_NAME "libslirp.so"
    RUNTIME_OUTPUT_DIRECTORY ${CMAKE_LIBRARY_OUTPUT_DIRECTORY}
)
target_link_libraries(slirp log)

# JNI library for pty creation and process management
add_library(ppplinknative SHARED ppplinknative.c)
target_link_libraries(ppplinknative log)
