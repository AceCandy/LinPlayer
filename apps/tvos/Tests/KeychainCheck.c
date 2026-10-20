#include "keychain.h"
#include <assert.h>
#include <stdio.h>

int main(int argc, char **argv) {
    assert(argc == 3);
    const char *name = argv[1];
    void *p = NULL; int n = 0;
    if (strcmp(argv[2], "save") == 0) {
        assert(lp_state_read(name, &p, &n) == errSecItemNotFound);
        assert(lp_state_write(name, "fixture-one", 11) == errSecSuccess);
        assert(lp_state_write(name, "fixture-two", 11) == errSecSuccess);
    } else {
        assert(lp_state_read(name, &p, &n) == errSecSuccess);
        assert(n == 11 && memcmp(p, "fixture-two", 11) == 0);
        free(p);
        CFMutableDictionaryRef q = lp_state_query(name);
        assert(SecItemDelete(q) == errSecSuccess);
        CFRelease(q);
        assert(lp_state_read(name, &p, &n) == errSecItemNotFound);
    }
    puts("Keychain check passed");
    return 0;
}
