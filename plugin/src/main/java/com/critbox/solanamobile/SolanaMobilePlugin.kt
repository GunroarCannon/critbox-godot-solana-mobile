package com.critbox.solanamobile

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.godotengine.godot.Godot
import org.godotengine.godot.plugin.GodotPlugin
import org.godotengine.godot.plugin.SignalInfo
import org.godotengine.godot.plugin.UsedByGodot
import org.json.JSONObject

/**
 * The Godot singleton "SolanaMobile": Mobile Wallet Adapter for GDScript.
 *
 * Only Strings and ints cross the JNI line. Bytes travel as base64 inside JSON,
 * so Godot's type marshalling never gets in the way. Wallet calls are async:
 * [request] returns an id at once, and the outcome arrives later as
 * `request_succeeded(id, json)` or `request_failed(id, code, message)`.
 */
class SolanaMobilePlugin(godot: Godot) : GodotPlugin(godot) {
    companion object {
        private const val TAG = "SolanaMobile"
        /** If the bridge activity hasn't claimed a request by then, it never will. */
        private const val BRIDGE_START_TIMEOUT_MS = 4000L
        private const val SEED_VAULT_PERMISSION = "com.solanamobile.seedvault.ACCESS_SEED_VAULT"
    }

    private val succeeded = SignalInfo("request_succeeded", Int::class.javaObjectType, String::class.java)
    private val failed = SignalInfo("request_failed", Int::class.javaObjectType, String::class.java, String::class.java)
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var config = DappConfig()

    init {
        // Outcomes land on the UI thread; signals go out on Godot's thread.
        MwaBridge.setListener { id, outcome ->
            if (!outcome.ok) Log.i(TAG, "request $id failed: ${outcome.code} ${outcome.message}")
            activity?.let { MwaKeepAliveService.release(it.applicationContext) }
            runOnRenderThread {
                if (outcome.ok) {
                    emitSignal(succeeded.name, id, outcome.json)
                } else {
                    emitSignal(failed.name, id, outcome.code, outcome.message)
                }
            }
        }
    }

    override fun getPluginName() = "SolanaMobile"

    override fun getPluginSignals(): Set<SignalInfo> = setOf(succeeded, failed)

    // --- Configuration ---

    /** `{identity_name, identity_uri, icon_uri, cluster}`. Returns "" or an error. */
    @UsedByGodot
    fun configure(json: String): String = try {
        val o = JSONObject(json)
        val d = DappConfig()
        config = DappConfig(
            identityName = o.optString("identity_name", d.identityName),
            identityUri = o.optString("identity_uri", d.identityUri),
            iconUri = o.optString("icon_uri", d.iconUri),
            cluster = o.optString("cluster", d.cluster),
        )
        ""
    } catch (e: Exception) {
        e.message ?: "bad config"
    }

    // --- Wallet requests ---

    /** Starts wallet op [op] with JSON [params]. Returns its id, or -1 if one is in flight. */
    @UsedByGodot
    fun request(op: String, params: String): Int {
        val walletOp = try {
            MwaOps.build(op, config, if (params.isEmpty()) JSONObject() else JSONObject(params))
        } catch (e: Exception) {
            // Report bad input through the same signal path, after the caller has the id.
            val id = MwaBridge.begin { Outcome.failure("invalid_request", e.message ?: "bad params") }
            if (id != -1) main.post { MwaBridge.complete(id, Outcome.failure("invalid_request", e.message ?: "bad params")) }
            return id
        }
        val id = MwaBridge.begin(walletOp)
        if (id == -1) return -1
        val host = activity
        if (host == null) {
            main.post { MwaBridge.complete(id, Outcome.failure("error", "No Android activity")) }
            return id
        }
        main.post {
            try {
                // Keeps the game's network and process while the wallet is in front.
                MwaKeepAliveService.hold(host.applicationContext)
                host.startActivity(Intent(host, MwaBridgeActivity::class.java).putExtra(MwaBridgeActivity.EXTRA_ID, id))
            } catch (e: Exception) {
                MwaBridge.complete(id, Outcome.failure("error", "Could not open the wallet bridge: ${e.message}"))
            }
        }
        main.postDelayed({
            if (MwaBridge.inFlight() == id && !MwaBridge.isTaken(id)) {
                MwaBridge.complete(id, Outcome.failure("error", "The wallet bridge did not start"))
            }
        }, BRIDGE_START_TIMEOUT_MS)
        return id
    }

    /** Ends the request in flight as `cancelled`. */
    @UsedByGodot
    fun cancel() = MwaBridge.cancel()

    /** The id of the request in flight, or -1. */
    @UsedByGodot
    fun busy_id(): Int = MwaBridge.inFlight()

    // --- Device ---

    /** True if any installed app answers the MWA association intent. */
    @UsedByGodot
    fun is_wallet_installed(): Boolean {
        val ctx = activity ?: return false
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("solana-wallet:/v1/associate/local"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        return probe.resolveActivity(ctx.packageManager) != null
    }

    /** `{manufacturer, model, brand, sdk, solana_mobile, seeker, seed_vault}`. */
    @UsedByGodot
    fun device_info(): String {
        val maker = Build.MANUFACTURER ?: ""
        val model = Build.MODEL ?: ""
        val solanaMobile = maker.contains("solana", ignoreCase = true)
        return JSONObject()
            .put("manufacturer", maker)
            .put("model", model)
            .put("brand", Build.BRAND ?: "")
            .put("sdk", Build.VERSION.SDK_INT)
            .put("solana_mobile", solanaMobile)
            .put("seeker", solanaMobile && model.contains("seeker", ignoreCase = true))
            .put("seed_vault", hasSeedVault())
            .toString()
    }

    private fun hasSeedVault(): Boolean {
        val ctx = activity ?: return false
        return try {
            ctx.packageManager.getPermissionInfo(SEED_VAULT_PERMISSION, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    // --- Transactions ---

    /**
     * Unsigned transfer transaction. Params: `payer`, `to`, `amount` (base units,
     * as a string so u64 survives), `blockhash`, and for tokens `mint`, `decimals`,
     * optional `token_program`, `create_ata` (default true), `memo`.
     * Returns `{tx}` (base64) or `{error}`.
     */
    @UsedByGodot
    fun build_transfer(params: String): String = try {
        val o = JSONObject(params)
        val bytes = TxBuilder.build(MwaOps.transferFrom(o))
        JSONObject().put("tx", MwaResults.b64(bytes)).toString()
    } catch (e: Exception) {
        JSONObject().put("error", e.message ?: e.javaClass.simpleName).toString()
    }

    /** The associated token account of `owner` for `mint` (base58), or "" on bad input. */
    @UsedByGodot
    fun token_account(owner: String, mint: String, tokenProgram: String): String = try {
        TxBuilder.associatedTokenAddress(owner, mint, tokenProgram.ifEmpty { TxBuilder.TOKEN_PROGRAM }).base58()
    } catch (e: Exception) {
        ""
    }
}
