/**
 * NEXTVM Native Hook Library
 *
 * PLT/GOT hooks for libc file I/O functions to implement native-level
 * path redirection for guest app file isolation.
 *
 * Hooks (installed via GOT patching):
 *   - open/openat     → redirect file paths to sandbox
 *   - access          → check sandbox path instead
 *   - stat/lstat      → stat sandbox path instead
 *   - readlink        → return sandbox path
 *   - fopen           → redirect file paths to sandbox
 *   - __system_property_get → spoof device properties
 *
 * Architecture:
 *   Java (NativeHookBridge.kt)
 *     ↓ JNI
 *   native-hook.cpp (this file)
 *     ↓ GOT/PLT patching via dl_iterate_phdr + ELF parsing
 *   hooked libc functions ←→ original libc functions (via saved pointers)
 */

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/mman.h>
#include <unistd.h>
#include <link.h>
#include <elf.h>

// Define ELF_R_SYM if not provided by NDK headers
#ifndef ELF_R_SYM
#ifdef __LP64__
#define ELF_R_SYM(info) ELF64_R_SYM(info)
#else
#define ELF_R_SYM(info) ELF32_R_SYM(info)
#endif
#endif

#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <cstdarg>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>
#include <mutex>
#include <cerrno>
#include <signal.h>
#include <setjmp.h>

#define LOG_TAG "NextVM-Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ==================== Global State ====================

static bool g_initialized = false;
static bool g_hooks_installed = false;
static std::mutex g_mutex;

// Path redirection: source prefix → target prefix
static std::unordered_map<std::string, std::string> g_path_redirects;

// Property spoofing: property name → spoofed value
static std::unordered_map<std::string, std::string> g_property_spoofs;

// Hidden paths: paths that should appear non-existent
static std::unordered_set<std::string> g_hidden_paths;

// /proc/self spoofing
static int g_spoofed_pid = -1;
static std::string g_spoofed_package_name;

// Host package data dir prefix
static std::string g_host_data_prefix;

// Virtual data root
static std::string g_virtual_data_root;

// ==================== Original Function Pointers ====================

typedef int (*orig_open_t)(const char*, int, ...);
typedef int (*orig_openat_t)(int, const char*, int, ...);
typedef int (*orig_access_t)(const char*, int);
typedef int (*orig_stat_t)(const char*, struct stat*);
typedef int (*orig_lstat_t)(const char*, struct stat*);
typedef ssize_t (*orig_readlink_t)(const char*, char*, size_t);
typedef FILE* (*orig_fopen_t)(const char*, const char*);
typedef int (*orig_system_property_get_t)(const char*, char*);

static orig_open_t real_open = nullptr;
static orig_openat_t real_openat = nullptr;
static orig_access_t real_access = nullptr;
static orig_stat_t real_stat = nullptr;
static orig_lstat_t real_lstat = nullptr;
static orig_readlink_t real_readlink = nullptr;
static orig_fopen_t real_fopen = nullptr;
static orig_system_property_get_t real_system_property_get = nullptr;

// ==================== Path Redirection Logic ====================

/**
 * Check if a path is hidden.
 */
static bool is_path_hidden(const char* path) {
    if (path == nullptr || g_hidden_paths.empty()) return false;
    return g_hidden_paths.count(std::string(path)) > 0;
}

/**
 * Check if a path needs redirection and return the redirected path.
 * Returns empty string if no redirection needed.
 */
static std::string redirect_path(const char* path) {
    if (path == nullptr || path[0] == '\0') return "";
    if (g_path_redirects.empty()) return "";

    std::string path_str(path);

    // Check each registered redirect prefix (longest match wins)
    std::string best_from;
    std::string best_to;
    for (const auto& redirect : g_path_redirects) {
        if (path_str.compare(0, redirect.first.length(), redirect.first) == 0) {
            if (redirect.first.length() > best_from.length()) {
                best_from = redirect.first;
                best_to = redirect.second;
            }
        }
    }

    if (!best_from.empty()) {
        return best_to + path_str.substr(best_from.length());
    }

    // Special case: /data/user/0/ is the same as /data/data/
    if (path_str.compare(0, 13, "/data/user/0/") == 0) {
        std::string alt_path = "/data/data/" + path_str.substr(13);
        return redirect_path(alt_path.c_str());
    }

    return "";
}

/**
 * Check if a path is a /proc/self path that needs spoofing.
 */
static bool is_proc_self_path(const char* path) {
    if (path == nullptr) return false;
    return strncmp(path, "/proc/self/", 11) == 0;
}

// ==================== Hook Implementations ====================

/**
 * Hooked open() — redirects file paths to sandbox, hides paths.
 */
static int hooked_open(const char* path, int flags, ...) {
    // Check hidden paths
    if (is_path_hidden(path)) {
        errno = ENOENT;
        return -1;
    }

    std::string redirected = redirect_path(path);
    const char* actual_path = redirected.empty() ? path : redirected.c_str();

    if (!redirected.empty()) {
        LOGD("open: %s -> %s", path, actual_path);
    }

    if (flags & O_CREAT) {
        va_list args;
        va_start(args, flags);
        mode_t mode = static_cast<mode_t>(va_arg(args, int));
        va_end(args);
        return real_open(actual_path, flags, mode);
    }
    return real_open(actual_path, flags);
}

/**
 * Hooked openat() — redirects file paths to sandbox, hides paths.
 */
static int hooked_openat(int dirfd, const char* path, int flags, ...) {
    if (is_path_hidden(path)) {
        errno = ENOENT;
        return -1;
    }

    std::string redirected = redirect_path(path);
    const char* actual_path = redirected.empty() ? path : redirected.c_str();

    if (!redirected.empty()) {
        LOGD("openat: %s -> %s", path, actual_path);
    }

    if (flags & O_CREAT) {
        va_list args;
        va_start(args, flags);
        mode_t mode = static_cast<mode_t>(va_arg(args, int));
        va_end(args);
        return real_openat(dirfd, actual_path, flags, mode);
    }
    return real_openat(dirfd, actual_path, flags);
}

/**
 * Hooked access() — checks sandbox path instead, hides paths.
 */
static int hooked_access(const char* path, int mode) {
    if (is_path_hidden(path)) {
        errno = ENOENT;
        return -1;
    }

    std::string redirected = redirect_path(path);
    const char* actual_path = redirected.empty() ? path : redirected.c_str();

    return real_access(actual_path, mode);
}

/**
 * Hooked stat() — stats sandbox path instead, hides paths.
 */
static int hooked_stat(const char* path, struct stat* buf) {
    if (is_path_hidden(path)) {
        errno = ENOENT;
        return -1;
    }

    std::string redirected = redirect_path(path);
    const char* actual_path = redirected.empty() ? path : redirected.c_str();

    return real_stat(actual_path, buf);
}

/**
 * Hooked lstat() — lstats sandbox path instead, hides paths.
 */
static int hooked_lstat(const char* path, struct stat* buf) {
    if (is_path_hidden(path)) {
        errno = ENOENT;
        return -1;
    }

    std::string redirected = redirect_path(path);
    const char* actual_path = redirected.empty() ? path : redirected.c_str();

    return real_lstat(actual_path, buf);
}

/**
 * Hooked readlink() — returns sandbox path instead.
 */
static ssize_t hooked_readlink(const char* path, char* buf, size_t bufsiz) {
    if (is_path_hidden(path)) {
        errno = ENOENT;
        return -1;
    }

    // Spoof /proc/self/exe
    if (is_proc_self_path(path) && strcmp(path, "/proc/self/exe") == 0 &&
        !g_spoofed_package_name.empty()) {
        // Return a fake exe path
        std::string fake = "/system/bin/app_process64";
        size_t len = fake.length();
        if (len > bufsiz) len = bufsiz;
        memcpy(buf, fake.c_str(), len);
        return static_cast<ssize_t>(len);
    }

    std::string redirected = redirect_path(path);
    const char* actual_path = redirected.empty() ? path : redirected.c_str();

    return real_readlink(actual_path, buf, bufsiz);
}

/**
 * Hooked fopen() — redirects file paths to sandbox, hides paths.
 */
static FILE* hooked_fopen(const char* path, const char* mode) {
    if (is_path_hidden(path)) {
        errno = ENOENT;
        return nullptr;
    }

    // Spoof /proc/self/cmdline
    if (is_proc_self_path(path) && strcmp(path, "/proc/self/cmdline") == 0 &&
        !g_spoofed_package_name.empty()) {
        // Create a temp file with spoofed cmdline
        FILE* tmp = tmpfile();
        if (tmp) {
            fwrite(g_spoofed_package_name.c_str(), 1, g_spoofed_package_name.length() + 1, tmp);
            fseek(tmp, 0, SEEK_SET);
            return tmp;
        }
    }

    // Spoof /proc/self/maps — filter out NEXTVM entries
    if (is_proc_self_path(path) && strcmp(path, "/proc/self/maps") == 0) {
        FILE* real_maps = real_fopen(path, mode);
        if (real_maps) {
            FILE* tmp = tmpfile();
            if (tmp) {
                char line[1024];
                while (fgets(line, sizeof(line), real_maps)) {
                    // Hide entries containing our library names
                    if (strstr(line, "nextvm") == nullptr &&
                        strstr(line, "lsplant") == nullptr &&
                        strstr(line, "dobby") == nullptr &&
                        strstr(line, "bhook") == nullptr &&
                        strstr(line, "xhook") == nullptr &&
                        strstr(line, "substrate") == nullptr &&
                        strstr(line, "xposed") == nullptr) {
                        fputs(line, tmp);
                    }
                }
                fclose(real_maps);
                fseek(tmp, 0, SEEK_SET);
                return tmp;
            }
            fclose(real_maps);
        }
    }

    std::string redirected = redirect_path(path);
    const char* actual_path = redirected.empty() ? path : redirected.c_str();

    if (!redirected.empty()) {
        LOGD("fopen: %s -> %s", path, actual_path);
    }

    return real_fopen(actual_path, mode);
}

/**
 * Hooked __system_property_get() — returns spoofed device properties.
 */
static int hooked_system_property_get(const char* name, char* value) {
    // Check if we have a spoofed value for this property
    if (name != nullptr && !g_property_spoofs.empty()) {
        std::string prop_name(name);
        auto it = g_property_spoofs.find(prop_name);
        if (it != g_property_spoofs.end()) {
            const std::string& spoofed = it->second;
            size_t len = spoofed.length();
            if (len > 91) len = 91; // PROP_VALUE_MAX - 1
            memcpy(value, spoofed.c_str(), len);
            value[len] = '\0';
            LOGD("property_get: %s -> %s (spoofed)", name, value);
            return static_cast<int>(len);
        }
    }

    // No spoof — call real implementation
    return real_system_property_get(name, value);
}

// ==================== GOT/PLT Hook Installation ====================

// SIGSEGV-safe memory probe: returns false if the address is not readable
static thread_local sigjmp_buf g_probe_jmp;
static thread_local volatile bool g_probing = false;

static void probe_signal_handler(int sig) {
    if (g_probing) {
        siglongjmp(g_probe_jmp, 1);
    }
}

/**
 * Check if a memory address is safely readable using mincore() as a fast check,
 * falling back to a SIGSEGV-safe read probe.
 */
static bool is_address_readable(const void* addr, size_t len) {
    if (addr == nullptr) return false;
    uintptr_t uaddr = reinterpret_cast<uintptr_t>(addr);
    // Reject addresses in the first 64KB (likely relative offsets, not real pointers)
    if (uaddr < 0x10000) return false;

    // Try writing to /proc/self/mem trick — but simplest: use msync as a probe
    size_t page_size = static_cast<size_t>(sysconf(_SC_PAGESIZE));
    uintptr_t page_start = uaddr & ~(page_size - 1);
    // msync on a non-mapped page returns -1 with ENOMEM
    if (msync(reinterpret_cast<void*>(page_start), page_size, MS_ASYNC) != 0) {
        return false;
    }
    // Also check the end of the range
    uintptr_t end_page = (uaddr + len - 1) & ~(page_size - 1);
    if (end_page != page_start) {
        if (msync(reinterpret_cast<void*>(end_page), page_size, MS_ASYNC) != 0) {
            return false;
        }
    }
    return true;
}

/**
 * Hook entry: maps a symbol name to our hook function and the saved original pointer.
 */
struct HookEntry {
    const char* symbol;
    void* hook_func;
    void** original_func;
};

static HookEntry g_hook_entries[] = {
    {"open",                    (void*)hooked_open,                 (void**)&real_open},
    {"openat",                  (void*)hooked_openat,               (void**)&real_openat},
    {"access",                  (void*)hooked_access,               (void**)&real_access},
    {"stat",                    (void*)hooked_stat,                 (void**)&real_stat},
    {"lstat",                   (void*)hooked_lstat,                (void**)&real_lstat},
    {"readlink",                (void*)hooked_readlink,             (void**)&real_readlink},
    {"fopen",                   (void*)hooked_fopen,                (void**)&real_fopen},
    {"__system_property_get",   (void*)hooked_system_property_get,  (void**)&real_system_property_get},
};
static constexpr int g_hook_count = sizeof(g_hook_entries) / sizeof(g_hook_entries[0]);

/**
 * Replace a single GOT entry.
 * Makes the GOT page writable, swaps the pointer, restores permissions.
 *
 * @param got_addr  Pointer to the GOT entry to patch
 * @param new_func  The hook function to install
 * @param old_func  Output: the original function pointer that was in the GOT
 * @return true on success
 */
static bool patch_got_entry(void** got_addr, void* new_func, void** old_func) {
    if (got_addr == nullptr) return false;
    // Validate the GOT address is in readable memory before dereferencing
    if (!is_address_readable(got_addr, sizeof(void*))) {
        LOGW("GOT addr %p not readable, skipping", got_addr);
        return false;
    }
    if (*got_addr == nullptr) return false;

    // Don't patch if already pointing to our hook
    if (*got_addr == new_func) return true;

    // Save the original
    if (old_func && *old_func == nullptr) {
        *old_func = *got_addr;
    }

    // Calculate page-aligned address
    size_t page_size = static_cast<size_t>(sysconf(_SC_PAGESIZE));
    uintptr_t page_start = reinterpret_cast<uintptr_t>(got_addr) & ~(page_size - 1);

    // Make page writable
    if (mprotect(reinterpret_cast<void*>(page_start), page_size,
                 PROT_READ | PROT_WRITE) != 0) {
        LOGE("mprotect RW failed for GOT entry at %p: %s",
             got_addr, strerror(errno));
        return false;
    }

    // Patch the GOT entry
    *got_addr = new_func;

    // Restore page permissions (read-only)
    mprotect(reinterpret_cast<void*>(page_start), page_size, PROT_READ);

    return true;
}

/**
 * Process a single loaded shared object: find its GOT and patch entries.
 *
 * Parses the ELF dynamic section to locate .rel.plt / .rela.plt relocations,
 * then patches GOT entries for our target symbols.
 */
static void patch_library_got(uintptr_t base_addr, const char* lib_name,
                               const ElfW(Dyn)* dynamic) {
    if (dynamic == nullptr) return;

    // Find required dynamic entries
    const ElfW(Sym)* symtab = nullptr;
    const char* strtab = nullptr;
    const ElfW(Rel)* rel_plt = nullptr;
    const ElfW(Rela)* rela_plt = nullptr;
    size_t rel_plt_size = 0;
    size_t rela_plt_size = 0;
    bool use_rela = false;

    for (const ElfW(Dyn)* dyn = dynamic; dyn->d_tag != DT_NULL; dyn++) {
        switch (dyn->d_tag) {
            case DT_SYMTAB:
                symtab = reinterpret_cast<const ElfW(Sym)*>(dyn->d_un.d_ptr);
                break;
            case DT_STRTAB:
                strtab = reinterpret_cast<const char*>(dyn->d_un.d_ptr);
                break;
            case DT_JMPREL:
                // Could be REL or RELA depending on DT_PLTREL
                rel_plt = reinterpret_cast<const ElfW(Rel)*>(dyn->d_un.d_ptr);
                rela_plt = reinterpret_cast<const ElfW(Rela)*>(dyn->d_un.d_ptr);
                break;
            case DT_PLTRELSZ:
                rel_plt_size = dyn->d_un.d_val;
                rela_plt_size = dyn->d_un.d_val;
                break;
            case DT_PLTREL:
                use_rela = (dyn->d_un.d_val == DT_RELA);
                break;
        }
    }

    if (symtab == nullptr || strtab == nullptr) return;
    if (rel_plt == nullptr && rela_plt == nullptr) return;

    // Validate symtab/strtab pointers — on some Android versions/libraries,
    // d_ptr values are relative offsets from base_addr rather than absolute addresses.
    // Addresses below 0x10000 are almost certainly relative offsets, not real pointers.
    uintptr_t symtab_addr = reinterpret_cast<uintptr_t>(symtab);
    uintptr_t strtab_addr = reinterpret_cast<uintptr_t>(strtab);

    if (symtab_addr < 0x10000 || strtab_addr < 0x10000) {
        if (base_addr > 0) {
            // Try adjusting by adding base_addr
            if (symtab_addr < 0x10000)
                symtab = reinterpret_cast<const ElfW(Sym)*>(base_addr + symtab_addr);
            if (strtab_addr < 0x10000)
                strtab = reinterpret_cast<const char*>(base_addr + strtab_addr);
        } else {
            LOGW("Skipping %s: symtab/strtab appear to be relative offsets but base_addr=0", lib_name);
            return;
        }
    }

    // Verify symtab and strtab are actually readable memory
    if (!is_address_readable(symtab, sizeof(ElfW(Sym))) ||
        !is_address_readable(strtab, 1)) {
        LOGW("Skipping %s: symtab(%p) or strtab(%p) not in readable memory",
             lib_name, symtab, strtab);
        return;
    }

    // Also validate relocation table pointer
    if (use_rela && rela_plt) {
        if (!is_address_readable(rela_plt, sizeof(ElfW(Rela)))) {
            LOGW("Skipping %s: rela_plt(%p) not readable", lib_name, rela_plt);
            return;
        }
    } else if (rel_plt) {
        if (!is_address_readable(rel_plt, sizeof(ElfW(Rel)))) {
            LOGW("Skipping %s: rel_plt(%p) not readable", lib_name, rel_plt);
            return;
        }
    }

    if (use_rela && rela_plt != nullptr && rela_plt_size > 0) {
        // Process RELA entries (arm64, x86_64)
        size_t count = rela_plt_size / sizeof(ElfW(Rela));
        for (size_t i = 0; i < count; i++) {
            const ElfW(Rela)& entry = rela_plt[i];
            size_t sym_idx = ELF_R_SYM(entry.r_info);
            if (sym_idx == 0) continue;

            // Validate sym_idx access is safe
            const ElfW(Sym)* sym_entry_ptr = &symtab[sym_idx];
            if (!is_address_readable(sym_entry_ptr, sizeof(ElfW(Sym)))) continue;

            uint32_t st_name = symtab[sym_idx].st_name;
            const char* sym_name = strtab + st_name;
            if (!is_address_readable(sym_name, 1)) continue;
            if (sym_name[0] == '\0') continue;

            // Check if this symbol is one we want to hook
            for (int h = 0; h < g_hook_count; h++) {
                if (strcmp(sym_name, g_hook_entries[h].symbol) == 0) {
                    void** got_addr = reinterpret_cast<void**>(
                        base_addr + entry.r_offset);
                    if (patch_got_entry(got_addr, g_hook_entries[h].hook_func,
                                       g_hook_entries[h].original_func)) {
                        LOGD("Hooked %s in %s (RELA)", sym_name, lib_name);
                    }
                    break;
                }
            }
        }
    } else if (rel_plt != nullptr && rel_plt_size > 0) {
        // Process REL entries (arm32, x86)
        size_t count = rel_plt_size / sizeof(ElfW(Rel));
        for (size_t i = 0; i < count; i++) {
            const ElfW(Rel)& entry = rel_plt[i];
            size_t sym_idx = ELF_R_SYM(entry.r_info);
            if (sym_idx == 0) continue;

            // Validate sym_idx access is safe
            const ElfW(Sym)* sym_entry_ptr = &symtab[sym_idx];
            if (!is_address_readable(sym_entry_ptr, sizeof(ElfW(Sym)))) continue;

            uint32_t st_name = symtab[sym_idx].st_name;
            const char* sym_name = strtab + st_name;
            if (!is_address_readable(sym_name, 1)) continue;
            if (sym_name[0] == '\0') continue;

            for (int h = 0; h < g_hook_count; h++) {
                if (strcmp(sym_name, g_hook_entries[h].symbol) == 0) {
                    void** got_addr = reinterpret_cast<void**>(
                        base_addr + entry.r_offset);
                    if (patch_got_entry(got_addr, g_hook_entries[h].hook_func,
                                       g_hook_entries[h].original_func)) {
                        LOGD("Hooked %s in %s (REL)", sym_name, lib_name);
                    }
                    break;
                }
            }
        }
    }
}

/**
 * dl_iterate_phdr callback — called for each loaded shared object.
 * We find the PT_DYNAMIC segment and use it to locate GOT entries.
 */
static int install_hooks_callback(struct dl_phdr_info* info, size_t /*size*/, void* /*data*/) {
    if (info->dlpi_name == nullptr) return 0;

    const char* name = info->dlpi_name;

    // Skip empty names (main executable) and our own library
    if (name[0] == '\0') return 0;
    if (strstr(name, "libnextvm") != nullptr) return 0;

    // Skip the linker itself
    if (strstr(name, "linker") != nullptr) return 0;

    // Skip vDSO and other kernel-mapped objects (no valid GOT to patch)
    if (strstr(name, "vdso") != nullptr) return 0;
    if (strstr(name, "[vdso]") != nullptr) return 0;

    // Skip libraries that commonly have RELRO-protected GOTs causing issues
    if (strstr(name, "libart.so") != nullptr) return 0;
    if (strstr(name, "libhwbinder") != nullptr) return 0;

    // Find PT_DYNAMIC segment
    const ElfW(Dyn)* dynamic = nullptr;
    for (int i = 0; i < info->dlpi_phnum; i++) {
        if (info->dlpi_phdr[i].p_type == PT_DYNAMIC) {
            dynamic = reinterpret_cast<const ElfW(Dyn)*>(
                info->dlpi_addr + info->dlpi_phdr[i].p_vaddr);
            break;
        }
    }

    if (dynamic != nullptr) {
        patch_library_got(info->dlpi_addr, name, dynamic);
    }

    return 0;
}

/**
 * Install GOT/PLT hooks into all currently loaded shared objects.
 */
static bool install_plt_hooks() {
    LOGI("Installing PLT hooks via GOT patching...");

    // Resolve original function pointers first (before hooking)
    if (!real_open)
        real_open = (orig_open_t)dlsym(RTLD_DEFAULT, "open");
    if (!real_openat)
        real_openat = (orig_openat_t)dlsym(RTLD_DEFAULT, "openat");
    if (!real_access)
        real_access = (orig_access_t)dlsym(RTLD_DEFAULT, "access");
    if (!real_stat)
        real_stat = (orig_stat_t)dlsym(RTLD_DEFAULT, "stat");
    if (!real_lstat)
        real_lstat = (orig_lstat_t)dlsym(RTLD_DEFAULT, "lstat");
    if (!real_readlink)
        real_readlink = (orig_readlink_t)dlsym(RTLD_DEFAULT, "readlink");
    if (!real_fopen)
        real_fopen = (orig_fopen_t)dlsym(RTLD_DEFAULT, "fopen");
    if (!real_system_property_get)
        real_system_property_get = (orig_system_property_get_t)dlsym(
            RTLD_DEFAULT, "__system_property_get");

    if (!real_open || !real_openat || !real_access || !real_stat) {
        LOGE("Failed to resolve one or more libc functions via dlsym");
        return false;
    }

    // Iterate all loaded libraries and patch GOT entries
    dl_iterate_phdr(install_hooks_callback, nullptr);

    LOGI("PLT hooks installed successfully");
    return true;
}

// ==================== JNI Bridge ====================

extern "C" {

/**
 * Initialize the native hook engine.
 * Resolves original function pointers and installs GOT/PLT hooks.
 */
JNIEXPORT jboolean JNICALL
Java_com_nextvm_core_hook_NativeHookBridge_nativeInit(
    JNIEnv* env, jobject thiz)
{
    (void)env; (void)thiz;
    std::lock_guard<std::mutex> lock(g_mutex);

    if (g_initialized) {
        LOGI("Native hook engine already initialized");
        return JNI_TRUE;
    }

    LOGI("Initializing NEXTVM native hook engine...");

    // Install PLT hooks via GOT patching
    g_hooks_installed = install_plt_hooks();
    if (!g_hooks_installed) {
        LOGW("GOT/PLT hooking failed — falling back to Java-level hooks only");
    }

    g_initialized = true;
    LOGI("Native hook engine initialized (hooks_installed=%d)", g_hooks_installed);
    return JNI_TRUE;
}

/**
 * Add a path redirection rule.
 */
JNIEXPORT void JNICALL
Java_com_nextvm_core_hook_NativeHookBridge_nativeAddPathRedirection(
    JNIEnv* env, jobject thiz, jstring sourcePath, jstring targetPath)
{
    (void)thiz;
    const char* src = env->GetStringUTFChars(sourcePath, nullptr);
    const char* tgt = env->GetStringUTFChars(targetPath, nullptr);

    if (src && tgt) {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_path_redirects[std::string(src)] = std::string(tgt);
        LOGI("Path redirect added: %s -> %s", src, tgt);
    }

    if (src) env->ReleaseStringUTFChars(sourcePath, src);
    if (tgt) env->ReleaseStringUTFChars(targetPath, tgt);
}

/**
 * Remove a path redirection rule.
 */
JNIEXPORT void JNICALL
Java_com_nextvm_core_hook_NativeHookBridge_nativeRemovePathRedirection(
    JNIEnv* env, jobject thiz, jstring sourcePath)
{
    (void)thiz;
    const char* src = env->GetStringUTFChars(sourcePath, nullptr);

    if (src) {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_path_redirects.erase(std::string(src));
        LOGI("Path redirect removed: %s", src);
    }

    if (src) env->ReleaseStringUTFChars(sourcePath, src);
}

/**
 * Clear all path redirection rules.
 */
JNIEXPORT void JNICALL
Java_com_nextvm_core_hook_NativeHookBridge_nativeClearPathRedirections(
    JNIEnv* env, jobject thiz)
{
    (void)env; (void)thiz;
    std::lock_guard<std::mutex> lock(g_mutex);
    g_path_redirects.clear();
    LOGI("All path redirects cleared");
}

/**
 * Set up /proc/self spoofing at native level.
 * Spoofs cmdline, comm, exe, and maps filtering.
 */
JNIEXPORT void JNICALL
Java_com_nextvm_core_hook_NativeHookBridge_nativeSpoofProcSelf(
    JNIEnv* env, jobject thiz, jint pid, jstring packageName)
{
    (void)thiz;
    const char* pkg = env->GetStringUTFChars(packageName, nullptr);
    if (pkg) {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_spoofed_pid = pid;
        g_spoofed_package_name = std::string(pkg);
        LOGI("/proc/self spoof set: pid=%d, pkg=%s", pid, pkg);
        env->ReleaseStringUTFChars(packageName, pkg);
    }
}

/**
 * Override a system property at native level.
 * MUST match Kotlin: nativeSpoofSystemProperty(key: String, value: String)
 */
JNIEXPORT void JNICALL
Java_com_nextvm_core_hook_NativeHookBridge_nativeSpoofSystemProperty(
    JNIEnv* env, jobject thiz, jstring key, jstring value)
{
    (void)thiz;
    const char* k = env->GetStringUTFChars(key, nullptr);
    const char* v = env->GetStringUTFChars(value, nullptr);

    if (k && v) {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_property_spoofs[std::string(k)] = std::string(v);
        LOGI("Property spoof set: %s -> %s", k, v);
    }

    if (k) env->ReleaseStringUTFChars(key, k);
    if (v) env->ReleaseStringUTFChars(value, v);
}

/**
 * Hide a path at native level (return ENOENT on access).
 */
JNIEXPORT void JNICALL
Java_com_nextvm_core_hook_NativeHookBridge_nativeHidePath(
    JNIEnv* env, jobject thiz, jstring path)
{
    (void)thiz;
    const char* p = env->GetStringUTFChars(path, nullptr);
    if (p) {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_hidden_paths.insert(std::string(p));
        LOGD("Path hidden: %s", p);
        env->ReleaseStringUTFChars(path, p);
    }
}

/**
 * Unhide a path at native level.
 */
JNIEXPORT void JNICALL
Java_com_nextvm_core_hook_NativeHookBridge_nativeUnhidePath(
    JNIEnv* env, jobject thiz, jstring path)
{
    (void)thiz;
    const char* p = env->GetStringUTFChars(path, nullptr);
    if (p) {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_hidden_paths.erase(std::string(p));
        LOGD("Path unhidden: %s", p);
        env->ReleaseStringUTFChars(path, p);
    }
}

/**
 * Clean up all native hooks and state.
 */
JNIEXPORT void JNICALL
Java_com_nextvm_core_hook_NativeHookBridge_nativeCleanup(
    JNIEnv* env, jobject thiz)
{
    (void)env; (void)thiz;
    std::lock_guard<std::mutex> lock(g_mutex);
    g_path_redirects.clear();
    g_property_spoofs.clear();
    g_hidden_paths.clear();
    g_spoofed_pid = -1;
    g_spoofed_package_name.clear();
    g_host_data_prefix.clear();
    g_virtual_data_root.clear();
    // Note: GOT patches remain in place but do nothing without redirect rules
    LOGI("Native hook state cleaned up");
}

/**
 * Set the host data directory prefix.
 */
JNIEXPORT void JNICALL
Java_com_nextvm_core_hook_NativeHookBridge_nativeSetHostDataPrefix(
    JNIEnv* env, jobject thiz, jstring prefix)
{
    (void)thiz;
    const char* p = env->GetStringUTFChars(prefix, nullptr);
    if (p) {
        g_host_data_prefix = std::string(p);
        env->ReleaseStringUTFChars(prefix, p);
        LOGI("Host data prefix: %s", g_host_data_prefix.c_str());
    }
}

/**
 * Set the virtual data root directory.
 */
JNIEXPORT void JNICALL
Java_com_nextvm_core_hook_NativeHookBridge_nativeSetVirtualDataRoot(
    JNIEnv* env, jobject thiz, jstring root)
{
    (void)thiz;
    const char* r = env->GetStringUTFChars(root, nullptr);
    if (r) {
        g_virtual_data_root = std::string(r);
        env->ReleaseStringUTFChars(root, r);
        LOGI("Virtual data root: %s", g_virtual_data_root.c_str());
    }
}

/**
 * Get the current redirect count (for debugging).
 */
JNIEXPORT jint JNICALL
Java_com_nextvm_core_hook_NativeHookBridge_nativeGetRedirectCount(
    JNIEnv* env, jobject thiz)
{
    (void)env; (void)thiz;
    return static_cast<jint>(g_path_redirects.size());
}

/**
 * Get the current property spoof count (for debugging).
 */
JNIEXPORT jint JNICALL
Java_com_nextvm_core_hook_NativeHookBridge_nativeGetPropertySpoofCount(
    JNIEnv* env, jobject thiz)
{
    (void)env; (void)thiz;
    return static_cast<jint>(g_property_spoofs.size());
}

/**
 * Check if the native engine is initialized.
 */
JNIEXPORT jboolean JNICALL
Java_com_nextvm_core_hook_NativeHookBridge_nativeIsInitialized(
    JNIEnv* env, jobject thiz)
{
    (void)env; (void)thiz;
    return g_initialized ? JNI_TRUE : JNI_FALSE;
}

// ==================== Runtime.nativeLoad Hook ====================
//
// Jiagu/360-protected apps call Runtime.load0() which delegates to
// Runtime.nativeLoad(String filename, ClassLoader loader, Class caller).
// In the virtual environment, the "caller" Class argument becomes null
// because Jiagu's native code calls System.loadLibrary via JNI reflection
// from a thread where the caller class cannot be resolved.
//
// ART's CheckJNI mode then calls GetObjectArrayElement on NULL (the
// ProtectionDomain array derived from the null caller) → SIGABRT.
//
// Fix: Hook Runtime.nativeLoad at JNI registration level via RegisterNatives.
// If caller==null, synthesize a non-null Class from the classLoader or use
// java.lang.Runtime as a fallback caller.

// Original native implementation saved via method registration replacement
static void* g_orig_nativeLoad_fn = nullptr;

// Type alias matching ART's native signature for Runtime.nativeLoad
// static jni: (JNIEnv*, jclass, jstring filename, jobject classLoader, jclass caller) -> jstring
typedef jstring (*NativeLoadFn)(JNIEnv*, jclass, jstring, jobject, jclass);

static jstring hooked_nativeLoad(JNIEnv* env, jclass runtimeClass,
                                  jstring filename, jobject classLoader, jclass caller) {
    if (caller == nullptr) {
        LOGI("Runtime.nativeLoad intercepted with null caller — fixing");

        // Strategy 1: Derive a class from the classLoader
        if (classLoader != nullptr) {
            // Try ClassLoader.loadClass("java.lang.Object") to get a valid Class
            jclass clsLoaderClass = env->GetObjectClass(classLoader);
            if (clsLoaderClass != nullptr) {
                jmethodID loadClassMethod = env->GetMethodID(
                    clsLoaderClass, "loadClass",
                    "(Ljava/lang/String;)Ljava/lang/Class;");
                if (loadClassMethod != nullptr) {
                    jstring objClassName = env->NewStringUTF("java.lang.Object");
                    if (objClassName != nullptr) {
                        // Clear any pending exception before calling loadClass
                        if (env->ExceptionCheck()) env->ExceptionClear();

                        jobject loadedClass = env->CallObjectMethod(
                            classLoader, loadClassMethod, objClassName);
                        env->DeleteLocalRef(objClassName);

                        if (env->ExceptionCheck()) {
                            env->ExceptionClear();
                        } else if (loadedClass != nullptr) {
                            caller = static_cast<jclass>(loadedClass);
                            LOGI("Runtime.nativeLoad: fixed null caller via classLoader → java.lang.Object");
                        }
                    }
                }
                env->DeleteLocalRef(clsLoaderClass);
            }
        }

        // Strategy 2: Fallback — use java.lang.Runtime itself as the caller
        if (caller == nullptr) {
            caller = runtimeClass;
            LOGI("Runtime.nativeLoad: fixed null caller → java.lang.Runtime (fallback)");
        }
    }

    // Call original implementation
    if (g_orig_nativeLoad_fn != nullptr) {
        return ((NativeLoadFn)g_orig_nativeLoad_fn)(env, runtimeClass, filename, classLoader, caller);
    }

    // If we somehow don't have the original, try dlsym as last resort
    LOGE("Runtime.nativeLoad: no original function pointer — cannot forward call");
    return nullptr;
}

/**
 * Install the Runtime.nativeLoad hook using RegisterNatives.
 *
 * Approach:
 * 1. Find java.lang.Runtime class
 * 2. Save original nativeLoad function pointer via GetMethodID + JNI internal lookup
 * 3. Register our hooked version via RegisterNatives
 */
static bool installNativeLoadHook(JNIEnv* env) {
    // Find java.lang.Runtime
    jclass runtimeClass = env->FindClass("java/lang/Runtime");
    if (runtimeClass == nullptr) {
        LOGE("installNativeLoadHook: cannot find java/lang/Runtime");
        if (env->ExceptionCheck()) env->ExceptionClear();
        return false;
    }

    // Try to get the original native function pointer from libart.so
    // The symbol name varies by ART version but we try the common ones
    void* libart = dlopen("libart.so", RTLD_NOLOAD);
    if (libart == nullptr) {
        LOGW("installNativeLoadHook: libart.so not found via RTLD_NOLOAD, trying dlopen");
        libart = dlopen("libart.so", RTLD_NOW);
    }

    if (libart != nullptr) {
        // ART internal symbol for Runtime_nativeLoad (static JNI method)
        // Try multiple symbol names as it varies across Android versions
        const char* symbols[] = {
            "_ZN3artL18Runtime_nativeLoadEP7_JNIEnvP7_jclassP8_jstringP8_jobjectS5_",
            "Runtime_nativeLoad",
            nullptr
        };

        for (int i = 0; symbols[i] != nullptr; i++) {
            g_orig_nativeLoad_fn = dlsym(libart, symbols[i]);
            if (g_orig_nativeLoad_fn != nullptr) {
                LOGI("installNativeLoadHook: found original at symbol '%s'", symbols[i]);
                break;
            }
        }
    }

    if (g_orig_nativeLoad_fn == nullptr) {
        // Fallback: use env->GetMethodID to verify the method exists,
        // then rely on RegisterNatives to stash the original internally.
        // We'll use a JNI trick: register, then we ARE the native now.
        // To call original, we need the old function pointer.
        // Without it, we try a different approach — call doLoad on Runtime directly.

        // Try using JNI GetStaticMethodID to verify method exists
        jmethodID nativeLoadMethod = env->GetStaticMethodID(
            runtimeClass, "nativeLoad",
            "(Ljava/lang/String;Ljava/lang/ClassLoader;Ljava/lang/Class;)Ljava/lang/String;");
        if (nativeLoadMethod == nullptr) {
            LOGE("installNativeLoadHook: Runtime.nativeLoad method not found");
            if (env->ExceptionCheck()) env->ExceptionClear();
            env->DeleteLocalRef(runtimeClass);
            return false;
        }

        // Without the original symbol, we can still hook but won't be able to forward.
        // Use a wrapper approach: save the method ID and call via JNI CallStatic
        // But this would recursively call our hook... So we MUST have the original.
        LOGE("installNativeLoadHook: cannot find original native symbol in libart.so");
        env->DeleteLocalRef(runtimeClass);
        return false;
    }

    // Register our hooked version
    JNINativeMethod methods[] = {
        {
            const_cast<char*>("nativeLoad"),
            const_cast<char*>("(Ljava/lang/String;Ljava/lang/ClassLoader;Ljava/lang/Class;)Ljava/lang/String;"),
            reinterpret_cast<void*>(hooked_nativeLoad)
        }
    };

    jint result = env->RegisterNatives(runtimeClass, methods, 1);
    env->DeleteLocalRef(runtimeClass);

    if (result != JNI_OK) {
        LOGE("installNativeLoadHook: RegisterNatives failed with code %d", result);
        g_orig_nativeLoad_fn = nullptr;
        return false;
    }

    LOGI("installNativeLoadHook: SUCCESS — Runtime.nativeLoad hooked via RegisterNatives");
    return true;
}

/**
 * JNI bridge for NativeHookBridge.nativeInstallRuntimeLoadHook()
 */
JNIEXPORT jboolean JNICALL
Java_com_nextvm_core_hook_NativeHookBridge_nativeInstallRuntimeLoadHook(
    JNIEnv* env, jobject thiz)
{
    (void)thiz;
    return installNativeLoadHook(env) ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
