#include "keychain.h"
#include <assert.h>
#include <stdio.h>

static void expect_status(OSStatus actual, OSStatus expected) {
    if (actual != expected) fprintf(stderr, "Keychain status: %d, expected: %d\n", (int)actual, (int)expected);
    assert(actual == expected);
}

int lp_keychain_check(const char *name, const char *phase) {
    void *p = NULL; int n = 0;
    if (strcmp(phase, "save") == 0) {
        expect_status(lp_state_read(name, &p, &n), errSecItemNotFound);
        expect_status(lp_state_write(name, "fixture-one", 11), errSecSuccess);
        expect_status(lp_state_write(name, "fixture-two", 11), errSecSuccess);
    } else if (strcmp(phase, "verify") == 0) {
        expect_status(lp_state_read(name, &p, &n), errSecSuccess);
        assert(n == 11 && memcmp(p, "fixture-two", 11) == 0);
        free(p);
        CFMutableDictionaryRef q = lp_state_query(name);
        expect_status(SecItemDelete(q), errSecSuccess);
        CFRelease(q);
        expect_status(lp_state_read(name, &p, &n), errSecItemNotFound);
    } else if (strcmp(phase, "missing") == 0) {
        expect_status(lp_state_read(name, &p, &n), errSecItemNotFound);
    } else if (strcmp(phase, "cleanup") == 0) {
        CFMutableDictionaryRef q = lp_state_query(name);
        OSStatus status = SecItemDelete(q);
        CFRelease(q);
        if (status != errSecItemNotFound) expect_status(status, errSecSuccess);
    } else {
        assert(!"unknown keychain check phase");
    }
    puts("Keychain check passed");
    return 0;
}

#ifndef KEYCHAIN_APP_CHECK
int main(int argc, char **argv) {
    assert(argc == 3);
    return lp_keychain_check(argv[1], argv[2]);
}
#endif
