// glibc_shim.cpp — emulator only.
//
// There is no android-x86_64 build of the Needle engine, so the emulator links
// the linux-x86_64 one. That library was built against glibc and a newer
// libc++ than the platform's; these are the symbols bionic and the platform
// libc++ do not have. Nothing here is built for the phone.

#include <ctype.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

namespace {

// glibc's ctype tables are indexed from -128 to 255.
unsigned short g_class[384];
int32_t g_lower[384];
int32_t g_upper[384];
const unsigned short* g_class_ptr = g_class + 128;
const int32_t* g_lower_ptr = g_lower + 128;
const int32_t* g_upper_ptr = g_upper + 128;

// Bit values of glibc's _ISupper ... _ISalnum on a little-endian machine.
enum : unsigned short {
    kUpper = 1 << 8, kLower = 1 << 9, kAlpha = 1 << 10, kDigit = 1 << 11,
    kXdigit = 1 << 12, kSpace = 1 << 13, kPrint = 1 << 14, kGraph = 1 << 15,
    kBlank = 1 << 0, kCntrl = 1 << 1, kPunct = 1 << 2, kAlnum = 1 << 3,
};

__attribute__((constructor)) void initTables() {
    for (int c = -128; c < 256; c++) {
        int i = c + 128;
        g_lower[i] = c;
        g_upper[i] = c;
        if (c < 0 || c > 127) continue;
        unsigned short m = 0;
        if (isupper(c)) m |= kUpper;
        if (islower(c)) m |= kLower;
        if (isalpha(c)) m |= kAlpha;
        if (isdigit(c)) m |= kDigit;
        if (isxdigit(c)) m |= kXdigit;
        if (isspace(c)) m |= kSpace;
        if (isprint(c)) m |= kPrint;
        if (isgraph(c)) m |= kGraph;
        if (isblank(c)) m |= kBlank;
        if (iscntrl(c)) m |= kCntrl;
        if (ispunct(c)) m |= kPunct;
        if (isalnum(c)) m |= kAlnum;
        g_class[i] = m;
        g_lower[i] = tolower(c);
        g_upper[i] = toupper(c);
    }
}

}  // namespace

extern "C" {

const unsigned short** __ctype_b_loc() { return &g_class_ptr; }
const int32_t** __ctype_tolower_loc() { return &g_lower_ptr; }
const int32_t** __ctype_toupper_loc() { return &g_upper_ptr; }

int __isoc99_fscanf(FILE* stream, const char* format, ...) {
    va_list args;
    va_start(args, format);
    int rc = vfscanf(stream, format, args);
    va_end(args);
    return rc;
}

// Dropped from bionic; the compiler emits it for equality-only memcmp.
int bcmp(const void* a, const void* b, size_t n) { return memcmp(a, b, n); }

// std::__1::__hash_memory(const void*, size_t), which libc++ exports from
// LLVM 21 on; the platform's is older. It backs std::hash for strings inside
// the engine only, so any stable hash will do. FNV-1a.
size_t needle_shim_hash_memory(const void* data, size_t n)
        __asm__("_ZNSt3__113__hash_memoryEPKvm");
size_t needle_shim_hash_memory(const void* data, size_t n) {
    const unsigned char* bytes = static_cast<const unsigned char*>(data);
    uint64_t hash = 0xcbf29ce484222325ull;
    for (size_t i = 0; i < n; i++) {
        hash ^= bytes[i];
        hash *= 0x100000001b3ull;
    }
    return static_cast<size_t>(hash);
}

}  // extern "C"
