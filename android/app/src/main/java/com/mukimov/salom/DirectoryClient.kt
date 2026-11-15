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
internal data class MessageEvent(val id: Long, val messageId: Long, val kind: String, val message: Message? = null)
internal data class MessageBatch(val messages: List<Message>, val receipts: Map<Long, String>, val events: List<MessageEvent>, val hasMore: Boolean, val clearedThrough: Long)

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

    private fun account(json: JSONObject) = SalomAccount(json.getString("id"), json.getString("name"), json.optString("username"), "", json.optBoolean("is_active"), json.optString("avatar_url"))

    suspend fun sendMessage(peerId: String, text: String, clientId: String, attachment: Attachment? = null, replyToId: Long? = null): Message = withContext(Dispatchers.IO) {
        syncLocal()
        message(request("messages", JSONObject().put("recipient_id", peerId).put("text", text).put("client_id", clientId).apply { attachment?.let { put("attachment_id", it.id) }; replyToId?.let { put("reply_to_id", it) } }, "POST"))
    }

    suspend fun heartbeat() = withContext(Dispatchers.IO) {
        syncLocal(); request("presence", JSONObject(), "POST"); Unit
    }

    suspend fun peerOnline(peerId: String): Boolean = withContext(Dispatchers.IO) {
        syncLocal(); request("profiles/" + URLEncoder.encode(peerId, "UTF-8")).optBoolean("is_online")
    }

    suspend fun setTyping(peerId: String, typing: Boolean) = withContext(Dispatchers.IO) {
        syncLocal(); request("typing", JSONObject().put("peer_id", peerId).put("typing", typing), "POST"); Unit
    }

    suspend fun peerTyping(peerId: String): Boolean = withContext(Dispatchers.IO) {
        syncLocal(); request("typing?peer_id=" + URLEncoder.encode(peerId, "UTF-8")).optBoolean("typing")
    }

    suspend fun messages(peerId: String, after: Long, receiptsAfter: Long = 0, eventsAfter: Long = 0): MessageBatch = withContext(Dispatchers.IO) {
        syncLocal()
        val result = request("messages?peer_id=" + URLEncoder.encode(peerId, "UTF-8") + "&after=$after&receipts_after=$receiptsAfter&events_after=$eventsAfter")
        val array = result.getJSONArray("messages")
        val receiptArray = result.optJSONArray("receipts")
        val receipts = if (receiptArray == null) emptyMap() else (0 until receiptArray.length()).associate { index -> receiptArray.getJSONObject(index).getLong("id") to receiptArray.getJSONObject(index).optString("receipt", "sent") }
        val eventsJson = result.optJSONArray("events")
        val events = if (eventsJson == null) emptyList() else (0 until eventsJson.length()).map { i -> eventsJson.getJSONObject(i).let { MessageEvent(it.getLong("id"),it.getLong("message_id"),it.getString("kind"),it.optJSONObject("message")?.let(::message)) } }
        MessageBatch((0 until array.length()).map { message(array.getJSONObject(it)) }, receipts, events, result.optBoolean("has_more"), result.optLong("cleared_through"))
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
    suspend fun editMessage(id: Long, text: String): Message = withContext(Dispatchers.IO) { message(request("messages/$id", JSONObject().put("text",text), "PUT")) }
    suspend fun deleteMessage(id: Long, everyone: Boolean) = withContext(Dispatchers.IO) { request("messages/$id?scope="+(if(everyone) "everyone" else "me"),method="DELETE"); Unit }
    suspend fun startLiveLocation(peerId: String, minutes: Int): Pair<String,Message> = withContext(Dispatchers.IO) {
        val result=request("locations",JSONObject().put("peer_id",peerId).put("duration_minutes",minutes),"POST")
        result.getString("id") to message(result.getJSONObject("message"))
    }
    suspend fun sendLocationOnce(peerId:String,latitude:Double,longitude:Double):Message = withContext(Dispatchers.IO) {
        message(request("locations/once",JSONObject().put("peer_id",peerId).put("latitude",latitude).put("longitude",longitude),"POST").getJSONObject("message"))
    }
    suspend fun updateLiveLocation(id:String,latitude:Double,longitude:Double) = withContext(Dispatchers.IO) { request("locations/$id",JSONObject().put("latitude",latitude).put("longitude",longitude),"PUT"); Unit }
    suspend fun liveLocation(id:String):JSONObject = withContext(Dispatchers.IO) { request("locations/$id") }
    suspend fun stopLiveLocation(id:String) = withContext(Dispatchers.IO) { request("locations/$id",method="DELETE"); Unit }
    private fun mediaConnection(path: String): HttpURLConnection = (URL("https://mukimov.site/salom/api/" + path).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10000; readTimeout = 60000; instanceFollowRedirects = false
        setRequestProperty("Authorization", "Bearer $token")
    }
    private fun mediaError(connection: HttpURLConnection): Nothing {
        val body = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val detail = runCatching { JSONObject(body).optString("detail") }.getOrDefault("")
        throw DirectoryException(detail.ifBlank { "Не удалось передать файл. Попробуй снова" }, connection.responseCode)
    }
    internal fun uploadAttachment(peer: String, attachment: Attachment, file: java.io.File, progress: (Float) -> Unit = {}): Attachment {
        val connection = mediaConnection("attachments?peer_id=" + URLEncoder.encode(peer, "UTF-8") + "&name=" + URLEncoder.encode(attachment.name, "UTF-8") + "&kind=" + attachment.kind + "&duration_ms=" + attachment.durationMs + "&waveform=" + URLEncoder.encode(attachment.waveform.joinToString(","), "UTF-8"))
        try {
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.setRequestProperty("Content-Type", attachment.mime)
            connection.setFixedLengthStreamingMode(file.length())
            connection.outputStream.use { output -> file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024); var sent = 0L
                while (true) { val count = input.read(buffer); if (count < 0) break; output.write(buffer, 0, count); sent += count; progress((sent.toFloat() / file.length()).coerceIn(0f, 1f)) }
            } }
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
        val quote = json.optJSONObject("reply_to")
        val quoteSender = quote?.optString("sender_id")
        return Message(json.getString("text"), json.getString("sender_id") == ownId, time, json.getLong("id"), json.optJSONObject("attachment")?.let { Attachment.from(it) }, json.optString("receipt", "sent"), quote?.optLong("id")?.takeIf { it > 0 }, quoteSender?.let { if (it == ownId) "Вы" else "Собеседник" }, quote?.optString("text"), json.optDouble("edited_at",0.0)>0, json.optString("location_id").takeIf { it.isNotBlank() && it!="null" })
    }
}
