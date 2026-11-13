//! `libtermux.so`: the native half of `com.termux.terminal.JNI` (pseudo-terminal, fork/exec, waitpid).
//!
//! Rust port of `src/main/jni/termux.c`. The crate is `no_std` and has no dependencies: it needs only `core`, `alloc`
//! and the C library of the platform, so a bare `rustc` can build it for every Android target (no Cargo, no crates.io,
//! no `libstd`, no unwinder). See the `buildRustJni` task in `build.gradle`.
#![no_std]
#![deny(unsafe_op_in_unsafe_fn)]

extern crate alloc;

mod jni;
mod pty;
mod sys;

use core::alloc::{GlobalAlloc, Layout};
use core::ffi::c_void;
use core::ptr;

use jni::{Env, JInt, JObject};

/// Heap of the process: `malloc` and friends, so Rust and the JVM share one allocator.
struct LibcAllocator;

/// Alignment that `malloc` guarantees on every supported platform.
const MALLOC_ALIGN: usize = 8;

unsafe impl GlobalAlloc for LibcAllocator {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        if layout.align() <= MALLOC_ALIGN {
            unsafe { sys::malloc(layout.size()).cast() }
        } else {
            let mut memory: *mut c_void = ptr::null_mut();
            if unsafe { sys::posix_memalign(&mut memory, layout.align(), layout.size()) } == 0 {
                memory.cast()
            } else {
                ptr::null_mut()
            }
        }
    }

    unsafe fn dealloc(&self, memory: *mut u8, _layout: Layout) {
        unsafe { sys::free(memory.cast()) }
    }

    unsafe fn realloc(&self, memory: *mut u8, layout: Layout, new_size: usize) -> *mut u8 {
        if layout.align() <= MALLOC_ALIGN {
            return unsafe { sys::realloc(memory.cast(), new_size).cast() };
        }
        let new_layout = unsafe { Layout::from_size_align_unchecked(new_size, layout.align()) };
        let new_memory = unsafe { self.alloc(new_layout) };
        if !new_memory.is_null() {
            unsafe {
                ptr::copy_nonoverlapping(memory, new_memory, layout.size().min(new_size));
                self.dealloc(memory, layout);
            }
        }
        new_memory
    }
}

#[global_allocator]
static ALLOCATOR: LibcAllocator = LibcAllocator;

/// Nothing here can panic on valid input; if it ever does, stop the process rather than unwind into the JVM.
#[panic_handler]
fn panic(_info: &core::panic::PanicInfo<'_>) -> ! {
    unsafe { sys::abort() }
}

/// The unwind tables of precompiled `core`/`alloc` code name this personality routine. The library is built with
/// `panic=abort`, so nothing ever unwinds and it is never called; defining it keeps the library loadable even when
/// such code is linked in (without LTO the dynamic loader would refuse the library with an undefined symbol).
#[no_mangle]
pub extern "C" fn rust_eh_personality() {}

#[no_mangle]
pub unsafe extern "C" fn Java_com_termux_terminal_JNI_createSubprocess(
    env: *mut c_void,
    _class: JObject,
    cmd: JObject,
    cwd: JObject,
    args: JObject,
    env_vars: JObject,
    process_id_array: JObject,
    rows: JInt,
    columns: JInt,
    cell_width: JInt,
    cell_height: JInt,
) -> JInt {
    let env = unsafe { Env::from_raw(env) };

    let Ok(argv) = env.string_array(args) else {
        env.throw_runtime_exception(b"GetStringUTFChars() failed for argv\0");
        return -1;
    };
    let Ok(envp) = env.string_array(env_vars) else {
        env.throw_runtime_exception(b"GetStringUTFChars() failed for env\0");
        return -1;
    };
    let cwd = env.utf(cwd);
    let cmd = env.utf(cmd);

    // SAFETY: the strings and tables stay alive until after the call.
    let spawned = unsafe { pty::spawn(cmd.as_ptr(), cwd.as_ptr(), argv.as_ptr(), envp.as_ptr(), rows, columns, cell_width, cell_height) };
    drop(cmd);
    drop(cwd);
    drop(argv);
    drop(envp);

    match spawned {
        Err(error) => {
            env.throw_runtime_exception(error.message());
            -1
        }
        Ok(subprocess) => {
            if env.set_first_int(process_id_array, subprocess.pid) {
                subprocess.master
            } else {
                env.throw_runtime_exception(b"JNI call GetPrimitiveArrayCritical(processIdArray, &isCopy) failed\0");
                pty::close(subprocess.master);
                -1
            }
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_termux_terminal_JNI_setPtyWindowSize(
    _env: *mut c_void,
    _class: JObject,
    fd: JInt,
    rows: JInt,
    columns: JInt,
    cell_width: JInt,
    cell_height: JInt,
) {
    pty::set_window_size(fd, rows, columns, cell_width, cell_height);
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_termux_terminal_JNI_setPtyUTF8Mode(_env: *mut c_void, _class: JObject, fd: JInt) {
    pty::set_utf8_mode(fd);
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_termux_terminal_JNI_waitFor(_env: *mut c_void, _class: JObject, pid: JInt) -> JInt {
    pty::wait_for(pid)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_termux_terminal_JNI_close(_env: *mut c_void, _class: JObject, file_descriptor: JInt) {
    pty::close(file_descriptor);
}
