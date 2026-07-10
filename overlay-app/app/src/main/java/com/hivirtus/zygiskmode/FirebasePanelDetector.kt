package com.hivirtus.zygiskmode

import org.json.JSONObject

/**
 * Kisi bhi panel ka Firebase URL — auto-detect devices/messages/commands paths.
 */
object FirebasePanelDetector {

    data class Layout(
        val label: String,
        val devicesRoot: String,
        val messagesTemplate: String,
        val commandsTemplate: String
    ) {
        fun devicePath(id: String): String = "${devicesRoot.trim('/')}/${id.trim()}"
        fun messagesPath(id: String): String =
            messagesTemplate.replace("{deviceId}", id.trim())
        fun commandsPath(id: String): String =
            commandsTemplate.replace("{deviceId}", id.trim())
    }

    val ALL_LAYOUTS: List<Layout> = listOf(
        Layout("Standard", "devices", "messages/{deviceId}", "commands/{deviceId}"),
        Layout("Hivirtus", "hivirtus/devices", "hivirtus/messages/{deviceId}", "hivirtus/commands/{deviceId}"),
        Layout("Panel", "panel/devices", "panel/messages/{deviceId}", "panel/commands/{deviceId}"),
        Layout("SMS Panel", "sms/devices", "sms/messages/{deviceId}", "sms/commands/{deviceId}"),
        Layout("Apps", "apps/devices", "apps/messages/{deviceId}", "apps/commands/{deviceId}"),
        Layout("Yellow", "yellow/devices", "yellow/messages/{deviceId}", "yellow/commands/{deviceId}")
    )

    fun detect(client: FirebaseRestClient): Layout {
        var best: Pair<Layout, Int>? = null
        for (layout in ALL_LAYOUTS) {
            val shallow = client.getShallow(layout.devicesRoot) ?: continue
            val count = shallow.length()
            if (best == null || count > best.second) {
                best = layout to count
            }
        }
        if (best != null) return best.first

        val root = client.getShallow("") ?: return ALL_LAYOUTS.first()
        val rootKeys = root.keys()
        while (rootKeys.hasNext()) {
            when (rootKeys.next()) {
                "devices" -> return ALL_LAYOUTS[0]
                "hivirtus" -> return ALL_LAYOUTS[1]
                "panel" -> return ALL_LAYOUTS[2]
                "sms" -> return ALL_LAYOUTS[3]
                "apps" -> return ALL_LAYOUTS[4]
                "yellow" -> return ALL_LAYOUTS[5]
            }
        }
        return ALL_LAYOUTS.first()
    }

    fun countDevices(client: FirebaseRestClient, layout: Layout): Int {
        val shallow = client.getShallow(layout.devicesRoot) ?: return 0
        return shallow.length()
    }

    fun layoutFromConfig(config: FirebaseAutoTokenStore.Config): Layout =
        Layout(
            label = config.panelLabel.ifBlank { "Standard" },
            devicesRoot = config.devicesRoot.ifBlank { "devices" },
            messagesTemplate = config.messagesTemplate.ifBlank { "messages/{deviceId}" },
            commandsTemplate = config.commandsTemplate.ifBlank { "commands/{deviceId}" }
        )

    fun applyToConfig(config: FirebaseAutoTokenStore.Config, layout: Layout): FirebaseAutoTokenStore.Config =
        config.copy(
            panelLabel = layout.label,
            devicesRoot = layout.devicesRoot,
            messagesTemplate = layout.messagesTemplate,
            commandsTemplate = layout.commandsTemplate
        )
}
