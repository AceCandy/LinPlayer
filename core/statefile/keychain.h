#include <Security/Security.h>
#include <CoreFoundation/CoreFoundation.h>
#include <stdlib.h>
#include <string.h>

static CFMutableDictionaryRef lp_state_query(const char *name) {
    CFMutableDictionaryRef q = CFDictionaryCreateMutable(NULL, 0, &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
    CFStringRef key = CFStringCreateWithCString(NULL, name, kCFStringEncodingUTF8);
    CFDictionarySetValue(q, kSecClass, kSecClassGenericPassword);
    CFDictionarySetValue(q, kSecAttrService, CFSTR("com.linplayer.tvos.state.v1"));
    CFDictionarySetValue(q, kSecAttrAccount, key);
    CFDictionarySetValue(q, kSecAttrSynchronizable, kCFBooleanFalse);
    CFRelease(key);
    return q;
}

static OSStatus lp_state_read(const char *name, void **bytes, int *length) {
    CFMutableDictionaryRef q = lp_state_query(name);
    CFDictionarySetValue(q, kSecReturnData, kCFBooleanTrue);
    CFTypeRef value = NULL;
    OSStatus status = SecItemCopyMatching(q, &value);
    CFRelease(q);
    if (status != errSecSuccess) return status;
    if (!value || CFGetTypeID(value) != CFDataGetTypeID()) {
        if (value) CFRelease(value);
        return errSecDecode;
    }
    CFIndex size = CFDataGetLength((CFDataRef)value);
    if (size > INT32_MAX) { CFRelease(value); return errSecDecode; }
    *length = (int)size;
    *bytes = malloc(size ? (size_t)size : 1);
    if (!*bytes) { CFRelease(value); return errSecAllocate; }
    memcpy(*bytes, CFDataGetBytePtr((CFDataRef)value), (size_t)size);
    CFRelease(value);
    return errSecSuccess;
}

static OSStatus lp_state_write(const char *name, const void *bytes, int length) {
    CFMutableDictionaryRef q = lp_state_query(name);
    CFDataRef data = CFDataCreate(NULL, bytes, length);
    const void *keys[] = { kSecValueData, kSecAttrAccessible };
    const void *values[] = { data, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly };
    CFDictionaryRef update = CFDictionaryCreate(NULL, keys, values, 2, &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
    OSStatus status = SecItemUpdate(q, update);
    if (status == errSecItemNotFound) {
        CFDictionarySetValue(q, kSecValueData, data);
        CFDictionarySetValue(q, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly);
        status = SecItemAdd(q, NULL);
    }
    CFRelease(update);
    CFRelease(data);
    CFRelease(q);
    return status;
}
