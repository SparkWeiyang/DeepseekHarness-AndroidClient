package com.spark.harness.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 极简 Markdown 渲染（覆盖 harness 输出常用元素：代码块、标题、列表、粗体/斜体/行内码/链接）。 */

private sealed interface MdBlock {
    data class Paragraph(val spans: List<MdSpan>) : MdBlock
    data class Code(val lang: String, val text: String) : MdBlock
    data class Heading(val level: Int, val spans: List<MdSpan>) : MdBlock
    data class Bullet(val spans: List<MdSpan>) : MdBlock
    data class Numbered(val index: Int, val spans: List<MdSpan>) : MdBlock
    data class Quote(val spans: List<MdSpan>) : MdBlock
    data object Rule : MdBlock
}

private sealed interface MdSpan {
    data class Text(val text: String) : MdSpan
    data class Code(val text: String) : MdSpan
    data class Bold(val text: String) : MdSpan
    data class Italic(val text: String) : MdSpan
    data class Strike(val text: String) : MdSpan
    data class Link(val text: String, val url: String) : MdSpan
}

private val INLINE = Regex(
    "(`[^`]+`)|(\\*\\*[^*]+\\*\\*)|(\\*[^*]+\\*)|(~~[^~]+~~)|(\\[([^\\]]+)\\]\\(([^)]+)\\))"
)

private fun parseInline(text: String): List<MdSpan> {
    val spans = mutableListOf<MdSpan>()
    var last = 0
    for (m in INLINE.findAll(text)) {
        if (m.range.first > last) {
            spans.add(MdSpan.Text(text.substring(last, m.range.first)))
        }
        val s = m.value
        when {
            s.startsWith("`") -> spans.add(MdSpan.Code(s.removeSurrounding("`")))
            s.startsWith("**") -> spans.add(MdSpan.Bold(s.removeSurrounding("**")))
            s.startsWith("*") -> spans.add(MdSpan.Italic(s.removeSurrounding("*")))
            s.startsWith("~~") -> spans.add(MdSpan.Strike(s.removeSurrounding("~~")))
            s.startsWith("[") -> spans.add(MdSpan.Link(m.groupValues[6], m.groupValues[7]))
        }
        last = m.range.last + 1
    }
    if (last < text.length) spans.add(MdSpan.Text(text.substring(last)))
    return spans
}

private fun parseBlocks(markdown: String): List<MdBlock> {
    val lines = markdown.split('\n')
    val blocks = mutableListOf<MdBlock>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        when {
            line.startsWith("```") -> {
                val lang = line.removePrefix("```").trim()
                val buf = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    buf.append(lines[i]).append('\n')
                    i++
                }
                i++ // 跳过收尾 ```
                blocks.add(MdBlock.Code(lang, buf.toString().trimEnd('\n')))
            }
            line.startsWith("#### ") -> {
                blocks.add(MdBlock.Heading(4, parseInline(line.removePrefix("#### "))))
                i++
            }
            line.startsWith("### ") -> {
                blocks.add(MdBlock.Heading(3, parseInline(line.removePrefix("### "))))
                i++
            }
            line.startsWith("## ") -> {
                blocks.add(MdBlock.Heading(2, parseInline(line.removePrefix("## "))))
                i++
            }
            line.startsWith("# ") -> {
                blocks.add(MdBlock.Heading(1, parseInline(line.removePrefix("# "))))
                i++
            }
            line.matches(Regex("^\\s*[-*]\\s+.*")) -> {
                val content = line.trim().removePrefix("- ").removePrefix("* ")
                blocks.add(MdBlock.Bullet(parseInline(content)))
                i++
            }
            line.matches(Regex("^\\s*\\d+\\.\\s+.*")) -> {
                val trimmed = line.trim()
                val idx = trimmed.substringBefore('.').toIntOrNull() ?: 1
                blocks.add(MdBlock.Numbered(idx, parseInline(trimmed.substringAfter(". "))))
                i++
            }
            line.startsWith(">") -> {
                blocks.add(MdBlock.Quote(parseInline(line.removePrefix(">").trim())))
                i++
            }
            line.matches(Regex("^\\s*[-*_]{3,}\\s*$")) -> {
                blocks.add(MdBlock.Rule)
                i++
            }
            line.isBlank() -> i++
            else -> {
                blocks.add(MdBlock.Paragraph(parseInline(line.trim())))
                i++
            }
        }
    }
    return blocks
}

@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { parseBlocks(markdown) }
    val colorScheme = MaterialTheme.colorScheme
    Column(modifier = modifier) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Code -> {
                    Surface(
                        color = colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Text(
                            text = block.text,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(12.dp)
                        )
                    }
                }
                is MdBlock.Heading -> {
                    val size = when (block.level) {
                        1 -> 22.sp
                        2 -> 18.sp
                        3 -> 16.sp
                        else -> 15.sp
                    }
                    Text(
                        text = inlineAnnotated(block.spans, colorScheme.primary, colorScheme.onSurfaceVariant),
                        fontSize = size,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                }
                is MdBlock.Bullet -> {
                    Row(Modifier.padding(start = 8.dp, top = 2.dp, bottom = 2.dp)) {
                        Text("•  ", fontFamily = FontFamily.Monospace)
                        Text(inlineAnnotated(block.spans, colorScheme.primary, colorScheme.onSurfaceVariant))
                    }
                }
                is MdBlock.Numbered -> {
                    Row(Modifier.padding(start = 8.dp, top = 2.dp, bottom = 2.dp)) {
                        Text("${block.index}. ", fontFamily = FontFamily.Monospace)
                        Text(inlineAnnotated(block.spans, colorScheme.primary, colorScheme.onSurfaceVariant))
                    }
                }
                is MdBlock.Quote -> {
                    Row(Modifier.padding(vertical = 2.dp)) {
                        Spacer(
                            Modifier
                                .width(3.dp)
                                .background(colorScheme.outlineVariant)
                        )
                        Text(
                            inlineAnnotated(block.spans, colorScheme.primary, colorScheme.onSurfaceVariant),
                            color = colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
                is MdBlock.Rule -> {
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                }
                is MdBlock.Paragraph -> {
                    Text(
                        text = inlineAnnotated(block.spans, colorScheme.primary, colorScheme.onSurfaceVariant),
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun inlineAnnotated(
    spans: List<MdSpan>,
    linkColor: androidx.compose.ui.graphics.Color,
    codeBg: androidx.compose.ui.graphics.Color
): AnnotatedString = buildAnnotatedString {
    spans.forEach { span ->
        when (span) {
            is MdSpan.Text -> append(span.text)
            is MdSpan.Code -> withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)
            ) { append(span.text) }
            is MdSpan.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(span.text) }
            is MdSpan.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(span.text) }
            is MdSpan.Strike -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(span.text) }
            is MdSpan.Link -> withStyle(
                SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
            ) { append(span.text) }
        }
    }
}
