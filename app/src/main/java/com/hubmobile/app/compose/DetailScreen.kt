package com.hubmobile.app.compose

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Issue / PR 详情 + 评论（原生）。
 *
 * 这是 v4 里落差最大的一处：列表已经是原生的，点进去却跳浏览器。
 * 现在详情、评论、发评论、关闭/重开都在 App 内完成。
 */

@Composable
fun IssueDetailScreen(
    owner: String,
    name: String,
    number: Int,
    isPr: Boolean = false,
    onBack: () -> Unit,
    onOpenUser: ((String) -> Unit)? = null
) {
    val ctx = LocalContext.current
    val c = LocalHubColors.current
    var d by remember { mutableStateOf<GitHubApi.IssueDetail?>(null) }
    var avatar by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var cs by remember { mutableStateOf<List<GitHubApi.CommentInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    fun load() {
        loading = true; err = null
        MainScope().launch {
            try {
                d = withContext(Dispatchers.IO) { GitHubApi.issueDetail(ctx, owner, name, number) }
                avatar = withContext(Dispatchers.IO) {
                    GitHubApi.loadBitmap(ctx, d!!.userAvatar)
                }
                cs = withContext(Dispatchers.IO) { GitHubApi.comments(ctx, owner, name, number) }
                loading = false
            } catch (e: Throwable) {
                err = if (e is ApiError) {
                    if (e.code == 404) "不存在（可能没权限看私有仓库）" else "HTTP ${e.code}"
                } else e.message
                loading = false
            }
        }
    }

    LaunchedEffect(owner, name, number) { load() }

    Column(Modifier.fillMaxSize().background(c.canvas)) {
        when {
            loading -> { SkeletonList(3); CenteredLoading() }
            err != null -> CenteredText(err!!) { TextButton(onClick = { load() }) { Text("重试") } }
            else -> {
                val det = d!!
                LazyColumn(Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 8.dp)) {
                    // 标题区
                    item {
                        Column(Modifier.fillMaxWidth().background(c.bg).padding(14.dp)) {
                            Text(det.title, color = c.fg, fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(7.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.background(
                                        if (det.state == "open") c.success else c.done,
                                        RoundedCornerShape(12.dp))
                                        .padding(horizontal = 9.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        if (isPr) {
                                            if (det.state == "open") "开启中" else "已关闭"
                                        } else {
                                            if (det.state == "open") "开启" else "已关闭"
                                        },
                                        color = c.bg, fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium)
                                }
                                Spacer(Modifier.width(8.dp))
                                Text("#${det.number}", color = c.fgMuted, fontSize = 12.sp)
                                Spacer(Modifier.width(8.dp))
                                Text(det.user, color = c.fgMuted, fontSize = 12.sp,
                                    modifier = if (onOpenUser != null)
                                        Modifier.clickable { onOpenUser(det.user) } else Modifier)
                                Spacer(Modifier.width(8.dp))
                                Text(GitHubApi.timeAgo(det.createdAt), color = c.fgSubtle,
                                    fontSize = 12.sp)
                            }
                            if (det.labels.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Row {
                                    det.labels.forEach { l ->
                                        Box(Modifier.padding(end = 5.dp)
                                            .background(c.accentSubtle, RoundedCornerShape(10.dp))
                                            .padding(horizontal = 8.dp, vertical = 2.dp)) {
                                            Text(l, color = c.accent, fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                            if (det.body.isNotBlank()) {
                                Spacer(Modifier.height(12.dp))
                                Divider(color = c.borderMuted, thickness = 0.5.dp)
                                Spacer(Modifier.height(10.dp))
                                MarkdownText(det.body, onLink = null)
                            }
                        }
                    }

                    // 评论
                    item {
                        Text("${cs.size} 条评论", color = c.fgMuted, fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                    }
                    if (cs.isEmpty()) {
                        item { Box(Modifier.fillMaxWidth().padding(20.dp), Alignment.Center) {
                            Text("还没有评论", color = c.fgSubtle, fontSize = 12.sp)
                        } }
                    } else {
                        items(cs) { cm ->
                            Column(Modifier.fillMaxWidth().background(c.bg)
                                .padding(horizontal = 14.dp, vertical = 11.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(24.dp)
                                        .background(c.neutralSubtle,
                                            androidx.compose.foundation.shape.CircleShape))
                                    Spacer(Modifier.width(8.dp))
                                    Text(cm.user, color = c.fg, fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium)
                                    Spacer(Modifier.width(8.dp))
                                    Text(GitHubApi.timeAgo(cm.createdAt), color = c.fgSubtle,
                                        fontSize = 11.sp)
                                }
                                if (cm.body.isNotBlank()) {
                                    Spacer(Modifier.height(7.dp))
                                    MarkdownText(cm.body, onLink = null)
                                }
                            }
                            Divider(color = c.borderMuted, thickness = 0.5.dp)
                        }
                    }
                }

                // 底部：评论框 + 关闭/重开
                Column(Modifier.fillMaxWidth().background(c.bg)
                    .padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            placeholder = { Text("写下评论…", fontSize = 12.sp) },
                            modifier = Modifier.weight(1f).height(52.dp),
                            maxLines = 3,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = c.fg, unfocusedTextColor = c.fg,
                                cursorColor = c.accent, focusedBorderColor = c.accent,
                                unfocusedBorderColor = c.border
                            )
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (draft.isBlank() || sending) return@Button
                                sending = true
                                MainScope().launch {
                                    val ok = try {
                                        withContext(Dispatchers.IO) {
                                            GitHubApi.addComment(ctx, owner, name, number, draft)
                                        }
                                    } catch (e: Throwable) { false }
                                    if (ok) { draft = ""; note = "已发送"; load() }
                                    else note = "发送失败（需要令牌或该会话已锁定）"
                                    sending = false
                                }
                            },
                            enabled = draft.isNotBlank() && !sending,
                            colors = ButtonDefaults.buttonColors(containerColor = c.primary)
                        ) { Text(if (sending) "…" else "发送", color = androidx.compose.ui.graphics.Color.White,
                            fontSize = 12.sp) }
                    }
                    if (note != null) {
                        Text(note!!, color = c.fgMuted, fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            MainScope().launch {
                                val open = det.state != "open"
                                val ok = try {
                                    withContext(Dispatchers.IO) {
                                        GitHubApi.setIssueState(ctx, owner, name, number, open)
                                    }
                                } catch (e: Throwable) { false }
                                if (ok) load() else note = "操作失败（需要写权限）"
                            }
                        }) {
                            Text(if (det.state == "open") "关闭" else "重新开启", fontSize = 12.sp)
                        }
                        OutlinedButton(onClick = {
                            com.hubmobile.app.compose.openWebStatic(
                                ctx, "https://github.com/$owner/$name/" +
                                    (if (isPr) "pull" else "issues") + "/$number")
                        }) { Text("网页版", fontSize = 12.sp) }
                    }
                }
            }
        }
    }
}

/** 供非 composable 处调用（按钮回调里） */
internal fun openWebStatic(ctx: android.content.Context, url: String) {
    val i = android.content.Intent(ctx, com.hubmobile.app.WebViewActivity::class.java)
    i.putExtra(com.hubmobile.app.WebViewActivity.EXTRA_URL, url)
    i.putExtra(com.hubmobile.app.WebViewActivity.EXTRA_TITLE, "githup")
    i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    ctx.startActivity(i)
}
