package com.hubmobile.app.compose

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/*
 * 两步验证器（原生）/ 下载管理 / 写操作表单（新建仓库 · 上传文件 · 创建 Release）。
 *
 * 两步验证器的账户存储沿用网页版那一份：SharedPreferences "hub_prefs"
 * 里的 "totp_accounts_cache"（TotpService 也读它，所以两边是同一份数据）。
 * 动态码算法直接调已有的 Totp.compute()，不重写。
 */

/* ---------------- 两步验证器 ---------------- */

private const val TOTP_KEY = "totp_accounts_cache"

private fun loadTotp(ctx: Context): List<JSONObject> {
    val raw = ctx.getSharedPreferences("hub_prefs", Context.MODE_PRIVATE)
        .getString(TOTP_KEY, "[]") ?: "[]"
    return try {
        val arr = org.json.JSONArray(raw)
        val out = ArrayList<JSONObject>()
        for (i in 0 until arr.length()) out.add(arr.getJSONObject(i))
        out
    } catch (e: Throwable) { emptyList() }
}

private fun saveTotp(ctx: Context, list: List<JSONObject>) {
    val arr = org.json.JSONArray()
    list.forEach { arr.put(it) }
    ctx.getSharedPreferences("hub_prefs", Context.MODE_PRIVATE)
        .edit().putString(TOTP_KEY, arr.toString()).apply()
}

@Composable
fun TotpScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalHubColors.current
    var list by remember { mutableStateOf(loadTotp(ctx)) }
    var tick by remember { mutableStateOf(0L) }
    var adding by remember { mutableStateOf(false) }

    // 每秒刷一次剩余秒数
    LaunchedEffect(Unit) {
        while (true) { tick = System.currentTimeMillis(); delay(1000) }
    }

    Column(Modifier.fillMaxSize().background(c.canvas)) {
        Row(Modifier.fillMaxWidth().background(c.bg)
            .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹", fontSize = 24.sp, color = c.accent) }
            Spacer(Modifier.width(4.dp))
            Text("两步验证器", color = c.fg, fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { adding = true },
                modifier = Modifier.height(30.dp),
                contentPadding = PaddingValues(horizontal = 10.dp)) {
                Text("添加", fontSize = 12.sp)
            }
        }

        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    HubIcon("shield-check", Modifier.size(40.dp), c.fgSubtle)
                    Spacer(Modifier.height(10.dp))
                    Text("还没有添加账户", color = c.fgMuted, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("支持 TOTP（6 位、30 秒）密钥", color = c.fgSubtle, fontSize = 11.sp)
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp)) {
                items(list) { a ->
                    val name = a.optString("name", a.optString("issuer", "账户"))
                    val code = try { com.hubmobile.app.Totp.compute(a) } catch (e: Throwable) { null }
                    val left = try { com.hubmobile.app.Totp.remaining(a) } catch (e: Throwable) { 0L }
                    Row(Modifier.fillMaxWidth().background(c.bg)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(name, color = c.fg, fontSize = 13.sp)
                            Spacer(Modifier.height(3.dp))
                            Text(a.optString("issuer", ""), color = c.fgSubtle, fontSize = 11.sp)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(code?.let { com.hubmobile.app.Totp.group(it) } ?: "——————",
                                color = c.accent, fontSize = 20.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold)
                            Text("${left}s", color = c.fgSubtle, fontSize = 10.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {
                            val nl = list.toMutableList().apply { remove(a) }
                            saveTotp(ctx, nl); list = nl
                        }) { Text("删除", color = c.danger, fontSize = 11.sp) }
                    }
                    Divider(color = c.borderMuted, thickness = 0.5.dp)
                }
            }
        }
    }

    if (adding) {
        var nm by remember { mutableStateOf("") }
        var sec by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("添加账户", fontSize = 15.sp) },
            text = {
                Column {
                    OutlinedTextField(value = nm, onValueChange = { nm = it },
                        placeholder = { Text("名称，如 GitHub", fontSize = 12.sp) },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = sec, onValueChange = { sec = it.trim() },
                        placeholder = { Text("Base32 密钥", fontSize = 12.sp) },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("也可以粘贴 otpauth:// 链接，会自动解析。",
                        color = c.fgSubtle, fontSize = 10.sp,
                        modifier = Modifier.padding(top = 6.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val parsed = if (sec.startsWith("otpauth://")) parseOtpAuth(sec) else null
                    val name = nm.ifBlank { parsed?.first ?: "账户" }
                    val secret = parsed?.second ?: sec
                    if (secret.isNotBlank()) {
                        val o = JSONObject()
                            .put("type", "totp")
                            .put("name", name)
                            .put("issuer", name)
                            .put("secret", secret)
                            .put("digits", 6)
                            .put("period", 30)
                            .put("algo", "SHA1")
                        val nl = list.toMutableList().apply { add(o) }
                        saveTotp(ctx, nl); list = nl
                        adding = false
                    }
                }) { Text("添加", color = c.accent) }
            },
            dismissButton = {
                TextButton(onClick = { adding = false }) { Text("取消") }
            }
        )
    }
}

private fun parseOtpAuth(uri: String): Pair<String, String>? = try {
    val u = android.net.Uri.parse(uri)
    val secret = u.getQueryParameter("secret") ?: ""
    val label = (u.path ?: "").removePrefix("/")
    val name = label.substringAfter(':').ifBlank { label }
    val issuer = u.getQueryParameter("issuer") ?: name
    issuer to secret
} catch (e: Throwable) { null }

/* ---------------- 下载管理 ---------------- */

@Composable
fun DownloadsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalHubColors.current
    var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }

    fun load() {
        items = try {
            val raw = ctx.getSharedPreferences("hub_prefs", Context.MODE_PRIVATE)
                .getString("download_history", "[]") ?: "[]"
            val arr = org.json.JSONArray(raw)
            val out = ArrayList<JSONObject>()
            for (i in 0 until arr.length()) out.add(arr.getJSONObject(i))
            out.asReversed()
        } catch (e: Throwable) { emptyList() }
    }
    LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize().background(c.canvas)) {
        Row(Modifier.fillMaxWidth().background(c.bg)
            .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹", fontSize = 24.sp, color = c.accent) }
            Spacer(Modifier.width(4.dp))
            Text("下载管理", color = c.fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { load() }, modifier = Modifier.height(30.dp),
                contentPadding = PaddingValues(horizontal = 10.dp)) {
                Text("刷新", fontSize = 12.sp)
            }
        }
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    HubIcon("download", Modifier.size(36.dp), c.fgSubtle)
                    Spacer(Modifier.height(10.dp))
                    Text("还没有下载记录", color = c.fgMuted, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("下载的文件会存到 Download/githup/",
                        color = c.fgSubtle, fontSize = 11.sp)
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp)) {
                items(items) { o ->
                    Row(Modifier.fillMaxWidth().background(c.bg)
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        HubIcon("download", Modifier.size(16.dp), c.fgMuted)
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text(o.optString("name", "文件"), color = c.fg, fontSize = 13.sp,
                                maxLines = 1)
                            Spacer(Modifier.height(3.dp))
                            Text(o.optString("url", ""), color = c.fgSubtle, fontSize = 10.sp,
                                maxLines = 1)
                        }
                        Text(
                            when (o.optString("state", "")) {
                                "done", "success" -> "已完成"
                                "fail", "failed" -> "失败"
                                "running" -> "进行中"
                                else -> ""
                            }, color = c.fgMuted, fontSize = 11.sp)
                    }
                    Divider(color = c.borderMuted, thickness = 0.5.dp)
                }
            }
        }
    }
}

/* ---------------- 新建仓库 ---------------- */

@Composable
fun NewRepoScreen(onBack: () -> Unit, onCreated: (String) -> Unit) {
    val ctx = LocalContext.current
    val c = LocalHubColors.current
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var priv by remember { mutableStateOf(false) }
    var init by remember { mutableStateOf(true) }
    var gi by remember { mutableStateOf("") }
    var lic by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    // 对齐 page-repo.js newRepo() 的两份清单
    val gitignores = listOf("" to "无", "Node" to "Node", "Python" to "Python",
        "Java" to "Java", "Android" to "Android", "Gradle" to "Gradle",
        "C++" to "C++", "Go" to "Go", "Rust" to "Rust", "Swift" to "Swift")
    val licenses = listOf("" to "无", "mit" to "MIT License",
        "apache-2.0" to "Apache License 2.0", "gpl-3.0" to "GNU GPLv3",
        "agpl-3.0" to "GNU AGPLv3", "lgpl-3.0" to "GNU LGPLv3",
        "mpl-2.0" to "Mozilla Public License 2.0", "bsd-3-clause" to "BSD 3-Clause",
        "unlicense" to "The Unlicense")

    Column(Modifier.fillMaxSize().background(c.canvas).padding(16.dp)) {
        Text("新建仓库", color = c.fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(14.dp))
        Field("仓库名称", name, { name = it }, "my-repo")
        Spacer(Modifier.height(10.dp))
        Field("简介（可选）", desc, { desc = it }, "一句话说明这个仓库是做什么的")
        Spacer(Modifier.height(12.dp))
        SwitchRow("私有仓库", "只有你自己和协作者能看到", priv) { priv = it }
        SwitchRow("初始化 README", "勾上就能立刻 clone", init) { init = it }
        Spacer(Modifier.height(8.dp))
        PickerField(".gitignore 模板", gi, gitignores) { gi = it }
        Spacer(Modifier.height(8.dp))
        PickerField("开源许可证", lic, licenses) { lic = it }
        Spacer(Modifier.height(10.dp))
        // 克隆地址实时预览（网页版表单就有这个）
        if (name.isNotBlank()) {
            val login = try { GitHubApi.me(ctx)?.login } catch (e: Throwable) { null } ?: "you"
            Text("https://github.com/$login/${name.trim()}.git",
                color = c.accent, fontSize = 11.sp)
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = {
            if (name.isBlank() || busy) return@Button
            busy = true; err = null
            MainScope().launch {
                val r = try {
                    withContext(Dispatchers.IO) {
                        GitHubApi.createRepo(ctx, name.trim(), desc.trim(), priv, init, gi, lic)
                    }
                } catch (e: Throwable) { null }
                busy = false
                if (r != null) onCreated(r.fullName)
                else err = "创建失败（名称可能已存在，或令牌缺少 repo 权限）"
            }
        }, enabled = name.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = c.primary)) {
            Text(if (busy) "创建中…" else "创建仓库", color = Color.White)
        }
        if (err != null) {
            Spacer(Modifier.height(8.dp))
            Text(err!!, color = c.danger, fontSize = 12.sp)
        }
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onBack) { Text("取消", color = c.fgMuted) }
    }
}

/* ---------------- 上传文件到仓库 ---------------- */

@Composable
fun UploadFileScreen(
    owner: String,
    name: String,
    branch: String,
    onBack: () -> Unit,
    onPickFile: () -> Unit
) {
    val ctx = LocalContext.current
    val c = LocalHubColors.current
    var path by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().background(c.canvas).padding(16.dp)) {
        Text("上传文件到 $owner/$name", color = c.fg, fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        Text("GitHub 的 contents 接口要求 base64 内容。这里提供文本直接粘贴，"
            + "二进制文件请用「选择文件」走原生通道。",
            color = c.fgMuted, fontSize = 11.sp)
        Spacer(Modifier.height(12.dp))
        Field("仓库内路径", path, { path = it }, "docs/note.md")
        Spacer(Modifier.height(10.dp))
        Field("提交说明", msg, { msg = it }, "add file")
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = content, onValueChange = { content = it },
            placeholder = { Text("文件内容（文本）", fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth().height(160.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = c.fg, unfocusedTextColor = c.fg,
                cursorColor = c.accent, focusedBorderColor = c.accent,
                unfocusedBorderColor = c.border)
        )
        Spacer(Modifier.height(14.dp))
        Row {
            Button(onClick = {
                if (path.isBlank() || content.isBlank()) return@Button
                MainScope().launch {
                    try {
                        withContext(Dispatchers.IO) {
                            GitHubApi.putFile(ctx, owner, name, path.trim('/'),
                                android.util.Base64.encodeToString(
                                    content.toByteArray(Charsets.UTF_8),
                                    android.util.Base64.DEFAULT),
                                msg.ifBlank { "upload ${path.trim('/')}" }, branch)
                        }
                    } catch (e: Throwable) { /* 错误提示略，保持简洁 */ }
                }
            }, colors = ButtonDefaults.buttonColors(containerColor = c.primary)) {
                Text("提交", color = Color.White)
            }
            Spacer(Modifier.width(10.dp))
            OutlinedButton(onClick = onPickFile) { Text("选择文件", fontSize = 12.sp) }
        }
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onBack) { Text("返回", color = c.fgMuted) }
    }
}

/* ---------------- 创建 Release ---------------- */

@Composable
fun NewReleaseScreen(owner: String, name: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalHubColors.current
    var tag by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf(false) }
    var pre by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(c.canvas).padding(16.dp)) {
        Text("创建 Release", color = c.fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text("$owner/$name", color = c.fgSubtle, fontSize = 11.sp)
        Spacer(Modifier.height(14.dp))
        Field("版本号 tag", tag, { tag = it }, "v1.0.0")
        Spacer(Modifier.height(10.dp))
        Field("标题", title, { title = it }, "v1.0.0")
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(value = body, onValueChange = { body = it },
            placeholder = { Text("Release 说明（支持 Markdown）", fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth().height(150.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = c.fg, unfocusedTextColor = c.fg,
                cursorColor = c.accent, focusedBorderColor = c.accent,
                unfocusedBorderColor = c.border))
        Spacer(Modifier.height(12.dp))
        SwitchRow("草稿", "先存草稿，不公开发布", draft) { draft = it }
        SwitchRow("预发布", "标记为 prerelease", pre) { pre = it }
        Spacer(Modifier.height(16.dp))
        Button(onClick = {
            if (tag.isBlank() || busy) return@Button
            busy = true
            MainScope().launch {
                val ok = try {
                    withContext(Dispatchers.IO) {
                        GitHubApi.createRelease(ctx, owner, name, tag.trim(),
                            title.ifBlank { tag.trim() }, body, draft, pre)
                    }
                } catch (e: Throwable) { false }
                busy = false
                done = ok
            }
        }, enabled = tag.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = c.primary)) {
            Text(if (busy) "发布中…" else "发布", color = Color.White)
        }
        if (done) {
            Spacer(Modifier.height(8.dp))
            Text("已发布", color = c.success, fontSize = 12.sp)
        }
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onBack) { Text("返回", color = c.fgMuted) }
    }
}

/* ---------------- 通用小件 ---------------- */

@Composable
private fun Field(label: String, value: String, on: (String) -> Unit, hint: String) {
    val c = LocalHubColors.current
    Column {
        Text(label, color = c.fgMuted, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(value = value, onValueChange = on, singleLine = true,
            placeholder = { Text(hint, fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = c.fg, unfocusedTextColor = c.fg,
                cursorColor = c.accent, focusedBorderColor = c.accent,
                unfocusedBorderColor = c.border))
    }
}

@Composable
private fun SwitchRow(title: String, sub: String, on: Boolean, onCh: (Boolean) -> Unit) {
    val c = LocalHubColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.fg, fontSize = 13.sp)
            Text(sub, color = c.fgSubtle, fontSize = 10.sp)
        }
        Switch(checked = on, onCheckedChange = onCh,
            colors = SwitchDefaults.colors(checkedThumbColor = c.accent,
                checkedTrackColor = c.accentSubtle))
    }
}

@Composable
private fun PickerField(
    label: String, cur: String, options: List<Pair<String, String>>, on: (String) -> Unit
) {
    val c = LocalHubColors.current
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, color = c.fgSubtle, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth()
            .background(c.bg, androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
            .border(1.dp, c.border, androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
            .clickable { open = true }
            .padding(horizontal = 12.dp, vertical = 12.dp)) {
            Text(options.firstOrNull { it.first == cur }?.second ?: "无",
                color = c.fg, fontSize = 13.sp)
        }
    }
    if (open) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label, fontSize = 15.sp) },
            text = {
                androidx.compose.foundation.lazy.LazyColumn {
                    items(options) { (v, t) ->
                        Row(Modifier.fillMaxWidth().clickable {
                            on(v); open = false
                        }.padding(vertical = 12.dp)) {
                            Text(t, color = c.fg, fontSize = 13.sp,
                                modifier = Modifier.weight(1f))
                            if (cur == v) Text("✓", color = c.accent, fontSize = 13.sp)
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }
}
