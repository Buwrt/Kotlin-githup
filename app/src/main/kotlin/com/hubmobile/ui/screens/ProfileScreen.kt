package com.hubmobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hubmobile.ui.components.Avatar
import com.hubmobile.ui.components.GithupCard
import com.hubmobile.ui.components.Octicons
import com.hubmobile.ui.components.SectionTitle
import com.hubmobile.ui.store.LocalStore
import com.hubmobile.ui.theme.Githup
import com.hubmobile.ui.theme.GithupDimens
import com.hubmobile.ui.theme.GithupType

/**
 * 我的 —— 身份卡（64dp 头像 + 名字 + @login + 简介）、三项统计，
 * 下面是分组设置列表。
 */
@Composable
fun ProfileScreen(
    me: User = Sample.me,
    darkTheme: Boolean = false,
    onToggleTheme: () -> Unit = {},
    onSetting: (String) -> Unit = {},
    onToggleNav: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val T = Githup
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(T.canvas)
            .padding(top = 8.dp)
    ) {
        item {
            GithupCard {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Avatar(size = 64.dp, url = me.avatar, contentDescription = me.login)
                    Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(
                            me.name.ifEmpty { me.login },
                            color = T.fg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold
                        )
                        Text("@${me.login}", color = T.fgMuted, fontSize = 13.sp)
                        if (me.bio.isNotEmpty()) {
                            Text(
                                me.bio, color = T.fgMuted, fontSize = 13.sp,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                    }
                }
                // 统计：仓库 / 关注者 / 关注中
                Row(modifier = Modifier.fillMaxWidth()) {
                    StatCell(me.publicRepos.toString(), "仓库", Modifier.weight(1f))
                    StatCell(me.followers.toString(), "关注者", Modifier.weight(1f))
                    StatCell(me.following.toString(), "关注中", Modifier.weight(1f))
                }
            }
        }

        item { SectionTitle("通用") }
        item {
            SettingsGroup {
                SettingRow(
                    icon = if (darkTheme) Octicons.EyeClosed else Octicons.Eye,
                    title = "深色模式",
                    value = if (darkTheme) "开" else "关",
                    onClick = onToggleTheme
                )
                RowDivider()
                SettingRow(
                    icon = Octicons.Globe,
                    title = "整页翻译",
                    subtitle = "把整页内容翻译成中文",
                    value = "关闭",
                    onClick = { onSetting("translate") }
                )
                RowDivider()
                SettingRow(
                    icon = Octicons.ThreeBars,
                    title = "底栏样式",
                    subtitle = "悬浮玻璃胶囊 / 贴底实心栏",
                    value = if (LocalStore.glassNav) "玻璃" else "朴素",
                    onClick = onToggleNav
                )
                RowDivider()
                SettingRow(
                    icon = Octicons.Star,
                    title = "本地收藏",
                    subtitle = "只在记本机，不上传",
                    value = LocalStore.starred().size.toString() + " 个",
                    onClick = { onSetting("starred") }
                )
            }
        }

        item { SectionTitle("安全") }
        item {
            SettingsGroup {
                SettingRow(
                    icon = Octicons.Shield,
                    title = "两步验证器",
                    subtitle = "后台常驻显示动态码",
                    onClick = { onSetting("totp") }
                )
                RowDivider()
                SettingRow(
                    icon = Octicons.Lock,
                    title = "访问令牌",
                    subtitle = "仅保存在本机加密存储",
                    onClick = { onSetting("token") }
                )
            }
        }

        item { SectionTitle("关于") }
        item {
            SettingsGroup {
                SettingRow(
                    icon = Octicons.Terminal,
                    title = "下载错误日志",
                    subtitle = "把运行日志导出成一份报告",
                    onClick = { onSetting("logs") }
                )
                RowDivider()
                SettingRow(
                    icon = Octicons.MarkGithub,
                    title = "版本",
                    value = "1.2.16",
                    onClick = { onSetting("about") }
                )
            }
        }

        item { Box(modifier = Modifier.size(24.dp)) }
    }
}

@Composable
private fun StatCell(value: String, label: String, modifier: Modifier = Modifier) {
    val T = Githup
    Column(
        modifier = modifier
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(value, color = T.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(label, color = T.fgMuted, fontSize = 12.sp)
    }
}

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    val T = Githup
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GithupDimens.pageGutter)
            .background(T.bg, androidx.compose.foundation.shape.RoundedCornerShape(GithupDimens.radius))
            .border(
                1.dp, T.border,
                androidx.compose.foundation.shape.RoundedCornerShape(GithupDimens.radius)
            )
    ) {
        content()
    }
}
