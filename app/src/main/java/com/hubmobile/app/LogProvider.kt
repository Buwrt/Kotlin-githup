package com.hubmobile.app

import android.net.Uri

/**
 * 把导出的错误日志交给系统分享面板。
 *
 * 日志文件在应用私有目录里，Android 7.0 起禁止把 file:// 直接抛给外部应用
 * （FileUriExposedException），而分享/发邮件这类动作必须让别的应用能读到这个文件。
 * 实现见 PrivateFileProvider。
 *
 * 为什么不用 DownloadProvider ——
 *   它的解析依赖 DownloadManager 的记录表，而这是我们自己生成的文件，
 *   没有对应记录，openFile 必然失败。不能复用。
 */
class LogProvider : PrivateFileProvider("exports", "text/plain") {

    companion object {
        /** 与 AndroidManifest 中 authorities 的后缀保持一致。 */
        const val AUTHORITY_SUFFIX = ".logs"
        const val DIR = "exports"

        @JvmStatic
        fun uriFor(authority: String, fileName: String): Uri =
            Uri.parse("content://$authority/$fileName")
    }
}
