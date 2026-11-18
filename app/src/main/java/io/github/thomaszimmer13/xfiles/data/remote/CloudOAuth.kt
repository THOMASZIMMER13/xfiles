package io.github.thomaszimmer13.xfiles.data.remote

import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Jetons rendus par le service : accès (court) et renouvellement (long, gardé chiffré). */
data class OAuthTokens(val access: String, val refresh: String?, val expiresInSeconds: Long)

/** Compte connecté : adresse (identifiant) et nom affiché. */
data class CloudAccount(val email: String, val name: String)

/**
 * Connexion OAuth 2 « code + PKCE » de Dropbox et OneDrive, sans secret d'application : la page
 * de connexion s'ouvre dans le navigateur et revient dans l'app par « xfiles-auth://… ».
 */
class CloudOAuth(
    private val dropboxKey: String,
    private val oneDriveId: String,
    /** « xfiles-auth » (version normale) ou « xfiles-auth-debug » : chaque version reçoit son retour. */
    private val scheme: String = "xfiles-auth",
    private val dropboxToken: String = "https://api.dropboxapi.com/oauth2/token",
    private val microsoftToken: String = "https://login.microsoftonline.com/common/oauth2/v2.0/token",
    private val dropboxApi: String = "https://api.dropboxapi.com/2",
    private val graph: String = "https://graph.microsoft.com/v1.0",
) {
    fun available(p: Protocol): Boolean = when (p) {
        Protocol.DROPBOX -> dropboxKey.isNotEmpty()
        Protocol.ONEDRIVE -> oneDriveId.isNotEmpty()
        Protocol.GDRIVE -> true
        else -> false
    }

    fun redirectUri(p: Protocol) = "$scheme://${p.scheme}"

    /** Adresse de la page de connexion ; [verifier] et [state] sont gardés jusqu'au retour. */
    fun authorizeUrl(p: Protocol, verifier: String, state: String): String = when (p) {
        Protocol.DROPBOX -> "https://www.dropbox.com/oauth2/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", dropboxKey)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("token_access_type", "offline")
            .addQueryParameter("redirect_uri", redirectUri(p))
            .addQueryParameter("code_challenge", challenge(verifier))
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("state", state)
            .build().toString()
        Protocol.ONEDRIVE -> "https://login.microsoftonline.com/common/oauth2/v2.0/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", oneDriveId)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", redirectUri(p))
            .addQueryParameter("scope", MICROSOFT_SCOPES)
            .addQueryParameter("code_challenge", challenge(verifier))
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("prompt", "select_account")
            .addQueryParameter("state", state)
            .build().toString()
        else -> throw IllegalArgumentException(p.name)
    }

    fun exchange(p: Protocol, code: String, verifier: String): OAuthTokens = token(
        p,
        FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", redirectUri(p))
            .add("code_verifier", verifier)
            .add("client_id", clientId(p))
            .apply { if (p == Protocol.ONEDRIVE) add("scope", MICROSOFT_SCOPES) }
            .build(),
    )

    fun refresh(p: Protocol, refreshToken: String): OAuthTokens = token(
        p,
        FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", clientId(p))
            .apply { if (p == Protocol.ONEDRIVE) add("scope", MICROSOFT_SCOPES) }
            .build(),
    )

    private fun clientId(p: Protocol) = if (p == Protocol.DROPBOX) dropboxKey else oneDriveId

    private fun token(p: Protocol, form: FormBody): OAuthTokens {
        val url = if (p == Protocol.DROPBOX) dropboxToken else microsoftToken
        CloudHttp.send(Request.Builder().url(url).post(form).build()).use { r ->
            val body = runCatching { r.body.string() }.getOrDefault("")
            if (!r.isSuccessful) {
                // « invalid_grant » : autorisation retirée ou expirée, il faut se reconnecter.
                val reason = if (r.code in 400..401 && ("invalid_grant" in body || "invalid_client" in body || r.code == 401)) RemoteException.Reason.AUTH else CloudHttp.reasonOf(r.code)
                throw RemoteException(reason, "jeton : HTTP ${r.code}")
            }
            val o = JSONObject(body)
            return OAuthTokens(o.getString("access_token"), o.optString("refresh_token").takeIf { it.isNotEmpty() }, o.optLong("expires_in", 3600))
        }
    }

    /** Compte du jeton : adresse et nom. */
    fun account(p: Protocol, accessToken: String): CloudAccount = when (p) {
        Protocol.DROPBOX -> get(Request.Builder().url("$dropboxApi/users/get_current_account").post("null".toRequestBody(CloudHttp.JSON)), accessToken).let {
            CloudAccount(it.optString("email"), it.optJSONObject("name")?.optString("display_name").orEmpty())
        }
        Protocol.ONEDRIVE -> get(Request.Builder().url("$graph/me?\$select=displayName,userPrincipalName,mail").get(), accessToken).let {
            CloudAccount(it.optString("mail").takeIf { m -> m.isNotEmpty() && m != "null" } ?: it.optString("userPrincipalName"), it.optString("displayName"))
        }
        Protocol.GDRIVE -> get(Request.Builder().url("https://www.googleapis.com/drive/v3/about?fields=user(emailAddress,displayName)").get(), accessToken).let {
            val user = it.getJSONObject("user")
            CloudAccount(user.optString("emailAddress"), user.optString("displayName"))
        }
        else -> throw IllegalArgumentException(p.name)
    }

    private fun get(builder: Request.Builder, token: String): JSONObject =
        CloudHttp.send(builder.header("Authorization", "Bearer $token").build()).use { r ->
            if (!r.isSuccessful) throw RemoteException(CloudHttp.reasonOf(r.code), "compte : HTTP ${r.code}")
            JSONObject(r.body.string())
        }

    companion object {
        private const val MICROSOFT_SCOPES = "Files.ReadWrite offline_access User.Read"

        private val random = SecureRandom()

        fun randomToken(bytes: Int = 32): String = ByteArray(bytes).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

        fun challenge(verifier: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    }
}
