/*
 * Android Bionic compatibility stubs for slirp
 */
#include <stddef.h>

/* Bionic has no crypt(). Provide dummy implementation for slirp/ppp/auth.c */
char *crypt(const char *key, const char *salt) {
    (void)key;
    (void)salt;
    return "*";
}
