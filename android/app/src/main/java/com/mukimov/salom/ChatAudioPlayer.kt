package com.mukimov.salom

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One player per chat, so two voice messages can never play over each other. */
internal class ChatAudioPlayer(context: Context, private val media: MediaFiles) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private var player: MediaPlayer? = null
    private var generation = 0
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change -> if (change < 0) pause() }
    private var focusRequest: AudioFocusRequest? = null
    var activeId by mutableStateOf<String?>(null); private set
    var playing by mutableStateOf(false); private set
    var loading by mutableStateOf(false); private set
    var positionMs by mutableLongStateOf(0); private set
    var durationMs by mutableLongStateOf(0); private set
    var error by mutableStateOf<String?>(null); private set
    @Suppress("DEPRECATION")
    private fun focus(): Boolean = if (Build.VERSION.SDK_INT >= 26) {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(focusListener).build()
        focusRequest = request
        manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    } else manager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    @Suppress("DEPRECATION")
    private fun abandonFocus() { if (Build.VERSION.SDK_INT >= 26) focusRequest?.let { manager.abandonAudioFocusRequest(it) } else manager.abandonAudioFocus(focusListener); focusRequest = null }
    fun pause() { runCatching { player?.takeIf { playing }?.pause() }; playing = false; abandonFocus() }
    fun stop() { generation++; runCatching { player?.release() }; player = null; activeId = null; playing = false; loading = false; positionMs = 0; durationMs = 0; error = null; abandonFocus() }
    fun tick() { if (playing) positionMs = runCatching { player?.currentPosition?.toLong() ?: 0 }.getOrDefault(0).coerceAtMost(durationMs) }
    fun seek(fraction: Float) {
        if (loading || player == null) return
        val value = (durationMs * fraction.coerceIn(0f, 1f)).toInt()
        if (runCatching { player?.seekTo(value) }.isSuccess) positionMs = value.toLong()
    }
    suspend fun toggle(attachment: Attachment) {
        if (activeId == attachment.id && player != null && !loading) {
            if (playing) pause() else if (focus()) { runCatching { player?.start() }.onSuccess { playing = true }.onFailure { error = "Не удалось воспроизвести" } }
            return
        }
        stop(); val ticket = generation
        activeId = attachment.id; durationMs = attachment.durationMs; loading = true
        try {
            val file = media.file(attachment)
            if (ticket != generation) return
            val instance = MediaPlayer(); player = instance
            instance.setAudioAttributes(attributes)
            instance.setOnPreparedListener { prepared ->
                if (ticket == generation) {
                    try {
                        durationMs = prepared.duration.toLong().coerceAtLeast(0); loading = false
                        if (focus()) { prepared.start(); playing = true } else error = "Звук сейчас занят другим приложением"
                    } catch (_: Exception) {
                        stop(); activeId = attachment.id; error = "Не удалось воспроизвести аудио. Нажми для повтора"
                    }
                }
            }
            instance.setOnCompletionListener { if (ticket == generation) { playing = false; positionMs = 0; runCatching { it.seekTo(0) }; abandonFocus() } }
            instance.setOnErrorListener { _, _, _ ->
                if (ticket == generation) { stop(); activeId = attachment.id; error = "Не удалось открыть аудио. Нажми для повтора" }
                true
            }
            instance.setDataSource(file.path)
            if (ticket != generation) return
            instance.prepareAsync()
        } catch (cancelled: CancellationException) { if (ticket == generation) stop(); throw cancelled }
        catch (_: Exception) { if (ticket == generation) { stop(); activeId = attachment.id; error = "Не удалось загрузить аудио. Нажми для повтора" } }
    }
}
