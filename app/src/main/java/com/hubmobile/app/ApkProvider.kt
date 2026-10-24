package com.hubmobile.app

import android.net.Uri

/**
 * 给系统安装器提供 APK 的临时读权限。
 *
 * 从 Actions 构建产物（ZIP）里解压出来的 APK 放在应用私有目录里，
 * 系统安装器读不到，Android 7.0 起又禁止把 file:// 直接抛给外部应用。
 * 实现见 PrivateFileProvider。
 */
class ApkProvider : PrivateFileProvider("apks", "application/vnd.android.package-archive") {

    companion object {
        /** 与 AndroidManifest 中 authorities 的后缀保持一致。 */
        const val AUTHORITY_SUFFIX = ".apks"
        const val DIR = "apks"

        @JvmStatic
        fun uriFor(authority: String, fileName: String): Uri =
            Uri.parse("content://$authority/$fileName")
    }
}
