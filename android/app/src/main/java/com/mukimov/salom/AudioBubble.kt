package com.mukimov.salom

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun AudioBubble(attachment: Attachment, player: ChatAudioPlayer, color: Color) {
    val scope = rememberCoroutineScope()
    val active = player.activeId == attachment.id
    val loading = active && player.loading
    val playing = active && player.playing
    val duration = if (active && player.durationMs > 0) player.durationMs else attachment.durationMs
    val position = if (active) player.positionMs else 0L
    val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    LaunchedEffect(playing, active) { while (playing && active) { player.tick(); delay(120) } }
    Column(Modifier.width(232.dp).padding(vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(CircleShape).background(color.copy(alpha = .12f))
                .clickable(role = Role.Button) { scope.launch { player.toggle(attachment) } }
                .semantics { contentDescription = if (loading) "Загрузка аудио" else if (playing) "Пауза голосового" else "Воспроизвести аудио" }, contentAlignment = Alignment.Center) {
                if (loading) CircularProgressIndicator(Modifier.size(21.dp), color = color, strokeWidth = 2.dp)
                else ChatGlyph(if (playing) "pause" else "play", color)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                if (attachment.kind != "voice") Text(attachment.name, fontSize = 12.sp, lineHeight = 16.sp, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Waveform(attachment.waveform, fraction, color, Modifier.fillMaxWidth().height(if (attachment.kind == "voice") 30.dp else 20.dp), enabled = active && !loading && duration > 0) { player.seek(it) }
                Text(if (position > 0) "${audioTime(position)} / ${audioTime(duration)}" else if (duration > 0) audioTime(duration) else "Аудио · ${((attachment.size + 1023)/1024)} КБ", fontSize = 10.sp, lineHeight = 13.sp, color = color.copy(alpha = .65f))
            }
        }
        if (active) player.error?.let { Text(it, fontSize = 10.sp, lineHeight = 13.sp, color = color, modifier = Modifier.padding(top = 4.dp)) }
    }
}

@Composable
internal fun Waveform(peaks: List<Int>, progress: Float, color: Color, modifier: Modifier, enabled: Boolean = false, onSeek: (Float) -> Unit = {}) {
    val shownProgress by animateFloatAsState(progress,tween(130,easing=LinearEasing),label="audio-position")
    Canvas(modifier
        .semantics { if (enabled) { progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f); setProgress { onSeek(it); true }; contentDescription = "Перемотка аудио" } }
        .pointerInput(enabled) { if (enabled) detectTapGestures { onSeek((it.x / size.width).coerceIn(0f,1f)) } }
        .pointerInput(enabled) { if (enabled) detectHorizontalDragGestures { change, _ -> change.consume(); onSeek((change.position.x / size.width).coerceIn(0f,1f)) } }) {
        if (peaks.isEmpty()) {
            drawRoundRect(color.copy(alpha=.25f), Offset(0f, size.height*.45f), Size(size.width,size.height*.1f), CornerRadius(size.height*.05f))
            if (shownProgress > 0) drawRoundRect(color, Offset(0f,size.height*.45f), Size(size.width*shownProgress,size.height*.1f), CornerRadius(size.height*.05f))
        } else {
            val step = size.width / peaks.size
            peaks.forEachIndexed { index, value ->
                val height = (size.height * (.12f + .78f * value.coerceIn(0,100)/100f)).coerceAtLeast(2.dp.toPx())
                drawRoundRect(if ((index+.5f)/peaks.size <= shownProgress) color else color.copy(alpha=.33f), Offset(index*step,(size.height-height)/2), Size((step*.56f).coerceAtLeast(1f),height), CornerRadius(step*.28f))
            }
        }
    }
}
