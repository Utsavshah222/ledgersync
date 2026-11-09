package com.ledgersync.app.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Small synchronous key-value store, readable from the SMS receiver without coroutines. */
class Settings private constructor(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("ledgersync", Context.MODE_PRIVATE)

    private val _version = MutableStateFlow(0)
    /** Bumps on every change so Compose can recompose. */
    val version: StateFlow<Int> = _version.asStateFlow()

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> _version.value++ }
    init { sp.registerOnSharedPreferenceChangeListener(listener) }

    var scriptUrl: String
        get() = sp.getString("scriptUrl", null)?.takeIf { it.isNotBlank() } ?: com.ledgersync.app.BuildConfig.DEFAULT_SCRIPT_URL
        set(v) = sp.edit().putString("scriptUrl", v.trim()).apply()

    var secretKey: String
        get() = sp.getString("secretKey", "") ?: ""
        set(v) = sp.edit().putString("secretKey", v.trim()).apply()

    var deviceName: String
        get() = sp.getString("deviceName", null) ?: "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        set(v) = sp.edit().putString("deviceName", v.trim()).apply()

    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) = sp.edit().putBoolean("onboarded", v).apply()

    var bankSendersOnly: Boolean
        get() = sp.getBoolean("bankSendersOnly", true)
        set(v) = sp.edit().putBoolean("bankSendersOnly", v).apply()

    var notifyOnCapture: Boolean
        get() = sp.getBoolean("notifyOnCapture", true)
        set(v) = sp.edit().putBoolean("notifyOnCapture", v).apply()

    var accountsJson: String
        get() = sp.getString("accounts", "[]") ?: "[]"
        set(v) = sp.edit().putString("accounts", v).apply()

    val accounts: List<BankAccount> get() = BankAccount.listFromJson(accountsJson)

    var accountsFetchedAt: Long
        get() = sp.getLong("accountsFetchedAt", 0)
        set(v) = sp.edit().putLong("accountsFetchedAt", v).apply()

    var summaryJson: String?
        get() = sp.getString("summary", null)
        set(v) = sp.edit().putString("summary", v).apply()

    /** Inbox is re-scanned from here on every periodic run — catches SMS that OEM battery savers hid from us. */
    var lastInboxScan: Long
        get() = sp.getLong("lastInboxScan", 0)
        set(v) = sp.edit().putLong("lastInboxScan", v).apply()

    var lastSyncAt: Long
        get() = sp.getLong("lastSyncAt", 0)
        set(v) = sp.edit().putLong("lastSyncAt", v).apply()

    var lastSyncError: String?
        get() = sp.getString("lastSyncError", null)
        set(v) = sp.edit().putString("lastSyncError", v).apply()

    val isConnected get() = scriptUrl.startsWith("https://") && secretKey.isNotBlank()

    companion object {
        @Volatile private var instance: Settings? = null
        fun get(context: Context): Settings = instance ?: synchronized(this) {
            instance ?: Settings(context.applicationContext).also { instance = it }
        }
    }
}
