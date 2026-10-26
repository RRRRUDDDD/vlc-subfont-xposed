/*
 * Native implementation of the libvlc.so font-path memory patch.
 * Some vendor kernels (e.g. Samsung) forbid writing /proc/[pid]/mem from app
 * domains, so this uses mprotect + memcpy instead: temporarily add
 * PROT_WRITE to the private file mapping (COW), write, then restore.
 */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

static long page_size(void) {
    static long ps;
    if (ps <= 0) {
        ps = sysconf(_SC_PAGESIZE);
        if (ps <= 0) ps = 4096;
    }
    return ps;
}

/* Write rep/rlen at target; exec keeps PROT_EXEC on restore. */
static int patch_target(uintptr_t target, const uint8_t *rep, size_t rlen, int exec) {
    long ps = page_size();
    uintptr_t pg = target & ~((uintptr_t) ps - 1);
    size_t span = (size_t) (target - pg) + rlen;
    size_t plen = (span + (size_t) ps - 1) / (size_t) ps * (size_t) ps;
    int prot = PROT_READ | (exec ? PROT_EXEC : 0);
    if (mprotect((void *) pg, plen, prot | PROT_WRITE) != 0) return -1;
    memcpy((void *) target, rep, rlen);
    if (mprotect((void *) pg, plen, prot) != 0) return -1;
    return 0;
}

static uint8_t *dup_jbytes(JNIEnv *env, jbyteArray arr, jsize len) {
    uint8_t *out = malloc((size_t) len);
    if (out == NULL) return NULL;
    (*env)->GetByteArrayRegion(env, arr, 0, len, (jbyte *) out);
    return out;
}

/*
 * Find and replace every pattern occurrence within [start, end).
 * Returns long[npat*2]: [count0, firstAddr0, count1, firstAddr1, ...],
 * or NULL on error.
 */
JNIEXPORT jlongArray JNICALL
Java_com_vlcsubfont_module_xposed_MemoryPatcher_nativePatchRange(
        JNIEnv *env, jclass clazz, jlong jstart, jlong jend, jboolean jexec,
        jobjectArray jpatterns, jobjectArray jrepls) {
    (void) clazz;
    uintptr_t start = (uintptr_t) jstart, end = (uintptr_t) jend;
    if (end <= start) return NULL;
    int npat = (*env)->GetArrayLength(env, jpatterns);
    if (npat <= 0 || (*env)->GetArrayLength(env, jrepls) != npat) return NULL;

    const uint8_t **pat = calloc((size_t) npat, sizeof(*pat));
    size_t *plen = calloc((size_t) npat, sizeof(*plen));
    const uint8_t **rep = calloc((size_t) npat, sizeof(*rep));
    size_t *rlen = calloc((size_t) npat, sizeof(*rlen));
    jlong *res = calloc((size_t) npat * 2, sizeof(*res));
    if (!pat || !plen || !rep || !rlen || !res) goto oom;

    for (int p = 0; p < npat; p++) {
        jbyteArray pa = (jbyteArray) (*env)->GetObjectArrayElement(env, jpatterns, p);
        jbyteArray ra = (jbyteArray) (*env)->GetObjectArrayElement(env, jrepls, p);
        if (pa == NULL || ra == NULL) goto oom;
        jsize pl = (*env)->GetArrayLength(env, pa);
        jsize rl = (*env)->GetArrayLength(env, ra);
        /* replacement may be 1 byte longer than the pattern (trailing NUL) */
        if (pl <= 0 || rl <= 0 || rl > pl + 1) goto oom;
        pat[p] = dup_jbytes(env, pa, pl);
        rep[p] = dup_jbytes(env, ra, rl);
        (*env)->DeleteLocalRef(env, pa);
        (*env)->DeleteLocalRef(env, ra);
        if (pat[p] == NULL || rep[p] == NULL) goto oom;
        plen[p] = (size_t) pl;
        rlen[p] = (size_t) rl;
    }

    {
        size_t overlap = 0;
        for (int p = 0; p < npat; p++) {
            if (plen[p] > overlap) overlap = plen[p];
        }
        size_t chunk = 1u << 20;
        uint8_t *buf = malloc(chunk + overlap);
        if (buf == NULL) goto oom;
        int err = 0;
        uintptr_t addr = start;
        size_t carry = 0;
        while (!err && addr < end) {
            size_t want = (size_t) (end - addr);
            if (want > chunk) want = chunk;
            /* ranges come from /proc/self/maps with 'r' perms: safe to read */
            memcpy(buf + carry, (const void *) addr, want);
            size_t window = carry + want;
            for (int p = 0; p < npat; p++) {
                if (window < plen[p]) continue;
                size_t i = 0;
                while (i + plen[p] <= window) {
                    const uint8_t *f = memmem(buf + i, window - i, pat[p], plen[p]);
                    if (f == NULL) break;
                    size_t off = (size_t) (f - buf);
                    uintptr_t target = addr - carry + off;
                    if (patch_target(target, rep[p], rlen[p], jexec != JNI_FALSE) == 0) {
                        res[2 * p]++;
                        if (res[2 * p] == 1) res[2 * p + 1] = (jlong) target;
                    } else {
                        err = 1;
                    }
                    i = off + plen[p];
                }
            }
            carry = overlap < window ? overlap : window;
            memmove(buf, buf + window - carry, carry);
            addr += want;
        }
        free(buf);

        jlongArray out = NULL;
        if (!err) {
            out = (*env)->NewLongArray(env, npat * 2);
            if (out != NULL) {
                (*env)->SetLongArrayRegion(env, out, 0, npat * 2, res);
            }
        }
        for (int p = 0; p < npat; p++) {
            free((void *) pat[p]);
            free((void *) rep[p]);
        }
        free(pat); free(plen); free(rep); free(rlen); free(res);
        return out;
    }

oom:
    if (pat != NULL) {
        for (int p = 0; p < npat; p++) {
            free((void *) pat[p]);
            free((void *) rep[p]);
        }
    }
    free(pat); free(plen); free(rep); free(rlen); free(res);
    return NULL;
}

/* Read back bytes at addr for verification. */
JNIEXPORT jboolean JNICALL
Java_com_vlcsubfont_module_xposed_MemoryPatcher_nativeRead(
        JNIEnv *env, jclass clazz, jlong jaddr, jbyteArray jout) {
    (void) clazz;
    jsize len = (*env)->GetArrayLength(env, jout);
    if (len <= 0) return JNI_FALSE;
    jbyte *out = (*env)->GetByteArrayElements(env, jout, NULL);
    if (out == NULL) return JNI_FALSE;
    memcpy(out, (const void *) (uintptr_t) jaddr, (size_t) len);
    (*env)->ReleaseByteArrayElements(env, jout, out, 0);
    return JNI_TRUE;
}
