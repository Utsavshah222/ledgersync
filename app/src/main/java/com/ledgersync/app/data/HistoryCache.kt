package com.ledgersync.app.data

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps the first page of each history view on disk (and in memory), so lists open instantly —
 * even offline — while a fresh copy loads in the background.
 */
object HistoryCache {
    private val mem = ConcurrentHashMap<String, String>()

    fun key(account: String?, type: String?) = "${account.orEmpty()}|${type.orEmpty()}"
    val DEFAULT = key(null, null)

    private fun file(ctx: Context, key: String): File {
        val hex = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(ctx.cacheDir, "history").apply { mkdirs() }, "$hex.json")
    }

    fun read(ctx: Context, key: String): HistoryPage? {
        val raw = mem[key] ?: runCatching { file(ctx, key).takeIf { it.exists() }?.readText() }.getOrNull()?.also { mem[key] = it }
        return HistoryPage.parse(raw)
    }

    fun write(ctx: Context, key: String, json: String) {
        mem[key] = json
        runCatching {
            val f = file(ctx, key)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    fun clear(ctx: Context) {
        mem.clear()
        runCatching { File(ctx.cacheDir, "history").deleteRecursively() }
    }
}
