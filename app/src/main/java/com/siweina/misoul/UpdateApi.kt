package com.siweina.misoul

import android.content.Context
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileOutputStream
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 从 GitHub Releases 取最新版本。
 *
 * 故意**不用 api.github.com**：未登录的 API 按 IP 限流 60 次/小时，手机在移动网络下
 * 很容易撞上 403。改用 releases.atom（普通网页、无限流），从第一个 entry 里取 tag，
 * APK 地址按发布约定拼：`MISoul-<tag>.apk`。
 */
object UpdateApi {

    const val REPO = "siweina/MISoul"
    private const val FEED = "https://github.com/$REPO/releases.atom"
    private const val DOWNLOAD = "https://github.com/$REPO/releases/download"
    private const val USER_AGENT = "MISoul-updater"

    data class Release(
        val tag: String,
        val name: String,
        val notes: String,
        val apkUrl: String,
        val prerelease: Boolean,
    )

    sealed interface Result {
        data class Found(val release: Release) : Result
        data object UpToDate : Result
        data class Failed(val message: String) : Result
    }

    fun apkUrl(tag: String): String = "$DOWNLOAD/$tag/MISoul-$tag.apk"

    fun check(currentVersion: String): Result {
        val xml = try {
            get(FEED)
        } catch (t: Throwable) {
            return Result.Failed(t.message ?: "网络请求失败")
        }
        val tag = try {
            latestTag(xml)
        } catch (t: Throwable) {
            null
        } ?: return Result.Failed("解析 releases 失败")

        if (!isNewer(tag, currentVersion)) return Result.UpToDate

        return Result.Found(
            Release(
                tag = tag,
                name = tag,
                notes = "",
                apkUrl = apkUrl(tag),
                prerelease = tag.contains("beta") || tag.contains("alpha") || tag.contains("rc"),
            ),
        )
    }

    /** 下载到 cache/updates/，返回文件；跳转由 HttpURLConnection 自动跟随。 */
    fun download(context: Context, release: Release): File? {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, "app-update.apk")
        return try {
            val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
            }
            if (connection.responseCode !in 200..299) {
                android.util.Log.w("MISoul", "download HTTP ${connection.responseCode} ${release.apkUrl}")
                return null
            }
            connection.inputStream.use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
            target
        } catch (t: Throwable) {
            android.util.Log.w("MISoul", "download failed: $t")
            null
        }
    }

    private fun latestTag(xml: String): String? {
        val parser: XmlPullParser = Xml.newPullParser()
        parser.setInput(StringReader(xml))
        var event = parser.eventType
        var inEntry = false
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "entry" -> inEntry = true
                    "link" -> if (inEntry) {
                        val href = parser.getAttributeValue(null, "href").orEmpty()
                        val marker = "/releases/tag/"
                        if (href.contains(marker)) return href.substringAfter(marker)
                    }
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == "entry") {
                inEntry = false
            }
            event = parser.next()
        }
        return null
    }

    private fun get(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/atom+xml, text/html")
        }
        val code = connection.responseCode
        if (code !in 200..299) {
            android.util.Log.w("MISoul", "feed HTTP $code")
            throw java.io.IOException("HTTP $code")
        }
        return connection.inputStream.bufferedReader().use { it.readText() }
    }

    /** 只比较数字段：0.2.1 > 0.2.0，1.0 > 0.9.9。 */
    internal fun isNewer(remote: String, local: String): Boolean {
        val a = numbers(remote)
        val b = numbers(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun numbers(version: String): List<Int> =
        version.trim().removePrefix("v").split(".", "-", "+")
            .mapNotNull { part -> part.takeWhile { it.isDigit() }.toIntOrNull() }
}
