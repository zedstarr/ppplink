#include <jni.h>
#include <pty.h>
#include <termios.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <sys/wait.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#define TAG "PPP LinkNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

static void free_strv(char **v) {
    if (!v) return;
    for (char **p = v; *p; p++) free(*p);
    free(v);
}

/* Copies a Java String[] into a NULL-terminated char*[], optionally with a
 * leading extra entry. Returns NULL on any failure. */
static char **to_strv(JNIEnv *env, jobjectArray arr, const char *first) {
    int n = arr ? (*env)->GetArrayLength(env, arr) : 0;
    int off = first ? 1 : 0;
    char **v = (char **)calloc((size_t)n + off + 1, sizeof(char *));
    if (!v) return NULL;
    if (first && !(v[0] = strdup(first))) { free(v); return NULL; }
    for (int i = 0; i < n; i++) {
        jstring js = (jstring)(*env)->GetObjectArrayElement(env, arr, i);
        const char *s = js ? (*env)->GetStringUTFChars(env, js, NULL) : NULL;
        if (!s) {
            if (js) (*env)->DeleteLocalRef(env, js);
            free_strv(v);
            return NULL;
        }
        v[i + off] = strdup(s);
        (*env)->ReleaseStringUTFChars(env, js, s);
        (*env)->DeleteLocalRef(env, js);
        if (!v[i + off]) { free_strv(v); return NULL; }
    }
    return v;
}

JNIEXPORT jintArray JNICALL
Java_org_ppplink_LinkEngine_nativeStart(JNIEnv *env, jobject thiz,
                                        jstring exeStr, jobjectArray argsArr, jobjectArray envArr) {
    (void)thiz;
    const char *exeTmp = exeStr ? (*env)->GetStringUTFChars(env, exeStr, NULL) : NULL;
    if (!exeTmp) return NULL;
    char *exe = strdup(exeTmp);
    (*env)->ReleaseStringUTFChars(env, exeStr, exeTmp);
    if (!exe) return NULL;

    char **argv = to_strv(env, argsArr, exe);      /* argv[0] = exe */
    char **envp = to_strv(env, envArr, NULL);
    if (!argv || !envp) {
        LOGE("out of memory building argv/envp");
        free_strv(argv); free_strv(envp); free(exe);
        return NULL;
    }

    /* Highest fd to close in the child (computed here: sysconf isn't async-signal-safe). */
    long maxfd = sysconf(_SC_OPEN_MAX);
    if (maxfd < 256 || maxfd > 65536) maxfd = 65536;

    int errPipe[2];
    if (pipe2(errPipe, O_CLOEXEC) != 0) {
        LOGE("pipe2 failed: %s", strerror(errno));
        free_strv(argv); free_strv(envp); free(exe);
        return NULL;
    }

    int masterFd = -1;
    pid_t pid = forkpty(&masterFd, NULL, NULL, NULL);
    if (pid < 0) {
        LOGE("forkpty failed: %s", strerror(errno));
        close(errPipe[0]); close(errPipe[1]);
        free_strv(argv); free_strv(envp); free(exe);
        return NULL;
    }

    if (pid == 0) {
        /* Child: only async-signal-safe calls from here to execve. */

        /* The ART runtime blocks and handles signals; don't let slirp inherit that. */
        sigset_t none;
        sigemptyset(&none);
        sigprocmask(SIG_SETMASK, &none, NULL);
        struct sigaction dfl;
        memset(&dfl, 0, sizeof(dfl));
        dfl.sa_handler = SIG_DFL;
        for (int sig = 1; sig < NSIG; sig++) {
            if (sig != SIGKILL && sig != SIGSTOP) sigaction(sig, &dfl, NULL);
        }

        /* Raw pty: bytes pass through untouched. */
        struct termios tio;
        if (tcgetattr(STDIN_FILENO, &tio) == 0) {
            cfmakeraw(&tio);
            tcsetattr(STDIN_FILENO, TCSANOW, &tio);
        }

        /* stderr -> pipe (dup2 clears O_CLOEXEC on fd 2). */
        dup2(errPipe[1], STDERR_FILENO);

        for (long fd = 3; fd < maxfd; fd++) close((int)fd);

        execve(exe, argv, envp);
        static const char msg[] = "execve failed\n";
        write(STDERR_FILENO, msg, sizeof(msg) - 1);
        _exit(127);
    }

    /* Parent */
    close(errPipe[1]);
    fcntl(masterFd, F_SETFD, FD_CLOEXEC);     /* don't leak into later children */
    free_strv(argv);
    free_strv(envp);
    free(exe);

    jintArray result = (*env)->NewIntArray(env, 3);
    if (!result) {
        kill(pid, SIGKILL);
        close(masterFd);
        close(errPipe[0]);
        return NULL;
    }
    jint vals[3] = {(jint)pid, (jint)masterFd, (jint)errPipe[0]};
    (*env)->SetIntArrayRegion(env, result, 0, 3, vals);
    LOGI("slirp spawned pid=%d masterFd=%d stderrFd=%d", pid, masterFd, errPipe[0]);
    return result;
}

JNIEXPORT jint JNICALL
Java_org_ppplink_LinkEngine_nativeWait(JNIEnv *env, jobject thiz, jint pid) {
    (void)env; (void)thiz;
    int status = 0;
    pid_t res;
    /* Retry on EINTR: returning early would make the app think slirp had exited. */
    do {
        res = waitpid((pid_t)pid, &status, 0);
    } while (res < 0 && errno == EINTR);
    if (res < 0) return -1;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return status;
}

JNIEXPORT void JNICALL
Java_org_ppplink_LinkEngine_nativeKill(JNIEnv *env, jobject thiz, jint pid, jint sig) {
    (void)env; (void)thiz;
    if (pid > 0) kill((pid_t)pid, (int)sig);
}
