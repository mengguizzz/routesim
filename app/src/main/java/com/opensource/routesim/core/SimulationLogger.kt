package com.opensource.routesim.core

import android.util.Log

/**
 * A deliberately small, structured logging facade. Every field is emitted as
 * `key=value`, so the output remains easy to filter with adb and avoids leaking
 * prose formatting into troubleshooting.
 */
object SimulationLogger {
    const val TAG = "RouteSim"

    @Volatile
    var sessionId: String = "idle"
        private set

    fun startSession(session: String) {
        sessionId = session
        event("session_start")
    }

    fun event(name: String, vararg fields: Pair<String, Any?>) {
        Log.i(TAG, format(name, fields))
    }

    fun warning(name: String, vararg fields: Pair<String, Any?>) {
        Log.w(TAG, format(name, fields))
    }

    fun error(name: String, throwable: Throwable? = null, vararg fields: Pair<String, Any?>) {
        Log.e(TAG, format(name, fields), throwable)
    }

    private fun format(name: String, fields: Array<out Pair<String, Any?>>): String {
        val suffix = fields.joinToString(separator = " ") { (key, value) ->
            "$key=${value ?: "null"}"
        }
        return buildString {
            append("event=")
            append(name)
            append(" session=")
            append(sessionId)
            if (suffix.isNotEmpty()) {
                append(' ')
                append(suffix)
            }
        }
    }
}
