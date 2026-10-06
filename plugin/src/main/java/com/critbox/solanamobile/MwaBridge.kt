package com.critbox.solanamobile

import android.app.Activity
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import org.json.JSONObject
import java.lang.ref.WeakReference

/** How one request ended: a JSON result, or an error code + message. */
class Outcome private constructor(val ok: Boolean, val json: String, val code: String, val message: String) {
    companion object {
        fun success(result: JSONObject) = Outcome(true, result.toString(), "", "")
        fun failure(code: String, message: String) = Outcome(false, "", code, message)
    }
}

typealias MwaOp = suspend (ActivityResultSender) -> Outcome

/**
 * Hands one MWA operation from the plugin to [MwaBridgeActivity] and its outcome
 * back. Only one request is ever in flight: a wallet can only show one prompt.
 * [complete] is idempotent, so the activity's normal finish, its destruction and
 * an explicit cancel can all race safely; the first one wins.
 */
object MwaBridge {
    private val lock = Any()
    private var nextId = 1
    private var pendingId = -1
    private var pendingOp: MwaOp? = null
    private var taken = false
    private var host: WeakReference<Activity>? = null
    private var listener: ((Int, Outcome) -> Unit)? = null

    fun setListener(l: (Int, Outcome) -> Unit) {
        synchronized(lock) { listener = l }
    }

    /** Queues [op]; returns its id, or -1 while another request is in flight. */
    fun begin(op: MwaOp): Int = synchronized(lock) {
        if (pendingId != -1) return -1
        pendingId = nextId++
        pendingOp = op
        taken = false
        host = null
        pendingId
    }

    /** The activity claims the op for [id] (null if it was cancelled meanwhile). */
    fun take(id: Int, activity: Activity): MwaOp? = synchronized(lock) {
        if (id != pendingId || taken) return null
        taken = true
        host = WeakReference(activity)
        pendingOp
    }

    fun isTaken(id: Int): Boolean = synchronized(lock) { id == pendingId && taken }

    fun inFlight(): Int = synchronized(lock) { pendingId }

    fun complete(id: Int, outcome: Outcome) {
        val l: ((Int, Outcome) -> Unit)?
        synchronized(lock) {
            if (id == -1 || id != pendingId) return
            pendingId = -1
            pendingOp = null
            taken = false
            host = null
            l = listener
        }
        l?.invoke(id, outcome)
    }

    /** Ends the request in flight as `cancelled` and closes its host activity. */
    fun cancel() {
        val id: Int
        val activity: Activity?
        synchronized(lock) {
            id = pendingId
            activity = host?.get()
        }
        complete(id, Outcome.failure("cancelled", "Cancelled by the game"))
        activity?.runOnUiThread { activity.finish() }
    }
}
