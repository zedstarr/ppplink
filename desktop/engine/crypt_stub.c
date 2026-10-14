/*
 * Linux builds only: slirp's PAP code references crypt(), which glibc keeps in
 * libcrypt (often not installed). PPP Link never uses PAP/CHAP login checks.
 * macOS has crypt() in libc, so this file isn't built there.
 */
char *crypt(const char *key, const char *salt)
{
    (void)key;
    (void)salt;
    return "*";
}
