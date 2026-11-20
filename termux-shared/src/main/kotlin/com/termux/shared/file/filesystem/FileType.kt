package com.termux.shared.file.filesystem

/** The [Enum] that defines file types.*/
enum class FileType {

    NO_EXIST("no exist", 0), // 0000000
    REGULAR("regular", 1), // 0000001
    DIRECTORY("directory", 2), // 0000010
    SYMLINK("symlink", 4), // 0000100
    CHARACTER("character", 8), // 0001000
    FIFO("fifo", 16), // 0010000
    BLOCK("block", 32), // 0100000
    UNKNOWN("unknown", 64); // 1000000

    private val name_: String
    private val value: Int

    constructor(name: String, value: Int) {
        this.name_ = name
        this.value = value
    }

    fun getName(): String {
        return name_
    }

    fun getValue(): Int {
        return value
    }
}
