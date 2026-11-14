package com.mukimov.salom

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

private val Yellow = Color(0xFFFFC400)
private val Ink = Color(0xFF25241F)
private val Soft = Color(0xFFF5F5F3)
private data class Person(val id: String, val name: String, val initials: String, val color: Color, val preview: String, val username: String = "")
private data class Message(val text: String, val mine: Boolean, val time: String = "")
private val savedPerson = Person("saved", "Избранное", "★", Yellow, "Сообщения и заметки для себя")

class MainActivity : ComponentActivity() {
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(245,245,243)
        window.navigationBarColor = android.graphics.Color.rgb(245,245,243)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        setContent { SalomApp(this) }
    }
}

private class Store(context: Context) {
    val prefs = context.getSharedPreferences("salom_local", Context.MODE_PRIVATE)
    fun text(key: String, fallback: String = "") = prefs.getString(key, fallback) ?: fallback
    fun save(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    fun messages(id: String): List<Message> {
        val raw = text("messages_$id")
        if (raw.isEmpty()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { val item = array.getJSONObject(it); Message(item.getString("text"), item.getBoolean("mine"), item.optString("time", "")) }.filter { it.mine }
        }.getOrDefault(emptyList())
    }
    fun saveMessages(id: String, messages: List<Message>) {
        val array = JSONArray()
        messages.forEach { array.put(JSONObject().put("text", it.text).put("mine", it.mine).put("time", it.time)) }
        save("messages_$id", array.toString())
    }
}

@Composable
private fun SalomApp(context: Context) {
    val store = remember { Store(context) }
    var signedIn by remember { mutableStateOf(store.text("signed_in") == "yes") }
    var dark by remember { mutableStateOf(store.text("dark") == "yes") }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var activeChat by remember { mutableStateOf<Person?>(null) }
    var profileVersion by remember { mutableIntStateOf(0) }
    val accounts = remember(profileVersion, signedIn) { listOf(SalomAccount("self", store.text("name"), store.text("username"), store.text("phone"), is_active = signedIn)) }
    val openAccount: (SalomAccount) -> Unit = { account -> activeChat = if (account.id == "self") savedPerson else Person(account.id, account.name, account.name.take(1).uppercase(), Yellow, "", account.username) }
    val scheme = if (dark) darkColorScheme(primary = Yellow, onPrimary = Ink, background = Color(0xFF171816), surface = Color(0xFF232420), secondaryContainer = Color(0xFF494125))
    else lightColorScheme(primary = Yellow, onPrimary = Ink, background = Soft, surface = Color.White, onSurface = Ink, secondaryContainer = Color(0xFFFFF0B2))
    SideEffect {
        @Suppress("DEPRECATION")
        (context as? ComponentActivity)?.window?.let { window ->
            window.statusBarColor = if (dark) 0xFF171816.toInt() else 0xFFF5F5F3.toInt()
            window.navigationBarColor = if (dark) 0xFF232420.toInt() else 0xFFFFFFFF.toInt()
            window.decorView.systemUiVisibility = if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }
    MaterialTheme(colorScheme = scheme, typography = Typography()) {
        Surface(Modifier.fillMaxSize(), color = scheme.background) {
            if (!signedIn) {
                Welcome(store) { signedIn = true }
            } else if (activeChat != null) {
                BackHandler { activeChat = null }
                Conversation(activeChat!!, store) { activeChat = null }
            } else {
                Scaffold(containerColor = scheme.background, bottomBar = {
                    Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(scheme.surface).selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
                        listOf("Чаты", "Контакты", "Настройки", "Профиль").forEachIndexed { index, title ->
                            Column(Modifier.weight(1f).heightIn(min = 56.dp).selectable(selected = tab == index, role = Role.Tab, onClick = { tab = index }).padding(vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Box(Modifier.size(width = 42.dp, height = 27.dp).clip(RoundedCornerShape(16.dp)).background(if (tab == index) Yellow else Color.Transparent), contentAlignment = Alignment.Center) {
                                    NavIcon(index, if (tab == index) Ink else scheme.onSurfaceVariant, Modifier.size(22.dp))
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(title, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1, color = if (tab == index) scheme.onSurface else scheme.onSurfaceVariant)
                            }
                        }
                    }
                }) { padding ->
                    Crossfade(targetState = tab, animationSpec = tween(140), modifier = Modifier.padding(padding), label = "navigation") { page ->
                        when (page) {
                            0 -> Chats(context, store, accounts, openAccount, { activeChat = it }, { tab = 1 }, { tab = 3 })
                            1 -> PhoneContacts(context, accounts, openAccount)
                            2 -> Settings(dark, { dark = it; store.save("dark", if (it) "yes" else "no") }, { store.save("signed_in", "no"); signedIn = false; tab = 0 })
                            else -> Profile(store, profileVersion) { profileVersion++ }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Welcome(store: Store, onEnter: () -> Unit) {
    var emailMode by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(store.text("name")) }
    var identity by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(36.dp))
        Box(Modifier.size(88.dp).clip(RoundedCornerShape(28.dp)).background(Yellow), contentAlignment = Alignment.Center) { NavIcon(0, Ink, Modifier.size(46.dp)) }
        Spacer(Modifier.height(22.dp))
        Text("Салом", fontSize = 42.sp, fontWeight = FontWeight.Bold)
        Text("Ближе к своим", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 17.sp)
        Spacer(Modifier.height(32.dp))
        Text("Давай знакомиться", Modifier.fillMaxWidth(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface).padding(4.dp)) {
            listOf("Телефон", "Email").forEachIndexed { index, label ->
                val selected = emailMode == (index == 1)
                Box(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if (selected) Yellow else Color.Transparent).clickable { emailMode = index == 1; identity = ""; error = "" }.padding(13.dp), contentAlignment = Alignment.Center) { Text(label, color = if (selected) Ink else MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium) }
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(name, { name = it.take(40) }, label = { Text("Твоё имя") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(identity, { identity = it.take(120); error = "" }, label = { Text(if (emailMode) "Email" else "Номер телефона") }, placeholder = { Text(if (emailMode) "name@example.com" else "+992 …") }, keyboardOptions = KeyboardOptions(keyboardType = if (emailMode) KeyboardType.Email else KeyboardType.Phone), singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), isError = error.isNotEmpty())
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(22.dp))
        Button(onClick = {
            val valid = if (emailMode) android.util.Patterns.EMAIL_ADDRESS.matcher(identity.trim()).matches() else identity.filterNot { it.isWhitespace() || it in "()-" }.matches(Regex("\\+[1-9][0-9]{6,14}"))
            if (name.isBlank()) error = "Напиши, как к тебе обращаться"
            else if (!valid) error = if (emailMode) "Проверь адрес email" else "Введи номер с + и кодом страны"
            else { store.save("name", name.trim()); store.save(if (emailMode) "email" else "phone", (if (emailMode) identity.trim() else identity.filterNot { it.isWhitespace() || it in "()-" })); store.save("signed_in", "yes"); onEnter() }
        }, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(17.dp)) { Text("Продолжить", fontWeight = FontWeight.Bold, fontSize = 15.sp) }
        Spacer(Modifier.height(20.dp))
        Text("Твой профиль останется на этом устройстве", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Header(title: String, subtitle: String? = null, trailing: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
}

@Composable
private fun Avatar(person: Person, size: Int = 46) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(person.color), contentAlignment = Alignment.Center) { Text(person.initials, color = Ink, fontSize = (size * .37).sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun Chats(context: Context, store: Store, accounts: List<SalomAccount>, onOpenAccount: (SalomAccount) -> Unit, onOpen: (Person) -> Unit, onNew: () -> Unit, onProfile: () -> Unit) {
    var search by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize()) {
        Column {
            Header("Салом") { Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onProfile), contentAlignment = Alignment.Center) { Box(Modifier.size(32.dp).clip(CircleShape).background(Yellow), contentAlignment = Alignment.Center) { Text(store.text("name", "С").take(1).uppercase(), fontSize = 14.sp, color = Ink, fontWeight = FontWeight.Bold) } } }
            CompactSearch(search, { search = it }, "Имя, username или номер")
            Spacer(Modifier.height(6.dp))
            val preview = remember { store.messages("saved").lastOrNull()?.text ?: savedPerson.preview }
            val visible = listOf(savedPerson)
            if (search.isNotBlank()) SmartPeopleResults(context, search, accounts, onOpenAccount, onProfile)
            else LazyColumn(contentPadding = PaddingValues(bottom = 90.dp)) {
                items(visible, key = { it.id }, contentType = { "chat" }) { person ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { onOpen(person) }.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(person)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(person.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 19.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.height(3.dp))
                            Text(preview, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (search.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(60.dp).clip(RoundedCornerShape(20.dp)).background(Yellow.copy(alpha = .18f)), contentAlignment = Alignment.Center) { NavIcon(0, MaterialTheme.colorScheme.onSurface, Modifier.size(27.dp)) }
                        Spacer(Modifier.height(16.dp))
                        Text("Начни с «Салом»", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                        Text("Твои близкие уже есть в телефонной книге", fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, bottom = 10.dp))
                        TextButton(onClick = onNew) { Text("Открыть контакты", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
        }
        FloatingActionButton(onClick = onNew, containerColor = Yellow, contentColor = Ink, shape = RoundedCornerShape(18.dp), modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).size(48.dp)) { Text("+", fontSize = 28.sp) }
    }
}

@Composable
private fun Conversation(person: Person, store: Store, onBack: () -> Unit) {
    var messages by remember(person.id) { mutableStateOf(store.messages(person.id)) }
    var draft by remember { mutableStateOf("") }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(0) }
    val darkChat = MaterialTheme.colorScheme.background.red < .5f
    val chatColors = if (darkChat) listOf(Color(0xFF22291D), Color(0xFF1B211A)) else listOf(Color(0xFFFFF6D5), Color(0xFFF4F8E9))
    var emojiOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(chatColors)).imePadding()) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp).fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surface).padding(horizontal = 2.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) { Text("‹", fontSize = 30.sp, color = MaterialTheme.colorScheme.onSurface) }
            Avatar(person, 34); Spacer(Modifier.width(10.dp))
            Column { Text(person.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold); Text(if (person.id == "saved") "Только для тебя" else "@${person.username}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (messages.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                Text("★", color = Yellow, fontSize = 48.sp)
                Spacer(Modifier.height(12.dp))
                Text("Всё важное — здесь", fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                Text("Сохраняй мысли, планы и заметки", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            }
        } else LazyColumn(state = listState, reverseLayout = true, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom)) {
            items(messages.asReversed()) { message ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.mine) Arrangement.End else Arrangement.Start) {
                    Column(Modifier.widthIn(max = 285.dp).clip(RoundedCornerShape(20.dp, 20.dp, if (message.mine) 5.dp else 20.dp, if (message.mine) 20.dp else 5.dp)).background(if (message.mine) Yellow else MaterialTheme.colorScheme.surface).padding(horizontal = 15.dp, vertical = 11.dp)) {
                        Text(message.text, color = if (message.mine) Ink else MaterialTheme.colorScheme.onSurface, fontSize = 16.sp)
                        if (message.time.isNotEmpty()) Text(message.time, color = Ink.copy(alpha = .58f), fontSize = 9.sp, modifier = Modifier.align(Alignment.End).padding(top = 2.dp))
                    }
                }
            }
        }
        if (emojiOpen) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf("💛", "😊", "👍", "👋", "✨").forEach { emoji ->
                Box(Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface).clickable { draft += emoji; emojiOpen = false }, contentAlignment = Alignment.Center) { Text(emoji, fontSize = 24.sp) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 5.dp), verticalAlignment = Alignment.Bottom) {
            Row(Modifier.weight(1f).heightIn(min = 46.dp).clip(RoundedCornerShape(25.dp)).background(MaterialTheme.colorScheme.surface), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp).clickable { emojiOpen = !emojiOpen }, contentAlignment = Alignment.Center) { Text("☺", fontSize = 25.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                BasicTextField(draft, { draft = it.take(4000) }, modifier = Modifier.weight(1f).padding(end = 12.dp, top = 11.dp, bottom = 11.dp), textStyle = androidx.compose.ui.text.TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, lineHeight = 21.sp), cursorBrush = SolidColor(Yellow), maxLines = 5, decorationBox = { inner ->
                    if (draft.isEmpty()) Text("Сообщение", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
                    inner()
                })
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { if (draft.isNotBlank()) { val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date()); messages = messages + Message(draft.trim(), true, time); store.saveMessages(person.id, messages); draft = ""; emojiOpen = false } }, enabled = draft.isNotBlank(), contentPadding = PaddingValues(0.dp), modifier = Modifier.size(46.dp), shape = CircleShape) { Text("↑", fontSize = 26.sp) }
        }
    }
}

@Composable
private fun Profile(store: Store, version: Int, onChanged: () -> Unit) {
    var edit by remember { mutableStateOf(false) }
    val name = remember(version) { store.text("name", "Твой профиль") }
    val about = remember(version) { store.text("about", "Салом! Я на связи 💛") }
    val username = remember(version) { store.text("username") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(16.dp))
        Avatar(Person("me", name, name.take(1).uppercase(), Yellow, ""), 76)
        Spacer(Modifier.height(12.dp))
        Text(name, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text("Мой профиль", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Button(onClick = { edit = true }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Редактировать профиль", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
        Spacer(Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (store.text("phone").isNotEmpty()) Detail(store.text("phone"), "Телефон")
                if (store.text("email").isNotEmpty()) Detail(store.text("email"), "Email")
                if (username.isNotEmpty()) Detail("@$username", "Имя пользователя")
                Detail(about, "О себе")
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("Всё начинается с «Салом»", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (edit) {
        var newName by remember { mutableStateOf(name) }; var newAbout by remember { mutableStateOf(about) }; var newUsername by remember { mutableStateOf(username) }
        val usernameValid = newUsername.isEmpty() || newUsername.matches(Regex("[a-zA-Z][a-zA-Z0-9_]{3,23}"))
        AlertDialog(onDismissRequest = { edit = false }, title = { Text("Твой профиль") }, text = { Column {
            OutlinedTextField(newName, { newName = it.take(40) }, label = { Text("Имя") }, singleLine = true)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(newUsername, { newUsername = it.removePrefix("@").take(24) }, label = { Text("@username") }, singleLine = true, isError = !usernameValid, supportingText = { Text("4–24: латинские буквы, цифры и _") })
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(newAbout, { newAbout = it.take(160) }, label = { Text("О себе") }, maxLines = 3)
        } }, confirmButton = { TextButton(onClick = { store.save("name", newName.trim()); store.save("about", newAbout.trim()); store.save("username", newUsername); onChanged(); edit = false }, enabled = newName.isNotBlank() && usernameValid) { Text("Сохранить", color = MaterialTheme.colorScheme.onSurface) } }, dismissButton = { TextButton(onClick = { edit = false }) { Text("Отмена", color = MaterialTheme.colorScheme.onSurface) } })
    }
}

@Composable
private fun Detail(value: String, label: String) { Column { Text(value, fontSize = 14.sp); Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp)) } }

@Composable
private fun Settings(dark: Boolean, onDark: (Boolean) -> Unit, onLogout: () -> Unit) {
    var confirmLogout by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Header("Настройки", "Сделай Салом своим")
        Card(Modifier.padding(horizontal = 14.dp), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Тёмная тема", fontWeight = FontWeight.Medium); Text("Для вечерних разговоров", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Switch(checked = dark, onCheckedChange = onDark, colors = SwitchDefaults.colors(checkedTrackColor = Yellow, checkedThumbColor = Ink)) }
                HorizontalDivider(Modifier.padding(vertical = 18.dp))
                Detail("На этом телефоне", "Хранение заметок")
                Spacer(Modifier.height(20.dp))
                Detail("0.3.1", "Версия Салом")
            }
        }
        Spacer(Modifier.height(18.dp))
        TextButton(onClick = { confirmLogout = true }, modifier = Modifier.padding(horizontal = 12.dp)) { Text("Выйти из профиля", color = MaterialTheme.colorScheme.error) }
    }
    if (confirmLogout) AlertDialog(onDismissRequest = { confirmLogout = false }, title = { Text("Выйти из профиля?") }, text = { Text("Локальные сообщения останутся на этом телефоне.") }, confirmButton = { TextButton(onClick = { confirmLogout = false; onLogout() }) { Text("Выйти", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Отмена", color = MaterialTheme.colorScheme.onSurface) } })
}

@Composable
private fun NavIcon(index: Int, color: Color, modifier: Modifier = Modifier.size(24.dp)) {
    Canvas(modifier) {
        val w = size.width; val h = size.height; val stroke = Stroke(width = w * .075f)
        when (index) {
            0 -> { drawRoundRect(color, Offset(w*.12f,h*.14f), Size(w*.76f,h*.57f), CornerRadius(w*.18f), style = stroke); drawPath(Path().apply { moveTo(w*.25f,h*.69f); lineTo(w*.23f,h*.88f); lineTo(w*.48f,h*.72f) }, color, style = stroke) }
            1 -> { drawCircle(color,w*.16f,Offset(w*.37f,h*.3f),style=stroke); drawArc(color,180f,180f,false,Offset(w*.08f,h*.52f),Size(w*.6f,h*.6f),style=stroke); drawCircle(color,w*.12f,Offset(w*.77f,h*.36f),style=stroke); drawLine(color,Offset(w*.77f,h*.62f),Offset(w*.91f,h*.83f),w*.075f) }
            2 -> { drawCircle(color,w*.29f,center,style=stroke); drawCircle(color,w*.09f,center,style=stroke); for (i in 0..7) { val a = i * Math.PI / 4; drawLine(color,Offset(w*.5f+(kotlin.math.cos(a)*w*.32f).toFloat(),h*.5f+(kotlin.math.sin(a)*h*.32f).toFloat()),Offset(w*.5f+(kotlin.math.cos(a)*w*.43f).toFloat(),h*.5f+(kotlin.math.sin(a)*h*.43f).toFloat()),w*.09f) } }
            else -> { drawCircle(color,w*.18f,Offset(w*.5f,h*.3f),style=stroke); drawArc(color,180f,180f,false,Offset(w*.16f,h*.57f),Size(w*.68f,h*.55f),style=stroke) }
        }
    }
}
