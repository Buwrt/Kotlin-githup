package com.hubmobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hubmobile.ui.components.Octicons
import com.hubmobile.ui.components.SectionTitle
import com.hubmobile.ui.components.SegItem
import com.hubmobile.ui.components.Segmented
import com.hubmobile.ui.theme.Githup
import com.hubmobile.ui.theme.GithupDimens
import com.hubmobile.ui.theme.GithupType

/**
 * 探索 —— 顶部「找点东西」入口（点了跳搜索），下面是 Trending。
 * 趋势项：左侧名次、中间仓库名 + 描述 + 语言/星标，右侧今日新增。
 */
@Composable
fun ExploreScreen(
    trending: List<TrendingRepo> = Sample.trending,
    onRepo: (Repo) -> Unit = {},
    onSearch: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val T = Githup
    var range by remember { mutableStateOf(TrendRange.Day) }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        item {
            Box(modifier = Modifier.padding(horizontal = GithupDimens.pageGutter, vertical = 10.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(T.bg, RoundedCornerShape(GithupDimens.radiusSm))
                        .border(1.dp, T.border, RoundedCornerShape(GithupDimens.radiusSm))
                        .clickable(onClick = onSearch)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Octicons.Search, null, tint = T.fgMuted, modifier = Modifier.size(16.dp))
                    Text(
                        "搜索仓库、议题、用户…",
                        color = T.fgSubtle, fontSize = GithupType.body,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }

        item {
            Box(modifier = Modifier.padding(horizontal = GithupDimens.pageGutter, vertical = 4.dp)) {
                Segmented(
                    items = listOf(
                        SegItem(TrendRange.Day, "今日"),
                        SegItem(TrendRange.Week, "本周"),
                        SegItem(TrendRange.Month, "本月")
                    ),
                    selected = range,
                    onSelect = { range = it },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        item {
            SectionTitle("Trending") {
                Text("近 30 天内活跃", color = T.fgSubtle, fontSize = GithupType.meta)
            }
        }

        itemsIndexed(trending) { i, t ->
            TrendingRow(t, onClick = { onRepo(t.repo) })
            if (i < trending.lastIndex) RowDivider()
        }
    }
}

enum class TrendRange { Day, Week, Month }

@Composable
private fun TrendingRow(t: TrendingRepo, onClick: () -> Unit) {
    val T = Githup
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(T.bg)
            .clickable(onClick = onClick)
            .padding(horizontal = GithupDimens.rowPadH, vertical = GithupDimens.rowPadV)
    ) {
        Text(
            "${t.rank}", color = T.fgSubtle, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(end = 12.dp, top = 1.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                t.repo.fullName, color = T.fg, fontSize = GithupType.body, fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            if (t.repo.desc.isNotEmpty()) {
                Text(
                    t.repo.desc, color = T.fgMuted, fontSize = 13.sp, maxLines = 2,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Row(
                modifier = Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (t.repo.lang.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(langColor(t.repo.lang), RoundedCornerShape(percent = 50))
                        )
                        Text(t.repo.lang, color = T.fgMuted, fontSize = GithupType.meta)
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(Octicons.Star, null, tint = T.fgMuted, modifier = Modifier.size(13.dp))
                    Text(formatCount(t.repo.stars), color = T.fgMuted, fontSize = GithupType.meta)
                }
            }
        }
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.padding(start = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Icon(Octicons.Star, null, tint = Color(0xFFE3B341), modifier = Modifier.size(13.dp))
                Text(t.starsToday, color = T.success, fontSize = GithupType.meta, fontWeight = FontWeight.Medium)
            }
            if (t.builders.isNotEmpty()) {
                Text(t.builders, color = T.fgSubtle, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
