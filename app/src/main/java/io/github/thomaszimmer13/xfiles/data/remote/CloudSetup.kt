package io.github.thomaszimmer13.xfiles.data.remote

import org.json.JSONObject

/**
 * Création (ou reconnexion) d'un compte rclone sans questions : rclone pose ses questions une à une
 * (« utiliser un navigateur ? », « type de OneDrive ? »…), chacune reçoit sa réponse par défaut, sauf
 * le navigateur (oui). L'appel qui attend l'accord donné dans le navigateur est bloquant.
 */
object CloudSetup {
    private const val MAX_QUESTIONS = 30

    fun configure(rpc: RcloneRpc, name: String, type: String, parameters: JSONObject) {
        val opt = { JSONObject().put("nonInteractive", true).put("obscure", true) }
        var out = rpc.call("config/create", JSONObject().put("name", name).put("type", type).put("parameters", parameters).put("opt", opt()))
        repeat(MAX_QUESTIONS) {
            out.optString("Error").takeIf { it.isNotEmpty() }?.let { throw RcloneException(500, it) }
            val state = out.optString("State")
            if (state.isEmpty()) return
            val option = out.optJSONObject("Option") ?: throw RcloneException(500, "question sans réponse : $state")
            val answer = when (option.optString("Name")) {
                "config_is_local" -> "true"
                // Identifiant partagé de rclone retiré (Google Drive) : il en faut un.
                "config_shared_client_id" -> throw RcloneException(400, "client_id required")
                else -> option.optString("ValueStr").ifEmpty { option.opt("Default")?.toString().orEmpty() }
            }
            out = rpc.call(
                "config/update",
                JSONObject().put("name", name).put("parameters", parameters)
                    .put("opt", opt().put("continue", true).put("state", state).put("result", answer)),
            )
        }
        throw RcloneException(500, "trop de questions")
    }
}
