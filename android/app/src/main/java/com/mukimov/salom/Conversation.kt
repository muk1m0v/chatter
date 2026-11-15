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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
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
    val audio = remember(media) { ChatAudioPlayer(context, media) }
    val recorder = remember(media) { VoiceRecorder(context, media) }
    var recording by remember { mutableStateOf(false) }
    var startingVoice by remember { mutableStateOf(false) }
    var voiceGeneration by remember { mutableIntStateOf(0) }
    var recordTime by remember { mutableLongStateOf(0) }
    var liveLevels by remember { mutableStateOf<List<Int>>(emptyList()) }
    var micDenied by remember { mutableStateOf(false) }
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
    val busy = sending || preparing || clearing || recording || startingVoice
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    fun cancelVoice() { voiceGeneration++; recorder.cancel(); recording = false; startingVoice = false; recordTime = 0; liveLevels = emptyList() }
    fun startVoice() {
        if (busy || draft.isNotBlank() || selected != null) return
        audio.stop(); focusManager.clearFocus(); keyboard?.hide(); sendError = null; micDenied = false
        startingVoice = true; voiceGeneration++; val ticket = voiceGeneration
        scope.launch {
            try {
                recorder.start()
                if (ticket == voiceGeneration && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) { recording = true; recordTime = 0; liveLevels = emptyList() }
                else recorder.cancel()
            } catch (cancelled: CancellationException) { recorder.cancel(); throw cancelled }
            catch (failure: Exception) { sendError = failure.message ?: "Не удалось начать запись" }
            finally { startingVoice = false }
        }
    }
    fun finishVoice() {
        if (!recording) return
        recording = false; preparing = true
        scope.launch {
            try { selected = recorder.finish(); uploaded = null; sendError = null }
            catch (cancelled: CancellationException) { recorder.cancel(); throw cancelled }
            catch (failure: Exception) { sendError = failure.message ?: "Не удалось сохранить запись" }
            finally { preparing = false; liveLevels = emptyList() }
        }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startVoice() else { micDenied = true; sendError = "Для голосовых сообщений разреши доступ к микрофону" }
    }
    DisposableEffect(lifecycle, recorder, audio) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { cancelVoice(); audio.stop() } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); cancelVoice(); audio.stop() }
    }
    androidx.activity.compose.BackHandler(recording || startingVoice) { cancelVoice() }
    LaunchedEffect(recording) {
        while (recording) {
            delay(120); recordTime = recorder.elapsed(); liveLevels = (liveLevels.takeLast(31) + recorder.sample())
            if (recordTime >= VoicePolicy.MAX_MS || recorder.reachedLimit) { finishVoice(); break }
        }
    }
    val choose: (android.net.Uri?) -> Unit = { uri ->
        if (uri != null) {
            preparing = true; sendError = null; micDenied = false
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
            if (attachment != null && audio.activeId == attachment.id) audio.stop()
            sending = true; sendError = null; micDenied = false
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
    val background = if (dark) listOf(Color(0xFF111D27), Color(0xFF17232B)) else listOf(Color(0xFFF2F0E6), Color(0xFFECEEE6))
    val wallpaper = remember(dark) { Modifier.chatWallpaper(dark) }
    val chatBackground = remember(dark) { Brush.verticalGradient(background) }
    val chatSurface = if (dark) Color(0xFF23303D) else Color.White
    Column(Modifier.fillMaxSize().background(chatBackground).then(wallpaper)) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp).fillMaxWidth().heightIn(min = 40.dp).clip(RoundedCornerShape(23.dp)).background(chatSurface), verticalAlignment = Alignment.CenterVertically) {
            ChatAction("back", "Назад", onClick = { if (recording || startingVoice) cancelVoice() else onBack() })
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
            TextButton(onClick = {
                if (micDenied) context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + context.packageName)))
                else { sendError = null; retry++ }
            }) { Text(if (micDenied) "Настройки" else "Повторить", fontSize = 11.sp) }
        }
        if (messages.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (loading) CircularProgressIndicator(Modifier.size(24.dp), color = Yellow, strokeWidth = 2.dp)
            else Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (saved) "Избранное" else "Начни с «Салом»", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Text(if (saved) "Сообщения и файлы для себя" else "Напиши сообщение или отправь фото", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        } else LazyColumn(state = listState, reverseLayout = true, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.Bottom)) {
            itemsIndexed(messages.asReversed()) { index, message ->
                val newer = if (index > 0) messages[messages.lastIndex - index + 1] else null
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
                    ChatMessage(message, maxWidth * .82f, media, audio, newer != null && newer.mine == message.mine && newer.time == message.time)
                }
            }
        }

        selected?.let { attachment ->
            Row(Modifier.padding(horizontal=8.dp,vertical=3.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(chatSurface).padding(start=12.dp,top=5.dp,bottom=5.dp), verticalAlignment=Alignment.CenterVertically) {
                if (attachment.kind == "voice") Box(Modifier.weight(1f)) { AudioBubble(attachment,audio,if (dark) Color(0xFFE1E8ED) else Ink) }
                else Column(Modifier.weight(1f)) { Text(attachment.name,maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=12.sp,lineHeight=16.sp); Text(fileSize(attachment.size),fontSize=10.sp,lineHeight=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                ChatAction("close","Убрать вложение",enabled=!busy) { audio.stop(); scope.launch { media.removeLocal(attachment); selected=null; uploaded=null } }
            }
        }
        if (preparing || clearing) LinearProgressIndicator(Modifier.fillMaxWidth(),color=Yellow)
        Row(Modifier.fillMaxWidth().padding(horizontal=7.dp,vertical=6.dp),verticalAlignment=Alignment.Bottom) {
            val inputShape=RoundedCornerShape(24.dp)
            Row(Modifier.weight(1f).heightIn(min=42.dp).clip(inputShape).background(chatSurface)
                .border(.7.dp,if (dark) Color.White.copy(alpha=.09f) else Color.Black.copy(alpha=.07f),inputShape)
                .padding(start=if(recording) 0.dp else 14.dp),verticalAlignment=Alignment.CenterVertically) {
                if (recording) {
                    ChatAction("close","Отменить запись",compact=true) { cancelVoice() }
                    Box(Modifier.size(6.dp).clip(CircleShape).background(Color(0xFFE46B64)))
                    Text(audioTime(recordTime),fontSize=14.sp,lineHeight=18.sp,modifier=Modifier.padding(horizontal=8.dp),color=if(dark) Color.White else Ink)
                    Waveform(liveLevels,0f,Yellow,Modifier.weight(1f).height(26.dp).padding(end=12.dp))
                } else {
                    BasicTextField(draft,{draft=it.take(4000)},enabled=!busy,modifier=Modifier.weight(1f).padding(vertical=8.dp),textStyle=TextStyle(color=if(dark) Color(0xFFEEF2F5) else Ink,fontSize=15.sp,lineHeight=20.sp),cursorBrush=SolidColor(Yellow),maxLines=4,decorationBox={inner ->
                        if(draft.isEmpty()) Text(if(selected==null) "Сообщение" else "Подпись",color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=15.sp,lineHeight=20.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                        inner()
                    })
                    Box {
                        ChatAction("attach","Прикрепить файл",enabled=!busy) { attachmentsMenu=true }
                        DropdownMenu(attachmentsMenu,onDismissRequest={attachmentsMenu=false}) {
                            DropdownMenuItem(text={Text("Фото и видео")},onClick={attachmentsMenu=false;pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))})
                            DropdownMenuItem(text={Text("Файл")},onClick={attachmentsMenu=false;pickFile.launch(arrayOf("*/*"))})
                            Text("До 25 МБ",modifier=Modifier.padding(horizontal=12.dp,vertical=6.dp),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
            val hasContent=draft.isNotBlank() || selected!=null
            val waiting=sending || preparing || clearing || startingVoice
            val action=if(waiting) "busy" else if(recording) "stop" else if(hasContent) "send" else "mic"
            Box(Modifier.size(42.dp).clip(CircleShape).background(Yellow.copy(alpha=if(waiting) .65f else 1f))
                .clickable(enabled=!waiting,role=Role.Button) {
                    when(action) {
                        "stop" -> finishVoice()
                        "send" -> send()
                        else -> if(androidx.core.content.ContextCompat.checkSelfPermission(context,android.Manifest.permission.RECORD_AUDIO)==android.content.pm.PackageManager.PERMISSION_GRANTED) startVoice() else micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                    }
                }.semantics { contentDescription=when(action){"mic"->"Записать голосовое";"stop"->"Остановить запись";"busy"->"Обработка сообщения";else->"Отправить"} },contentAlignment=Alignment.Center) {
                AnimatedContent(action,transitionSpec={ (fadeIn(tween(140))+scaleIn(initialScale=.6f,animationSpec=tween(160))) togetherWith (fadeOut(tween(90))+scaleOut(targetScale=.65f,animationSpec=tween(100))) },label="microphone-send") { mode ->
                    if(mode=="busy") CircularProgressIndicator(Modifier.size(19.dp),strokeWidth=2.dp,color=Ink) else ChatGlyph(mode,Ink)
                }
            }
        }
    }

    if (confirmClear) AlertDialog(onDismissRequest = { if (!clearing) confirmClear = false }, title = { Text("Очистить диалог?") }, text = { Text(if (saved) "Все сообщения и файлы из Избранного будут удалены с этого телефона." else "Все сообщения этого диалога будут скрыты у тебя на всех устройствах. У собеседника история останется.") }, confirmButton = {
        TextButton(enabled = !clearing, onClick = {
            audio.stop(); clearing = true; sendError = null
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
internal fun AttachmentBubble(attachment: Attachment, media: MediaFiles, contentColor: Color) {
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
    val color = contentColor
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
internal fun ChatAction(kind: String, label: String, enabled: Boolean = true, compact: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.size(if (compact) 36.dp else 40.dp).clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        ChatGlyph(kind, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else .4f))
    }
}
@Composable
internal fun ChatGlyph(kind: String, color: Color) {
    Canvas(Modifier.size(21.dp)) {
        val w = size.width; val h = size.height; val line = w * .075f
        when (kind) {
            "mic" -> { drawRoundRect(color, Offset(w*.36f,h*.09f), Size(w*.28f,h*.51f), androidx.compose.ui.geometry.CornerRadius(w*.14f)); drawPath(Path().apply { moveTo(w*.22f,h*.42f); cubicTo(w*.22f,h*.88f,w*.78f,h*.88f,w*.78f,h*.42f) },color,style=Stroke(line)); drawLine(color,Offset(w*.5f,h*.76f),Offset(w*.5f,h*.94f),line); drawLine(color,Offset(w*.33f,h*.94f),Offset(w*.67f,h*.94f),line) }
            "play" -> drawPath(Path().apply { moveTo(w*.3f,h*.16f); lineTo(w*.8f,h*.5f); lineTo(w*.3f,h*.84f); close() },color)
            "pause" -> { drawRoundRect(color,Offset(w*.28f,h*.18f),Size(w*.15f,h*.64f),androidx.compose.ui.geometry.CornerRadius(w*.03f)); drawRoundRect(color,Offset(w*.57f,h*.18f),Size(w*.15f,h*.64f),androidx.compose.ui.geometry.CornerRadius(w*.03f)) }
            "stop" -> drawRoundRect(color,Offset(w*.23f,h*.23f),Size(w*.54f,h*.54f),androidx.compose.ui.geometry.CornerRadius(w*.08f))
            "menu" -> for (y in listOf(.22f, .5f, .78f)) drawCircle(color, w*.065f, Offset(w*.5f, h*y))
            "back" -> { drawLine(color, Offset(w*.65f,h*.2f), Offset(w*.35f,h*.5f), line); drawLine(color, Offset(w*.35f,h*.5f), Offset(w*.65f,h*.8f), line) }
            "close" -> { drawLine(color, Offset(w*.28f,h*.28f), Offset(w*.72f,h*.72f), line); drawLine(color, Offset(w*.72f,h*.28f), Offset(w*.28f,h*.72f), line) }
            "send" -> { drawPath(Path().apply { moveTo(w*.22f,h*.24f); lineTo(w*.83f,h*.5f); lineTo(w*.22f,h*.76f); lineTo(w*.34f,h*.5f); close() }, color); drawLine(Color.White.copy(alpha=.6f), Offset(w*.34f,h*.5f), Offset(w*.66f,h*.5f), w*.035f) }
            else -> drawPath(Path().apply { moveTo(w*.37f,h*.61f); lineTo(w*.66f,h*.32f); cubicTo(w*.78f,h*.2f,w*.93f,h*.36f,w*.79f,h*.51f); lineTo(w*.43f,h*.86f); cubicTo(w*.18f,h*1.08f,-w*.03f,h*.76f,w*.17f,h*.55f); lineTo(w*.57f,h*.15f); cubicTo(w*.82f,-h*.1f,w*1.14f,h*.23f,w*.92f,h*.46f); lineTo(w*.53f,h*.85f) }, color, style = Stroke(line))
        }
    }
}
