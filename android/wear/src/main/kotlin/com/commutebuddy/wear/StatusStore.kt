package com.commutebuddy.wear

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CommuteStatusSnapshot(
    val action: String,
    val summary: String,
    val affectedRoutes: String,
    val rerouteHint: String?,
    val timestamp: Long
)

object StatusStore {

    private const val PREFS_NAME = "commute_status"
    private const val KEY_ACTION = "action"
    private const val KEY_SUMMARY = "summary"
    private const val KEY_AFFECTED_ROUTES = "affected_routes"
    private const val KEY_REROUTE_HINT = "reroute_hint"
    private const val KEY_TIMESTAMP = "timestamp"
    private const val KEY_HAS_DATA = "has_data"

    private val _flow = MutableStateFlow<CommuteStatusSnapshot?>(null)
    val flow: StateFlow<CommuteStatusSnapshot?> = _flow.asStateFlow()

    /** Seed the in-memory flow from SharedPreferences (call on Activity start). */
    fun init(context: Context) {
        if (_flow.value == null) {
            _flow.value = load(context)
        }
    }

    fun save(context: Context, snapshot: CommuteStatusSnapshot) {
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        editor.putString(KEY_ACTION, snapshot.action)
        editor.putString(KEY_SUMMARY, snapshot.summary)
        editor.putString(KEY_AFFECTED_ROUTES, snapshot.affectedRoutes)
        if (snapshot.rerouteHint != null) {
            editor.putString(KEY_REROUTE_HINT, snapshot.rerouteHint)
        } else {
            editor.remove(KEY_REROUTE_HINT)
        }
        editor.putLong(KEY_TIMESTAMP, snapshot.timestamp)
        editor.putBoolean(KEY_HAS_DATA, true)
        editor.apply()
        _flow.value = snapshot
    }

    fun load(context: Context): CommuteStatusSnapshot? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_HAS_DATA, false)) return null
        return CommuteStatusSnapshot(
            action = prefs.getString(KEY_ACTION, "NORMAL") ?: "NORMAL",
            summary = prefs.getString(KEY_SUMMARY, "") ?: "",
            affectedRoutes = prefs.getString(KEY_AFFECTED_ROUTES, "") ?: "",
            rerouteHint = prefs.getString(KEY_REROUTE_HINT, null),
            timestamp = prefs.getLong(KEY_TIMESTAMP, 0L)
        )
    }
}
