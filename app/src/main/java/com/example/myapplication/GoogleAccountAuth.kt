package com.example.myapplication

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.tasks.await

class GoogleAccountAuth(private val app: Application) {
    private val firebase = FirebaseApp.initializeApp(app)?.let(FirebaseAuth::getInstance)
    private val credentials = CredentialManager.create(app)

    // The resource is generated only after fetching google-services.json.
    // Developer builds without that local config can still build and show a clear setup state.
    @SuppressLint("DiscouragedApi")
    private fun webClientId(): String {
        val id = app.resources.getIdentifier("default_web_client_id", "string", app.packageName)
        return if (id != 0) app.getString(id) else ""
    }
    val configured get() = firebase != null && webClientId().isNotBlank()
    val email get() = firebase?.currentUser?.email

    private suspend fun googleCredential(context: Context): com.google.firebase.auth.AuthCredential {
        check(configured) { "Đăng nhập Google chưa được cấu hình cho bản ứng dụng này." }
        val option = GetSignInWithGoogleOption.Builder(webClientId()).build()
        val response = credentials.getCredential(context,
            GetCredentialRequest.Builder().addCredentialOption(option).build())
        val custom = response.credential as? CustomCredential
            ?: throw IllegalStateException("Không nhận được thông tin đăng nhập Google.")
        check(custom.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            "Loại thông tin đăng nhập không được hỗ trợ."
        }
        val credential = GoogleIdTokenCredential.createFrom(custom.data)
        return GoogleAuthProvider.getCredential(credential.idToken, null)
    }

    suspend fun signIn(context: Context) {
        val credential = googleCredential(context)
        firebase!!.signInWithCredential(credential).await()
    }

    suspend fun idToken(): String {
        val user = firebase?.currentUser
            ?: throw BackendException("Hãy đăng nhập Google để tiếp tục.", "AUTH_REQUIRED", 401)
        return user.getIdToken(false).await().token?.takeIf { it.isNotBlank() }
            ?: throw BackendException("Phiên Google đã hết hạn. Hãy đăng nhập lại.", "AUTH_EXPIRED", 401)
    }

    suspend fun reauthenticate(context: Context) {
        val user = firebase?.currentUser
            ?: throw BackendException("Hãy đăng nhập lại để xóa tài khoản.", "AUTH_REQUIRED", 401)
        user.reauthenticate(googleCredential(context)).await()
    }

    suspend fun deleteFirebaseUser() {
        firebase?.currentUser?.delete()?.await()
    }

    suspend fun signOut() {
        firebase?.signOut()
        // Firebase session is already cleared even if the platform credential provider is unavailable.
        runCatching { credentials.clearCredentialState(ClearCredentialStateRequest()) }
    }
}
