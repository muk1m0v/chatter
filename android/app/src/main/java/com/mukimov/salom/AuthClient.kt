package com.mukimov.salom

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

internal class AuthClient(private val context: Context, private val directory: DirectoryClient) {
    suspend fun googleToken(): String {
        val clientId = context.getString(R.string.salom_google_web_client_id)
        if (clientId.isBlank()) throw DirectoryException("Вход Google ещё настраивается. Попробуй после обновления приложения")
        val request = GetCredentialRequest.Builder().addCredentialOption(
            GetSignInWithGoogleOption.Builder(clientId).build()).build()
        val credential = CredentialManager.create(context).getCredential(context, request).credential
        if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
            throw DirectoryException("Google не вернул данные для входа. Попробуй ещё раз")
        val google = GoogleIdTokenCredential.createFrom(credential.data)
        val user = FirebaseAuth.getInstance().signInWithCredential(GoogleAuthProvider.getCredential(google.idToken, null)).await().user
        return user?.getIdToken(true)?.await()?.token ?: throw DirectoryException("Не удалось подтвердить вход Google")
    }
    suspend fun usernameAvailable(username: String): Boolean = withContext(Dispatchers.IO) {
        directory.request("auth/username?username=" + URLEncoder.encode(username, "UTF-8")).optBoolean("available")
    }
    suspend fun identity(firebaseToken: String, bind: Boolean, name: String = "", username: String = ""): JSONObject = withContext(Dispatchers.IO) {
        directory.request("auth/identity", JSONObject().put("bind_current", bind).put("name", name.trim())
            .put("username", username.trim()).put("firebase_token", firebaseToken), "POST")
    }
    suspend fun status(): JSONObject = withContext(Dispatchers.IO) { directory.request("auth/me") }
    fun accept(result: JSONObject) {
        val profile = result.getJSONObject("profile")
        val identity = context.getSharedPreferences("salom_directory", Context.MODE_PRIVATE)
        val local = context.getSharedPreferences("salom_local", Context.MODE_PRIVATE)
        val changedAccount = identity.getString("device_id", "") != profile.getString("id")
        val snapshot = JSONObject(result.toString()).apply { remove("session_token") }
        check(identity.edit().putString("device_id", profile.getString("id"))
            .putString("token_encrypted_v1", SecureTokenStore.encryptedValue(context, result.getString("session_token")))
            .remove("token")
            .putString("auth_snapshot", snapshot.toString()).commit()) { "Не удалось сохранить вход" }
        local.edit().apply {
            if (changedAccount) remove("about")
            remove("phone")
            putString("name", profile.getString("name")); putString("username", profile.getString("username"))
            putString("avatar_url", profile.optString("avatar_url"))
            putString("email", result.optString("email"))
            putString("account_verified", "yes"); putString("signed_in", "yes")
            putString("owner_profile_id", profile.getString("id"))
        }.commit()
        FirebaseAuth.getInstance().signOut()
    }
    suspend fun logout() {
        withContext(Dispatchers.IO) { runCatching { directory.request("auth/logout", JSONObject(), "POST") } }
        FirebaseAuth.getInstance().signOut()
        runCatching { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
        FirebaseMessaging.getInstance().deleteToken()
        context.getSharedPreferences("salom_local", Context.MODE_PRIVATE).edit().putString("signed_in", "no").commit()
    }
}
