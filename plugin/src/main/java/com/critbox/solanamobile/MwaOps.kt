package com.critbox.solanamobile

import android.net.Uri
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.Solana
import com.solana.mobilewalletadapter.common.signin.SignInWithSolana
import org.json.JSONArray
import org.json.JSONObject

/** Who the dApp is (shown in the wallet prompt) and which cluster it uses. */
data class DappConfig(
    val identityName: String = "Godot dApp",
    val identityUri: String = "https://godotengine.org",
    val iconUri: String = "favicon.ico",
    val cluster: String = "mainnet-beta",
)

/**
 * One factory per wallet operation. Each returns an [MwaOp] that
 * [MwaBridgeActivity] runs inside a single MWA session. A session re-uses
 * `auth_token` when given (no approval prompt if the wallet still trusts it).
 */
object MwaOps {
    private fun adapter(config: DappConfig, authToken: String) = MobileWalletAdapter(
        connectionIdentity = ConnectionIdentity(
            identityUri = Uri.parse(config.identityUri),
            iconUri = Uri.parse(config.iconUri),
            identityName = config.identityName,
        ),
    ).apply {
        blockchain = when (config.cluster) {
            "devnet" -> Solana.Devnet
            "testnet" -> Solana.Testnet
            else -> Solana.Mainnet
        }
        this.authToken = authToken.ifEmpty { null }
    }

    private fun payloads(params: JSONObject, key: String): Array<ByteArray> {
        val arr = params.optJSONArray(key) ?: JSONArray()
        require(arr.length() > 0) { "'$key' must hold at least one base64 entry" }
        return Array(arr.length()) { MwaResults.unb64(arr.getString(it)) }
    }

    fun build(op: String, config: DappConfig, params: JSONObject): MwaOp {
        val token = params.optString("auth_token", "")
        return when (op) {
            "authorize" -> authorize(config, token, params.optJSONObject("sign_in"))
            "deauthorize" -> deauthorize(config, token)
            "capabilities" -> capabilities(config, token)
            "sign_messages" -> signMessages(config, token, payloads(params, "messages"))
            "sign_transactions" -> signTransactions(config, token, payloads(params, "transactions"))
            "sign_and_send" -> signAndSend(config, token, payloads(params, "transactions"))
            else -> throw IllegalArgumentException("Unknown op '$op'")
        }
    }

    fun authorize(config: DappConfig, token: String, signIn: JSONObject?): MwaOp = { sender ->
        val payload = signIn?.let {
            SignInWithSolana.Payload(it.optString("domain", Uri.parse(config.identityUri).host ?: ""),
                it.optString("statement", "Sign in to ${config.identityName}"))
        }
        MwaResults.from(adapter(config, token).transact(sender, payload) { }) { _, _ -> }
    }

    fun deauthorize(config: DappConfig, token: String): MwaOp = { sender ->
        MwaResults.from(adapter(config, token).disconnect(sender)) { _, _ -> }
    }

    fun capabilities(config: DappConfig, token: String): MwaOp = { sender ->
        MwaResults.from(adapter(config, token).transact(sender, null) { getCapabilities() }) { caps, json ->
            json.put("capabilities", JSONObject()
                .put("max_transactions", caps.maxTransactionsPerSigningRequest)
                .put("max_messages", caps.maxMessagesPerSigningRequest)
                .put("supported_transaction_versions",
                    JSONArray((caps.supportedTransactionVersions ?: emptyArray()).map { it.toString() }))
                .put("supported_optional_features", JSONArray((caps.supportedOptionalFeatures ?: emptyArray()).toList())))
        }
    }

    fun signMessages(config: DappConfig, token: String, messages: Array<ByteArray>): MwaOp = { sender ->
        MwaResults.from(adapter(config, token).transact(sender, null) { auth ->
            signMessagesDetached(messages, arrayOf(auth.accounts.first().publicKey))
        }) { res, json ->
            val sigs = JSONArray()
            val signed = JSONArray()
            res.messages.forEach { m ->
                sigs.put(MwaResults.b64(m.signatures.first()))
                signed.put(MwaResults.b64(m.message))
            }
            json.put("signatures", sigs).put("signed_messages", signed)
        }
    }

    fun signTransactions(config: DappConfig, token: String, txs: Array<ByteArray>): MwaOp = { sender ->
        MwaResults.from(adapter(config, token).transact(sender, null) { signTransactions(txs) }) { res, json ->
            json.put("signed_transactions", JSONArray(res.signedPayloads.map(MwaResults::b64)))
        }
    }

    fun signAndSend(config: DappConfig, token: String, txs: Array<ByteArray>): MwaOp = { sender ->
        MwaResults.from(adapter(config, token).transact(sender, null) { signAndSendTransactions(txs) }) { res, json ->
            // Base58, the form explorers and RPC getTransaction take.
            json.put("signatures", JSONArray(res.signatures.map(Base58::encode)))
        }
    }
}
