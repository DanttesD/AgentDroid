//! The few C library functions and Linux constants the library needs, declared by hand so that no crate is required.
//!
//! The constants are the generic Linux values, identical on every Android ABI (arm64-v8a, armeabi-v7a, x86_64, x86)
//! and on x86_64/aarch64 GNU/Linux, which is what the host-side tests use.

use core::ffi::{c_char, c_int, c_ulong, c_void};

pub const O_RDWR: c_int = 0o2;
pub const O_CLOEXEC: c_int = 0o2000000;
pub const TIOCSWINSZ: c_ulong = 0x5414;
pub const TCSANOW: c_int = 0;
pub const IUTF8: u32 = 0o040000;
pub const IXON: u32 = 0o002000;
pub const IXOFF: u32 = 0o010000;
pub const SIG_UNBLOCK: c_int = 1;
pub const ENOENT: c_int = 2;
pub const EINTR: c_int = 4;

/// Offset of `d_name` in `struct dirent64` (`u64 d_ino; i64 d_off; u16 d_reclen; u8 d_type; char d_name[]`): the same
/// layout in glibc and in Bionic, on 32-bit and 64-bit.
pub const DIRENT64_NAME_OFFSET: usize = 19;

/// Room for a `struct termios` of any Linux libc (Bionic: 36 bytes, glibc: 60 bytes). `c_iflag` is the first field.
#[repr(C, align(8))]
pub struct TermiosBuf(pub [u32; 64]);

/// Room for a `sigset_t` of any Linux libc (Bionic: 4 or 8 bytes, glibc: 128 bytes).
#[repr(C, align(8))]
pub struct SigsetBuf(pub [u64; 16]);

#[link(name = "c")]
extern "C" {
    pub fn open(path: *const c_char, flags: c_int, ...) -> c_int;
    pub fn close(fd: c_int) -> c_int;
    pub fn dup2(old_fd: c_int, new_fd: c_int) -> c_int;
    pub fn fork() -> c_int;
    pub fn setsid() -> c_int;
    pub fn grantpt(fd: c_int) -> c_int;
    pub fn unlockpt(fd: c_int) -> c_int;
    pub fn ptsname_r(fd: c_int, buf: *mut c_char, len: usize) -> c_int;
    pub fn tcgetattr(fd: c_int, termios: *mut TermiosBuf) -> c_int;
    pub fn tcsetattr(fd: c_int, action: c_int, termios: *const TermiosBuf) -> c_int;
    pub fn ioctl(fd: c_int, request: c_ulong, ...) -> c_int;
    pub fn waitpid(pid: c_int, status: *mut c_int, options: c_int) -> c_int;
    pub fn sigfillset(set: *mut SigsetBuf) -> c_int;
    pub fn sigprocmask(how: c_int, set: *const SigsetBuf, old_set: *mut SigsetBuf) -> c_int;
    pub fn opendir(path: *const c_char) -> *mut c_void;
    pub fn readdir64(dir: *mut c_void) -> *const u8;
    pub fn dirfd(dir: *mut c_void) -> c_int;
    pub fn closedir(dir: *mut c_void) -> c_int;
    pub fn atoi(text: *const c_char) -> c_int;
    pub fn clearenv() -> c_int;
    pub fn putenv(entry: *mut c_char) -> c_int;
    pub fn chdir(path: *const c_char) -> c_int;
    pub fn execvp(file: *const c_char, argv: *const *const c_char) -> c_int;
    pub fn strerror(errnum: c_int) -> *const c_char;
    pub fn strlen(text: *const c_char) -> usize;
    pub fn write(fd: c_int, buf: *const c_void, count: usize) -> isize;
    pub fn _exit(status: c_int) -> !;
    pub fn abort() -> !;
    pub fn malloc(size: usize) -> *mut c_void;
    pub fn realloc(ptr: *mut c_void, size: usize) -> *mut c_void;
    pub fn free(ptr: *mut c_void);
    pub fn posix_memalign(out: *mut *mut c_void, align: usize, size: usize) -> c_int;
    #[cfg(target_os = "android")]
    fn __errno() -> *mut c_int;
    #[cfg(not(target_os = "android"))]
    fn __errno_location() -> *mut c_int;
}

/// The calling thread's `errno`.
pub fn errno() -> c_int {
    // SAFETY: both functions return the address of the calling thread's errno, which is always valid.
    #[cfg(target_os = "android")]
    unsafe {
        *__errno()
    }
    #[cfg(not(target_os = "android"))]
    unsafe {
        *__errno_location()
    }
}
