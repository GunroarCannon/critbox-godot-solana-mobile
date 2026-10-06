package com.critbox.solanamobile

import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * An invisible activity that hosts exactly one MWA session.
 *
 * MWA's [ActivityResultSender] must be created before its activity starts, which
 * Godot's own activity is long past by the time GDScript asks to connect. So each
 * request gets a fresh transparent activity: create the sender in [onCreate], run
 * the operation, report, finish. Closing it early (the user backs out of the
 * wallet, or the system destroys it) reports `cancelled`; a request never hangs.
 */
class MwaBridgeActivity : ComponentActivity() {
    companion object {
        const val EXTRA_ID = "com.critbox.solanamobile.REQUEST_ID"
        private const val TAG = "SolanaMobile"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var requestId = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Registered before STARTED, as ActivityResultLauncher requires.
        val sender = ActivityResultSender(this)
        requestId = intent.getIntExtra(EXTRA_ID, -1)
        // A re-created activity (process death) has no op to resume.
        val op = if (savedInstanceState == null) MwaBridge.take(requestId, this) else null
        if (op == null) {
            finishQuietly()
            return
        }
        scope.launch {
            val outcome = try {
                op(sender)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "MWA request $requestId failed", e)
                MwaResults.fromException(e)
            }
            MwaBridge.complete(requestId, outcome)
            finishQuietly()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        MwaBridge.complete(requestId, Outcome.failure("cancelled", "The wallet closed before answering"))
        super.onDestroy()
    }

    private fun finishQuietly() {
        finish()
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
