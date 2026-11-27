package com.termux.app.agent

/** Browser profiles 1..7: same screen, separate process and separate cookies (see AndroidManifest).*/
class WebBrowserSlots {
    private constructor()

    open class Slot1 : WebBrowserActivity() {
        protected override fun slot(): Int {
            return 1
        }
    }

    open class Slot2 : WebBrowserActivity() {
        protected override fun slot(): Int {
            return 2
        }
    }

    open class Slot3 : WebBrowserActivity() {
        protected override fun slot(): Int {
            return 3
        }
    }

    open class Slot4 : WebBrowserActivity() {
        protected override fun slot(): Int {
            return 4
        }
    }

    open class Slot5 : WebBrowserActivity() {
        protected override fun slot(): Int {
            return 5
        }
    }

    open class Slot6 : WebBrowserActivity() {
        protected override fun slot(): Int {
            return 6
        }
    }

    open class Slot7 : WebBrowserActivity() {
        protected override fun slot(): Int {
            return 7
        }
    }
}
