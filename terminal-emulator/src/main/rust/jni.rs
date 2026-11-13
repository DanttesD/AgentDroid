//! The part of the JNI function table this library calls, and small owners for the Java strings it reads.

use alloc::vec::Vec;
use core::ffi::{c_char, c_void, CStr};
use core::ptr;

pub type JInt = i32;
pub type JSize = i32;
/// `jobject` and every type derived from it (`jclass`, `jstring`, arrays).
pub type JObject = *mut c_void;

// Indices into the JNI function table (`struct JNINativeInterface_` in <jni.h>); the table is part of the JNI
// specification and identical in ART and in HotSpot.
const FIND_CLASS: usize = 6;
const THROW_NEW: usize = 14;
const DELETE_LOCAL_REF: usize = 23;
const GET_STRING_UTF_CHARS: usize = 169;
const RELEASE_STRING_UTF_CHARS: usize = 170;
const GET_ARRAY_LENGTH: usize = 171;
const GET_OBJECT_ARRAY_ELEMENT: usize = 173;
const GET_PRIMITIVE_ARRAY_CRITICAL: usize = 222;
const RELEASE_PRIMITIVE_ARRAY_CRITICAL: usize = 223;

/// The `JNIEnv*` of the calling thread.
#[derive(Clone, Copy)]
pub struct Env(*mut c_void);

impl Env {
    /// # Safety
    /// `raw` must be the `JNIEnv*` that the JVM passed to the running native method.
    pub unsafe fn from_raw(raw: *mut c_void) -> Env {
        Env(raw)
    }

    /// # Safety
    /// `T` must be the function pointer type of table entry `index`.
    unsafe fn function<T: Copy>(self, index: usize) -> T {
        // JNIEnv is a pointer to a pointer to the table of function pointers.
        let table = unsafe { *(self.0 as *const *const *const c_void) };
        unsafe { core::mem::transmute_copy::<*const c_void, T>(&*table.add(index)) }
    }

    /// Throws `java.lang.RuntimeException`. `message` must end with a NUL byte.
    pub fn throw_runtime_exception(self, message: &[u8]) {
        debug_assert!(message.last() == Some(&0));
        unsafe {
            let find_class: unsafe extern "C" fn(*mut c_void, *const c_char) -> JObject = self.function(FIND_CLASS);
            let class = find_class(self.0, b"java/lang/RuntimeException\0".as_ptr().cast());
            // A null class means that FindClass has already left an exception pending.
            if !class.is_null() {
                let throw_new: unsafe extern "C" fn(*mut c_void, JObject, *const c_char) -> JInt = self.function(THROW_NEW);
                throw_new(self.0, class, message.as_ptr().cast());
            }
        }
    }

    fn delete_local_ref(self, object: JObject) {
        unsafe {
            let delete: unsafe extern "C" fn(*mut c_void, JObject) = self.function(DELETE_LOCAL_REF);
            delete(self.0, object);
        }
    }

    /// Modified-UTF-8 characters of `string`, or null if the string is null or the VM is out of memory.
    fn string_utf_chars(self, string: JObject) -> *const c_char {
        // ART answers null for a null string; HotSpot would crash, so do not ask.
        if string.is_null() {
            return ptr::null();
        }
        unsafe {
            let get: unsafe extern "C" fn(*mut c_void, JObject, *mut u8) -> *const c_char = self.function(GET_STRING_UTF_CHARS);
            get(self.0, string, ptr::null_mut())
        }
    }

    fn release_string_utf_chars(self, string: JObject, chars: *const c_char) {
        unsafe {
            let release: unsafe extern "C" fn(*mut c_void, JObject, *const c_char) = self.function(RELEASE_STRING_UTF_CHARS);
            release(self.0, string, chars);
        }
    }

    fn array_length(self, array: JObject) -> JSize {
        unsafe {
            let length: unsafe extern "C" fn(*mut c_void, JObject) -> JSize = self.function(GET_ARRAY_LENGTH);
            length(self.0, array)
        }
    }

    fn object_array_element(self, array: JObject, index: JSize) -> JObject {
        unsafe {
            let get: unsafe extern "C" fn(*mut c_void, JObject, JSize) -> JObject = self.function(GET_OBJECT_ARRAY_ELEMENT);
            get(self.0, array, index)
        }
    }

    /// Stores `value` in the first element of the `int[]` `array`.
    /// Returns false if the array is null, empty or cannot be pinned (an exception may be pending then).
    pub fn set_first_int(self, array: JObject, value: JInt) -> bool {
        if array.is_null() || self.array_length(array) < 1 {
            return false;
        }
        unsafe {
            let get: unsafe extern "C" fn(*mut c_void, JObject, *mut u8) -> *mut JInt = self.function(GET_PRIMITIVE_ARRAY_CRITICAL);
            let elements = get(self.0, array, ptr::null_mut());
            if elements.is_null() {
                return false;
            }
            *elements = value;
            let release: unsafe extern "C" fn(*mut c_void, JObject, *mut JInt, JInt) = self.function(RELEASE_PRIMITIVE_ARRAY_CRITICAL);
            release(self.0, array, elements, 0);
        }
        true
    }

    /// Modified-UTF-8 characters of a Java string, released when dropped. Null for a null string.
    pub fn utf(self, string: JObject) -> Utf {
        Utf { env: self, string, chars: self.string_utf_chars(string) }
    }

    /// Copies the elements of a `String[]` into NUL-terminated buffers.
    /// `Ok` with no elements for a null or empty array; `Err` if an element cannot be read.
    pub fn string_array(self, array: JObject) -> Result<StringArray, ()> {
        let length = if array.is_null() { 0 } else { self.array_length(array) };
        let mut strings: Vec<Vec<u8>> = Vec::new();
        for index in 0..length.max(0) {
            let element = self.object_array_element(array, index);
            let chars = self.string_utf_chars(element);
            if chars.is_null() {
                self.delete_local_ref(element);
                return Err(());
            }
            // SAFETY: `chars` is a NUL-terminated string that stays valid until it is released below.
            strings.push(unsafe { CStr::from_ptr(chars) }.to_bytes_with_nul().to_vec());
            self.release_string_utf_chars(element, chars);
            self.delete_local_ref(element);
        }
        let mut pointers: Vec<*const c_char> = strings.iter().map(|s| s.as_ptr().cast()).collect();
        if !pointers.is_empty() {
            pointers.push(ptr::null());
        }
        Ok(StringArray { _strings: strings, pointers })
    }
}

/// See [`Env::utf`].
pub struct Utf {
    env: Env,
    string: JObject,
    chars: *const c_char,
}

impl Utf {
    /// The characters, or null.
    pub fn as_ptr(&self) -> *const c_char {
        self.chars
    }
}

impl Drop for Utf {
    fn drop(&mut self) {
        if !self.chars.is_null() {
            self.env.release_string_utf_chars(self.string, self.chars);
        }
    }
}

/// A `String[]` copied into C memory: a NULL-terminated `char*` table, as `execvp` and `putenv` expect.
pub struct StringArray {
    // owns the memory the pointers refer to
    _strings: Vec<Vec<u8>>,
    pointers: Vec<*const c_char>,
}

impl StringArray {
    /// The NULL-terminated pointer table, or null when there are no strings (the C code passes NULL then).
    pub fn as_ptr(&self) -> *const *const c_char {
        if self.pointers.is_empty() {
            ptr::null()
        } else {
            self.pointers.as_ptr()
        }
    }
}
