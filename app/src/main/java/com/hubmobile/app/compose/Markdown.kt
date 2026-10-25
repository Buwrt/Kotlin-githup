package com.hubmobile.app.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * 极简 Markdown 渲染（自己写的，不引第三方库）。
 *
 * 为什么自己写：项目一直守着"除 stdlib 外零第三方依赖"，Compose 已经让了一步，
 * Markdown 这块能自己写就自己写 —— 而且网页版那份 md.js 有 52KB（GFM 全量 +
 * 代码高亮 + XSS 过滤），原样搬过来既不现实也不必要。
 *
 * 支持：标题 1~6、围栏代码块、行内代码、粗体、斜体、删除线、链接、
 *       无序/有序列表、引用、分隔线、图片（显示为占位文字）。
 *
 * 没做（诚实说明）：
 *   · 代码语法高亮 —— 只做等宽字体 + 灰底，不做 token 着色
 *   · 表格 —— GFM 表格渲染成普通段落
 *   · 嵌套列表 —— 只支持一层
 * 这几项要真做，建议引一个成熟库（如 markwon 的 Compose 适配），那是另一个决定。
 */

private sealed class MdBlock {
    class Heading(val level: Int, val text: String) : MdBlock()
    class Code(val lang: String, val code: String) : MdBlock()
    class Quote(val text: String) : MdBlock()
    class Bullet(val items: List<String>, val ordered: Boolean) : MdBlock()
    class Image(val alt: String, val url: String) : MdBlock()
    class Para(val text: String) : MdBlock()
    object Rule : MdBlock()
}

private fun parseMarkdown(src: String): List<MdBlock> {
    val lines = src.replace("\r\n", "\n").split("\n")
    val out = ArrayList<MdBlock>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]

        // 围栏代码块
        if (line.trimStart().startsWith("```")) {
            val lang = line.trim().removePrefix("```").trim()
            val sb = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                sb.append(lines[i]).append('\n')
                i++
            }
            i++ // 跳过收尾的 ```
            out.add(MdBlock.Code(lang, sb.toString().trimEnd()))
            continue
        }

        val t = line.trim()

        if (t.isEmpty()) { i++; continue }

        // 分隔线
        if (t.matches(Regex("^(-{3,}|\\*{3,}|_{3,})$"))) {
            out.add(MdBlock.Rule); i++; continue
        }

        // 标题
        val h = Regex("^(#{1,6})\\s+(.*)$").find(t)
        if (h != null) {
            out.add(MdBlock.Heading(h.groupValues[1].length, h.groupValues[2]))
            i++; continue
        }

        // 图片单独成行
        val img = Regex("^!\\[([^\\]]*)\\]\\(([^)]+)\\)$").find(t)
        if (img != null) {
            out.add(MdBlock.Image(img.groupValues[1], img.groupValues[2]))
            i++; continue
        }

        // 引用
        if (t.startsWith(">")) {
            val sb = StringBuilder()
            while (i < lines.size && lines[i].trim().startsWith(">")) {
                sb.append(lines[i].trim().removePrefix(">").trim()).append(' ')
                i++
            }
            out.add(MdBlock.Quote(sb.toString().trim()))
            continue
        }

        // 列表
        val bullet = Regex("^[-*+]\\s+(.*)$").find(t)
        val numbered = Regex("^\\d+[.)]\\s+(.*)$").find(t)
        if (bullet != null || numbered != null) {
            val ordered = numbered != null
            val items = ArrayList<String>()
            while (i < lines.size) {
                val lt = lines[i].trim()
                val b = Regex("^[-*+]\\s+(.*)$").find(lt)
                val n = Regex("^\\d+[.)]\\s+(.*)$").find(lt)
                val item = (if (ordered) n else b)?.groupValues?.get(1)
                if (item == null) break
                items.add(item)
                i++
            }
            out.add(MdBlock.Bullet(items, ordered))
            continue
        }

        // 普通段落：连续非空行合并
        val sb = StringBuilder()
        while (i < lines.size) {
            val lt = lines[i].trim()
            if (lt.isEmpty()) break
            if (lt.startsWith("#") || lt.startsWith("```") || lt.startsWith(">")
                || lt.startsWith("---") || lt.matches(Regex("^[-*+]\\s+.*"))
                || lt.matches(Regex("^\\d+[.)]\\s+.*"))) break
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(lt)
            i++
        }
        if (sb.isNotEmpty()) out.add(MdBlock.Para(sb.toString()))
        else i++
    }
    return out
}

/** 行内标记 → AnnotatedString。链接用字符串注解挂上去，配合 ClickableText 可点。 */
private fun buildInline(text: String, codeBg: Color = HubColors.CodeBgDark, link: Color = HubColors.AccentDark): AnnotatedString = buildAnnotatedString {
    val bt = "\u0060"           // 行内代码
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith(bt, i) -> {
                val end = text.indexOf(bt, i + 1)
                if (end > i + 1) {
                    pushStyle(SpanStyle(fontFamily = FontFamily.Monospace,
                        background = codeBg, fontSize = 12.sp))
                    append(text.substring(i + 1, end))
                    pop(); i = end + 1
                } else { append(text[i]); i++ }
            }
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > i + 2) {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(text.substring(i + 2, end))
                    pop(); i = end + 2
                } else { append(text[i]); i++ }
            }
            text.startsWith("~~", i) -> {
                val end = text.indexOf("~~", i + 2)
                if (end > i + 2) {
                    pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                    append(text.substring(i + 2, end))
                    pop(); i = end + 2
                } else { append(text[i]); i++ }
            }
            text.startsWith("[", i) -> {
                val mid = text.indexOf("]", i)
                if (mid > i && mid + 1 < text.length && text[mid + 1] == '(') {
                    val end = text.indexOf(")", mid + 2)
                    if (end > mid) {
                        val label = text.substring(i + 1, mid)
                        val url = text.substring(mid + 2, end)
                        pushStringAnnotation("URL", url)
                        pushStyle(SpanStyle(color = link,
                            textDecoration = TextDecoration.Underline))
                        append(label)
                        pop(); pop(); i = end + 1
                    } else { append(text[i]); i++ }
                } else { append(text[i]); i++ }
            }
            text.startsWith("*", i) && !text.startsWith("**", i) -> {
                val end = text.indexOf("*", i + 1)
                if (end > i + 1) {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    append(text.substring(i + 1, end))
                    pop(); i = end + 1
                } else { append(text[i]); i++ }
            }
            else -> { append(text[i]); i++ }
        }
    }
}

@Composable
fun MarkdownText(
    src: String,
    modifier: Modifier = Modifier,
    onLink: ((String) -> Unit)? = null
) {
    val blocks = remember(src) { parseMarkdown(src) }
    Column(modifier.fillMaxWidth()) {
        blocks.forEach { b ->
            when (b) {
                is MdBlock.Heading -> {
                    val size = when (b.level) {
                        1 -> 22.sp; 2 -> 19.sp; 3 -> 16.sp
                        4 -> 14.sp; 5 -> 13.sp; else -> 12.sp
                    }
                    Text(
                        buildInline(b.text, LocalHubColors.current.codeBg, LocalHubColors.current.accent),
                        color = LocalHubColors.current.fg, fontSize = size,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                    if (b.level <= 2) {
                        Spacer(Modifier.height(2.dp).fillMaxWidth()
                            .background(LocalHubColors.current.border))
                    }
                }
                is MdBlock.Code -> {
                    val scroll = rememberScrollState()
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp)
                            .background(LocalHubColors.current.bgHi, RoundedCornerShape(6.dp))
                            .padding(10.dp)
                    ) {
                        Column(Modifier.horizontalScroll(scroll)) {
                            if (b.lang.isNotBlank()) {
                                Text(b.lang, color = LocalHubColors.current.fgMuted, fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace)
                                Spacer(Modifier.height(4.dp))
                            }
                            Text(
                                b.code, color = LocalHubColors.current.fg, fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                style = TextStyle(lineHeight = 17.sp)
                            )
                        }
                    }
                }
                is MdBlock.Quote -> {
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 5.dp)
                            .background(LocalHubColors.current.bg, RoundedCornerShape(4.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(buildInline(b.text, LocalHubColors.current.codeBg, LocalHubColors.current.accent), color = LocalHubColors.current.fgMuted, fontSize = 13.sp)
                    }
                }
                is MdBlock.Bullet -> {
                    Column(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp)) {
                        b.items.forEachIndexed { idx, it ->
                            Text(
                                buildAnnotatedString {
                                    append(if (b.ordered) "${idx + 1}. " else "• ")
                                    append(buildInline(it, LocalHubColors.current.codeBg, LocalHubColors.current.accent))
                                },
                                color = LocalHubColors.current.fg, fontSize = 13.sp,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
                is MdBlock.Image -> {
                    Text("[图片] ${b.alt.ifBlank { b.url }}",
                        color = LocalHubColors.current.fgMuted, fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 4.dp))
                }
                MdBlock.Rule -> {
                    Spacer(Modifier.height(8.dp))
                    Spacer(Modifier.height(1.dp).fillMaxWidth().background(LocalHubColors.current.border))
                    Spacer(Modifier.height(8.dp))
                }
                is MdBlock.Para -> {
                    val ann = buildInline(b.text, LocalHubColors.current.codeBg, LocalHubColors.current.accent)
                    if (onLink == null) {
                        Text(ann, color = LocalHubColors.current.fg, fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 4.dp))
                    } else {
                        ClickableText(
                            ann,
                            style = androidx.compose.ui.text.TextStyle(
                                color = LocalHubColors.current.fg, fontSize = 13.sp),
                            modifier = Modifier.padding(vertical = 4.dp),
                            onClick = { pos ->
                                ann.getStringAnnotations("URL", pos, pos)
                                    .firstOrNull()?.let { onLink(it.item) }
                            }
                        )
                    }
                }
            }
        }
    }
}
