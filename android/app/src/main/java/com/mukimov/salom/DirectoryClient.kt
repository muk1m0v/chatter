package com.mukimov.salom

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.UUID

internal class DirectoryException(message: String) : Exception(message)

/** Installation credential authenticates only this public profile; phones/emails never leave the device. */
internal class DirectoryClient(context: Context) {
    private val identity = context.getSharedPreferences("salom_directory", Context.MODE_PRIVATE)
    private val local = context.getSharedPreferences("salom_local", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private var syncedSignature: Pair<String, String>? = null
    val ownId: String = identity.getString("device_id", null) ?: UUID.randomUUID().toString().also { identity.edit().putString("device_id", it).commit() }
    private val token: String = identity.getString("token", null) ?: ByteArray(32).also { SecureRandom().nextBytes(it) }.let { Base64.encodeToString(it, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING) }.also { identity.edit().putString("token", it).commit() }

    private fun request(path: String, body: JSONObject? = null): JSONObject {
        val connection = URL("https://mukimov.site/salom/api/$path").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 7000
            connection.readTimeout = 10000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.requestMethod = "PUT"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                val data = body.toString().toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(data.size)
                connection.outputStream.use { it.write(data) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val result = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (code !in 200..299) {
                val detail = result.opt("detail")
                throw DirectoryException(if (detail is String && detail.isNotBlank()) detail else "Каталог временно недоступен ($code)")
            }
            return result
        } catch (exception: DirectoryException) { throw exception }
        catch (exception: Exception) { throw DirectoryException("Не удалось связаться с сервером. Проверь интернет и попробуй снова") }
        finally { connection.disconnect() }
    }

    suspend fun publish(name: String, username: String): SalomAccount = withContext(Dispatchers.IO) {
        mutex.withLock {
            val result = request("profile", JSONObject().put("device_id", ownId).put("name", name.trim()).put("username", username.trim()))
            syncedSignature = name.trim() to username.trim()
            account(result)
        }
    }

    suspend fun syncLocal() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val name = local.getString("name", "").orEmpty().trim()
            val username = local.getString("username", "").orEmpty().trim()
            if (name.isNotEmpty() && syncedSignature != (name to username)) {
                request("profile", JSONObject().put("device_id", ownId).put("name", name).put("username", username))
                syncedSignature = name to username
            }
        }
    }

    suspend fun search(query: String): List<SalomAccount> = withContext(Dispatchers.IO) {
        syncLocal()
        val prefix = query.trim().removePrefix("@")
        if (!prefix.matches(Regex("[A-Za-z0-9_]{1,24}"))) return@withContext emptyList()
        val array = request("search?q=" + URLEncoder.encode(prefix, "UTF-8")).getJSONArray("profiles")
        (0 until array.length()).map { account(array.getJSONObject(it)) }
    }

    private fun account(json: JSONObject) = SalomAccount(json.getString("id"), json.getString("name"), json.optString("username"), "", json.optBoolean("is_active"))
}
