package com.termux.app.workspace

import java.io.File
import java.io.IOException

/** Read-only bounded tree inventory. Never executes remote shell filenames.*/
class ServerDownloadPlan {
    interface Source {
        @Throws(Exception::class)
        fun stat(path: String): ServerSftp.Info

        @Throws(Exception::class)
        fun list(path: String): MutableList<ServerSftp.Entry>

        @Throws(Exception::class)
        fun canonical(path: String): String
    }

    fun interface Progress {
        @Throws(Exception::class)
        fun scanned(entries: Int, path: String)
    }

    class Item {
        @JvmField val remote: String
        @JvmField val relative: String
        @JvmField val info: ServerSftp.Info

        constructor(p: String, r: String, i: ServerSftp.Info) {
            remote = p
            relative = r
            info = i
        }
    }

    @JvmField val files: MutableList<Item> = ArrayList<Item>()
    @JvmField val directories: MutableList<String> = ArrayList<String>()
    @JvmField var bytes: Long = 0L
    @JvmField var scanned: Int = 0
    @JvmField var skipped: Int = 0

    @Throws(Exception::class)
    private fun walk(source: Source, path: String, relative: String, depth: Int, progress: Progress) {
        if (depth > MAX_DEPTH || ++scanned > MAX_ENTRIES) {
            throw IOException("TREE_LIMIT")
        }
        progress.scanned(scanned, path)
        val info: ServerSftp.Info = source.stat(path)
        if (!info.exists) {
            throw IOException("REMOTE_CHANGED")
        }
        if (!info.regular() && !info.directory()) {
            skipped++
            return
        }
        if (!source.canonical(path).equals(path)) {
            throw IOException("REMOTE_SYMLINK_OR_CHANGED")
        }
        if (info.directory()) {
            directories.add(relative)
            for (child in source.list(path)) {
                if (!ServerSftp.validName(child.name)) {
                    throw IOException("UNSAFE_NAME")
                }
                walk(source, path + (if (path.equals("/")) "" else "/") + child.name, relative + "/" + child.name, depth + 1, progress)
            }
        } else {
            if (info.size > ServerSftp.MAX_DOWNLOAD_BYTES || info.size > MAX_TOTAL - bytes) {
                throw IOException("DOWNLOAD_SIZE_LIMIT")
            }
            bytes += info.size
            files.add(Item(path, relative, info))
        }
    }

    companion object {
        const val MAX_ENTRIES: Int = 20000
        const val MAX_DEPTH: Int = 64
        const val MAX_TOTAL: Long = 256L * 1024 * 1024 * 1024

        @Throws(Exception::class)
        @JvmStatic
        fun scan(source: Source, paths: MutableList<String>, progress: Progress): ServerDownloadPlan {
            if (paths.isEmpty() || paths.size > 500) {
                throw IOException("SELECTION_LIMIT")
            }
            val plan = ServerDownloadPlan()
            val roots: MutableSet<String> = HashSet<String>()
            for (path in LinkedHashSet<String>(paths)) {
                if (path == null || !path.startsWith("/") || path.indexOf('\u0000') >= 0) {
                    throw IOException("INVALID_PATH")
                }
                val name: String = File(path).getName()
                if (!ServerSftp.validName(name) || !roots.add(name)) {
                    throw IOException("DUPLICATE_OR_UNSAFE_NAME")
                }
                plan.walk(source, path, name, 0, progress)
            }
            return plan
        }

        @Throws(IOException::class)
        @JvmStatic
        fun checkedRelative(relative: String?): String {
            if (relative == null || relative.isEmpty() || relative.startsWith("/")) {
                throw IOException("INVALID_DESTINATION")
            }
            for (name in (relative as java.lang.String).split("/", -1)) {
                if (!ServerSftp.validName(name)) {
                    throw IOException("INVALID_DESTINATION")
                }
            }
            return relative
        }
    }
}
