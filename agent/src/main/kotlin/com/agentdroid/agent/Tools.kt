package com.agentdroid.agent

import com.agentdroid.exec.ExecBackend
import com.agentdroid.exec.ExecRequest
import com.agentdroid.exec.ShellQuote

/** Built-in tools; every one runs through the selected [ExecBackend]. */
object Tools {
    @JvmStatic
    fun standard(backend: ExecBackend, workDir: String?): List<Tool> {
        val l = ArrayList<Tool>()
        l.add(Shell(backend, workDir))
        l.add(ReadFile(backend, workDir))
        l.add(WriteFile(backend, workDir))
        l.add(ListDir(backend, workDir))
        return l
    }

    internal fun str(a: Map<String, Any?>, k: String): String {
        val v = a[k]
        if (v !is String || v.isEmpty()) throw IllegalArgumentException("missing argument: $k")
        return v
    }

    internal fun resolve(workDir: String?, p: String): String {
        if (p.startsWith("/") || workDir == null) return p
        return workDir + (if (workDir.endsWith("/")) "" else "/") + p
    }

    internal fun schema(names: Array<String>, descs: Array<String>, vararg required: String): Map<String, Any?> {
        val props = LinkedHashMap<String, Any?>()
        for (i in names.indices) props[names[i]] = Json.obj("type", "string", "description", descs[i])
        val req = ArrayList<Any?>()
        for (r in required) req.add(r)
        return Json.obj("type", "object", "properties", props, "required", req)
    }

    class Shell(private val b: ExecBackend, private val dir: String?) : Tool {
        override fun spec(): ToolSpec = ToolSpec(
            "shell", "Run a shell command on the device and get exit code, stdout and stderr.",
            schema(arrayOf("command"), arrayOf("Shell command"), "command"),
        )

        override fun needsApproval(args: Map<String, Any?>): Boolean = true

        override fun describe(args: Map<String, Any?>): String = "Run: " + args["command"]

        @Throws(Exception::class)
        override fun execute(args: Map<String, Any?>): String {
            val rb = ExecRequest.builder(str(args, "command")).timeoutMs(60_000L)
            if (dir != null) rb.workingDir(dir)
            val r = b.exec(rb.build())
            val sb = StringBuilder()
            if (r.timedOut) sb.append("[timed out]\n")
            sb.append("exit code: ").append(r.exitCode).append('\n')
            if (r.stdout().isNotEmpty()) sb.append("stdout:\n").append(r.stdout()).append('\n')
            if (r.stderr().isNotEmpty()) sb.append("stderr:\n").append(r.stderr()).append('\n')
            return sb.toString()
        }
    }

    class ReadFile(private val b: ExecBackend, private val dir: String?) : Tool {
        override fun spec(): ToolSpec =
            ToolSpec("read_file", "Read a text file.", schema(arrayOf("path"), arrayOf("File path"), "path"))

        override fun needsApproval(args: Map<String, Any?>): Boolean = false

        override fun describe(args: Map<String, Any?>): String = "Read " + args["path"]

        @Throws(Exception::class)
        override fun execute(args: Map<String, Any?>): String =
            String(b.readFile(resolve(dir, str(args, "path"))), Charsets.UTF_8)
    }

    class WriteFile(private val b: ExecBackend, private val dir: String?) : Tool {
        override fun spec(): ToolSpec = ToolSpec(
            "write_file", "Create or overwrite a file with the given content.",
            schema(arrayOf("path", "content"), arrayOf("File path", "Full file content"), "path", "content"),
        )

        override fun needsApproval(args: Map<String, Any?>): Boolean = true

        override fun describe(args: Map<String, Any?>): String {
            val c = args["content"]
            return "Write " + args["path"] + " (" + (if (c is String) c.length else 0) + " chars)"
        }

        @Throws(Exception::class)
        override fun execute(args: Map<String, Any?>): String {
            val c = args["content"]
            val content = if (c is String) c else ""
            b.writeFile(resolve(dir, str(args, "path")), content.toByteArray(Charsets.UTF_8))
            return "written " + content.length + " chars"
        }
    }

    class ListDir(private val b: ExecBackend, private val dir: String?) : Tool {
        override fun spec(): ToolSpec = ToolSpec(
            "list_dir", "List a directory (ls -la).",
            schema(arrayOf("path"), arrayOf("Directory path"), "path"),
        )

        override fun needsApproval(args: Map<String, Any?>): Boolean = false

        override fun describe(args: Map<String, Any?>): String = "List " + args["path"]

        @Throws(Exception::class)
        override fun execute(args: Map<String, Any?>): String {
            val r = b.exec(ExecRequest.builder("ls -la " + ShellQuote.quote(resolve(dir, str(args, "path")))).build())
            return if (r.ok()) r.stdout() else "error: " + r.stderr()
        }
    }
}
