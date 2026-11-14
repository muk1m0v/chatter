package com.mukimov.salom

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun AccountSecurityCard(directory: DirectoryClient, onVerify: () -> Unit) {
    val context = LocalContext.current
    val client = remember(directory) { AuthClient(context, directory) }
    var linked by remember(directory) { mutableStateOf(false) }
    var email by remember(directory) { mutableStateOf("") }
    var loaded by remember(directory) { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(directory, refresh) {
        try { val status = client.status(); linked = status.optBoolean("google_linked"); email = status.optString("email"); loaded = true; error = null }
        catch (failure: DirectoryException) { error = failure.message }
    }
    Card(Modifier.fillMaxWidth().padding(top = 14.dp), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Аккаунт Google", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            when {
                error != null -> { Text(error!!, fontSize = 12.sp); TextButton(onClick = { refresh++ }) { Text("Повторить") } }
                !loaded -> CircularProgressIndicator(Modifier.size(20.dp), color = Yellow, strokeWidth = 2.dp)
                linked -> { Text("✓ Google подключён", fontSize = 14.sp); Text(email, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                else -> { Text("Подключи Google для входа на других устройствах. Твои чаты сохранятся.", fontSize = 13.sp); TextButton(onClick = onVerify) { Text("Подключить Google") } }
            }
        }
    }
}
