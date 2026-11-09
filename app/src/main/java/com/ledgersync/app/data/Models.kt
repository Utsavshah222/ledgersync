package com.ledgersync.app.data

import org.json.JSONArray
import org.json.JSONObject

/** A bank account the company wants to track. Source of truth: the "Accounts" tab of the Google Sheet. */
data class BankAccount(
    val nickname: String,
    val bank: String,
    val number: String,
    val owner: String, // Company | Personal
    val aliases: List<String> = emptyList(), // e.g. debit-card last 4 digits linked to this account
    val active: Boolean = true,
    val balance: Double? = null,
    val balanceAsOf: Long? = null,
) {
    val digits: String get() = number.filter { it.isDigit() }
    val masked: String get() = if (digits.length >= 4) "••" + digits.takeLast(4) else number

    fun toJson() = JSONObject().apply {
        put("nickname", nickname); put("bank", bank); put("number", number); put("owner", owner)
        put("aliases", JSONArray(aliases)); put("active", active)
        balance?.let { put("balance", it) }; balanceAsOf?.let { put("balanceAsOf", it) }
    }

    companion object {
        fun fromJson(o: JSONObject): BankAccount {
            val al = o.optJSONArray("aliases")
            val aliases = when {
                al != null -> (0 until al.length()).map { al.optString(it) }
                else -> o.optString("aliases").split(',', ' ', ';')
            }.map { it.trim() }.filter { it.isNotEmpty() }
            return BankAccount(
                nickname = o.optString("nickname").trim(),
                bank = o.optString("bank").trim(),
                number = o.optString("number").trim(),
                owner = o.optString("owner", "Company").ifBlank { "Company" },
                aliases = aliases,
                active = o.optBoolean("active", true),
                balance = if (o.has("balance") && !o.isNull("balance") && o.optString("balance").isNotBlank()) o.optDouble("balance") else null,
                balanceAsOf = if (o.has("balanceAsOf") && !o.isNull("balanceAsOf")) o.optLong("balanceAsOf").takeIf { it > 0 } else null,
            )
        }

        fun listFromJson(s: String?): List<BankAccount> = try {
            val a = JSONArray(s ?: "[]"); (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
        } catch (_: Exception) { emptyList() }

        fun listToJson(list: List<BankAccount>): String = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()
    }
}

data class PeriodStats(val credit: Double = 0.0, val debit: Double = 0.0, val count: Int = 0) {
    val net get() = credit - debit
    operator fun plus(o: PeriodStats) = PeriodStats(credit + o.credit, debit + o.debit, count + o.count)

    companion object {
        fun fromJson(o: JSONObject?) = if (o == null) PeriodStats() else
            PeriodStats(o.optDouble("credit", 0.0), o.optDouble("debit", 0.0), o.optInt("count", 0))
    }
}

data class AccountSummary(
    val nickname: String,
    val bank: String,
    val owner: String,
    val masked: String,
    val balance: Double?,
    val balanceAsOf: Long?,
    val month: PeriodStats,
    val year: PeriodStats,
    val all: PeriodStats,
) {
    fun period(p: Period) = when (p) { Period.MONTH -> month; Period.YEAR -> year; Period.ALL -> all }
}

enum class Period(val label: String) { MONTH("This Month"), YEAR("This Year"), ALL("All Time") }

data class Summary(val accounts: List<AccountSummary>, val fetchedAt: Long) {
    companion object {
        fun parse(json: String?): Summary? = try {
            val o = JSONObject(json ?: return null)
            val arr = o.getJSONArray("accounts")
            Summary(
                accounts = (0 until arr.length()).map {
                    val a = arr.getJSONObject(it)
                    AccountSummary(
                        nickname = a.optString("nickname"),
                        bank = a.optString("bank"),
                        owner = a.optString("owner", "Company"),
                        masked = a.optString("masked"),
                        balance = if (a.isNull("balance") || a.optString("balance").isBlank()) null else a.optDouble("balance"),
                        balanceAsOf = a.optLong("balanceAsOf").takeIf { t -> t > 0 },
                        month = PeriodStats.fromJson(a.optJSONObject("month")),
                        year = PeriodStats.fromJson(a.optJSONObject("year")),
                        all = PeriodStats.fromJson(a.optJSONObject("all")),
                    )
                },
                fetchedAt = o.optLong("fetchedAt", System.currentTimeMillis()),
            )
        } catch (_: Exception) { null }
    }
}
