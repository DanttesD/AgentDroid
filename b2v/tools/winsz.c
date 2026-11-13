#include <stdio.h>
#include <sys/ioctl.h>
int main(void) {
    struct winsize w;
    if (ioctl(0, TIOCGWINSZ, &w) != 0) { perror("ioctl"); return 1; }
    printf("%u %u %u %u\n", w.ws_row, w.ws_col, w.ws_xpixel, w.ws_ypixel);
    return 0;
}
