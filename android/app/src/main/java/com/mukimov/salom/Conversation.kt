package com.mukimov.salom

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
internal fun Conversation(person: Person, store: Store, directory: DirectoryClient, onBack: () -> Unit) {
    val saved = person.id == "saved"
    var messages by remember(person.id) { mutableStateOf(if (saved) store.messages(person.id) else emptyList()) }
    var draft by remember(person.id) { mutableStateOf("") }
    var emojiOpen by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var connectionError by remember { mutableStateOf<String?>(null) }
    var sendError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(!saved) }
    var retry by remember { mutableIntStateOf(0) }
    var pendingText by remember { mutableStateOf("") }
    var pendingId by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val listState = rememberLazyListState()

    LaunchedEffect(person.id, retry) {
        if (!saved) {
            var cursor = 0L
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    try {
                        do {
                            val batch = directory.messages(person.id, cursor)
                            if (batch.messages.isNotEmpty()) {
                                messages = (messages + batch.messages).distinctBy { it.remoteId }.sortedBy { it.remoteId }
                                cursor = batch.messages.last().remoteId
                            }
                        } while (batch.hasMore)
                        connectionError = null
                    } catch (exception: CancellationException) { throw exception }
                    catch (exception: DirectoryException) { connectionError = exception.message }
                    finally { loading = false }
                    delay(2500)
                }
            }
        }
    }
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(0) }
    val send: () -> Unit = {
        val text = draft.trim()
        if (text.isNotEmpty() && !sending) {
            if (saved) {
                val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                messages = messages + Message(text, true, time)
                store.saveMessages(person.id, messages)
                draft = ""; emojiOpen = false
            } else {
                if (pendingText != text || pendingId.isEmpty()) { pendingText = text; pendingId = UUID.randomUUID().toString() }
                val clientId = pendingId
                sending = true; sendError = null
                scope.launch {
                    try {
                        val sent = directory.sendMessage(person.id, text, clientId)
                        messages = (messages + sent).distinctBy { it.remoteId }.sortedBy { it.remoteId }
                        if (draft.trim() == text) draft = ""
                        pendingText = ""; pendingId = ""; emojiOpen = false
                    } catch (exception: DirectoryException) { sendError = exception.message }
                    finally { sending = false }
                }
            }
        }
    }
    val dark = MaterialTheme.colorScheme.background.red < .5f
    val background = if (dark) listOf(Color(0xFF22291D), Color(0xFF1B211A)) else listOf(Color(0xFFFFF6D5), Color(0xFFF4F8E9))
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(background))) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp).fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surface).padding(2.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) { Text("‹", fontSize = 30.sp) }
            Box(Modifier.size(34.dp).clip(CircleShape).background(person.color), contentAlignment = Alignment.Center) { Text(person.initials, color = Ink, fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(person.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(if (saved) "Только для тебя" else "@${person.username}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val error = sendError ?: connectionError
        if (error != null) Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(error, color = MaterialTheme.colorScheme.error, fontSize = 11.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { retry++ }) { Text("Обновить", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface) }
        }
        if (messages.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (loading) CircularProgressIndicator(Modifier.size(26.dp), color = Yellow, strokeWidth = 2.dp)
                else {
                    Text(if (saved) "★" else "👋", fontSize = 42.sp, color = Yellow)
                    Text(if (saved) "Всё важное — здесь" else "Начни с «Салом»", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (saved) "Сохраняй мысли, планы и заметки" else "Напиши первое сообщение", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            }
        } else LazyColumn(state = listState, reverseLayout = true, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom)) {
            items(messages.asReversed()) { message ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.mine) Arrangement.End else Arrangement.Start) {
                    Column(Modifier.widthIn(max = 285.dp).clip(RoundedCornerShape(18.dp, 18.dp, if (message.mine) 4.dp else 18.dp, if (message.mine) 18.dp else 4.dp)).background(if (message.mine) Yellow else MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(message.text, color = if (message.mine) Ink else MaterialTheme.colorScheme.onSurface, fontSize = 16.sp)
                        if (message.time.isNotEmpty()) Text(message.time, color = if (message.mine) Ink.copy(alpha = .58f) else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp, modifier = Modifier.align(Alignment.End).padding(top = 2.dp))
                    }
                }
            }
        }
        if (emojiOpen) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf("💛", "😊", "👍", "👋", "✨").forEach { emoji -> Box(Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface).clickable(enabled = !sending) { draft = (draft + emoji).take(4000); emojiOpen = false }, contentAlignment = Alignment.Center) { Text(emoji, fontSize = 24.sp) } }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 5.dp), verticalAlignment = Alignment.Bottom) {
            Row(Modifier.weight(1f).heightIn(min = 46.dp).clip(RoundedCornerShape(25.dp)).background(MaterialTheme.colorScheme.surface), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp).clickable(enabled = !sending) { emojiOpen = !emojiOpen }, contentAlignment = Alignment.Center) { Text("☺", fontSize = 25.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                BasicTextField(draft, { draft = it.take(4000) }, enabled = !sending, modifier = Modifier.weight(1f).padding(end = 12.dp, top = 11.dp, bottom = 11.dp), textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, lineHeight = 21.sp), cursorBrush = SolidColor(Yellow), maxLines = 5, decorationBox = { inner ->
                    if (draft.isEmpty()) Text("Сообщение", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
                    inner()
                })
            }
            Spacer(Modifier.width(6.dp))
            Button(onClick = send, enabled = draft.isNotBlank() && !sending, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(46.dp), shape = CircleShape) {
                if (sending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Ink) else Text("↑", fontSize = 26.sp)
            }
        }
    }
}
