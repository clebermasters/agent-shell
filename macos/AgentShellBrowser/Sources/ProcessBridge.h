#include <libproc.h>
#include <sys/proc_info.h>
#include <stdint.h>
#include <sys/sysctl.h>
#include <stdlib.h>
#include <string.h>

static inline uint64_t ASProcessBirth(int32_t pid) {
    struct proc_bsdinfo info = {0};
    if (pid <= 0 || proc_pidinfo(pid, PROC_PIDTBSDINFO, 0, &info, sizeof(info)) != sizeof(info)
        || info.pbi_pid != (uint32_t)pid || info.pbi_start_tvusec >= 1000000
        || info.pbi_start_tvsec > UINT64_MAX / 1000000) return 0;
    return info.pbi_start_tvsec * 1000000 + info.pbi_start_tvusec;
}

/* Check arguments, not a process title or a cached PID, before targeting input.
 * KERN_PROCARGS2's environment follows argv and is never inspected. */
static inline int ASProcessUsesProfile(int32_t pid, const char *profile) {
    uint64_t before = ASProcessBirth(pid);
    if (!before || !profile) return 0;
    int mib[] = {CTL_KERN, KERN_PROCARGS2, pid};
    size_t length = 0;
    if (sysctl(mib, 3, NULL, &length, NULL, 0) != 0 || length < sizeof(int) || length > 4 * 1024 * 1024) return 0;
    unsigned char *buffer = malloc(length);
    if (!buffer) return 0;
    size_t capacity = length;
    if (sysctl(mib, 3, buffer, &length, NULL, 0) != 0 || length < sizeof(int) || length > capacity) { free(buffer); return 0; }
    int argc = 0; memcpy(&argc, buffer, sizeof(argc));
    unsigned char *cursor = buffer + sizeof(argc), *end = buffer + length;
    unsigned char *zero = memchr(cursor, 0, (size_t)(end - cursor));
    if (!zero || argc < 1 || (size_t)argc > length) { free(buffer); return 0; }
    cursor = zero + 1;
    while (cursor < end && !*cursor) ++cursor;
    const char prefix[] = "--user-data-dir=";
    int matched = 0, next_is_profile = 0;
    for (int index = 0; index < argc && cursor < end; ++index) {
        zero = memchr(cursor, 0, (size_t)(end - cursor));
        if (!zero) break;
        const char *arg = (const char *)cursor;
        if (next_is_profile && strcmp(arg, profile) == 0) matched = 1;
        if (strncmp(arg, prefix, sizeof(prefix)-1) == 0 && strcmp(arg+sizeof(prefix)-1, profile) == 0) matched = 1;
        next_is_profile = strcmp(arg, "--user-data-dir") == 0;
        cursor = zero + 1;
    }
    free(buffer);
    return matched && ASProcessBirth(pid) == before;
}
