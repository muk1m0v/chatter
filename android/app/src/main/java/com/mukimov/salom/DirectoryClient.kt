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

internal class DirectoryException(message: String, val status: Int = 0) : Exception(message)
internal data class ConversationPreview(val account: SalomAccount, val lastText: String)
internal data class MessageBatch(val messages: List<Message>, val hasMore: Boolean, val clearedThrough: Long)

/** Private installation/session credential for the Salom API. Google sign-in uses AuthClient. */
internal class DirectoryClient(context: Context) {
    private val identity = context.getSharedPreferences("salom_directory", Context.MODE_PRIVATE)
    private val local = context.getSharedPreferences("salom_local", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private var syncedSignature: Pair<String, String>? = null
    val ownId: String = identity.getString("device_id", null) ?: UUID.randomUUID().toString().also { identity.edit().putString("device_id", it).commit() }
    private val token: String = identity.getString("token", null) ?: ByteArray(32).also { SecureRandom().nextBytes(it) }.let { Base64.encodeToString(it, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING) }.also { identity.edit().putString("token", it).commit() }
    init {
        // Recover the profile projection if the process stopped between the two preference writes.
        if (local.getString("owner_profile_id", "") != ownId) {
            identity.getString("auth_snapshot", null)?.let { raw ->
                runCatching {
                    val snapshot = JSONObject(raw)
                    val profile = snapshot.getJSONObject("profile")
                    if (profile.getString("id") == ownId) local.edit().remove("about")
                        .putString("name", profile.getString("name")).putString("username", profile.getString("username"))
                        .remove("phone").putString("email", snapshot.optString("email"))
                        .putString("owner_profile_id", ownId).putString("account_verified", "yes").putString("signed_in", "yes").commit()
                }
            }
        }
    }

    internal fun request(path: String, body: JSONObject? = null, method: String = if (body != null) "PUT" else "GET"): JSONObject {
        val connection = URL("https://mukimov.site/salom/api/$path").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 7000
            connection.readTimeout = 10000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")
            connection.requestMethod = method
            if (body != null) {
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
                throw DirectoryException(if (detail is String && detail.isNotBlank()) detail else "Сервер временно недоступен ($code)", code)
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

    suspend fun sendMessage(peerId: String, text: String, clientId: String, attachment: Attachment? = null): Message = withContext(Dispatchers.IO) {
        syncLocal()
        message(request("messages", JSONObject().put("recipient_id", peerId).put("text", text).put("client_id", clientId).apply { attachment?.let { put("attachment_id", it.id) } }, "POST"))
    }

    suspend fun messages(peerId: String, after: Long): MessageBatch = withContext(Dispatchers.IO) {
        syncLocal()
        val result = request("messages?peer_id=" + URLEncoder.encode(peerId, "UTF-8") + "&after=$after")
        val array = result.getJSONArray("messages")
        MessageBatch((0 until array.length()).map { message(array.getJSONObject(it)) }, result.optBoolean("has_more"), result.optLong("cleared_through"))
    }

    suspend fun conversations(): List<ConversationPreview> = withContext(Dispatchers.IO) {
        syncLocal()
        val array = request("conversations").getJSONArray("conversations")
        (0 until array.length()).map { val item = array.getJSONObject(it); ConversationPreview(account(item.getJSONObject("profile")), item.getString("last_text")) }
    }

    suspend fun registerPush(pushToken: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        syncLocal()
        request("push-token", JSONObject().put("token", pushToken).put("enabled", enabled))
        Unit
    }

    suspend fun peer(peerId: String): SalomAccount = withContext(Dispatchers.IO) {
        syncLocal()
        account(request("profiles/" + URLEncoder.encode(peerId, "UTF-8")))
    }


    suspend fun clearConversation(peerId: String): Long = withContext(Dispatchers.IO) {
        request("messages?peer_id=" + URLEncoder.encode(peerId, "UTF-8"), method = "DELETE").getLong("cleared_through")
    }
    private fun mediaConnection(path: String): HttpURLConnection = (URL("https://mukimov.site/salom/api/" + path).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10000; readTimeout = 60000; instanceFollowRedirects = false
        setRequestProperty("Authorization", "Bearer $token")
    }
    private fun mediaError(connection: HttpURLConnection): Nothing {
        val body = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val detail = runCatching { JSONObject(body).optString("detail") }.getOrDefault("")
        throw DirectoryException(detail.ifBlank { "Не удалось передать файл. Попробуй снова" }, connection.responseCode)
    }
    internal fun uploadAttachment(peer: String, attachment: Attachment, file: java.io.File): Attachment {
        val connection = mediaConnection("attachments?peer_id=" + URLEncoder.encode(peer, "UTF-8") + "&name=" + URLEncoder.encode(attachment.name, "UTF-8") + "&kind=" + attachment.kind + "&duration_ms=" + attachment.durationMs + "&waveform=" + URLEncoder.encode(attachment.waveform.joinToString(","), "UTF-8"))
        try {
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.setRequestProperty("Content-Type", attachment.mime)
            connection.setFixedLengthStreamingMode(file.length())
            connection.outputStream.use { output -> file.inputStream().use { it.copyTo(output, 64 * 1024) } }
            if (connection.responseCode !in 200..299) mediaError(connection)
            return Attachment.from(JSONObject(connection.inputStream.bufferedReader().use { it.readText() }))
        } catch (failure: DirectoryException) { throw failure }
        catch (_: Exception) { throw DirectoryException("Не удалось загрузить файл. Проверь интернет и повтори") }
        finally { connection.disconnect() }
    }
    internal fun downloadAttachment(attachment: Attachment, file: java.io.File) {
        val connection = mediaConnection("attachments/" + attachment.id)
        val temporary = java.io.File(file.parentFile, attachment.id + ".part")
        try {
            if (connection.responseCode !in 200..299) mediaError(connection)
            var total = 0L
            connection.inputStream.use { input -> temporary.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val count = input.read(buffer); if (count < 0) break; total += count
                    if (total > MediaFiles.MAX_FILE || total > attachment.size) throw DirectoryException("Неверный размер вложения")
                    output.write(buffer, 0, count)
                }
            } }
            if (total != attachment.size) throw DirectoryException("Файл загружен не полностью. Повтори попытку")
            if (!temporary.renameTo(file)) throw DirectoryException("Не удалось сохранить файл")
        } catch (failure: DirectoryException) { throw failure }
        catch (_: Exception) { throw DirectoryException("Не удалось скачать файл. Проверь интернет и повтори") }
        finally { temporary.delete(); connection.disconnect() }
    }

    private fun message(json: JSONObject): Message {
        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date((json.getDouble("created") * 1000).toLong()))
        return Message(json.getString("text"), json.getString("sender_id") == ownId, time, json.getLong("id"), json.optJSONObject("attachment")?.let { Attachment.from(it) })
    }
}
