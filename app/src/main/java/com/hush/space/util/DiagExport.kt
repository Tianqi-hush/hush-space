package com.hush.space.util

import android.content.Context
import android.os.Environment
import com.hush.space.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 2.6.54：一键导出诊断日志。
 *
 * <p>以前用户是自己去文件管理器里翻 diag.log，很容易拿到一份过期的
 * （Android/data 目录在部分 ROM 上会残留旧文件），结果是「日志和上次一模一样」。
 * 这里把两个位置的日志取最新的一份，复制到公共目录并带上版本和时间戳，
 * 用户只要发这个文件就不会再拿错。</p>
 */
object DiagExport {

    fun export(context: Context): String {
        return try {
            val ext = Environment.getExternalStorageDirectory()
            val outDir = File(ext, "Download/HushSpace/export")
            if (!outDir.isDirectory && !outDir.mkdirs()) {
                return "无法创建导出目录：${outDir.absolutePath}"
            }
            val stamp = SimpleDateFormat("MMdd-HHmmss", Locale.US).format(Date())
            val out = File(outDir, "diag-${BuildConfig.VERSION_NAME}-$stamp.log")

            val candidates = buildList {
                val dataDir = File(ext, "Android/data/" + context.packageName + "/files")
                // 2.6.55：diag.log 打不开时会退到 diag2.log / diag3.log，全部扫一遍
                dataDir.listFiles { f ->
                    f.isFile && f.name.startsWith("diag") && f.name.endsWith(".log")
                }?.let { addAll(it) }
                add(File(ext, "Download/HushSpace/diag.log"))
                File(context.filesDir, "diag").listFiles { f ->
                    f.isFile && f.name.endsWith(".log")
                }?.let { addAll(it) }
                add(File(context.filesDir, "diag.log"))
            }.filter { it.isFile && it.length() > 0 }

            if (candidates.isEmpty()) return "没有找到日志文件，请先打开一次本应用"

            val newest = candidates.maxByOrNull { it.lastModified() }!!
            out.bufferedWriter().use { w ->
                w.write("# hush " + BuildConfig.VERSION_NAME + " (" +
                        BuildConfig.VERSION_CODE + ") export " + stamp + "\n")
                w.write("# source=" + newest.absolutePath + " len=" + newest.length() + "\n")
                newest.bufferedReader().useLines { lines -> lines.forEach { w.write(it); w.write("\n") } }
            }
            out.absolutePath
        } catch (t: Throwable) {
            "导出失败：${t.message}"
        }
    }
}
