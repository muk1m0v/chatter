package com.mukimov.salom
import androidx.compose.runtime.*
import androidx.compose.material3.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
internal fun EmailOtpForm(directory: DirectoryClient, client: AuthClient, done: () -> Unit) {
 var open by remember { mutableStateOf(false) }; var email by remember { mutableStateOf("") }; var code by remember { mutableStateOf("") }
 var sent by remember { mutableStateOf(false) }; var ticket by remember { mutableStateOf("") }; var username by remember { mutableStateOf("") }; var name by remember { mutableStateOf("") }
 var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf("") }; val scope=rememberCoroutineScope()
 TextButton(enabled=!busy,onClick={open=!open}) { Text(if(open) "Скрыть Email" else "Войти по Email") }
 if(open) {
  OutlinedTextField(email,{email=it.take(254)},label={Text("Email")},enabled=!busy && !sent,singleLine=true)
  if(sent && ticket.isEmpty()) OutlinedTextField(code,{code=it.filter(Char::isDigit).take(6)},label={Text("Код · действует 5 минут")},singleLine=true)
  if(ticket.isNotEmpty()) { OutlinedTextField(name,{name=it.take(80)},label={Text("Имя")}); OutlinedTextField(username,{username=it.take(24)},label={Text("Username")}) }
  Button(enabled=!busy,onClick={busy=true;error="";scope.launch {
   try {
    val body=JSONObject().put("email",email.trim())
    val path=if(!sent) "code" else if(ticket.isEmpty()) "verify" else "register"
    if(path=="verify") body.put("code",code)
    if(path=="register") body.put("ticket",ticket).put("username",username).put("name",name)
    val result=withContext(Dispatchers.IO){directory.request("auth/email/"+path,body,"POST")}
    if(path=="code") sent=true else if(result.optBoolean("needs_username")) ticket=result.getString("ticket") else {client.accept(result);done()}
   } catch(e:Exception){error=e.message?:"Не удалось войти"} finally{busy=false}
  }}) {Text(if(!sent) "Получить код" else if(ticket.isEmpty()) "Подтвердить" else "Создать аккаунт")}
  TextButton(enabled=!busy,onClick={sent=false;ticket="";code=""}){Text("Другой email / повторить отправку")}
  if(error.isNotEmpty()) Text(error,color=MaterialTheme.colorScheme.error)
 }
}
