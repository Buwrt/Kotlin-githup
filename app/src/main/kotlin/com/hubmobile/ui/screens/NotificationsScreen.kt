package com.hubmobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hubmobile.ui.components.EmptyState
import com.hubmobile.ui.components.Octicons
import com.hubmobile.ui.components.SegItem
import com.hubmobile.ui.components.Segmented
import com.hubmobile.ui.theme.Githup
import com.hubmobile.ui.theme.GithupDimens
import com.hubmobile.ui.theme.GithupType

/**
 * 通知 —— 顶部「全部 / 未读」分段，下面是通知列表。
 * 未读项左侧一枚强调色圆点，类型图标在右前方（Issue / PR / Release / 提及）。
 */
@Composable
fun NotificationsScreen(
    items: List<NotificationItem> = Sample.notifications,
    onItem: (NotificationItem) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val T = Githup
    var filter by remember { mutableStateOf(NotifyFilter.All) }
    val shown = if (filter == NotifyFilter.Unread) items.filter { it.unread } else items

    Column(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.padding(horizontal = GithupDimens.pageGutter, vertical = 10.dp)) {
            Segmented(
                items = listOf(
                    SegItem(NotifyFilter.All, "全部"),
                    SegItem(NotifyFilter.Unread, "未读")
                ),
                selected = filter,
                onSelect = { filter = it },
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (shown.isEmpty()) {
            EmptyState(Octicons.Bell, "没有未读通知", "有新消息时会出现在这里")
            return@Column
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .background(T.bg)
        ) {
            itemsIndexed(shown) { i, n ->
                NotificationRow(n, onClick = { onItem(n) })
                if (i < shown.lastIndex) RowDivider()
            }
        }
    }
}

enum class NotifyFilter { All, Unread }

@Composable
private fun NotificationRow(n: NotificationItem, onClick: () -> Unit) {
    val T = Githup
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(T.bg)
            .clickable(onClick = onClick)
            .padding(horizontal = GithupDimens.rowPadH, vertical = GithupDimens.rowPadV),
        verticalAlignment = Alignment.Top
    ) {
        // 左侧未读点
        Box(
            modifier = Modifier
                .padding(top = 6.dp, end = 8.dp)
                .size(8.dp)
                .background(
                    if (n.unread) T.accent else androidx.compose.ui.graphics.Color.Transparent,
                    RoundedCornerShape(percent = 50)
                )
        )
        Icon(
            kindIcon(n.kind), null,
            tint = if (n.unread) T.fg else T.fgSubtle,
            modifier = Modifier.size(18.dp).padding(top = 2.dp)
        )
        Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
            Text(
                n.title, color = T.fg, fontSize = GithupType.body,
                fontWeight = if (n.unread) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2
            )
            Row(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(n.repo, color = T.fgMuted, fontSize = GithupType.meta, maxLines = 1)
                Text("·", color = T.fgSubtle, fontSize = GithupType.meta)
                Text(n.time, color = T.fgMuted, fontSize = GithupType.meta)
            }
        }
    }
}
