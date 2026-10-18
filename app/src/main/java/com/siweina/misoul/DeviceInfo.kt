package com.siweina.misoul

import android.content.Context
import android.os.Build
import android.os.Process
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 概览页每行：左边标题，右边值。 */
data class InfoRow(val label: String, val value: String)

/**
 * 概览页的机器信息，做法照 LSPosed 的状态卡：全部现取，不缓存。
 */
object DeviceInfo {

    fun rows(context: Context, moduleVersion: String, xiaoai: String): List<InfoRow> = buildList {
        add(InfoRow("设备", deviceName()))
        add(InfoRow("系统", "Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）"))
        add(InfoRow("内核", kernel()))
        add(InfoRow("架构", architecture()))
        add(InfoRow("框架", lsposed()))
        add(InfoRow("模块", "MISoul $moduleVersion"))
        add(InfoRow("小爱", xiaoai))
        add(InfoRow("进程位数", if (Process.is64Bit()) "64 位" else "32 位"))
    }

    private fun deviceName(): String {
        // MIUI 的市场名（如 REDMI K90 Pro Max）在 ro.product.marketname 里，Build.MODEL 是代号
        val marketName = runCatching {
            val process = ProcessBuilder("getprop", "ro.product.marketname").start()
            process.inputStream.bufferedReader().readText().trim().also { process.waitFor() }
        }.getOrNull()
        if (!marketName.isNullOrBlank()) return marketName

        val brand = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        return if (model.startsWith(brand, ignoreCase = true)) model else "$brand $model"
    }

    private fun kernel(): String {
        val fromProperty = System.getProperty("os.version").orEmpty()
        if (fromProperty.isNotBlank()) {
            return fromProperty.substringBefore("-g") .ifBlank { fromProperty }
        }
        return runCatching {
            java.io.File("/proc/version").readText().split(" ").getOrNull(2).orEmpty()
        }.getOrDefault("未知").ifBlank { "未知" }
    }

    private fun architecture(): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "未知"
        val pageKb = runCatching {
            android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE) / 1024
        }.getOrDefault(0)
        return if (pageKb > 0) "$abi（${pageKb}K）" else abi
    }

    // 查 org.lsposed.manager 会撞上 HyperOS 的「获取已安装应用信息」隐私弹窗，
    // 不值得为了一个版本号打扰用户，框架信息直接用模块自身的常量。
    private fun lsposed(): String = "LSPosed · libxposed API 102"


    fun now(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
}
