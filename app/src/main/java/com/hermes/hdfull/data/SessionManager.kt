package com.hermes.hdfull.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class SessionManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("hdfull_session", Context.MODE_PRIVATE)

    var host: String
        get() = prefs.getString("host", "") ?: ""
        set(v) = prefs.edit { putString("host", v) }

    var loggedIn: Boolean
        get() = prefs.getBoolean("logged_in", false)
        set(v) = prefs.edit { putBoolean("logged_in", v) }

    var username: String
        get() = prefs.getString("username", "") ?: ""
        set(v) = prefs.edit { putString("username", v) }

    fun logout() {
        prefs.edit {
            putBoolean("logged_in", false)
            putString("host", "")
        }
    }
}
