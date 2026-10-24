package com.hubmobile.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * 把私有目录里的单个文件交给外部应用读取的公共外壳。
 *
 * 由来 ——
 *   LogProvider 和 ApkProvider 原本是两份几乎一字不差的 Java 代码：
 *   都是「目录名 + MIME 不同，其余全一样」。迁到 Kotlin 时把它们合成
 *   这一个抽象基类，两个子类只剩几行常量。这是本次迁移顺手清掉的第一处重复。
 *
 * 为什么不用 FileProvider ——
 *   官方的 FileProvider 要引入 androidx.core，而这个项目一直坚持零第三方
 *   依赖（APK 才 1.1MB）。几十行 ContentProvider 就能达到同样效果。
 *
 * 暴露范围 ——
 *   只暴露 filesDir/<子目录>/ 这一个目录，且路径只取 URI 的最后一段
 *   （不含分隔符与 ..），因此不存在路径穿越风险。
 */
abstract class PrivateFileProvider(
    private val dirName: String,
    private val mimeType: String
) : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = context ?: throw FileNotFoundException("no context")
        val name = uri.lastPathSegment
        if (name.isNullOrEmpty() || name.contains("/") || name.contains("..")) {
            throw FileNotFoundException("bad name")
        }
        val f = File(File(ctx.filesDir, dirName), name)
        if (!f.exists()) throw FileNotFoundException(f.absolutePath)
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = mimeType

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int = 0
}
