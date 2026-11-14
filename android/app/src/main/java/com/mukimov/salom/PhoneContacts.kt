package com.mukimov.salom

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.provider.Settings
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

@Immutable
internal data class PhoneContact(val key: String, val contactId: Long, val name: String, val number: String, val initials: String, val colorIndex: Int)

internal fun readContacts(context: Context): List<PhoneContact> {
    val phone = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
    val columns = arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY, ContactsContract.CommonDataKinds.Phone.NUMBER)
    val contacts = LinkedHashMap<String, PhoneContact>()
    val whitespace = Regex("\\s+")
    context.contentResolver.query(phone, columns, null, null, ContactsContract.CommonDataKinds.Phone.SORT_KEY_PRIMARY + " ASC")?.use { cursor ->
        while (cursor.moveToNext()) {
            val id = cursor.getLong(0)
            val number = cursor.getString(2)?.trim().orEmpty()
            if (number.isBlank()) continue
            val name = cursor.getString(1)?.trim()?.takeIf { it.isNotBlank() } ?: number
            val key = "$id:${number.filter { it.isDigit() }}"
            val initials = name.split(whitespace).take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString("")
            contacts.putIfAbsent(key, PhoneContact(key, id, name, number, initials, (id % 5).toInt()))
        }
    }
    return contacts.values.toList()
}

@Composable
internal fun PhoneContacts(context: Context, accounts: List<SalomAccount> = emptyList(), onOpenAccount: (SalomAccount) -> Unit = {}) {
    val accent = Color(0xFFFFC400)
    var granted by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) }
    var asked by rememberSaveable { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var contacts by remember { mutableStateOf<List<PhoneContact>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<PhoneContact?>(null) }
    val listState = rememberLazyListState()
    val owner = LocalLifecycleOwner.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed -> granted = allowed; asked = true; refresh++ }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
                refresh++
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(granted, refresh) {
        if (!granted) { contacts = emptyList(); return@LaunchedEffect }
        loading = true
        error = false
        try {
            contacts = withContext(Dispatchers.IO) { readContacts(context) }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: SecurityException) {
            granted = false
            contacts = emptyList()
        } catch (exception: Exception) {
            error = true
        } finally { loading = false }
    }
    val visible = remember(search, contacts, accounts) {
        if (search.isBlank()) contacts else contacts.mapNotNull { contact ->
            val account = accounts.firstOrNull { canonicalPhone(it.phone) == canonicalPhone(contact.number) }
            smartSearchScore(search, contact.name, account?.username.orEmpty(), contact.number)?.let { contact to it }
        }.sortedBy { it.second }.map { it.first }
    }
    val palette = remember { listOf(Color(0xFFFFE9A6), Color(0xFFDFEBDD), Color(0xFFEADFF4), Color(0xFFDDEAF6), Color(0xFFFFE4D2)) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Контакты", fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
                Text(if (granted) "На телефоне · ${contacts.size}" else "Твои близкие — рядом", fontSize = 11.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (granted) TextButton(onClick = { refresh++ }, enabled = !loading) { Text("Обновить", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp) }
        }
        if (!granted) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(80.dp).clip(CircleShape).background(accent), contentAlignment = Alignment.Center) { Text("+", fontSize = 38.sp, color = Color(0xFF25241F)) }
                    Spacer(Modifier.height(22.dp))
                    Text("Кто в твоих контактах?", fontSize = 21.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    Text("Разреши доступ, чтобы видеть имена и номера из телефонной книги. Контакты остаются на устройстве.", fontSize = 14.sp, lineHeight = 21.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
                    Button(onClick = { launcher.launch(Manifest.permission.READ_CONTACTS) }, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Показать контакты", color = Color(0xFF25241F), fontWeight = FontWeight.SemiBold) }
                    if (asked) TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("Разрешения приложения", color = MaterialTheme.colorScheme.onSurface) }
                }
            }
        } else {
            CompactSearch(search, { search = it }, "Имя, username или номер")
            if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), color = accent)
            if (error) {
                Text("Не удалось прочитать контакты", modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { refresh++ }) { Text("Попробовать ещё", color = MaterialTheme.colorScheme.onSurface) }
            } else LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(top = 6.dp, bottom = 12.dp)) {
                items(visible, key = { it.key }, contentType = { "contact" }) { contact ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable {
                        val account = accounts.firstOrNull { it.phone.isNotBlank() && canonicalPhone(it.phone) == canonicalPhone(contact.number) }
                        if (contactDestination(account) == ContactDestination.CHAT) onOpenAccount(account!!) else selected = contact
                    }.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).clip(CircleShape).background(palette[contact.colorIndex]), contentAlignment = Alignment.Center) { Text(contact.initials, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = Color(0xFF25241F)) }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(contact.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(contact.number, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
                if (visible.isEmpty() && !loading) item {
                    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (search.isBlank()) "Здесь появятся твои контакты" else "Ничего не найдено", fontWeight = FontWeight.Medium)
                        if (search.isBlank()) Text("Сохрани номер в телефонной книге и нажми «Обновить»", textAlign = TextAlign.Center, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
                    }
                }
            }
        }
    }
    selected?.let { contact -> ContactActions(context, contact, accounts.firstOrNull { it.phone.isNotBlank() && canonicalPhone(it.phone) == canonicalPhone(contact.number) }) { selected = null } }
}

@Composable
internal fun ContactActions(context: Context, contact: PhoneContact, account: SalomAccount?, onDismiss: () -> Unit) {
        AlertDialog(onDismissRequest = onDismiss, title = { Text(contact.name) }, text = { Column {
            Text(contact.number)
            if (contactDestination(account) == ContactDestination.UNKNOWN) Text("Статус аккаунта ещё не подтверждён", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        } }, confirmButton = {
            TextButton(onClick = {
                val invitation = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "Салом! 💛 Скачай приложение для Android: https://mukimov.site/")
                }
                context.startActivity(Intent.createChooser(invitation, "Пригласить в Салом"))
                onDismiss()
            }) { Text("Пригласить в Салом", color = MaterialTheme.colorScheme.onSurface) }
        }, dismissButton = {
            TextButton(onClick = {
                val intent = Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contact.contactId))
                runCatching { context.startActivity(intent) }
                onDismiss()
            }) { Text("Открыть контакт", color = MaterialTheme.colorScheme.onSurface) }
        })
}
