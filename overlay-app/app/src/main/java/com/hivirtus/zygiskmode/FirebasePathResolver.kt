package com.hivirtus.zygiskmode

object FirebasePathResolver {

    fun layout(config: FirebaseAutoTokenStore.Config): FirebasePanelDetector.Layout =
        FirebasePanelDetector.layoutFromConfig(config)

    fun device(config: FirebaseAutoTokenStore.Config, id: String): String =
        layout(config).devicePath(id)

    fun messages(config: FirebaseAutoTokenStore.Config, id: String): String =
        layout(config).messagesPath(id)

    fun commands(config: FirebaseAutoTokenStore.Config, id: String): String =
        layout(config).commandsPath(id)

    fun mirrorDevicePaths(deviceId: String): List<String> =
        FirebasePanelDetector.ALL_LAYOUTS.map { it.devicePath(deviceId) }.distinct()

    fun mirrorMessagePaths(deviceId: String): List<String> =
        FirebasePanelDetector.ALL_LAYOUTS.map { it.messagesPath(deviceId) }.distinct()

    fun mirrorCommandPaths(deviceId: String): List<String> =
        FirebasePanelDetector.ALL_LAYOUTS.map { it.commandsPath(deviceId) }.distinct()
}
