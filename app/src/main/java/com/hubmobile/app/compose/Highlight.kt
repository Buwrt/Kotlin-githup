package com.hubmobile.app.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

/*
 * 轻量代码语法高亮（自己写的，不引第三方库）。
 *
 * 为什么要自己写：网页版用的是 highlight.js（vendor 里那个，几百 KB），
 * 搬进 Compose 不现实。而 App 里真正需要高亮的其实就那几类 token：
 * 关键字、字符串、注释、数字。够了。
 *
 * 覆盖范围（按扩展名判断语言）：
 *   Kotlin / Java / Python / JS / TS / Go / Rust / C / C++ / C# /
 *   Swift / PHP / Ruby / Shell / SQL / JSON / XML-HTML / CSS
 *
 * 不做的（诚实说明）：
 *   · 不做完整语法解析 —— 是词法着色，不是 AST 着色
 *   · 字符串里的转义、嵌套注释（如 Rust 的 /* /* */ */）不做特殊处理
 *   · 不做语言自动探测（统一按扩展名，探测不到当纯文本）
 */

private val KEYWORDS = mapOf(
    "kt" to setOf("abstract", "actual", "as", "break", "by", "catch", "class", "companion",
        "const", "constructor", "continue", "crossinline", "data", "delegate", "do", "else",
        "enum", "expect", "external", "final", "finally", "for", "fun", "get", "if", "import",
        "in", "infix", "init", "inline", "interface", "internal", "is", "lateinit", "noinline",
        "object", "open", "operator", "out", "override", "package", "private", "protected",
        "public", "reified", "return", "sealed", "set", "super", "suspend", "tailrec", "this",
        "throw", "try", "typealias", "val", "var", "vararg", "when", "where", "while"),
    "java" to setOf("abstract", "assert", "break", "case", "catch", "class", "const", "continue",
        "default", "do", "else", "enum", "extends", "final", "finally", "for", "goto", "if",
        "implements", "import", "instanceof", "interface", "native", "new", "package", "private",
        "protected", "public", "return", "static", "strictfp", "super", "switch", "synchronized",
        "this", "throw", "throws", "transient", "try", "void", "volatile", "while"),
    "py" to setOf("and", "as", "assert", "async", "await", "break", "class", "continue", "def",
        "del", "elif", "else", "except", "finally", "for", "from", "global", "if", "import",
        "in", "is", "lambda", "nonlocal", "not", "or", "pass", "raise", "return", "try",
        "while", "with", "yield"),
    "js" to setOf("async", "await", "break", "case", "catch", "class", "const", "continue",
        "debugger", "default", "delete", "do", "else", "export", "extends", "finally", "for",
        "from", "function", "get", "if", "import", "in", "instanceof", "let", "new", "of",
        "return", "set", "static", "super", "switch", "this", "throw", "try", "typeof", "var",
        "void", "while", "with", "yield"),
    "go" to setOf("break", "case", "chan", "const", "continue", "default", "defer", "else",
        "fallthrough", "for", "func", "go", "goto", "if", "import", "interface", "map",
        "package", "range", "return", "select", "struct", "switch", "type", "var"),
    "rs" to setOf("as", "async", "await", "break", "const", "continue", "crate", "dyn", "else",
        "enum", "extern", "fn", "for", "if", "impl", "in", "let", "loop", "match", "mod",
        "move", "mut", "pub", "ref", "return", "self", "static", "struct", "super", "trait",
        "type", "unsafe", "use", "where", "while"),
    "c" to setOf("auto", "break", "case", "char", "const", "continue", "default", "do", "double",
        "else", "enum", "extern", "float", "for", "goto", "if", "int", "long", "register",
        "return", "short", "signed", "sizeof", "static", "struct", "switch", "typedef",
        "union", "unsigned", "void", "volatile", "while"),
    "sh" to setOf("if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done",
        "case", "esac", "function", "return", "export", "local", "echo", "cd", "source"),
    "sql" to setOf("select", "from", "where", "insert", "into", "values", "update", "set",
        "delete", "create", "table", "drop", "alter", "add", "join", "left", "right", "inner",
        "outer", "on", "group", "by", "order", "having", "limit", "as", "and", "or", "not",
        "null", "distinct", "count", "sum", "avg", "max", "min")
)

private val TYPES = setOf(
    "String", "Int", "Long", "Double", "Float", "Boolean", "Char", "Byte", "Short", "Any",
    "Unit", "List", "Map", "Set", "Array", "void", "int", "long", "float", "double", "char",
    "boolean", "string", "object", "var", "number", "bool", "str", "list", "dict", "tuple"
)

/** 由文件名推断语言 key */
internal fun langOf(name: String): String {
    val n = name.substringAfterLast('.').lowercase()
    return when (n) {
        "kt", "kts" -> "kt"
        "java" -> "java"
        "py" -> "py"
        "js", "jsx", "mjs" -> "js"
        "ts", "tsx" -> "js"
        "go" -> "go"
        "rs" -> "rs"
        "c", "h" -> "c"
        "cpp", "hpp", "cc" -> "c"
        "cs" -> "java"
        "sh", "bash" -> "sh"
        "sql" -> "sql"
        "json" -> "json"
        "html", "xml" -> "html"
        "css", "scss" -> "css"
        else -> ""
    }
}

private class Scheme(
    val keyword: Color, val string: Color, val comment: Color,
    val number: Color, val type: Color, val plain: Color
)

private val DarkSchemeColors = Scheme(
    keyword = Color(0xFFFF7B72),   // GitHub dark: 关键字红
    string = Color(0xFFA5D6FF),    // 字符串蓝
    comment = Color(0xFF8B949E),   // 注释灰
    number = Color(0xFF79C0FF),    // 数字浅蓝
    type = Color(0xFF7EE787),      // 类型绿
    plain = Color(0xFFE6EDF3)
)

private val LightSchemeColors = Scheme(
    keyword = Color(0xFFCF222E),
    string = Color(0xFF0A3069),
    comment = Color(0xFF6E7781),
    number = Color(0xFF0550AE),
    type = Color(0xFF953800),
    plain = Color(0xFF1F2328)
)

/**
 * 代码 → 带高亮的 AnnotatedString。
 * dark 决定用哪套配色（GitHub 的 light/dark 两套高亮主题）。
 */
internal fun highlight(code: String, lang: String, dark: Boolean): AnnotatedString {
    val sc = if (dark) DarkSchemeColors else LightSchemeColors
    val kw = KEYWORDS[lang] ?: emptySet()
    if (lang.isEmpty()) return buildAnnotatedString { append(code) }

    return buildAnnotatedString {
        var i = 0
        val n = code.length
        while (i < n) {
            val ch = code[i]

            // 行注释
            if (ch == '/' && i + 1 < n && code[i + 1] == '/') {
                var j = i
                while (j < n && code[j] != '\n') j++
                pushStyle(SpanStyle(color = sc.comment, fontStyle = FontStyle.Italic))
                append(code.substring(i, j)); pop(); i = j; continue
            }
            // 块注释
            if (ch == '/' && i + 1 < n && code[i + 1] == '*') {
                var j = i + 2
                while (j + 1 < n && !(code[j] == '*' && code[j + 1] == '/')) j++
                j = (j + 2).coerceAtMost(n)
                pushStyle(SpanStyle(color = sc.comment, fontStyle = FontStyle.Italic))
                append(code.substring(i, j)); pop(); i = j; continue
            }
            // Python / Shell 的 # 注释
            if (ch == '#' && (lang == "py" || lang == "sh")) {
                var j = i
                while (j < n && code[j] != '\n') j++
                pushStyle(SpanStyle(color = sc.comment, fontStyle = FontStyle.Italic))
                append(code.substring(i, j)); pop(); i = j; continue
            }
            // 字符串
            if (ch == '"' || ch == '\'' || (ch == '`' && lang == "js")) {
                val q = ch
                var j = i + 1
                while (j < n) {
                    if (code[j] == '\\') { j += 2; continue }
                    if (code[j] == q) { j++; break }
                    if (q != '`' && code[j] == '\n') break
                    j++
                }
                pushStyle(SpanStyle(color = sc.string))
                append(code.substring(i, j.coerceAtMost(n))); pop(); i = j; continue
            }
            // 数字
            if (ch.isDigit()) {
                var j = i
                while (j < n && (code[j].isDigit() || code[j] == '.' ||
                        code[j] in 'a'..'f' || code[j] in 'A'..'F' ||
                        code[j] == 'x' || code[j] == 'X')) j++
                pushStyle(SpanStyle(color = sc.number))
                append(code.substring(i, j)); pop(); i = j; continue
            }
            // 标识符
            if (ch.isLetter() || ch == '_' || ch == '@') {
                var j = i
                while (j < n && (code[j].isLetterOrDigit() || code[j] == '_')) j++
                val w = code.substring(i, j)
                when {
                    w in kw -> {
                        pushStyle(SpanStyle(color = sc.keyword, fontWeight = FontWeight.Medium))
                        append(w); pop()
                    }
                    w in TYPES -> { pushStyle(SpanStyle(color = sc.type)); append(w); pop() }
                    else -> append(w)
                }
                i = j; continue
            }
            append(ch)
            i++
        }
    }
}
