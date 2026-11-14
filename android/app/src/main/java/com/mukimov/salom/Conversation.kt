package com.mukimov.salom

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    val context = LocalContext.current
    val media = remember(directory) { MediaFiles(context, directory) }
    var messages by remember(person.id) { mutableStateOf(if (saved) store.messages(person.id) else emptyList()) }
    var draft by remember(person.id) { mutableStateOf("") }
    var selected by remember { mutableStateOf<Attachment?>(null) }
    var uploaded by remember { mutableStateOf<Attachment?>(null) }
    var sending by remember { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var attachmentsMenu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var connectionError by remember { mutableStateOf<String?>(null) }
    var sendError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(!saved) }
    var retry by remember { mutableIntStateOf(0) }
    var clearedThrough by remember { mutableLongStateOf(0) }
    var pendingSignature by remember { mutableStateOf("") }
    var pendingId by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val listState = rememberLazyListState()
    val busy = sending || preparing || clearing
    val choose: (android.net.Uri?) -> Unit = { uri ->
        if (uri != null) {
            preparing = true; sendError = null
            scope.launch {
                try { val prepared = media.prepare(uri); selected?.let { media.removeLocal(it) }; selected = prepared; uploaded = null }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { sendError = (failure as? DirectoryException)?.message ?: "Не удалось открыть файл" }
                finally { preparing = false }
            }
        }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), choose)
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia(), choose)

    LaunchedEffect(person.id, retry) {
        if (!saved) {
            var cursor = clearedThrough
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    try {
                        do {
                            val batch = directory.messages(person.id, cursor)
                            clearedThrough = maxOf(clearedThrough, batch.clearedThrough)
                            messages = (messages + batch.messages).filter { it.remoteId > clearedThrough }.distinctBy { it.remoteId }.sortedBy { it.remoteId }
                            cursor = maxOf(cursor, clearedThrough, batch.messages.lastOrNull()?.remoteId ?: 0)
                        } while (batch.hasMore)
                        connectionError = null
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: DirectoryException) { connectionError = failure.message }
                    finally { loading = false }
                    delay(2500)
                }
            }
        }
    }
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(0) }
    val send: () -> Unit = {
        val text = draft.trim()
        val attachment = selected
        if ((text.isNotEmpty() || attachment != null) && !busy) {
            sending = true; sendError = null
            val signature = text + ":" + attachment?.id.orEmpty()
            if (signature != pendingSignature || pendingId.isEmpty()) { pendingSignature = signature; pendingId = UUID.randomUUID().toString() }
            val clientId = pendingId
            scope.launch {
                try {
                    val sent = if (saved) {
                        val local = attachment?.let { media.saveLocal(it) }
                        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                        Message(text, true, time, attachment = local)
                    } else {
                        if (attachment != null && uploaded == null) uploaded = media.upload(person.id, attachment)
                        directory.sendMessage(person.id, text, clientId, uploaded)
                    }
                    messages = if (saved) messages + sent else (messages + sent).filter { it.remoteId > clearedThrough }.distinctBy { it.remoteId }.sortedBy { it.remoteId }
                    if (saved) store.saveMessages(person.id, messages)
                    draft = ""; selected = null; uploaded = null; pendingId = ""; pendingSignature = ""
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { sendError = (failure as? DirectoryException)?.message ?: "Не удалось отправить. Попробуй снова" }
                finally { sending = false }
            }
        }
    }
    val dark = MaterialTheme.colorScheme.background.red < .5f
    val background = if (dark) listOf(Color(0xFF22291D), Color(0xFF1B211A)) else listOf(Color(0xFFFFF6D5), Color(0xFFF4F8E9))
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(background))) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp).fillMaxWidth().heightIn(min = 40.dp).clip(RoundedCornerShape(23.dp)).background(MaterialTheme.colorScheme.surface), verticalAlignment = Alignment.CenterVertically) {
            ChatAction("back", "Назад", onClick = onBack)
            Box(Modifier.size(30.dp).clip(CircleShape).background(person.color), contentAlignment = Alignment.Center) { Text(person.initials, color = Ink, fontWeight = FontWeight.Medium, fontSize = 14.sp) }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f).padding(vertical = 3.dp)) {
                Text(person.name, fontSize = 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (saved) "Только для тебя" else "@${person.username}", fontSize = 10.sp, lineHeight = 13.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                ChatAction("menu", "Меню диалога", enabled = !busy) { menu = true }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Очистить диалог") }, onClick = { menu = false; confirmClear = true })
                }
            }
        }
        val error = sendError ?: connectionError
        if (error != null) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(error, color = MaterialTheme.colorScheme.error, fontSize = 11.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { sendError = null; retry++ }) { Text("Повторить", fontSize = 11.sp) }
        }
        if (messages.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (loading) CircularProgressIndicator(Modifier.size(24.dp), color = Yellow, strokeWidth = 2.dp)
            else Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (saved) "Избранное" else "Начни с «Салом»", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Text(if (saved) "Сообщения и файлы для себя" else "Напиши сообщение или отправь фото", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        } else LazyColumn(state = listState, reverseLayout = true, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.Bottom)) {
            items(messages.asReversed()) { message ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.mine) Arrangement.End else Arrangement.Start) {
                    Column(Modifier.widthIn(max = 280.dp).clip(RoundedCornerShape(16.dp, 16.dp, if (message.mine) 4.dp else 16.dp, if (message.mine) 16.dp else 4.dp)).background(if (message.mine) Yellow else MaterialTheme.colorScheme.surface).padding(horizontal = 10.dp, vertical = 6.dp)) {
                        message.attachment?.let { AttachmentBubble(it, media, message.mine) }
                        if (message.text.isNotEmpty()) Text(message.text, color = if (message.mine) Ink else MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, lineHeight = 20.sp)
                        if (message.time.isNotEmpty()) Text(message.time, color = if (message.mine) Ink.copy(alpha = .58f) else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp, lineHeight = 11.sp, modifier = Modifier.align(Alignment.End).padding(top = 2.dp))
                    }
                }
            }
        }
        selected?.let { attachment ->
            Row(Modifier.padding(horizontal = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(attachment.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, lineHeight = 16.sp); Text(fileSize(attachment.size), fontSize = 10.sp, lineHeight = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                ChatAction("close", "Убрать вложение", enabled = !busy) { scope.launch { media.removeLocal(attachment); selected = null; uploaded = null } }
            }
        }
        if (preparing || clearing) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Yellow)
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.Bottom) {
            Row(Modifier.weight(1f).heightIn(min = 36.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface).padding(start = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(draft, { draft = it.take(4000) }, enabled = !busy, modifier = Modifier.weight(1f).padding(vertical = 8.dp), textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, lineHeight = 18.sp), cursorBrush = SolidColor(Yellow), maxLines = 4, decorationBox = { inner ->
                    if (draft.isEmpty()) Text(if (selected == null) "Сообщение" else "Подпись", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, lineHeight = 18.sp)
                    inner()
                })
                Box {
                    ChatAction("attach", "Прикрепить файл", enabled = !busy, compact = true) { attachmentsMenu = true }
                    DropdownMenu(attachmentsMenu, onDismissRequest = { attachmentsMenu = false }) {
                        DropdownMenuItem(text = { Text("Фото и видео") }, onClick = { attachmentsMenu = false; pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) })
                        DropdownMenuItem(text = { Text("Файл") }, onClick = { attachmentsMenu = false; pickFile.launch(arrayOf("*/*")) })
                        Text("До 25 МБ", modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.width(4.dp))
            val canSend = !busy && (draft.isNotBlank() || selected != null)
            Box(Modifier.size(36.dp).clip(CircleShape).background(if (canSend || sending) Yellow else MaterialTheme.colorScheme.surface).clickable(enabled = canSend, role = Role.Button, onClick = send).semantics { contentDescription = "Отправить" }, contentAlignment = Alignment.Center) {
                if (sending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Ink)
                else ChatGlyph("send", if (canSend) Ink else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { if (!clearing) confirmClear = false }, title = { Text("Очистить диалог?") }, text = { Text(if (saved) "Все сообщения и файлы из Избранного будут удалены с этого телефона." else "Все сообщения этого диалога будут скрыты у тебя на всех устройствах. У собеседника история останется.") }, confirmButton = {
        TextButton(enabled = !clearing, onClick = {
            clearing = true; sendError = null
            scope.launch {
                try {
                    if (saved) { val removed = messages; kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { store.clearMessages(person.id) }; messages = emptyList(); removed.forEach { it.attachment?.let { attachment -> media.removeLocal(attachment) } } }
                    else { clearedThrough = maxOf(clearedThrough, directory.clearConversation(person.id)); val old = messages.filter { it.remoteId <= clearedThrough }; messages = messages.filter { it.remoteId > clearedThrough }; old.forEach { it.attachment?.let { attachment -> media.removeLocal(attachment) } }; retry++ }
                    confirmClear = false
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { sendError = (failure as? DirectoryException)?.message ?: "Не удалось очистить диалог"; confirmClear = false }
                finally { clearing = false }
            }
        }) { Text("Очистить", color = MaterialTheme.colorScheme.error) }
    }, dismissButton = { TextButton(enabled = !clearing, onClick = { confirmClear = false }) { Text("Отмена") } })
}

private fun fileSize(bytes: Long): String = if (bytes >= 1024 * 1024) String.format(java.util.Locale.getDefault(), "%.1f МБ", bytes / (1024.0 * 1024.0)) else "${(bytes + 1023) / 1024} КБ"

@Composable
private fun AttachmentBubble(attachment: Attachment, media: MediaFiles, mine: Boolean) {
    val scope = rememberCoroutineScope()
    var bitmap by remember(attachment.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var loading by remember(attachment.id) { mutableStateOf(false) }
    var error by remember(attachment.id) { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val photo = attachment.mime.startsWith("image/")
    LaunchedEffect(attachment.id, attempt) {
        if (photo) {
            loading = true
            try { bitmap = media.bitmap(attachment); error = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "Нажми, чтобы повторить" }
            finally { loading = false }
        }
    }
    val color = if (mine) Ink else MaterialTheme.colorScheme.onSurface
    Column(Modifier.widthIn(max = 240.dp).clickable(role = Role.Button) {
        if (photo && error != null) attempt++ else scope.launch {
            loading = true; error = null
            try { media.open(attachment) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = (failure as? DirectoryException)?.message ?: "Не удалось открыть файл" }
            finally { loading = false }
        }
    }.padding(bottom = 3.dp)) {
        if (bitmap != null) Image(bitmap!!.asImageBitmap(), contentDescription = attachment.name, modifier = Modifier.width(230.dp).heightIn(max = 240.dp).aspectRatio((bitmap!!.width.toFloat()/bitmap!!.height).coerceIn(.8f, 1.8f)).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
        else Row(Modifier.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            if (loading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = color)
            else Text(if (attachment.mime.startsWith("video/")) "▶" else "↓", fontSize = 24.sp, lineHeight = 28.sp, color = color)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.widthIn(max = 185.dp)) { Text(attachment.name, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = color); Text(fileSize(attachment.size), fontSize = 10.sp, lineHeight = 13.sp, color = color.copy(alpha = .65f)) }
        }
        error?.let { Text(it, fontSize = 10.sp, color = color) }
    }
}

@Composable
private fun ChatAction(kind: String, label: String, enabled: Boolean = true, compact: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.size(if (compact) 36.dp else 40.dp).clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        ChatGlyph(kind, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else .4f))
    }
}
@Composable
private fun ChatGlyph(kind: String, color: Color) {
    Canvas(Modifier.size(21.dp)) {
        val w = size.width; val h = size.height; val line = w * .075f
        when (kind) {
            "menu" -> for (y in listOf(.22f, .5f, .78f)) drawCircle(color, w*.065f, Offset(w*.5f, h*y))
            "back" -> { drawLine(color, Offset(w*.65f,h*.2f), Offset(w*.35f,h*.5f), line); drawLine(color, Offset(w*.35f,h*.5f), Offset(w*.65f,h*.8f), line) }
            "close" -> { drawLine(color, Offset(w*.28f,h*.28f), Offset(w*.72f,h*.72f), line); drawLine(color, Offset(w*.72f,h*.28f), Offset(w*.28f,h*.72f), line) }
            "send" -> { drawPath(Path().apply { moveTo(w*.22f,h*.24f); lineTo(w*.83f,h*.5f); lineTo(w*.22f,h*.76f); lineTo(w*.34f,h*.5f); close() }, color); drawLine(Color.White.copy(alpha=.6f), Offset(w*.34f,h*.5f), Offset(w*.66f,h*.5f), w*.035f) }
            else -> drawPath(Path().apply { moveTo(w*.37f,h*.61f); lineTo(w*.66f,h*.32f); cubicTo(w*.78f,h*.2f,w*.93f,h*.36f,w*.79f,h*.51f); lineTo(w*.43f,h*.86f); cubicTo(w*.18f,h*1.08f,-w*.03f,h*.76f,w*.17f,h*.55f); lineTo(w*.57f,h*.15f); cubicTo(w*.82f,-h*.1f,w*1.14f,h*.23f,w*.92f,h*.46f); lineTo(w*.53f,h*.85f) }, color, style = Stroke(line))
        }
    }
}
