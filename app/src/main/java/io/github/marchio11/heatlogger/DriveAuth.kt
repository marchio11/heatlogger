package io.github.marchio11.heatlogger

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationClient
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.TimeUnit

/**
 * Accesso a Google Drive con il solo permesso "drive.file": l'app vede e modifica
 * soltanto i file che crea lei (la cartella Heatlogger e i GPX giornalieri).
 */
object DriveAuth {
    const val SCOPE = "https://www.googleapis.com/auth/drive.file"

    fun request(): AuthorizationRequest =
        AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    fun client(context: Context): AuthorizationClient = Identity.getAuthorizationClient(context)

    /** Token senza interazione (da usare in background). null = serve ricollegare Drive dall'app. */
    fun tokenBlocking(context: Context): String? {
        val result = Tasks.await(client(context).authorize(request()), 30, TimeUnit.SECONDS)
        return if (result.hasResolution()) null else result.accessToken
    }
}
