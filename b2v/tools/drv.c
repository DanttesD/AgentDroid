/* Calls the native functions that Java cannot reach (setPtyUTF8Mode) and misuse cases, without a JVM:
   the JNIEnv and jclass arguments of these functions are not used. */

#include <dlfcn.h>
#include <signal.h>
#include <sys/wait.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <termios.h>
#include <unistd.h>
#include <sys/ioctl.h>

typedef void (*set_utf8_t)(void *, void *, int);
typedef void (*set_size_t)(void *, void *, int, int, int, int, int);
typedef int (*wait_t)(void *, void *, int);
typedef void (*close_t)(void *, void *, int);

static int iutf8(int fd) { struct termios t; if (tcgetattr(fd, &t)) return -1; return (t.c_iflag & IUTF8) != 0; }

int main(int argc, char **argv) {
    void *h = dlopen(argv[1], RTLD_NOW);
    if (!h) { printf("dlopen failed: %s\n", dlerror()); return 1; }
    set_utf8_t set_utf8 = (set_utf8_t) dlsym(h, "Java_com_termux_terminal_JNI_setPtyUTF8Mode");
    set_size_t set_size = (set_size_t) dlsym(h, "Java_com_termux_terminal_JNI_setPtyWindowSize");
    wait_t wait_for = (wait_t) dlsym(h, "Java_com_termux_terminal_JNI_waitFor");
    close_t close_fd = (close_t) dlsym(h, "Java_com_termux_terminal_JNI_close");
    if (!set_utf8 || !set_size || !wait_for || !close_fd) { printf("dlsym failed\n"); return 1; }

    int m = posix_openpt(O_RDWR | O_NOCTTY);
    grantpt(m); unlockpt(m);
    struct termios t;
    tcgetattr(m, &t);
    printf("initial iutf8=%d\n", iutf8(m));
    t.c_iflag &= ~IUTF8; tcsetattr(m, TCSANOW, &t);
    printf("after clearing iutf8=%d\n", iutf8(m));
    set_utf8(0, 0, m);
    printf("after setPtyUTF8Mode iutf8=%d\n", iutf8(m));
    t.c_iflag |= IUTF8; t.c_iflag |= IXON | IXOFF | ICRNL; tcsetattr(m, TCSANOW, &t);
    set_utf8(0, 0, m);
    tcgetattr(m, &t);
    printf("second call keeps flags: iutf8=%d ixon=%d ixoff=%d icrnl=%d\n", iutf8(m), !!(t.c_iflag & IXON), !!(t.c_iflag & IXOFF), !!(t.c_iflag & ICRNL));

    set_size(0, 0, m, 24, 80, 8, 16);
    struct winsize w; ioctl(m, TIOCGWINSZ, &w);
    printf("winsize %u %u %u %u\n", w.ws_row, w.ws_col, w.ws_xpixel, w.ws_ypixel);
    set_size(0, 0, m, 70000, 65537, 3, 5);   /* values wrap to 16 bits like the C casts */
    ioctl(m, TIOCGWINSZ, &w);
    printf("winsize wrapped %u %u %u %u\n", w.ws_row, w.ws_col, w.ws_xpixel, w.ws_ypixel);
    set_size(0, 0, m, -1, -2, 100000, -100000);
    ioctl(m, TIOCGWINSZ, &w);
    printf("winsize negative %u %u %u %u\n", w.ws_row, w.ws_col, w.ws_xpixel, w.ws_ypixel);

    /* misuse: invalid descriptors must not crash */
    set_utf8(0, 0, -1); set_utf8(0, 0, 99999); set_size(0, 0, -1, 1, 2, 3, 4); close_fd(0, 0, -1); close_fd(0, 0, 99999);
    printf("invalid descriptors ok\n");
    /* a descriptor that is not a terminal */
    int n = open("/dev/null", O_RDWR); set_utf8(0, 0, n); set_size(0, 0, n, 1, 2, 3, 4); close_fd(0, 0, n);
    printf("non-tty descriptor ok\n");
    /* close() really closes */
    int d = dup(m); close_fd(0, 0, d);
    printf("closed dup: fcntl=%d\n", fcntl(d, F_GETFD) == -1);

    /* waitFor: exit codes and signals of real children */
    int codes[] = {0, 1, 42, 255};
    for (int i = 0; i < 4; i++) {
        pid_t p = fork();
        if (p == 0) _exit(codes[i]);
        printf("waitFor exit %d -> %d\n", codes[i], wait_for(0, 0, p));
    }
    int sigs[] = {9, 15, 11, 1, 6};
    for (int i = 0; i < 5; i++) {
        pid_t p = fork();
        if (p == 0) { signal(sigs[i], SIG_DFL); kill(getpid(), sigs[i]); pause(); _exit(99); }
        printf("waitFor signal %d -> %d\n", sigs[i], wait_for(0, 0, p));
    }
    return 0;
}
