package com.ledgersync.app.sync

import com.ledgersync.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class AppUpdate(val version: String, val apkUrl: String, val pageUrl: String, val notes: String)

/**
 * Checks the GitHub repo's latest Release for a newer APK.
 * Works for public repos; for a private repo GitHub returns 404 and the check silently does nothing.
 */
object UpdateChecker {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    suspend fun check(currentVersion: String): AppUpdate? = withContext(Dispatchers.IO) {
        val repo = BuildConfig.GITHUB_REPO
        if (repo.isBlank()) return@withContext null
        runCatching {
            val req = Request.Builder()
                .url("https://api.github.com/repos/$repo/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "LedgerSync-Android")
                .build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@use null
                val o = JSONObject(res.body?.string().orEmpty())
                val tag = o.optString("tag_name").removePrefix("v").trim()
                if (tag.isEmpty() || !isNewer(tag, currentVersion)) return@use null
                val assets = o.optJSONArray("assets")
                val apk = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith(".apk", true) }
                AppUpdate(
                    version = tag,
                    apkUrl = apk?.optString("browser_download_url") ?: o.optString("html_url"),
                    pageUrl = o.optString("html_url"),
                    notes = o.optString("body").take(400),
                )
            }
        }.getOrNull()
    }

    /** Semantic comparison: 1.10.0 > 1.9.3. */
    fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.split('.', '-').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val a = parts(candidate); val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
