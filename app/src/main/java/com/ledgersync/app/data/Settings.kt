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
        // Phones still pointing at an older built-in deployment move to the current one automatically.
        get() = sp.getString("scriptUrl", null)?.takeIf { it.isNotBlank() && it !in RETIRED_URLS } ?: com.ledgersync.app.BuildConfig.DEFAULT_SCRIPT_URL
        set(v) = sp.edit().putString("scriptUrl", v.trim()).apply()

    var secretKey: String
        get() = sp.getString("secretKey", "") ?: ""
        set(v) = sp.edit().putString("secretKey", v.trim()).apply()

    var deviceName: String
        get() = sp.getString("deviceName", null) ?: "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        set(v) = sp.edit().putString("deviceName", v.trim()).apply()

    /**
     * Bank name the user types for THIS phone. When set, every SMS captured here is filed under this exact
     * name in the sheet's "Bank" column (and its account group), instead of the name auto-detected from the
     * sender ID. Blank = keep the automatic detection.
     */
    var bankName: String
        get() = sp.getString("bankName", "") ?: ""
        set(v) = sp.edit().putString("bankName", v.trim()).apply()

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

    /**
     * Banks the user has turned OFF. An SMS whose bank is in this set is never logged to the sheet.
     * Stored as a JSON array of bank names (matching SmsParser.knownBanks). Empty = every bank is on.
     */
    var disabledBanksJson: String
        get() = sp.getString("disabledBanks", "[]") ?: "[]"
        set(v) = sp.edit().putString("disabledBanks", v).apply()

    val disabledBanks: Set<String>
        get() = try {
            val a = org.json.JSONArray(disabledBanksJson)
            (0 until a.length()).mapTo(mutableSetOf()) { a.getString(it) }
        } catch (_: Exception) { emptySet() }

    fun isBankEnabled(bank: String): Boolean = bank !in disabledBanks

    /** Turn a bank on or off for logging. */
    fun setBankEnabled(bank: String, enabled: Boolean) {
        val set = disabledBanks.toMutableSet()
        if (enabled) set.remove(bank) else set.add(bank)
        disabledBanksJson = org.json.JSONArray(set.toList()).toString()
    }

    /**
     * A nickname the user gave an individual bank during setup, keyed by the bank name (SmsParser.knownBanks).
     * When a bank has a nickname here, every SMS from that bank is filed under this exact name in the sheet's
     * "Bank" column and account group — e.g. "HDFC Bank" → "HDFC Current". Blank / absent = use the bank's own
     * name. Stored as a JSON object {bank: nickname}. Takes priority over the phone-wide [bankName].
     */
    var bankNicknamesJson: String
        get() = sp.getString("bankNicknames", "{}") ?: "{}"
        set(v) = sp.edit().putString("bankNicknames", v).apply()

    val bankNicknames: Map<String, String>
        get() = try {
            val o = org.json.JSONObject(bankNicknamesJson)
            o.keys().asSequence().associateWith { o.getString(it).trim() }.filterValues { it.isNotBlank() }
        } catch (_: Exception) { emptyMap() }

    /** The nickname set for [bank], or blank if none. */
    fun nicknameFor(bank: String): String = bankNicknames[bank] ?: ""

    /** Set (or, when blank / same as the bank's own name, clear) the nickname for one bank. */
    fun setBankNickname(bank: String, nickname: String) {
        val o = try { org.json.JSONObject(bankNicknamesJson) } catch (_: Exception) { org.json.JSONObject() }
        val n = nickname.trim()
        if (n.isBlank() || n == bank) o.remove(bank) else o.put(bank, n)
        bankNicknamesJson = o.toString()
    }

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
        private val RETIRED_URLS = setOf(
            "https://script.google.com/macros/s/AKfycbycALS2aqb8trvSf3PGv_NsYBagb7F0D8z3C9jo2wmZt_gtI87UyXSoEVpfm_RZ9DWv/exec",
            "https://script.google.com/macros/s/AKfycbzVXIBmhyPsPvDZHnAMIUaIuLS7S5mnGqH9AouqSorKAseR4lDKsZPfYjZkkDXs1Kzm/exec",
            "https://script.google.com/macros/s/AKfycbzTRewFI3e3V5LXSP-Sa9jkPmmtZcUpMf20F5q8n54tob11u2M8ZLzJCyX_L6K7i2ft/exec",
        )
        @Volatile private var instance: Settings? = null
        fun get(context: Context): Settings = instance ?: synchronized(this) {
            instance ?: Settings(context.applicationContext).also { instance = it }
        }
    }
}
