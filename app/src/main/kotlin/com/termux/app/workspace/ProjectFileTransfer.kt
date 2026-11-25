package com.termux.app.workspace

import java.io.OutputStream

/** Quoted operations in one selected environment, with whole-batch preflight and no overwrite.*/
object ProjectFileTransfer {
    fun interface Runner {
        @Throws(Exception::class)
        fun run(script: String, output: OutputStream, max: Int)
    }

    const val MAX_SELECTED: Int = 50

    @Throws(Exception::class)
    @JvmStatic
    fun execute(entries: MutableList<ProjectFiles.Entry>, folder: String?, move: Boolean, rename: Boolean, runner: Runner) {
        val receipt = ProjectFileReceipt(entries.size)
        val script: String = script(entries, folder, move, rename, false, receipt)
        try {
            runner.run(script, receipt, ProjectFileReceipt.MAX_OUTPUT)
            receipt.requireComplete()
        } catch (error: Exception) {
            throw ProjectFiles.TransferFailure(receipt.acknowledged(), error)
        }
    }

    @JvmStatic
    fun script(entries: MutableList<ProjectFiles.Entry>, folder: String?, move: Boolean, rename: Boolean, checkOnly: Boolean): String {
        return script(entries, folder, move, rename, checkOnly, null)
    }

    @JvmStatic
    fun script(entries: MutableList<ProjectFiles.Entry>, folder: String?, move: Boolean, rename: Boolean, checkOnly: Boolean, receipt: ProjectFileReceipt?): String {
        var receipt = receipt
        if (entries.isEmpty() || entries.size > MAX_SELECTED) {
            throw IllegalArgumentException("INVALID_SELECTION")
        }
        val s = StringBuilder("set -eu\numask 077\nexport LC_ALL=C\nto=\$(cd -P -- " + ProjectFiles.target(folder) + " && pwd -P) || exit 21\n")
        val paths: MutableSet<String> = HashSet<String>()
        var i = 0
        while (i < entries.size) {
            val e: ProjectFiles.Entry = entries.get(i)
            if (!e.path.startsWith("/") || e.name.isEmpty() || e.name.equals(".") || e.name.equals("..") || e.name.contains("/") || !paths.add(e.path)) {
                throw IllegalArgumentException("INVALID_SELECTION")
            }
            var parent: String = e.path.substring(0, e.path.lastIndexOf('/'))
            if (parent.isEmpty()) {
                parent = "/"
            }
            s.append("parent=\$(cd -P -- ").append(ProjectFiles.target(parent)).append(" && pwd -P) || exit 21\n").append("src").append(i).append("=\"\${parent%/}/\"").append(SessionShell.quote(e.name)).append("\n").append("src=\"\$src").append(i).append("\"\n").append("if ! [ -f \"\$src\" ] && ! [ -d \"\$src\" ] && ! [ -L \"\$src\" ]; then exit 21; fi\n").append("if [ -d \"\$src\" ] && ! [ -L \"\$src\" ]; then case \"\$to/\" in \"\$src/\"*) exit 22;; esac; fi\n")
            if (i > 0) {
                s.append("for previous in")
                for (j in 0 until (i)) {
                    s.append(" \"\$src").append(j).append("\"")
                }
                s.append("; do\ncase \"\$src/\" in \"\$previous/\"*) exit 22;; esac\ncase \"\$previous/\" in \"\$src/\"*) exit 22;; esac\ndone\n")
            }
            val dot: Int = if (e.directory) -1 else e.name.lastIndexOf('.')
            val stem: String = if (dot > 0) e.name.substring(0, dot) else e.name
            val suffix: String = if (dot > 0) e.name.substring(dot) else ""
            s.append("stem=").append(SessionShell.quote(stem)).append("\nsuffix=").append(SessionShell.quote(suffix)).append("\n")
            s.append("name=").append(SessionShell.quote(e.name)).append("\ndst=\"\${to%/}/\$name\"\nbase=\$name\nn=1\nwhile :; do\ncollision=0\n[ ! -e \"\$dst\" ] && [ ! -L \"\$dst\" ] || collision=1\n")
            if (i > 0) {
                s.append("for previous in")
                for (j in 0 until (i)) {
                    s.append(" \"\$dst").append(j).append("\"")
                }
                s.append("; do [ \"\$dst\" != \"\$previous\" ] || collision=1; done\n")
            }
            s.append("[ \"\$collision\" = 1 ] || break\n")
            if (!rename) {
                s.append("exit 23\n")
            } else {
                s.append("n=\$((n+1))\n[ \"\$n\" -le 10000 ] || exit 23\ndst=\"\${to%/}/\$stem (\$n)\$suffix\"\n")
            }
            s.append("done\ndst").append(i).append("=\$dst\n")
            i++
        }
        if (checkOnly) {
            return s.append("printf 'READY'\n").toString()
        }
        if (receipt == null) {
            receipt = ProjectFileReceipt(entries.size)
        }
        // GNU/Termux mv -T -n never merges into a directory or overwrites a raced-in target.
        run {
            var i = 0
            while (i < entries.size) {
                s.append("src=\"\$src").append(i).append("\"\ndst=\"\$dst").append(i).append("\"\n")
                if (move) {
                    s.append("mv -T -n -- \"\$src\" \"\$dst\" || exit 24\nif [ -e \"\$src\" ] || [ -L \"\$src\" ]; then exit 23; fi\n")
                } else {
                    s.append("tmp=\$(mktemp -d -- \"\${to%/}/.termux-jobs-copy.XXXXXXXX\") || exit 24\ntrap 'rm -rf -- \"\$tmp\"' EXIT\ncp -R -P -- \"\$src\" \"\$tmp/item\" || exit 24\nmv -T -n -- \"\$tmp/item\" \"\$dst\" || exit 24\nif [ -e \"\$tmp/item\" ] || [ -L \"\$tmp/item\" ]; then exit 23; fi\nrmdir -- \"\$tmp\"\ntrap - EXIT\n")
                }
                s.append("printf '%s\\n' ").append(SessionShell.quote(receipt.progress(i + 1))).append("\n")
                i++
            }
        }
        return s.append("printf '%s\\n' ").append(SessionShell.quote(receipt.completion())).append("\n").toString()
    }
}
