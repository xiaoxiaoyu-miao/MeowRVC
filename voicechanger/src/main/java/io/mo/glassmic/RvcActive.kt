package io.mo.glassmic

object RvcActive {
    @Volatile private var active = false
    @Volatile var blockMic: Boolean = false

    fun get(): Boolean = active
    fun set(value: Boolean) { active = value }
}
