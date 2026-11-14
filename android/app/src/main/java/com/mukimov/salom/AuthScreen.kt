package com.mukimov.salom

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
internal fun AuthScreen(context: Context, directory: DirectoryClient, bindCurrent: Boolean, onSuccess: () -> Unit, onCancel: (() -> Unit)? = null) {
    val client = remember(directory) { AuthClient(context, directory) }
    val local = remember { context.getSharedPreferences("salom_local", Context.MODE_PRIVATE) }
    val hasLegacy = local.getString("name", "").orEmpty().isNotEmpty() && local.getString("account_verified", "") != "yes"
    var preserveLegacy by remember { mutableStateOf(hasLegacy) }
    val binding = bindCurrent || preserveLegacy
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf<String?>(null) }
    var needsUsername by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(if (binding) local.getString("name", "").orEmpty() else "") }
    var username by remember { mutableStateOf("") }
    var available by remember { mutableStateOf<Boolean?>(null) }
    var checking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun finish(result: JSONObject) {
        if (result.optBoolean("needs_username")) needsUsername = true
        else { client.accept(result); token = null; onSuccess() }
    }
    LaunchedEffect(username, needsUsername) {
        available = null; checking = false
        if (needsUsername && AuthPolicy.validUsername(username)) {
            checking = true
            try { delay(350); available = client.usernameAvailable(username) }
            catch (_: DirectoryException) { available = null }
            finally { checking = false }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (onCancel != null) Row(Modifier.fillMaxWidth()) { TextButton(onClick = onCancel, enabled = !busy) { Text("Назад") } }
        Spacer(Modifier.height(52.dp))
        Text("Салом", fontSize = 42.sp, fontWeight = FontWeight.Bold)
        Text("Ближе к своим 💛", modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(48.dp))
        AnimatedContent(needsUsername, transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(100)) }, label = "google-auth") { choosing ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (!choosing) {
                    Text(if (binding) "Подключи Google к своему профилю" else "Добро пожаловать", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (binding) "Твой username и переписка сохранятся" else "Выбери Google-аккаунт, чтобы начать общение или вернуться в свои чаты", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = {
                        busy = true; error = null
                        scope.launch {
                            try {
                                token = client.googleToken()
                                if (name.isBlank()) name = FirebaseAuth.getInstance().currentUser?.displayName.orEmpty().take(80)
                                finish(client.identity(token!!, binding))
                            } catch (_: GetCredentialCancellationException) { /* Account chooser dismissed. */ }
                            catch (_: NoCredentialException) { error = "Добавь Google-аккаунт в настройках телефона и попробуй снова" }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { error = if (failure is DirectoryException) failure.message else if (failure is FirebaseAuthException) "Не удалось войти через Google. Проверь интернет и повтори" else "Вход Google недоступен. Проверь интернет и сервисы Google Play" }
                            finally { busy = false }
                        }
                    }, enabled = !busy, shape = RoundedCornerShape(24.dp), colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White, contentColor = Color(0xFF1F1F1F)), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Icon(painterResource(R.drawable.google_g), contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp)); Text("Войти через Google", fontWeight = FontWeight.Medium)
                    }
                    Text("Для регистрации и входа используется одна кнопка", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (hasLegacy && !bindCurrent) TextButton(onClick = { preserveLegacy = !preserveLegacy; error = null }, enabled = !busy) { Text(if (preserveLegacy) "Войти в другой аккаунт" else "Подключить сохранённый профиль") }
                } else {
                    Text("✓ Google подтверждён", color = Color(0xFF258A50), fontWeight = FontWeight.Medium)
                    Text("Как тебя найти?", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(name, { name = it.take(80); error = null }, label = { Text("Твоё имя") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
                    OutlinedTextField(username, { username = it.removePrefix("@").take(24); error = null }, label = { Text("Username") }, prefix = { Text("@") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), supportingText = { Text(when { checking -> "Проверяем…"; available == true -> "✓ Username свободен"; available == false -> "Этот username уже занят"; else -> "4–24: латинские буквы, цифры и _" }) })
                    Button(onClick = {
                        busy = true; error = null
                        scope.launch {
                            try { finish(client.identity(token ?: throw DirectoryException("Повтори вход Google"), binding, name, username)) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { error = failure.message ?: "Не удалось сохранить профиль"; if ((failure as? DirectoryException)?.status == 409) available = false }
                            finally { busy = false }
                        }
                    }, enabled = !busy && available == true && name.isNotBlank(), modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Продолжить") }
                    TextButton(onClick = { token = null; needsUsername = false; error = null; FirebaseAuth.getInstance().signOut() }, enabled = !busy) { Text("Выбрать другой Google-аккаунт") }
                }
            }
        }
        if (busy) CircularProgressIndicator(Modifier.padding(20.dp).size(24.dp), color = Yellow, strokeWidth = 2.dp)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(top = 16.dp)) }
    }
}
