package com.hivirtus.zygiskmode

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Firebase Realtime Database REST — no Firebase SDK.
 */
class FirebaseRestClient(private val config: FirebaseAutoTokenStore.Config) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    fun push(path: String, payload: Map<String, Any?>): String? {
        val url = endpoint(path)
        val body = JSONObject(payload).toString().toRequestBody(jsonMedia)
        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "push failed ${response.code} path=$path")
                    return null
                }
                val text = response.body?.string().orEmpty()
                JSONObject(text).optString("name").ifBlank { null }
            }
        } catch (e: Exception) {
            Log.w(TAG, "push error: ${e.message}")
            null
        }
    }

    fun patch(path: String, payload: Map<String, Any?>): Boolean {
        val url = endpoint(path)
        val body = JSONObject(payload).toString().toRequestBody(jsonMedia)
        val request = Request.Builder()
            .url(url)
            .patch(body)
            .build()
        return try {
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.w(TAG, "patch error: ${e.message}")
            false
        }
    }

    fun put(path: String, payload: Map<String, Any?>): Boolean {
        val url = endpoint(path)
        val body = JSONObject(payload).toString().toRequestBody(jsonMedia)
        val request = Request.Builder()
            .url(url)
            .put(body)
            .build()
        return try {
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.w(TAG, "put error: ${e.message}")
            false
        }
    }

    fun getShallow(path: String): JSONObject? {
        val url = shallowEndpoint(path)
        val request = Request.Builder().url(url).get().build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val text = response.body?.string().orEmpty()
                if (text == "null" || text.isBlank()) return JSONObject()
                JSONObject(text)
            }
        } catch (e: Exception) {
            Log.w(TAG, "getShallow error: ${e.message}")
            null
        }
    }

    fun testConnection(): Boolean = getShallow("") != null || getShallow("devices") != null

    private fun shallowEndpoint(path: String): String {
        val base = normalizeDbUrl(config.dbUrl)
        val cleanPath = path.trim().trim('/')
        val pathPart = if (cleanPath.isBlank()) "" else "/$cleanPath"
        val auth = if (config.dbAuthSecret.isNotBlank()) {
            "&auth=${config.dbAuthSecret.trim()}"
        } else {
            ""
        }
        return "$base$pathPart.json?shallow=true$auth"
    }

    fun get(path: String): JSONObject? {
        val url = endpoint(path)
        val request = Request.Builder().url(url).get().build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val text = response.body?.string().orEmpty()
                if (text == "null" || text.isBlank()) return null
                JSONObject(text)
            }
        } catch (e: Exception) {
            Log.w(TAG, "get error: ${e.message}")
            null
        }
    }

    private fun endpoint(path: String): String {
        val base = normalizeDbUrl(config.dbUrl)
        val cleanPath = path.trim('/')
        val suffix = if (config.dbAuthSecret.isNotBlank()) {
            "?auth=${config.dbAuthSecret.trim()}"
        } else {
            ""
        }
        return "$base/$cleanPath.json$suffix"
    }

    companion object {
        private const val TAG = "FirebaseRestClient"

        fun normalizeDbUrl(raw: String): String {
            var url = raw.trim()
            if (url.isBlank()) return ""
            if (!url.startsWith("http")) url = "https://$url"
            url = url.removeSuffix("/")
            url = url.removeSuffix(".json")
            return url
        }
    }
}
