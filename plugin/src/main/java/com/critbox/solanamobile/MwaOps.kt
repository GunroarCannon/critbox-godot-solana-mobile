package com.critbox.solanamobile

import android.net.Uri
import android.util.Log
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.Solana
import com.solana.mobilewalletadapter.clientlib.TransactionParams
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
    private const val TAG = "SolanaMobile"
    private var cached: Pair<DappConfig, MobileWalletAdapter>? = null

    /**
     * One adapter per config, kept between requests: it remembers the wallet's
     * `wallet_uri_base`, so after the first connect requests go straight to that
     * wallet instead of showing Android's app chooser every time.
     */
    @Synchronized
    private fun adapter(config: DappConfig, authToken: String): MobileWalletAdapter {
        val hit = cached?.takeIf { it.first == config }?.second
        val adapter = hit ?: MobileWalletAdapter(
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
        }
        cached = config to adapter
        adapter.authToken = authToken.ifEmpty { null }
        return adapter
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
            "transfer" -> transfer(config, token, params.getJSONObject("transfer"), params.getString("rpc_url"))
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

    /**
     * A transfer from build-transfer JSON (`to`, `amount`, `mint`, ...; `payer`
     * and `blockhash` may be left out). Shared by `build_transfer` and [transfer].
     */
    fun transferFrom(o: JSONObject, payer: String = o.optString("payer", ""), blockhash: String = o.optString("blockhash", "")) =
        TxBuilder.Transfer(
            payer = payer,
            to = o.getString("to"),
            amount = o.get("amount").toString().toULong(),
            blockhash = blockhash,
            mint = o.optString("mint", ""),
            decimals = o.optInt("decimals", 0),
            tokenProgram = o.optString("token_program", TxBuilder.TOKEN_PROGRAM).ifEmpty { TxBuilder.TOKEN_PROGRAM },
            createAta = o.optBoolean("create_ata", true),
            memo = o.optString("memo", ""),
        )

    /**
     * Build, sign and send a transfer in one session. The blockhash is refreshed
     * once the wallet is connected, so the chooser and the wallet's start-up don't
     * eat into its ~36 s (devnet) life. Android 16 blocks a background app's
     * network, so if that refresh fails the one fetched before the wallet opened
     * (`blockhash` + `slot` in [spec]) is used. The payer defaults to the
     * authorised account.
     */
    fun transfer(config: DappConfig, token: String, spec: JSONObject, rpcUrl: String): MwaOp = { sender ->
        val prefetched = spec.optString("blockhash", "").takeIf { it.isNotEmpty() }
            ?.let { Rpc.Blockhash(it, spec.optLong("slot", 0L)) }
        MwaResults.from(adapter(config, token).transact(sender, null) { auth ->
            val latest = try {
                Rpc.latestBlockhash(rpcUrl, if (prefetched != null) 1 else 3).also { Log.i(TAG, "Blockhash refreshed in session") }
            } catch (e: RpcException) {
                Log.i(TAG, "In-session blockhash refresh failed (${e.message}); using the prefetched one")
                prefetched ?: throw e
            }
            val payer = spec.optString("payer", "").ifEmpty { Base58.encode(auth.accounts.first().publicKey) }
            val tx = TxBuilder.build(transferFrom(spec, payer, latest.blockhash))
            // Min context slot: the wallet's RPC must have seen our blockhash before it simulates.
            val minSlot = latest.slot.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
            signAndSendTransactions(arrayOf(tx), TransactionParams(minSlot, "confirmed", null, null, null))
        }) { res, json ->
            json.put("signatures", JSONArray(res.signatures.map(Base58::encode)))
        }
    }

    fun signAndSend(config: DappConfig, token: String, txs: Array<ByteArray>): MwaOp = { sender ->
        MwaResults.from(adapter(config, token).transact(sender, null) { signAndSendTransactions(txs) }) { res, json ->
            // Base58, the form explorers and RPC getTransaction take.
            json.put("signatures", JSONArray(res.signatures.map(Base58::encode)))
        }
    }
}
