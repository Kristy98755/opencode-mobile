package kanami.isa.opencode.connect.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val CodeBg = Color(0xFF1C2129)
private val InlineCodeBg = Color(0x3330363D)

private data class Block(val kind: String, val text: String, val lang: String = "")

private fun splitBlocks(src: String): List<Block> {
    val blocks = mutableListOf<Block>()
    var i = 0
    val lines = src.split('\n')
    val buf = StringBuilder()
    var inCode = false
    var lang = ""
    var inList = false
    while (i < lines.size) {
        val line = lines[i]
        if (line.trimStart().startsWith("```")) {
            if (inCode) {
                blocks.add(Block("code", buf.toString().trimEnd(), lang))
                buf.clear()
                inCode = false
            } else {
                if (buf.isNotEmpty()) {
                    flushText(buf, blocks, inList)
                    inList = false
                }
                inCode = true
                lang = line.trimStart().removePrefix("```").trim()
            }
            i++
            continue
        }
        if (inCode) {
            if (buf.isNotEmpty()) buf.append('\n')
            buf.append(line)
            i++
            continue
        }
        val trimmed = line.trimStart()
        val isBullet = trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("• ")
        val isOrdered = trimmed.length > 2 && trimmed[0].isDigit() && trimmed.substringBefore(' ').endsWith('.')
        if (isBullet || isOrdered) {
            if (!inList && buf.isNotEmpty()) {
                flushText(buf, blocks, inList = false)
                buf.clear()
            }
            inList = true
            buf.append(line).append('\n')
        } else if (line.isBlank()) {
            if (buf.isNotEmpty()) {
                flushText(buf, blocks, inList)
                inList = false
                buf.clear()
            }
        } else {
            buf.append(line).append('\n')
        }
        i++
    }
    if (inCode && buf.isNotEmpty()) blocks.add(Block("code", buf.toString(), lang))
    else if (buf.isNotEmpty()) flushText(buf, blocks, inList)
    return blocks
}

private fun flushText(buf: StringBuilder, blocks: MutableList<Block>, inList: Boolean) {
    val t = buf.toString().trimEnd()
    if (t.isNotEmpty()) blocks.add(if (inList) Block("list", t) else Block("text", t))
    buf.setLength(0)
}

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onBackground,
) {
    val blocks = remember(text) { splitBlocks(text) }
    Column(modifier = modifier) {
        blocks.forEachIndexed { idx, block ->
            when (block.kind) {
                "code" -> CodeBlock(block.text)
                "list" -> ListBlock(block.text, color)
                else -> TextBlock(block.text, color)
            }
            if (idx != blocks.lastIndex) Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun TextBlock(text: String, color: Color) {
    val lines = text.split('\n')
    lines.forEachIndexed { i, raw ->
        val line = raw.trim()
        when {
            line.startsWith("### ") -> Header(line.removePrefix("### "), 15.sp, color)
            line.startsWith("## ") -> Header(line.removePrefix("## "), 16.sp, color)
            line.startsWith("# ") -> Header(line.removePrefix("# "), 17.sp, color)
            line.startsWith("> ") -> Text(
                inlineMarkdown(line.removePrefix("> "), color),
                color = color.copy(alpha = 0.8f),
                fontStyle = FontStyle.Italic,
                style = MaterialTheme.typography.bodyMedium,
            )
            line == "---" || line == "***" -> Spacer(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outline)
            )
            else -> Text(
                inlineMarkdown(line, color),
                style = MaterialTheme.typography.bodyMedium,
                color = color,
            )
        }
        if (i != lines.lastIndex) Spacer(Modifier.height(2.dp))
    }
}

@Composable
private fun Header(text: String, size: androidx.compose.ui.unit.TextUnit, color: Color) {
    Text(
        inlineMarkdown(text, color),
        fontSize = size,
        fontWeight = FontWeight.SemiBold,
        color = color,
        style = MaterialTheme.typography.titleMedium,
    )
}

@Composable
private fun ListBlock(text: String, color: Color) {
    Column {
        text.split('\n').filter { it.isNotBlank() }.forEach { raw ->
            val line = raw.trimStart()
            val marker = when {
                line.startsWith("- ") || line.startsWith("* ") || line.startsWith("• ") -> "•"
                line.length > 2 && line[0].isDigit() && line.substringBefore(' ').endsWith('.') ->
                    line.substringBefore(' ')
                else -> "•"
            }
            val content = line.replace(Regex("^([-*•]|\\d+\\.)\\s+"), "")
            Row(modifier = Modifier.padding(start = 4.dp)) {
                Text(
                    "$marker  ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(inlineMarkdown(content, color), style = MaterialTheme.typography.bodyMedium, color = color)
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}

@Composable
private fun CodeBlock(code: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CodeBg, RoundedCornerShape(6.dp))
            .padding(vertical = 8.dp)
    ) {
        Text(
            code,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = Color(0xFFC9D1D9),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun inlineMarkdown(text: String, color: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    val n = text.length
    while (i < n) {
        val two = text.substring(i, minOf(i + 2, n))
        when {
            two == "**" || two == "__" -> {
                val end = text.indexOf(two, i + 2)
                if (end > 0) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                } else { append(two); i += 2 }
            }
            two == "``" -> {
                val end = text.indexOf("``", i + 2)
                if (end > 0) {
                    appendCode(text.substring(i + 2, end))
                    i = end + 2
                } else { append(two); i += 2 }
            }
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > 0) {
                    appendCode(text.substring(i + 1, end))
                    i = end + 1
                } else { append('`'); i++ }
            }
            text[i] == '*' && (i + 1 < n && text[i + 1] != '*') -> {
                val end = text.indexOf('*', i + 1)
                if (end > 0) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else { append('*'); i++ }
            }
            text[i] == '[' && text.indexOf(']', i) > 0 && text.indexOf("](", i) > 0 -> {
                val close = text.indexOf(']', i)
                val open = text.indexOf("](", close)
                val end = text.indexOf(')', open)
                if (end > 0) {
                    val label = text.substring(i + 1, close)
                    withStyle(SpanStyle(color = Color(0xFF58A6FF))) { append(label) }
                    i = end + 1
                } else { append('['); i++ }
            }
            else -> { append(text[i]); i++ }
        }
    }
}

private fun AnnotatedString.Builder.appendCode(s: String) {
    withStyle(
        SpanStyle(
            fontFamily = FontFamily.Monospace,
            background = InlineCodeBg,
            fontSize = 12.5.sp,
        )
    ) { append(s) }
}
