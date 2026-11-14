package com.mukimov.salom

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun SmartPeopleResults(context: Context, query: String, accounts: List<SalomAccount>, onOpenAccount: (SalomAccount) -> Unit, onSelf: () -> Unit) {
    var contacts by remember { mutableStateOf<List<PhoneContact>>(emptyList()) }
    var selected by remember { mutableStateOf<PhoneContact?>(null) }
    LaunchedEffect(context) {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            contacts = withContext(Dispatchers.IO) { try { readContacts(context) } catch (_: SecurityException) { emptyList() } }
        }
    }
    val people = remember(query, accounts) { accounts.filter { it.is_active }.mapNotNull { account -> smartSearchScore(query, account.name, account.username, account.phone)?.let { account to it } }.sortedBy { it.second }.map { it.first } }
    val matchedContacts = remember(query, contacts, accounts) { contacts.filter { contact -> accounts.none { it.is_active && it.phone.isNotBlank() && canonicalPhone(it.phone) == canonicalPhone(contact.number) } }.mapNotNull { contact -> smartSearchScore(query, contact.name, phone = contact.number)?.let { contact to it } }.sortedBy { it.second }.map { it.first } }
    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 88.dp)) {
        if (people.isNotEmpty()) item { SectionLabel("Профили") }
        items(people, key = { "account:${it.id}" }, contentType = { "account" }) { account ->
            SearchRow(account.name, if (account.username.isNotBlank()) "@${account.username}" else "Мой профиль") { if (account.id == "self") onSelf() else onOpenAccount(account) }
        }
        if (matchedContacts.isNotEmpty()) item { SectionLabel("Контакты телефона") }
        items(matchedContacts, key = { "contact:${it.key}" }, contentType = { "phone" }) { contact -> SearchRow(contact.name, contact.number) { selected = contact } }
        if (people.isEmpty() && matchedContacts.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Ничего не найдено", fontWeight = FontWeight.Medium)
                Text("Попробуй начало имени, username или номер", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
    selected?.let { ContactActions(context, it, null) { selected = null } }
}

@Composable private fun SectionLabel(text: String) { Text(text, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)) }
@Composable private fun SearchRow(name: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(Color(0xFFFFE9A6)), contentAlignment = Alignment.Center) { Text(name.take(1).uppercase(), color = Color(0xFF25241F), fontSize = 18.sp) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) { Text(name, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp)) }
    }
}
