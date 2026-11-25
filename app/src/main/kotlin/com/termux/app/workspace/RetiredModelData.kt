package com.termux.app.workspace

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** Removal migration for the retired on-device model feature, not a workspace reset.*/
object RetiredModelData {

    @Throws(IOException::class)
    @JvmStatic
    fun clean(context: Context) {
        removeOwnedDirectory(context.getFilesDir())
        removeOwnedDirectory(context.getCacheDir())
        context.deleteSharedPreferences("local-ai-diagnostics")
    }

    @Throws(IOException::class)
    @JvmStatic
    fun removeOwnedDirectory(appDirectory: File) {
        // Fixed child only: no profile-supplied paths, HOME, PREFIX or proot roots.
        val child: Path = appDirectory.getCanonicalFile().toPath().resolve("local-ai")
        if (!Files.exists(child, LinkOption.NOFOLLOW_LINKS)) {
            return
        }
        // Default walk never follows symbolic links, including the top-level path.
        Files.walkFileTree(child, object : SimpleFileVisitor<Path>() {
            @Throws(IOException::class)
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            @Throws(IOException::class)
            override fun postVisitDirectory(dir: Path, failure: IOException?): FileVisitResult {
                if (failure != null) {
                    throw failure
                }
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }
}
