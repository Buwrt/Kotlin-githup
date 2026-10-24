package com.hubmobile.app

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.SequenceInputStream

/**
 * 文件选择、目录展开、读取与上传。
 *
 * 表单里的「选择文件 / 选择文件夹」、release 附件上传、仓库文件上传，
 * 都走这里 —— 前端拿到的是带读权限的 content:// URI，真正的字节始终
 * 由原生侧读，避免大文件在 JS 里兜一圈把内存撑爆。
 *
 * 【从 JsBridge 拆出】逻辑一字未改，只换了语言。
 */
internal class FileBridge(private val ctx: BridgeContext) {

    /** 当前挂起的文件选择回调 id（一次只允许一个）。 */
    private var pendingPickId: String? = null
    /** 权限回调未回来前暂存的 accept 参数。 */
    private var pendingPickAccept: String? = null

    /*
     * 打开选择器；结果通过 window.Native._pick(id, json) 回调。
     *
     * accept 决定走哪种选择器：
     *   - 纯图片 / 纯视频 / 图片+视频  ->  系统「相册」（见 launchGallery）
     *   - 其它（APK、压缩包、任意文件）->  系统文件浏览器（见 launchFileBrowser）
     *
     * 为什么媒体要单独走相册：
     *   用户要的是「在相册里挑照片」，而不是在一个列着 DCIM、Download、
     *   Android 这些目录名的文件管理器里翻。ACTION_OPEN_DOCUMENT 虽然稳，
     *   但它是文件浏览器语义，对普通用户太绕。
     *
     * 为什么之前会看到「此应用只能访问您选择的照片 / 无照片或视频」：
     *   那是 Android 13+ 的系统「照片选择器」。它按【授权范围】显示内容 ——
     *   应用一个媒体权限都没被授予时，它就只剩那句提示。
     *   现在选之前先申请权限（READ_MEDIA_IMAGES / READ_MEDIA_VIDEO，
     *   旧系统是 READ_EXTERNAL_STORAGE）。
     */
    @JavascriptInterface
    fun pickFile(id: String?, accept: String?) {
        pendingPickId = id
        val raw = if (accept.isNullOrEmpty()) "*/*" else accept.trim()
        ctx.activity.runOnUiThread {
            if (isMediaAccept(raw)) {
                // 相册路径：先要权限，拿到（或拿不到）都继续开相册
                if (!ensureMediaPermission(raw)) {
                    pendingPickAccept = raw   // 等权限回调，见 onMediaPermissionResult()
                    return@runOnUiThread
                }
                launchGallery(raw)
            } else {
                launchFileBrowser(raw)
            }
        }
    }

    /** 这个 accept 是不是「只要图片 / 视频」—— 是的话走相册，不走文件浏览器。 */
    private fun isMediaAccept(accept: String?): Boolean {
        val a = accept?.trim() ?: return false
        if (a.isEmpty() || "*/*" == a) return false   // 任意文件 -> 文件浏览器
        val types = splitTypes(a)
        for (t in types) {
            // 只要出现非图片非视频的类型，就说明要的是「文件」，不能只给相册
            if (!t.startsWith("image/") && !t.startsWith("video/")) return false
        }
        return types.isNotEmpty()
    }

    /**
     * 打开系统相册（图片 / 视频）。
     *
     * 三级降级，按系统版本挑最新的可用方式：
     *   1. Android 13+：ACTION_PICK_IMAGES —— 系统照片选择器，就是相册那个界面。
     *   2. Android 7~12：ACTION_PICK + MediaStore 的 images/video 集合。
     *   3. 都没有（极少见）：退回文件浏览器，至少能用。
     */
    private fun launchGallery(accept: String) {
        val types = splitTypes(accept)
        var wantImage = false
        var wantVideo = false
        for (t in types) {
            if (t.startsWith("image/")) wantImage = true
            else if (t.startsWith("video/")) wantVideo = true
            else {
                wantImage = true
                wantVideo = true
            }
        }

        // 1) Android 13+ 的系统照片选择器（就是「相册」那个界面）
        //
        //    图片和视频【可以同时选】：不限定 MIME 类型时选择器会同时显示
        //    照片和视频；只有 setType("image/*") 才只给图片。
        //    上一版这里搞错了 —— 「两者都要」时主动跳过系统相册、退到
        //    ACTION_PICK + MediaStore.Files，而部分定制系统把它渲染成
        //    文件浏览器，于是用户要「去文件夹里翻视频」。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val i = Intent("android.provider.action.PICK_IMAGES")
                if (wantImage && !wantVideo) {
                    i.setType("image/*")     // 只要图片
                } else if (wantVideo && !wantImage) {
                    i.setType("video/*")     // 只要视频
                }
                // 两者都要：不 setType —— 相册同时显示照片和视频
                try {
                    i.putExtra("android.provider.extra.PICK_IMAGES_MAX", 100)
                } catch (ignored: Exception) {
                }
                ctx.activity.startActivityForResult(i, FilePick.REQ_PICK)
                return
            } catch (ignored: Exception) {
                // 落到下一级
            }
        }

        // 2) Android 7~12：ACTION_PICK + MediaStore（也是相册界面）
        try {
            val i = Intent(Intent.ACTION_PICK)
            if (wantImage && !wantVideo) {
                i.setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
            } else if (wantVideo && !wantImage) {
                i.setDataAndType(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video/*")
            } else {
                // 图片 + 视频：让用户在相册里挑，EXTRA_MIME_TYPES 声明两种类型
                i.setDataAndType(MediaStore.Files.getContentUri("external"), "*/*")
                i.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
            }
            try {
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            } catch (ignored: Exception) {
            }
            try {
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (ignored: Exception) {
            }
            ctx.activity.startActivityForResult(i, FilePick.REQ_PICK)
            return
        } catch (ignored: Exception) {
            // 落到下一级
        }

        // 3) 兜底：文件浏览器
        launchFileBrowser(accept)
    }

    /** 非媒体文件（APK / 压缩包 / 任意文件）走系统文件浏览器。 */
    private fun launchFileBrowser(accept: String) {
        try {
            val types = splitTypes(accept)
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            if (types.size == 1) {
                i.setType(types[0])
            } else {
                i.setType("*/*")   // 通配 + EXTRA_MIME_TYPES：setType 只认一个类型串
                i.putExtra(Intent.EXTRA_MIME_TYPES, types)
            }
            try {
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            } catch (ignored: Exception) {
            }
            try {
                i.addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )
            } catch (ignored: Exception) {
            }
            ctx.activity.startActivityForResult(i, FilePick.REQ_PICK)
        } catch (ignored: Exception) {
            // 个别精简系统没有 DocumentsUI：退回老方式，至少给个选择器
            try {
                val i = Intent(Intent.ACTION_GET_CONTENT)
                i.addCategory(Intent.CATEGORY_OPENABLE)
                val types = splitTypes(accept)
                if (types.size == 1) i.setType(types[0])
                else {
                    i.setType("*/*")
                    i.putExtra(Intent.EXTRA_MIME_TYPES, types)
                }
                ctx.activity.startActivityForResult(
                    Intent.createChooser(i, "选择文件"), FilePick.REQ_PICK
                )
            } catch (ignored2: Exception) {
                failPick(pendingPickId, "无法打开文件选择器")
            }
        }
    }

    /**
     * 按 accept 判断需要哪种媒体权限；不需要或已经有了就返回 true。
     * 需要但还没有：发起运行时申请并返回 false。
     */
    private fun ensureMediaPermission(accept: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val need = ArrayList<String>()
        val wantsImage = accept.contains("image") || accept == "*/*"
        val wantsVideo = accept.contains("video") || accept == "*/*"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+：分区媒体权限，只能按类型申请。
            // 申请前先看「已授予」——
            //   Android 14 上 READ_MEDIA_IMAGES 未授予，但
            //   READ_MEDIA_VISUAL_USER_SELECTED 已授予（用户选了「仅选中的照片」）时，
            //   相册是有内容的，不该再弹一次权限框骚扰用户。
            val imgOk = ctx.activity.checkSelfPermission(
                android.Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED
            val vidOk = ctx.activity.checkSelfPermission(
                android.Manifest.permission.READ_MEDIA_VIDEO
            ) == PackageManager.PERMISSION_GRANTED
            var partialOk = false
            if (Build.VERSION.SDK_INT >= 34) {
                try {
                    partialOk = ctx.activity.checkSelfPermission(
                        android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                    ) == PackageManager.PERMISSION_GRANTED
                } catch (ignored: Throwable) {
                }
            }
            if (wantsImage && !imgOk && !partialOk) {
                need.add(android.Manifest.permission.READ_MEDIA_IMAGES)
            }
            if (wantsVideo && !vidOk && !partialOk) {
                need.add(android.Manifest.permission.READ_MEDIA_VIDEO)
            }
            if (need.isEmpty()) return true
            // 已拿到「部分照片」权限时不再申请，直接开相册
            if (partialOk && (wantsImage || wantsVideo)) return true
        } else {
            if ((wantsImage || wantsVideo)
                && ctx.activity.checkSelfPermission(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                need.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        if (need.isEmpty()) return true
        return try {
            ctx.activity.requestPermissions(need.toTypedArray(), JsBridge.REQ_MEDIA_PERM)
            false
        } catch (ignored: Exception) {
            true   // 申请失败也别把选择器堵死
        }
    }

    /** 权限申请结果由 MainActivity 转发过来。无论如何都把选择器打开。 */
    fun onMediaPermissionResult() {
        val accept = pendingPickAccept
        pendingPickAccept = null
        if (pendingPickId == null) return
        launchGallery(if (accept.isNullOrEmpty()) "*/*" else accept)
    }

    /* 把 accept 拆成 MIME 数组：多个类型按逗号切开，
     * 空或通配按单元素处理。（这里不写行内举例，是因为 Kotlin 的块注释可以嵌套，
     * 注释里再出现斜杠加星号的字面量会把注释提前关掉。） */
    private fun splitTypes(accept: String?): Array<String> {
        if (accept.isNullOrEmpty() || "*/*" == accept.trim()) return arrayOf("*/*")
        val parts = accept.split(",")
        val out = ArrayList<String>()
        for (p in parts) {
            val t = p.trim()
            if (t.isNotEmpty()) out.add(t)
        }
        if (out.isEmpty()) out.add("*/*")
        return out.toTypedArray()
    }

    private fun failPick(id: String?, msg: String) {
        ctx.runJs(
            "window.Native._pick(" + JSONObject.quote(id.toString()) + ",null,"
                    + JSONObject.quote(msg) + ")"
        )
    }

    /** MainActivity 在 onActivityResult 中转发结果。 */
    fun onPickResult(resultCode: Int, data: Intent?) {
        val id = pendingPickId
        pendingPickId = null
        if (id == null) return
        if (resultCode != Activity.RESULT_OK || data == null) {
            ctx.runJs("window.Native._pick(" + JSONObject.quote(id) + ",null,\"\")")
            return
        }

        // 多选：ClipData 里是一个列表；单选仍在 data.getData()。
        val uris = ArrayList<Uri>()
        try {
            val cd = data.clipData
            if (cd != null) {
                for (i in 0 until cd.itemCount) {
                    val u = cd.getItemAt(i).uri
                    if (u != null) uris.add(u)
                }
            } else {
                val single = data.data
                if (single != null) uris.add(single)
            }
        } catch (ignored: Exception) {
        }

        if (uris.isEmpty()) {
            // 用户什么都没选（点返回 / 关掉选择器）
            ctx.runJs("window.Native._pick(" + JSONObject.quote(id) + ",null,\"\")")
            return
        }

        try {
            val arr = JSONArray()
            for (uri in uris) {
                /*
                  持久化读权限 —— 但**只能对支持持久化的来源调**。

                  系统文件浏览器（ACTION_OPEN_DOCUMENT）返回的 URI 支持持久化；
                  而系统相册（ACTION_PICK / Android 13+ 的照片选择器）返回的
                  **明确不支持** —— 对它调 takePersistableUriPermission 必抛
                  SecurityException。

                  以前这行裸奔在循环里，相册选完一张图，这里一抛就把整个
                  try 块带崩，用户看到的是「读取文件信息失败」—— 上传功能
                  在相册路径上全军覆没。现在逐条尝试、失败只影响它自己。
                */
                try {
                    val flags = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
                    ctx.activity.contentResolver.takePersistableUriPermission(uri, flags)
                } catch (ignored: Exception) {
                    // 不支持持久化（相册等来源）—— 临时权限足够本次上传
                }
                val meta = FilePick.query(ctx.activity, uri)
                val jo = JSONObject()
                jo.put("name", meta.name)
                jo.put("size", meta.size)
                jo.put("mime", meta.mime)
                jo.put("uri", uri.toString())
                arr.put(jo)
            }
            // 单个也包成数组，前端统一按数组处理（_pick 里再摊平回单对象）
            ctx.runJs("window.Native._pick(" + JSONObject.quote(id) + "," + arr + ",\"\")")
        } catch (ignored: Exception) {
            failPick(id, "读取文件信息失败")
        }
    }

    fun onPickCancel() {
        val id = pendingPickId
        pendingPickId = null
        if (id != null) {
            ctx.runJs("window.Native._pick(" + JSONObject.quote(id) + ",null,\"\")")
        }
    }

    /**
     * 选择一个**文件夹**（对齐官网「拖整个文件夹上传」的能力）。
     *
     * 走 ACTION_OPEN_DOCUMENT_TREE：系统文件浏览器里选中目录本身，
     * 返回的是目录的 tree:// 授权，再由 listFolder 展开成文件清单。
     */
    @JavascriptInterface
    fun pickFolder(id: String?) {
        pendingPickId = id
        ctx.activity.runOnUiThread {
            try {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                i.addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                            or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                )
                ctx.activity.startActivityForResult(i, FilePick.REQ_PICK_FOLDER)
            } catch (ignored: Exception) {
                failPick(pendingPickId, "无法打开文件夹选择器")
            }
        }
    }

    /** MainActivity 在 onActivityResult 中转发文件夹选择结果。 */
    fun onPickFolderResult(data: Intent?) {
        val id = pendingPickId
        pendingPickId = null
        if (id == null) return
        try {
            val tree = data?.data
            if (tree == null) {
                ctx.runJs("window.Native._pick(" + JSONObject.quote(id) + ",null,\"\")")
                return
            }
            /* tree URI 支持持久化授权 —— take 成功后，之后 listFolder /
               逐个上传时（哪怕跨过一次 Activity 重建）读取权限都还在。 */
            try {
                ctx.activity.contentResolver.takePersistableUriPermission(
                    tree, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (ignored: Exception) {
            }

            // 目录名：treeDocId 形如 "primary:Download/githup"，取最后一段
            val docId = DocumentsContract.getTreeDocumentId(tree)
            var name = docId.substring(docId.lastIndexOf('/') + 1)
            if (name.isEmpty()) name = "folder"

            val jo = JSONObject()
            jo.put("folder", true)
            jo.put("name", name)
            jo.put("uri", tree.toString())
            ctx.runJs("window.Native._pick(" + JSONObject.quote(id) + "," + jo + ",\"\")")
        } catch (ignored: Exception) {
            failPick(id, "读取文件夹信息失败")
        }
    }

    /**
     * 展开一个已授权的目录树：递归列出里面所有文件（含子目录），
     * 通过 window.Native._list(id, files, err) 回传。
     */
    @JavascriptInterface
    fun listFolder(id: String?, treeUriStr: String?) {
        ctx.background {
            try {
                val tree = Uri.parse(treeUriStr)
                val items = FilePick.listTree(ctx.activity, tree, FilePick.MAX_TREE_FILES)
                val arr = JSONArray()
                for (it in items) {
                    val jo = JSONObject()
                    jo.put("name", it.name)
                    jo.put("path", it.path)
                    jo.put("size", it.size)
                    jo.put("mime", it.mime)
                    jo.put("uri", it.uri)
                    arr.put(jo)
                }
                ctx.runJs(
                    "window.Native._list(" + JSONObject.quote(id.toString()) + ","
                            + arr + ",\"\")"
                )
            } catch (t: Throwable) {
                val msg = t.message ?: t.javaClass.simpleName
                ctx.runJs(
                    "window.Native._list(" + JSONObject.quote(id.toString())
                            + ",null," + JSONObject.quote(msg) + ")"
                )
            }
        }
    }

    /*
     * 读取选中文件 Base64 的硬上限 —— 这不是「建议」，是保命线。
     *
     * Base64 结果会拼进一行 JS 用 evaluateJavascript 灌给 WebView：
     * 一个 25MB 的文件就是 33MB 的字符串，中转链路（quote 转义副本、
     * 跨进程传给渲染进程、V8 解析）每一步都在堆内存的悬崖上 ——
     * 中低端机「一上传就闪退」的真身就是它。
     *
     * 大文件一律用 uploadMultipartB64：文件在原生侧边读边编、直接进网络，
     * 内存占用恒定几 KB。
     */
    private val readB64Max = 12L * 1024 * 1024

    @JavascriptInterface
    fun readFileBase64(id: String?, uriStr: String?, maxBytes: Long) {
        ctx.background {
            try {
                val uri = Uri.parse(uriStr)
                /* 双上限：调用方给的小上限优先，但本方法自己的保命线
                   永远生效 —— 谁也拦不住前端哪天传个 100 进来 */
                val cap = if (maxBytes > 0 && maxBytes < readB64Max) maxBytes else readB64Max
                val meta = FilePick.query(ctx.activity, uri)
                if (meta.size > cap) {
                    ctx.runJs(
                        "window.Native._read(" + JSONObject.quote(id.toString())
                                + ",null," + JSONObject.quote(
                            "文件过大（" + FilePick.human(meta.size)
                                    + "），超过 " + FilePick.human(cap) + " 限制"
                        ) + ")"
                    )
                    return@background
                }
                val bytes = FilePick.readAll(ctx.activity, uri, cap)
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                ctx.runJs(
                    "window.Native._read(" + JSONObject.quote(id.toString()) + ","
                            + JSONObject.quote(b64) + ",\"\")"
                )
            } catch (t: Throwable) {
                val msg = t.message ?: t.javaClass.simpleName
                ctx.runJs(
                    "window.Native._read(" + JSONObject.quote(id.toString()) + ",null,"
                            + JSONObject.quote(msg) + ")"
                )
            }
        }
    }

    /**
     * 二进制上传到指定 URL（用于 Release 附件）。
     *
     * ⚠️ 走流式 —— 以前是「把整个文件 readAll 成 byte[] → 一次性 POST」，
     * 附件上限 200MB 意味着最大的合法文件要在内存里完整躺一份，
     * ByteArrayOutputStream 扩容峰值还是文件大小的两倍多，低端机直接 OOM。
     */
    @JavascriptInterface
    fun uploadBinary(id: String?, url: String?, uriStr: String?, headersJson: String?) {
        ctx.background {
            var input: InputStream? = null
            try {
                val headers = parseHeaders(headersJson)
                val uri = Uri.parse(uriStr)
                var ctype = headers.remove("Content-Type")
                if (ctype.isNullOrEmpty()) ctype = "application/octet-stream"

                val size = FilePick.sizeOf(ctx.activity, uri)
                if (size > FilePick.MAX_ASSET_BYTES) {
                    throw Exception(
                        "文件过大（" + FilePick.human(size)
                                + "），附件不能超过 " + FilePick.human(FilePick.MAX_ASSET_BYTES)
                    )
                }

                input = FilePick.open(ctx.activity, uri)
                val r = Http.requestMultipart(url ?: "", input, size, ctype, headers)
                emitCb(id, r.code, r.body, r.headers)
            } catch (t: Throwable) {
                emitError(id, t)
            } finally {
                closeQuietly(input)
            }
        }
    }

    /**
     * multipart/form-data 上传：用于往 GitHub 传图片 / 视频附件。（流式，见上）
     *
     * @param head 文件之前的内容（含末尾空行）
     * @param tail 文件之后的内容（含结束边界）
     */
    @JavascriptInterface
    fun uploadMultipart(
        id: String?, url: String?, uriStr: String?, headersJson: String?,
        head: String?, tail: String?
    ) {
        ctx.background {
            var input: InputStream? = null
            try {
                val headers = parseHeaders(headersJson)
                val uri = Uri.parse(uriStr)
                val ctype = headers.remove("Content-Type")   // 由请求头单独带，别重复

                val headBytes = head?.toByteArray(charset("UTF-8")) ?: ByteArray(0)
                val tailBytes = tail?.toByteArray(charset("UTF-8")) ?: ByteArray(0)
                val fileLen = FilePick.sizeOf(ctx.activity, uri)
                val total = headBytes.size + fileLen + tailBytes.size

                // 头 + 文件 + 尾拼成一个流，交给底层流式发送
                input = SequenceInputStream(
                    ByteArrayInputStream(headBytes),
                    SequenceInputStream(
                        FilePick.open(ctx.activity, uri),
                        ByteArrayInputStream(tailBytes)
                    )
                )
                val r = Http.requestMultipart(url ?: "", input, total, ctype, headers)
                emitCb(id, r.code, r.body, r.headers)
            } catch (t: Throwable) {
                emitError(id, t)
            } finally {
                closeQuietly(input)
            }
        }
    }

    /**
     * 「JSON 里嵌 Base64 文件」的流式上传 —— 给仓库文件上传（Contents API）用。
     *
     * 文件内容**不再回前端**：原生把 head → 文件流（边读边 Base64）→ tail
     * 三段拼成一个流直接发出去。内存占用恒定几 KB，与文件大小无关。
     */
    @JavascriptInterface
    fun uploadMultipartB64(
        id: String?, url: String?, uriStr: String?, headersJson: String?,
        head: String?, tail: String?
    ) {
        ctx.background {
            var input: InputStream? = null
            try {
                val headers = parseHeaders(headersJson)
                val uri = Uri.parse(uriStr)
                var ctype = headers.remove("Content-Type")
                if (ctype.isNullOrEmpty()) ctype = "application/json"

                val headBytes = head?.toByteArray(charset("UTF-8")) ?: ByteArray(0)
                val tailBytes = tail?.toByteArray(charset("UTF-8")) ?: ByteArray(0)
                val fileLen = FilePick.sizeOf(ctx.activity, uri)
                if (fileLen > FilePick.MAX_CONTENTS_BYTES) {
                    throw Exception(
                        "文件过大（" + FilePick.human(fileLen)
                                + "），仓库文件不能超过 " +
                                FilePick.human(FilePick.MAX_CONTENTS_BYTES)
                    )
                }
                val total = headBytes.size + FilePick.base64Length(fileLen) + tailBytes.size

                // Contents API 只认 PUT —— 别用默认的 POST 重载
                input = SequenceInputStream(
                    ByteArrayInputStream(headBytes),
                    SequenceInputStream(
                        FilePick.base64Encoding(FilePick.open(ctx.activity, uri)),
                        ByteArrayInputStream(tailBytes)
                    )
                )
                val r = Http.requestMultipart("PUT", url ?: "", input, total, ctype, headers)
                emitCb(id, r.code, r.body, r.headers)
            } catch (t: Throwable) {
                emitError(id, t)
            } finally {
                closeQuietly(input)
            }
        }
    }

    /**
     * 裸体二进制上传：请求体就是文件本身，没有 multipart 包装。
     *
     * GitHub 的附件直传端点（uploads.github.com/user-attachments/assets）
     * 要的就是这个形态 —— Content-Type 是文件的真实 mime，body 是原始字节。
     */
    @JavascriptInterface
    fun uploadRaw(id: String?, url: String?, uriStr: String?, headersJson: String?) {
        ctx.background {
            var input: InputStream? = null
            try {
                val headers = parseHeaders(headersJson)
                val uri = Uri.parse(uriStr)
                var ctype = headers.remove("Content-Type")
                if (ctype.isNullOrEmpty()) ctype = "application/octet-stream"
                val total = FilePick.sizeOf(ctx.activity, uri)
                input = FilePick.open(ctx.activity, uri)

                val r = Http.requestMultipart(url ?: "", input, total, ctype, headers)
                emitCb(id, r.code, r.body, r.headers)
            } catch (t: Throwable) {
                emitError(id, t)
            } finally {
                closeQuietly(input)
            }
        }
    }

    /**
     * 在应用内把用户输入的字母数字变成签名密钥（PKCS12 的 Base64）。
     *
     * 用户不想为了签名去电脑上敲 keytool，所以钥匙由 App 自己生成。
     * 同一串输入会派生出同一把钥匙，因此换设备、换时间都能重建出
     * 完全相同的签名，打出来的包可以覆盖安装。
     * 计算 RSA 2048 需要几秒，所以走线程池，结果通过 window.Native._ks 回调。
     */
    @JavascriptInterface
    fun makeKeystore(
        id: String?, seed: String?, alias: String?, storePass: String?, keyPass: String?
    ) {
        ctx.background {
            try {
                val ks = KeyTool.makeKeystore(seed ?: "", alias ?: "", storePass ?: "", keyPass ?: "")
                val b64 = Base64.encodeToString(ks, Base64.NO_WRAP)
                ctx.runJs(
                    "window.Native._ks(" + JSONObject.quote(id.toString()) + ","
                            + JSONObject.quote(b64) + ",\"\")"
                )
            } catch (t: Throwable) {
                val msg = t.message ?: t.javaClass.simpleName
                ctx.runJs(
                    "window.Native._ks(" + JSONObject.quote(id.toString()) + ",null,"
                            + JSONObject.quote(msg) + ")"
                )
            }
        }
    }

    private fun parseHeaders(headersJson: String?): HashMap<String, String> {
        val headers = HashMap<String, String>()
        if (headersJson.isNullOrEmpty()) return headers
        val jo = JSONObject(headersJson)
        val it = jo.keys()
        while (it.hasNext()) {
            val k = it.next()
            headers[k] = jo.optString(k, "")
        }
        return headers
    }

    private fun emitCb(id: String?, code: Int, body: String?, headers: String?) {
        ctx.runJs(
            "window.Native._cb(" + JSONObject.quote(id.toString()) + ","
                    + code + "," + JSONObject.quote(body ?: "") + ","
                    + JSONObject.quote(headers ?: "{}") + ")"
        )
    }

    private fun emitError(id: String?, t: Throwable) {
        val msg = t.message ?: t.javaClass.simpleName
        ctx.runJs(
            "window.Native._cb(" + JSONObject.quote(id.toString()) + ",0,"
                    + JSONObject.quote("") + ","
                    + JSONObject.quote("{\"error\":" + JSONObject.quote(msg) + "}") + ")"
        )
    }

    private fun closeQuietly(c: InputStream?) {
        try {
            c?.close()
        } catch (ignored: Throwable) {
        }
    }
}
