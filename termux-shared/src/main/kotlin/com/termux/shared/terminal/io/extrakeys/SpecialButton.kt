package com.termux.shared.terminal.io.extrakeys

/** The [Class] that implements special buttons for [ExtraKeysView].*/
open class SpecialButton {

    /** The special button key.*/
    private val key: String

    /**
     * Initialize a [SpecialButton].
     *
     * @param key The unique key name for the special button. The key is registered in [.map]
     *            with which the [SpecialButton] can be retrieved via a call to
     *            [.valueOf].
     **/
    constructor(key: String) {
        this.key = key
        map.put(key, this)
    }

    /** Get [.key] for this [SpecialButton].*/
    open fun getKey(): String {
        return key
    }

    override fun toString(): String {
        return key
    }

    companion object {

        private val map: HashMap<String, SpecialButton> = HashMap<String, SpecialButton>()

        @JvmField val CTRL: SpecialButton = SpecialButton("CTRL")
        @JvmField val ALT: SpecialButton = SpecialButton("ALT")
        @JvmField val SHIFT: SpecialButton = SpecialButton("SHIFT")
        @JvmField val FN: SpecialButton = SpecialButton("FN")

        /**
         * Get the [SpecialButton] for `key`.
         *
         * @param key The unique key name for the special button.
         **/
        @JvmStatic
        fun valueOf(key: String): SpecialButton? {
            return map.get(key)
        }
    }
}
