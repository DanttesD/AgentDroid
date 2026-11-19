/*
 * Copyright (c) 2008, 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 **/

package com.termux.shared.file.filesystem

import android.os.Build
import android.system.StructStat
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Unix implementation of PosixFileAttributes.
 * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r3:libcore/ojluni/src/main/java/sun/nio/fs/UnixFileAttributes.java
 **/
open class FileAttributes {
    private var filePath: String? = null
    private var fileDescriptor: FileDescriptor? = null

    private var st_mode: Int = 0
    private var st_ino: Long = 0L
    private var st_dev: Long = 0L
    private var st_rdev: Long = 0L
    private var st_nlink: Long = 0L
    private var st_uid: Int = 0
    private var st_gid: Int = 0
    private var st_size: Long = 0L
    private var st_blksize: Long = 0L
    private var st_blocks: Long = 0L
    private var st_atime_sec: Long = 0L
    private var st_atime_nsec: Long = 0L
    private var st_mtime_sec: Long = 0L
    private var st_mtime_nsec: Long = 0L
    private var st_ctime_sec: Long = 0L
    private var st_ctime_nsec: Long = 0L

    // created lazily
    @Volatile private var owner: String? = null
    @Volatile private var group: String? = null
    @Volatile private var key: FileKey? = null

    private constructor(filePath: String?) {
        this.filePath = filePath
    }

    private constructor(fileDescriptor: FileDescriptor) {
        this.fileDescriptor = fileDescriptor
    }

    open fun file(): String? {
        if (filePath != null) {
            return filePath
        } else if (fileDescriptor != null) {
            return fileDescriptor!!.toString()
        } else {
            return null
        }
    }

    // package-private
    open fun isSameFile(attrs: FileAttributes): Boolean {
        return st_ino == attrs.st_ino && st_dev == attrs.st_dev
    }

    // package-private
    open fun mode(): Int {
        return st_mode
    }

    open fun blksize(): Long {
        return st_blksize
    }

    open fun blocks(): Long {
        return st_blocks
    }

    open fun ino(): Long {
        return st_ino
    }

    open fun dev(): Long {
        return st_dev
    }

    open fun rdev(): Long {
        return st_rdev
    }

    open fun nlink(): Long {
        return st_nlink
    }

    open fun uid(): Int {
        return st_uid
    }

    open fun gid(): Int {
        return st_gid
    }

    open fun lastAccessTime(): FileTime {
        return toFileTime(st_atime_sec, st_atime_nsec)
    }

    open fun lastModifiedTime(): FileTime {
        return toFileTime(st_mtime_sec, st_mtime_nsec)
    }

    open fun lastChangeTime(): FileTime {
        return toFileTime(st_ctime_sec, st_ctime_nsec)
    }

    open fun creationTime(): FileTime {
        return lastModifiedTime()
    }

    open fun isRegularFile(): Boolean {
        return st_mode and UnixConstants.S_IFMT == UnixConstants.S_IFREG
    }

    open fun isDirectory(): Boolean {
        return st_mode and UnixConstants.S_IFMT == UnixConstants.S_IFDIR
    }

    open fun isSymbolicLink(): Boolean {
        return st_mode and UnixConstants.S_IFMT == UnixConstants.S_IFLNK
    }

    open fun isCharacter(): Boolean {
        return st_mode and UnixConstants.S_IFMT == UnixConstants.S_IFCHR
    }

    open fun isFifo(): Boolean {
        return st_mode and UnixConstants.S_IFMT == UnixConstants.S_IFIFO
    }

    open fun isBlock(): Boolean {
        return st_mode and UnixConstants.S_IFMT == UnixConstants.S_IFBLK
    }

    open fun isOther(): Boolean {
        val type: Int = st_mode and UnixConstants.S_IFMT
        return type != UnixConstants.S_IFREG && type != UnixConstants.S_IFDIR && type != UnixConstants.S_IFLNK
    }

    open fun isDevice(): Boolean {
        val type: Int = st_mode and UnixConstants.S_IFMT
        return type == UnixConstants.S_IFCHR || type == UnixConstants.S_IFBLK || type == UnixConstants.S_IFIFO
    }

    open fun size(): Long {
        return st_size
    }

    open fun fileKey(): FileKey? {
        if (key == null) {
            synchronized(this) {
                if (key == null) {
                    key = FileKey(st_dev, st_ino)
                }
            }
        }
        return key
    }

    open fun owner(): String? {
        if (owner == null) {
            synchronized(this) {
                if (owner == null) {
                    owner = Integer.toString(st_uid)
                }
            }
        }
        return owner
    }

    open fun group(): String? {
        if (group == null) {
            synchronized(this) {
                if (group == null) {
                    group = Integer.toString(st_gid)
                }
            }
        }
        return group
    }

    open fun permissions(): MutableSet<FilePermission> {
        val bits: Int = st_mode and UnixConstants.S_IAMB
        val perms = HashSet<FilePermission>()

        if (bits and UnixConstants.S_IRUSR > 0) {
            perms.add(FilePermission.OWNER_READ)
        }
        if (bits and UnixConstants.S_IWUSR > 0) {
            perms.add(FilePermission.OWNER_WRITE)
        }
        if (bits and UnixConstants.S_IXUSR > 0) {
            perms.add(FilePermission.OWNER_EXECUTE)
        }

        if (bits and UnixConstants.S_IRGRP > 0) {
            perms.add(FilePermission.GROUP_READ)
        }
        if (bits and UnixConstants.S_IWGRP > 0) {
            perms.add(FilePermission.GROUP_WRITE)
        }
        if (bits and UnixConstants.S_IXGRP > 0) {
            perms.add(FilePermission.GROUP_EXECUTE)
        }

        if (bits and UnixConstants.S_IROTH > 0) {
            perms.add(FilePermission.OTHERS_READ)
        }
        if (bits and UnixConstants.S_IWOTH > 0) {
            perms.add(FilePermission.OTHERS_WRITE)
        }
        if (bits and UnixConstants.S_IXOTH > 0) {
            perms.add(FilePermission.OTHERS_EXECUTE)
        }

        return perms
    }

    open fun loadFromStructStat(structStat: StructStat) {
        this.st_mode = structStat.st_mode
        this.st_ino = structStat.st_ino
        this.st_dev = structStat.st_dev
        this.st_rdev = structStat.st_rdev
        this.st_nlink = structStat.st_nlink
        this.st_uid = structStat.st_uid
        this.st_gid = structStat.st_gid
        this.st_size = structStat.st_size
        this.st_blksize = structStat.st_blksize
        this.st_blocks = structStat.st_blocks

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            this.st_atime_sec = structStat.st_atim.tv_sec
            this.st_atime_nsec = structStat.st_atim.tv_nsec
            this.st_mtime_sec = structStat.st_mtim.tv_sec
            this.st_mtime_nsec = structStat.st_mtim.tv_nsec
            this.st_ctime_sec = structStat.st_ctim.tv_sec
            this.st_ctime_nsec = structStat.st_ctim.tv_nsec
        } else {
            this.st_atime_sec = structStat.st_atime
            this.st_atime_nsec = 0L
            this.st_mtime_sec = structStat.st_mtime
            this.st_mtime_nsec = 0L
            this.st_ctime_sec = structStat.st_ctime
            this.st_ctime_nsec = 0L
        }
    }

    open fun getFileString(): String {
        return "File: `" + file() + "`"
    }

    open fun getTypeString(): String {
        return "Type: `" + FileTypes.getFileType(this).getName() + "`"
    }

    open fun getSizeString(): String {
        return "Size: `" + size() + "`"
    }

    open fun getBlocksString(): String {
        return "Blocks: `" + blocks() + "`"
    }

    open fun getIOBlockString(): String {
        return "IO Block: `" + blksize() + "`"
    }

    open fun getDeviceString(): String {
        return "Device: `" + java.lang.Long.toHexString(st_dev) + "`"
    }

    open fun getInodeString(): String {
        return "Inode: `" + st_ino + "`"
    }

    open fun getLinksString(): String {
        return "Links: `" + nlink() + "`"
    }

    open fun getDeviceTypeString(): String {
        return "Device Type: `" + rdev() + "`"
    }

    open fun getOwnerString(): String {
        return "Owner: `" + owner() + "`"
    }

    open fun getGroupString(): String {
        return "Group: `" + group() + "`"
    }

    open fun getPermissionString(): String {
        return "Permissions: `" + FilePermissions.toString(permissions()) + "`"
    }

    open fun getAccessTimeString(): String {
        return "Access Time: `" + lastAccessTime() + "`"
    }

    open fun getModifiedTimeString(): String {
        return "Modified Time: `" + lastModifiedTime() + "`"
    }

    open fun getChangeTimeString(): String {
        return "Change Time: `" + lastChangeTime() + "`"
    }

    override fun toString(): String {
        return getFileAttributesLogString(this)
    }

    companion object {

        // get the FileAttributes for a given file
        @Throws(IOException::class)
        @JvmStatic
        fun get(filePath: String?, followLinks: Boolean): FileAttributes {
            var fileAttributes: FileAttributes

            if (filePath == null || filePath.isEmpty()) {
                fileAttributes = FileAttributes(null as String?)
            } else {
                fileAttributes = FileAttributes(File(filePath).getAbsolutePath())
            }

            if (followLinks) {
                NativeDispatcher.stat(filePath, fileAttributes)
            } else {
                NativeDispatcher.lstat(filePath, fileAttributes)
            }

            // Logger.logDebug(fileAttributes.toString());
            return fileAttributes
        }

        // get the FileAttributes for an open file
        @Throws(IOException::class)
        @JvmStatic
        fun get(fileDescriptor: FileDescriptor): FileAttributes {
            val fileAttributes = FileAttributes(fileDescriptor)
            NativeDispatcher.fstat(fileDescriptor, fileAttributes)
            return fileAttributes
        }

        @JvmStatic
        private fun toFileTime(sec: Long, nsec: Long): FileTime {
            if (nsec == 0L) {
                return FileTime.from(sec, TimeUnit.SECONDS)
            } else {
                // truncate to microseconds to avoid overflow with timestamps
                // way out into the future. We can re-visit this if FileTime
                // is updated to define a from(secs,nsecs) method.
                val micro: Long = sec * 1000000L + nsec / 1000L
                return FileTime.from(micro, TimeUnit.MICROSECONDS)
            }
        }

        @JvmStatic
        fun getFileAttributesLogString(fileAttributes: FileAttributes?): String {
            if (fileAttributes == null) {
                return "null"
            }

            val logString = StringBuilder()

            logString.append(fileAttributes.getFileString())

            logString.append("\n").append(fileAttributes.getTypeString())

            logString.append("\n").append(fileAttributes.getSizeString())
            logString.append("\n").append(fileAttributes.getBlocksString())
            logString.append("\n").append(fileAttributes.getIOBlockString())

            logString.append("\n").append(fileAttributes.getDeviceString())
            logString.append("\n").append(fileAttributes.getInodeString())
            logString.append("\n").append(fileAttributes.getLinksString())

            if (fileAttributes.isBlock() || fileAttributes.isCharacter()) {
                logString.append("\n").append(fileAttributes.getDeviceTypeString())
            }

            logString.append("\n").append(fileAttributes.getOwnerString())
            logString.append("\n").append(fileAttributes.getGroupString())
            logString.append("\n").append(fileAttributes.getPermissionString())

            logString.append("\n").append(fileAttributes.getAccessTimeString())
            logString.append("\n").append(fileAttributes.getModifiedTimeString())
            logString.append("\n").append(fileAttributes.getChangeTimeString())

            return logString.toString()
        }
    }
}
