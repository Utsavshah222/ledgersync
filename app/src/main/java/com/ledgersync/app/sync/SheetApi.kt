package com.ledgersync.app.sync

import com.ledgersync.app.data.BankAccount
import com.ledgersync.app.data.TxnEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SheetException(message: String) : Exception(message)

/** Talks to the Google Apps Script web app that lives inside the company's Google Sheet. */
class SheetApi(private val url: String, private val key: String) {

    private fun get(action: String): JSONObject {
        val httpUrl = url.toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("action", action)
            ?.addQueryParameter("key", key)
            ?.build() ?: throw SheetException("The Web App URL is not valid")
        return execute(Request.Builder().url(httpUrl).get().build())
    }

    private fun post(payload: JSONObject): JSONObject {
        payload.put("key", key)
        val body = payload.toString().toRequestBody("text/plain; charset=utf-8".toMediaType())
        return execute(Request.Builder().url(url).post(body).build())
    }

    private fun execute(req: Request): JSONObject {
        val res = try { client.newCall(req).execute() } catch (e: Exception) {
            throw SheetException("No internet or Google not reachable (${e.javaClass.simpleName})")
        }
        res.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw SheetException("Google returned HTTP ${it.code}")
            val json = try { JSONObject(text) } catch (_: Exception) {
                throw SheetException(
                    if (text.contains("<html", true)) "Script not deployed for \"Anyone\" access — check the deployment"
                    else "Unexpected reply from the script"
                )
            }
            if (!json.optBoolean("ok")) throw SheetException(json.optString("error", "Script reported an error"))
            return json
        }
    }

    suspend fun ping(): JSONObject = withContext(Dispatchers.IO) { get("ping") }

    suspend fun fetchAccounts(): List<BankAccount> = withContext(Dispatchers.IO) {
        val arr = get("config").optJSONArray("accounts") ?: JSONArray()
        (0 until arr.length()).map { BankAccount.fromJson(arr.getJSONObject(it)) }.filter { it.nickname.isNotBlank() }
    }

    suspend fun fetchSummary(): String = withContext(Dispatchers.IO) {
        get("summary").put("fetchedAt", System.currentTimeMillis()).toString()
    }

    suspend fun addAccount(acc: BankAccount): Unit = withContext(Dispatchers.IO) {
        post(JSONObject().put("action", "addAccount").put("account", acc.toJson()))
    }

    /** Returns txnKey → status ("added" | "duplicate"). */
    suspend fun push(device: String, txns: List<TxnEntity>): Map<String, String> = withContext(Dispatchers.IO) {
        val arr = JSONArray()
        txns.forEach { t ->
            arr.put(JSONObject().apply {
                put("id", t.txnKey); put("ts", t.timestamp); put("account", t.accountNick); put("bank", t.bank)
                put("masked", t.accountMasked); put("owner", t.owner); put("type", t.type); put("amount", t.amount)
                put("balance", t.balance ?: JSONObject.NULL); put("mode", t.mode); put("party", t.party)
                put("ref", t.reference); put("sender", t.sender); put("sms", t.body)
            })
        }
        val res = post(JSONObject().put("action", "push").put("device", device).put("txns", arr))
        val r = res.optJSONObject("results") ?: JSONObject()
        r.keys().asSequence().associateWith { r.optString(it) }
    }

    companion object {
        private val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }
}
