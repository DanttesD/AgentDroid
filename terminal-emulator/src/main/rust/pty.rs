//! Pseudo-terminal, subprocess and window-size handling (the former `create_subprocess` and friends of termux.c).

use alloc::vec::Vec;
use core::ffi::{c_char, c_int, CStr};
use core::ptr;

use crate::sys::{self, SigsetBuf, TermiosBuf};

/// Why [`spawn`] failed; each case becomes a `RuntimeException` with the message of the C implementation.
pub enum SpawnError {
    OpenPtmx,
    Grantpt,
    Fork,
}

impl SpawnError {
    /// NUL-terminated message of the exception.
    pub fn message(&self) -> &'static [u8] {
        match self {
            SpawnError::OpenPtmx => b"Cannot open /dev/ptmx\0",
            SpawnError::Grantpt => b"Cannot grantpt()/unlockpt()/ptsname_r() on /dev/ptmx\0",
            SpawnError::Fork => b"Fork failed\0",
        }
    }
}

/// A started subprocess: the pty master and the process id.
pub struct Spawned {
    pub master: c_int,
    pub pid: c_int,
}

/// `struct winsize`: rows, columns and the size of the window in pixels. Like the C code, the values wrap to 16 bits.
fn window_size(rows: i32, columns: i32, cell_width: i32, cell_height: i32) -> [u16; 4] {
    [rows as u16, columns as u16, columns.wrapping_mul(cell_width) as u16, rows.wrapping_mul(cell_height) as u16]
}

pub fn set_window_size(fd: c_int, rows: i32, columns: i32, cell_width: i32, cell_height: i32) {
    let size = window_size(rows, columns, cell_width, cell_height);
    // SAFETY: TIOCSWINSZ reads a `struct winsize` (four u16) from the pointer; a bad descriptor only makes it fail.
    unsafe {
        sys::ioctl(fd, sys::TIOCSWINSZ, size.as_ptr());
    }
}

pub fn set_utf8_mode(fd: c_int) {
    let mut termios = TermiosBuf([0; 64]);
    // SAFETY: the buffer is larger than any `struct termios`; a bad descriptor only makes the calls fail.
    unsafe {
        if sys::tcgetattr(fd, &mut termios) != 0 {
            return;
        }
        if termios.0[0] & sys::IUTF8 == 0 {
            termios.0[0] |= sys::IUTF8;
            sys::tcsetattr(fd, sys::TCSANOW, &termios);
        }
    }
}

/// Waits for `pid` and returns its exit code, or minus the number of the signal that killed it.
pub fn wait_for(pid: c_int) -> c_int {
    let mut status: c_int = 0;
    loop {
        // SAFETY: `status` is a valid out pointer.
        if unsafe { sys::waitpid(pid, &mut status, 0) } >= 0 {
            break;
        }
        // The C code read an uninitialised status here. Retry when interrupted, otherwise report 0 (the value of
        // its "should never happen" branch).
        if sys::errno() != sys::EINTR {
            return 0;
        }
    }
    if status & 0x7f == 0 {
        // WIFEXITED / WEXITSTATUS
        (status >> 8) & 0xff
    } else if ((((status & 0x7f) + 1) as i8) >> 1) > 0 {
        // WIFSIGNALED / WTERMSIG
        -(status & 0x7f)
    } else {
        0
    }
}

pub fn close(fd: c_int) {
    // SAFETY: closing a descriptor number cannot violate memory safety.
    unsafe {
        sys::close(fd);
    }
}

/// Text for the terminal when `chdir()` or `exec()` fails in the child, e.g. `exec("/bin/x")`.
fn quoted(prefix: &[u8], text: *const c_char) -> Vec<u8> {
    let mut message = Vec::new();
    message.extend_from_slice(prefix);
    if text.is_null() {
        message.extend_from_slice(b"(null)");
    } else {
        // SAFETY: `text` is NUL-terminated (see `spawn`).
        message.extend_from_slice(unsafe { CStr::from_ptr(text) }.to_bytes());
    }
    message.extend_from_slice(b"\")");
    message
}

/// Starts `cmd` with a new pseudo-terminal as its controlling terminal.
///
/// # Safety
/// `cmd` and `cwd` must be null or NUL-terminated. `argv` and `envp` must be null or NULL-terminated tables of
/// NUL-terminated strings. All of them must stay valid until the call returns.
#[allow(clippy::too_many_arguments)]
pub unsafe fn spawn(
    cmd: *const c_char,
    cwd: *const c_char,
    argv: *const *const c_char,
    envp: *const *const c_char,
    rows: i32,
    columns: i32,
    cell_width: i32,
    cell_height: i32,
) -> Result<Spawned, SpawnError> {
    // SAFETY (all calls below): plain system calls with valid arguments.
    let master = unsafe { sys::open(b"/dev/ptmx\0".as_ptr().cast(), sys::O_RDWR | sys::O_CLOEXEC) };
    if master < 0 {
        return Err(SpawnError::OpenPtmx);
    }

    let mut slave_name = [0 as c_char; 64];
    let prepared = unsafe {
        sys::grantpt(master) == 0 && sys::unlockpt(master) == 0 && sys::ptsname_r(master, slave_name.as_mut_ptr(), slave_name.len()) == 0
    };
    if !prepared {
        unsafe { sys::close(master) };
        return Err(SpawnError::Grantpt);
    }

    // Enable UTF-8 mode and disable flow control to prevent Ctrl+S from locking up the display.
    let mut termios = TermiosBuf([0; 64]);
    if unsafe { sys::tcgetattr(master, &mut termios) } == 0 {
        termios.0[0] |= sys::IUTF8;
        termios.0[0] &= !(sys::IXON | sys::IXOFF);
        unsafe { sys::tcsetattr(master, sys::TCSANOW, &termios) };
    }

    set_window_size(master, rows, columns, cell_width, cell_height);

    // The child of a multi-threaded process should not allocate, so what it prints is prepared before fork().
    let chdir_message = quoted(b"chdir(\"", cwd);
    let exec_message = quoted(b"exec(\"", cmd);

    let pid = unsafe { sys::fork() };
    if pid < 0 {
        unsafe { sys::close(master) };
        return Err(SpawnError::Fork);
    }
    if pid > 0 {
        return Ok(Spawned { master, pid });
    }
    unsafe { run_child(master, &slave_name, cmd, cwd, argv, envp, &chdir_message, &exec_message) }
}

/// The child between fork() and exec(): makes the pty its terminal, cleans up, changes directory and executes.
///
/// # Safety
/// Only called in the freshly forked child, with the arguments of [`spawn`].
#[allow(clippy::too_many_arguments)]
unsafe fn run_child(
    master: c_int,
    slave_name: &[c_char; 64],
    cmd: *const c_char,
    cwd: *const c_char,
    argv: *const *const c_char,
    envp: *const *const c_char,
    chdir_message: &[u8],
    exec_message: &[u8],
) -> ! {
    unsafe {
        // Clear signals which the Android java process may have blocked.
        let mut signals = SigsetBuf([0; 16]);
        sys::sigfillset(&mut signals);
        sys::sigprocmask(sys::SIG_UNBLOCK, &signals, ptr::null_mut());

        sys::close(master);
        sys::setsid();

        let slave = sys::open(slave_name.as_ptr(), sys::O_RDWR);
        if slave < 0 {
            sys::_exit(255); // exit(-1) in the C code
        }
        sys::dup2(slave, 0);
        sys::dup2(slave, 1);
        sys::dup2(slave, 2);

        // Close every other descriptor the process has inherited.
        let self_dir = sys::opendir(b"/proc/self/fd\0".as_ptr().cast());
        if !self_dir.is_null() {
            let self_dir_fd = sys::dirfd(self_dir);
            loop {
                let entry = sys::readdir64(self_dir);
                if entry.is_null() {
                    break;
                }
                let fd = sys::atoi(entry.add(sys::DIRENT64_NAME_OFFSET).cast());
                if fd > 2 && fd != self_dir_fd {
                    sys::close(fd);
                }
            }
            sys::closedir(self_dir);
        }

        sys::clearenv();
        if !envp.is_null() {
            let mut entry = envp;
            while !(*entry).is_null() {
                sys::putenv(*entry as *mut c_char);
                entry = entry.add(1);
            }
        }

        if sys::chdir(cwd) != 0 {
            report(chdir_message, sys::errno());
        }
        // glibc's execvp() does not tolerate a null file name (Bionic reports ENOENT), so do not call it with one.
        let error = if cmd.is_null() {
            sys::ENOENT
        } else {
            sys::execvp(cmd, argv);
            sys::errno()
        };
        // Show terminal output about the failing exec() call.
        report(exec_message, error);
        sys::_exit(1)
    }
}

/// Writes `message: strerror(errnum)` and a newline to the terminal, like perror().
unsafe fn report(message: &[u8], errnum: c_int) {
    unsafe {
        let reason = sys::strerror(errnum);
        write_all(message);
        write_all(b": ");
        write_all(core::slice::from_raw_parts(reason.cast::<u8>(), sys::strlen(reason)));
        write_all(b"\n");
    }
}

unsafe fn write_all(mut bytes: &[u8]) {
    while !bytes.is_empty() {
        let written = unsafe { sys::write(2, bytes.as_ptr().cast(), bytes.len()) };
        if written > 0 {
            bytes = &bytes[written as usize..];
        } else if written == 0 || sys::errno() != sys::EINTR {
            return;
        }
    }
}
