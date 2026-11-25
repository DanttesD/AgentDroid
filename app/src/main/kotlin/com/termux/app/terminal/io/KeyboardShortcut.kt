package com.termux.app.terminal.io

open class KeyboardShortcut {

    @JvmField val codePoint: Int
    @JvmField val shortcutAction: Int

    constructor(codePoint: Int, shortcutAction: Int) {
        this.codePoint = codePoint
        this.shortcutAction = shortcutAction
    }
}
