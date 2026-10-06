package com.critbox.solanamobile

import android.util.Base64
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import com.solana.mobilewalletadapter.clientlib.protocol.JsonRpc20Client
import com.solana.mobilewalletadapter.clientlib.protocol.MobileWalletAdapterClient.AuthorizationResult
import com.solana.mobilewalletadapter.clientlib.scenario.LocalAssociationScenario
import com.solana.mobilewalletadapter.common.ProtocolContract
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException

/** Turns MWA results and errors into the JSON / error codes GDScript sees. */
object MwaResults {
    fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    fun <T> from(result: TransactionResult<T>, payload: (T, JSONObject) -> Unit): Outcome = when (result) {
        is TransactionResult.Success -> {
            @Suppress("USELESS_CAST")
            val json = authJson(result.authResult as AuthorizationResult?)
            payload(result.payload, json)
            Outcome.success(json)
        }
        is TransactionResult.NoWalletFound -> Outcome.failure("no_wallet", result.message)
        is TransactionResult.Failure -> fromException(result.e, result.message)
    }

    fun authJson(auth: AuthorizationResult?): JSONObject {
        val json = JSONObject()
        if (auth == null) return json
        json.put("auth_token", auth.authToken ?: "")
        val first = auth.accounts?.firstOrNull()
        json.put("public_key", first?.publicKey?.let(Base58::encode) ?: "")
        json.put("account_label", first?.accountLabel ?: "")
        json.put("wallet_uri_base", auth.walletUriBase?.toString() ?: "")
        val accounts = JSONArray()
        auth.accounts?.forEach { a ->
            accounts.put(JSONObject()
                .put("public_key", Base58.encode(a.publicKey))
                .put("label", a.accountLabel ?: "")
                .put("chains", JSONArray((a.chains ?: emptyArray()).toList()))
                .put("features", JSONArray((a.features ?: emptyArray()).toList())))
        }
        json.put("accounts", accounts)
        auth.signInResult?.let { s ->
            json.put("sign_in", JSONObject()
                .put("public_key", Base58.encode(s.publicKey))
                .put("signed_message", b64(s.signedMessage))
                .put("signature", b64(s.signature))
                .put("signature_type", s.signatureType ?: "ed25519"))
        }
        return json
    }

    /** Walks the cause chain for the most specific known error. */
    fun fromException(e: Throwable?, fallback: String = ""): Outcome {
        var cur: Throwable? = e
        while (cur != null) {
            when (cur) {
                is JsonRpc20Client.JsonRpc20RemoteException -> return Outcome.failure(codeFor(cur.code), cur.message ?: fallback)
                is TimeoutException -> return Outcome.failure("timeout", cur.message ?: "The wallet took too long")
                is CancellationException, is InterruptedException ->
                    return Outcome.failure("cancelled", cur.message ?: "Cancelled")
                is LocalAssociationScenario.ConnectionFailedException ->
                    return Outcome.failure("connection_failed", cur.message ?: "Could not reach the wallet")
            }
            cur = cur.cause
        }
        return Outcome.failure("error", e?.message ?: fallback.ifEmpty { "Wallet request failed" })
    }

    fun codeFor(rpcCode: Int): String = when (rpcCode) {
        // -1: the wallet no longer honours this session; -3: the user said no to this one request.
        ProtocolContract.ERROR_AUTHORIZATION_FAILED -> "unauthorized"
        ProtocolContract.ERROR_NOT_SIGNED -> "declined"
        ProtocolContract.ERROR_NOT_SUBMITTED -> "not_submitted"
        ProtocolContract.ERROR_INVALID_PAYLOADS -> "invalid_payloads"
        ProtocolContract.ERROR_TOO_MANY_PAYLOADS -> "too_many_payloads"
        ProtocolContract.ERROR_CLUSTER_NOT_SUPPORTED -> "cluster_not_supported"
        else -> "error"
    }
}
