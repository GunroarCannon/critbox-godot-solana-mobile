package com.critbox.solanamobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** An RPC call that failed; reported to GDScript as `rpc_error`. */
class RpcException(message: String) : Exception(message)

/**
 * The few JSON-RPC calls the plugin makes itself. Only used where timing matters:
 * a blockhash lives 150 blocks (about 36 s on devnet, 60 s on mainnet), so a
 * transfer tries to refresh it inside the wallet session. Android 16 cuts a
 * background app's network while the wallet is in front, so that refresh may
 * fail; the caller then falls back to one fetched before the wallet opened.
 */
object Rpc {
    data class Blockhash(val blockhash: String, val slot: Long)

    suspend fun latestBlockhash(url: String, attempts: Int = ATTEMPTS): Blockhash {
        val result = call(url, "getLatestBlockhash", JSONArray().put(JSONObject().put("commitment", "confirmed")), attempts)
        return Blockhash(result.getJSONObject("value").getString("blockhash"),
            result.getJSONObject("context").getLong("slot"))
    }

    private const val ATTEMPTS = 3

    /**
     * Network failures (a dropped DNS lookup, a reset socket) are retried, as
     * phones on mobile data see them often; an error reply from the node is not.
     */
    private suspend fun call(url: String, method: String, params: JSONArray, attempts: Int): JSONObject {
        var last: IOException? = null
        repeat(attempts) { attempt ->
            try {
                return callOnce(url, method, params)
            } catch (e: IOException) {
                last = e
                if (attempt < attempts - 1) delay(500L * (attempt + 1))
            }
        }
        throw RpcException("$method failed: ${last?.message ?: "network error"}")
    }

    private suspend fun callOnce(url: String, method: String, params: JSONArray): JSONObject = withContext(Dispatchers.IO) {
        val body = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", method).put("params", params)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 10_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode != 200) throw RpcException("$method: HTTP ${conn.responseCode} from $url")
            val reply = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            reply.optJSONObject("error")?.let { throw RpcException("$method: ${it.optString("message")}") }
            reply.getJSONObject("result")
        } catch (e: RpcException) {
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw RpcException("$method failed: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            conn.disconnect()
        }
    }
}
