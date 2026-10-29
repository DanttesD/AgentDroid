# :adb

Own ADB client (no Shizuku, no external app). Ported from the Shizuku manager
`adb` package (Apache-2.0, see LICENSE-Shizuku-Apache-2.0); package renamed to
`com.agentdroid.adb`, Rikka helper dependencies removed, hidden Conscrypt call
replaced with reflection (`ConscryptCompat`), native pairing library renamed
to `libagentadb.so`.

Contents: ADB protocol/messages/client, TLS pairing (Android 11+), mDNS
discovery, key storage. Not yet verified on a device; no Android SDK in the
authoring sandbox.
