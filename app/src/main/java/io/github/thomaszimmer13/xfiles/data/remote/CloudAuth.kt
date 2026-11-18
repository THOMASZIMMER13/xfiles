package io.github.thomaszimmer13.xfiles.data.remote

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * Comptes en ligne : jetons d'accès (gardés en mémoire jusqu'à leur expiration), connexion
 * Google par les services Google Play, connexion Dropbox et OneDrive par le navigateur.
 * Le jeton de renouvellement est gardé chiffré dans le mot de passe du serveur ; s'il change,
 * [onRefreshToken] l'enregistre.
 */
class CloudAuth(
    context: Context,
    val oauth: CloudOAuth,
    private val onRefreshToken: (serverId: String, token: String) -> Unit,
) {
    private val app = context.applicationContext
    private class Cached(val token: String, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, Cached>()
    private val latestRefresh = ConcurrentHashMap<String, String>()
    private val locks = ConcurrentHashMap<String, Any>()

    fun tokens(server: RemoteServer): TokenSource = object : TokenSource {
        override fun token(): String = accessToken(server)

        override fun invalidate(token: String) {
            cache.remove(server.id)
            if (server.protocol == Protocol.GDRIVE) {
                runCatching { Tasks.await(Identity.getAuthorizationClient(app).clearToken(ClearTokenRequest.builder().setToken(token).build()), 20, TimeUnit.SECONDS) }
            }
        }
    }

    private fun accessToken(server: RemoteServer): String = synchronized(locks.getOrPut(server.id) { Any() }) {
        cache[server.id]?.takeIf { it.expiresAt > System.currentTimeMillis() + 60_000 }?.let { return it.token }
        val fresh = when (server.protocol) {
            Protocol.GDRIVE -> Cached(googleSilent(server.username), System.currentTimeMillis() + 50 * 60_000L)
            Protocol.DROPBOX, Protocol.ONEDRIVE -> {
                val refresh = latestRefresh[server.id] ?: server.password
                if (refresh.isEmpty()) throw RemoteException(RemoteException.Reason.AUTH, "pas de jeton")
                val t = oauth.refresh(server.protocol, refresh)
                t.refresh?.takeIf { it != refresh }?.let {
                    latestRefresh[server.id] = it
                    onRefreshToken(server.id, it)
                }
                Cached(t.access, System.currentTimeMillis() + t.expiresInSeconds * 1000)
            }
            else -> throw IllegalArgumentException(server.protocol.name)
        }
        cache[server.id] = fresh
        fresh.token
    }

    /** Jeton déjà obtenu à la connexion : la première liste n'en redemande pas. */
    fun remember(serverId: String, access: String, expiresInSeconds: Long) {
        cache[serverId] = Cached(access, System.currentTimeMillis() + expiresInSeconds * 1000)
    }

    /** Compte supprimé ou reconnecté : ses jetons en mémoire sont oubliés. */
    fun forget(serverId: String) {
        cache.remove(serverId)
        latestRefresh.remove(serverId)
    }

    // --- Google (services Google Play) ---

    private fun googleRequest(email: String?): AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(DRIVE_SCOPE)))
        .apply { if (!email.isNullOrEmpty()) setAccount(Account(email, "com.google")) }
        .build()

    /** Jeton sans afficher d'écran ; si l'accord doit être redonné, erreur « identifiants ». */
    private fun googleSilent(email: String): String {
        val result = try {
            Tasks.await(Identity.getAuthorizationClient(app).authorize(googleRequest(email)), 30, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            throw RemoteException(RemoteException.Reason.AUTH, e.cause?.message, e)
        } catch (e: java.util.concurrent.TimeoutException) {
            throw RemoteException(RemoteException.Reason.UNREACHABLE, e.message, e)
        }
        if (result.hasResolution()) throw RemoteException(RemoteException.Reason.AUTH, "accord Google à redonner")
        return result.accessToken ?: throw RemoteException(RemoteException.Reason.AUTH, "pas de jeton Google")
    }

    sealed interface GoogleStep {
        data class Token(val access: String) : GoogleStep

        /** Choix du compte ou accord à donner : écran Google à afficher. */
        data class NeedsUser(val intent: PendingIntent) : GoogleStep
    }

    suspend fun googleAuthorize(email: String?): GoogleStep = withContext(Dispatchers.IO) {
        val result = try {
            Tasks.await(Identity.getAuthorizationClient(app).authorize(googleRequest(email)), 60, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            throw RemoteException(RemoteException.Reason.AUTH, e.cause?.message, e.cause ?: e)
        }
        val pending = result.pendingIntent
        if (result.hasResolution() && pending != null) GoogleStep.NeedsUser(pending)
        else GoogleStep.Token(result.accessToken ?: throw RemoteException(RemoteException.Reason.AUTH, "pas de jeton Google"))
    }

    /** Retour de l'écran Google : le jeton, ou une erreur si l'utilisateur a refusé. */
    fun googleResult(data: Intent?): String {
        val result = try {
            Identity.getAuthorizationClient(app).getAuthorizationResultFromIntent(data)
        } catch (e: Exception) {
            throw RemoteException(RemoteException.Reason.AUTH, e.message, e)
        }
        return result.accessToken ?: throw RemoteException(RemoteException.Reason.AUTH, "pas de jeton Google")
    }

    // --- Dropbox, OneDrive (navigateur) ---

    private val prefs = app.getSharedPreferences("cloud-login", Context.MODE_PRIVATE)
    private val _redirect = MutableStateFlow<Uri?>(null)

    /** Dernier retour du navigateur, pas encore traité. */
    val redirect: StateFlow<Uri?> = _redirect.asStateFlow()

    /** Prépare la connexion et renvoie l'adresse de la page à ouvrir. */
    fun beginLogin(p: Protocol): String {
        val verifier = CloudOAuth.randomToken(48)
        val state = CloudOAuth.randomToken(16)
        prefs.edit().putString("protocol", p.name).putString("verifier", verifier).putString("state", state).apply()
        return oauth.authorizeUrl(p, verifier, state)
    }

    fun onRedirect(uri: Uri) {
        _redirect.value = uri
    }

    class Login(val protocol: Protocol, val tokens: OAuthTokens, val account: CloudAccount)

    /** Termine la connexion après le retour du navigateur (code échangé contre les jetons). */
    suspend fun completeLogin(uri: Uri): Login = withContext(Dispatchers.IO) {
        _redirect.compareAndSet(uri, null)
        val protocol = prefs.getString("protocol", null)?.let { runCatching { Protocol.valueOf(it) }.getOrNull() }
        val verifier = prefs.getString("verifier", null)
        val state = prefs.getString("state", null)
        prefs.edit().clear().apply()
        if (protocol == null || verifier == null || uri.getQueryParameter("state") != state) {
            throw RemoteException(RemoteException.Reason.PROTOCOL, "connexion interrompue")
        }
        uri.getQueryParameter("error")?.let { throw RemoteException(RemoteException.Reason.AUTH, it) }
        val code = uri.getQueryParameter("code") ?: throw RemoteException(RemoteException.Reason.AUTH, "pas de code")
        val tokens = oauth.exchange(protocol, code, verifier)
        if (tokens.refresh.isNullOrEmpty()) throw RemoteException(RemoteException.Reason.AUTH, "pas de jeton de renouvellement")
        Login(protocol, tokens, oauth.account(protocol, tokens.access))
    }

    suspend fun account(p: Protocol, access: String): CloudAccount = withContext(Dispatchers.IO) { oauth.account(p, access) }

    private companion object {
        const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive"
    }
}
