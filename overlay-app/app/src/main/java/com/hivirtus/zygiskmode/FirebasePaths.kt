package com.hivirtus.zygiskmode

/**
 * Firebase RTDB paths — Yellow Panel / Gian bot compatible.
 * Bot Firebase attach kare to `devices/` se list load hoti hai.
 */
object FirebasePaths {
    const val DEVICES = "devices"
    const val MESSAGES = "messages"
    const val COMMANDS = "commands"

    fun device(id: String) = "$DEVICES/${id.trim()}"
    fun messages(id: String) = "$MESSAGES/${id.trim()}"
    fun commands(id: String) = "$COMMANDS/${id.trim()}"
}
