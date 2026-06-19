package com.aistudio.epubedit.kqptxy.util

import android.text.Spanned
import android.text.style.CharacterStyle
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.core.text.HtmlCompat
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

object RichTextUtil {
    fun htmlToAnnotatedString(html: String): AnnotatedString {
        if (html.isBlank()) return AnnotatedString("")
        val spanned = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY)
        return buildAnnotatedString {
            append(spanned.toString())
            val spans = spanned.getSpans(0, spanned.length, Any::class.java)
            for (span in spans) {
                val start = spanned.getSpanStart(span)
                val end = spanned.getSpanEnd(span)
                if (start < 0 || end > spanned.length || start >= end) continue
                when (span) {
                    is StyleSpan -> {
                        when (span.style) {
                            android.graphics.Typeface.BOLD -> addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, end)
                            android.graphics.Typeface.ITALIC -> addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, end)
                            android.graphics.Typeface.BOLD_ITALIC -> addStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic), start, end)
                        }
                    }
                    is UnderlineSpan -> addStyle(SpanStyle(textDecoration = TextDecoration.Underline), start, end)
                    is StrikethroughSpan -> addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), start, end)
                    
                    is android.text.style.ForegroundColorSpan -> {
                        addStyle(SpanStyle(color = androidx.compose.ui.graphics.Color(span.foregroundColor)), start, end)
                    }
                    is android.text.style.BackgroundColorSpan -> {
                        addStyle(SpanStyle(background = androidx.compose.ui.graphics.Color(span.backgroundColor)), start, end)
                    }
                    is android.text.style.SuperscriptSpan -> {
                        addStyle(SpanStyle(baselineShift = androidx.compose.ui.text.style.BaselineShift.Superscript), start, end)
                    }
                    is android.text.style.SubscriptSpan -> {
                        addStyle(SpanStyle(baselineShift = androidx.compose.ui.text.style.BaselineShift.Subscript), start, end)
                    }
                    is android.text.style.URLSpan -> {
                        addStringAnnotation(tag = "URL", annotation = span.url, start = start, end = end)
                        addStyle(SpanStyle(color = androidx.compose.ui.graphics.Color.Blue, textDecoration = TextDecoration.Underline), start, end)
                    }
                    is android.text.style.RelativeSizeSpan -> {
                        addStyle(SpanStyle(fontSize = span.sizeChange.em), start, end)
                    }
                    is android.text.style.AbsoluteSizeSpan -> {
                        addStyle(SpanStyle(fontSize = span.size.sp), start, end)
                    }
                    is android.text.style.AlignmentSpan -> {
                        val alignStr = when (span.alignment) {
                            android.text.Layout.Alignment.ALIGN_CENTER -> "center"
                            android.text.Layout.Alignment.ALIGN_OPPOSITE -> "right"
                            else -> "left"
                        }
                        addStringAnnotation(tag = "ALIGN", annotation = alignStr, start = start, end = end)
                    }
                    is android.text.style.BulletSpan -> {
                        addStringAnnotation(tag = "LIST_ITEM", annotation = "bullet", start = start, end = end)
                    }
                    is android.text.style.QuoteSpan -> {
                        addStringAnnotation(tag = "QUOTE", annotation = "quote", start = start, end = end)
                        addStyle(SpanStyle(background = androidx.compose.ui.graphics.Color.LightGray.copy(alpha = 0.2f), fontStyle = FontStyle.Italic), start, end)
                    }
                }
            }
        }
    }

    fun annotatedStringToHtml(annotatedString: AnnotatedString): String {
        val text = annotatedString.text
        if (text.isBlank()) return "<p></p>"
        
        val sb = StringBuilder()
        
        val htmlParsed = buildStringFromAnnotated(annotatedString)
        
        // Process paragraphs
        val parts = htmlParsed.split(Regex("\\n\\s*\\n+"))
        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.isNotEmpty()) {
                val brReplaced = trimmed.replace("\n", "<br/>\n")
                if (brReplaced.lowercase().startsWith("<p") || brReplaced.lowercase().startsWith("<h") || brReplaced.lowercase().startsWith("<div") || brReplaced.lowercase().startsWith("<blockquote") || brReplaced.lowercase().startsWith("<ul") || brReplaced.lowercase().startsWith("<li")) {
                    sb.append(brReplaced).append("\n")
                } else {
                    sb.append("<p>").append(brReplaced).append("</p>\n")
                }
            }
        }
        
        return sb.toString().trim()
    }
    
    private fun buildStringFromAnnotated(ann: AnnotatedString): String {
        val spanStyles = ann.spanStyles
        val text = ann.text
        
        data class Tag(val offset: Int, val isOpen: Boolean, val tag: String, val priority: Int)
        val tags = mutableListOf<Tag>()
        
        // Helper to deduplicate identical spans on the same range
        val uniqueSpans = spanStyles.distinctBy { "${it.start}-${it.end}-${it.item}" }
        
        for (span in uniqueSpans) {
            val style = span.item
            val sbTagsOpen = mutableListOf<String>()
            val sbTagsClose = mutableListOf<String>()
            
            if (style.fontWeight == FontWeight.Bold) {
                sbTagsOpen.add("<b>")
                sbTagsClose.add(0, "</b>")
            }
            if (style.fontStyle == FontStyle.Italic) {
                sbTagsOpen.add("<i>")
                sbTagsClose.add(0, "</i>")
            }
            if (style.textDecoration != null) {
                if (style.textDecoration!!.contains(TextDecoration.Underline)) {
                    sbTagsOpen.add("<u>")
                    sbTagsClose.add(0, "</u>")
                }
                if (style.textDecoration!!.contains(TextDecoration.LineThrough)) {
                    sbTagsOpen.add("<s>")
                    sbTagsClose.add(0, "</s>")
                }
            }
            if (style.baselineShift == androidx.compose.ui.text.style.BaselineShift.Superscript) {
                sbTagsOpen.add("<sup>")
                sbTagsClose.add(0, "</sup>")
            }
            if (style.baselineShift == androidx.compose.ui.text.style.BaselineShift.Subscript) {
                sbTagsOpen.add("<sub>")
                sbTagsClose.add(0, "</sub>")
            }
            if (style.color != androidx.compose.ui.graphics.Color.Unspecified && style.color != androidx.compose.ui.graphics.Color.Transparent) {
                try {
                    val argb = style.color.toArgb()
                    val hexColor = String.format("#%06X", 0xFFFFFF and argb)
                    sbTagsOpen.add("<span style=\"color:$hexColor\">")
                    sbTagsClose.add(0, "</span>")
                } catch (e: Exception) {
                    // Ignore
                }
            }
            if (style.background != androidx.compose.ui.graphics.Color.Unspecified && style.background != androidx.compose.ui.graphics.Color.Transparent) {
                try {
                    val argb = style.background.toArgb()
                    val hexBg = String.format("#%06X", 0xFFFFFF and argb)
                    sbTagsOpen.add("<span style=\"background-color:$hexBg\">")
                    sbTagsClose.add(0, "</span>")
                } catch (e: Exception) {
                    // Ignore
                }
            }
            if (style.fontSize != androidx.compose.ui.unit.TextUnit.Unspecified) {
                try {
                    if (style.fontSize.isEm) {
                        val scale = style.fontSize.value
                        if (scale >= 1.45f) {
                            sbTagsOpen.add("<h1>")
                            sbTagsClose.add(0, "</h1>")
                        } else if (scale >= 1.35f) {
                            sbTagsOpen.add("<h2>")
                            sbTagsClose.add(0, "</h2>")
                        } else if (scale >= 1.25f) {
                            sbTagsOpen.add("<h3>")
                            sbTagsClose.add(0, "</h3>")
                        } else if (scale >= 1.15f) {
                            sbTagsOpen.add("<h4>")
                            sbTagsClose.add(0, "</h4>")
                        } else if (scale >= 1.05f) {
                            sbTagsOpen.add("<h5>")
                            sbTagsClose.add(0, "</h5>")
                        } else {
                            val percent = (scale * 100).toInt()
                            sbTagsOpen.add("<span style=\"font-size:$percent%\">")
                            sbTagsClose.add(0, "</span>")
                        }
                    } else if (style.fontSize.isSp) {
                        val px = style.fontSize.value.toInt()
                        sbTagsOpen.add("<span style=\"font-size:${px}px\">")
                        sbTagsClose.add(0, "</span>")
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }
            
            val openStr = sbTagsOpen.joinToString("")
            val closeStr = sbTagsClose.joinToString("")
            
            if (openStr.isNotEmpty()) {
                tags.add(Tag(span.start, true, openStr, 1))
                tags.add(Tag(span.end, false, closeStr, 0))
            }
        }
        
        val annotations = ann.getStringAnnotations(start = 0, end = text.length)
        for (range in annotations) {
            val sbTagsOpen = mutableListOf<String>()
            val sbTagsClose = mutableListOf<String>()
            
            when (range.tag) {
                "URL" -> {
                    sbTagsOpen.add("<a href=\"${range.item}\">")
                    sbTagsClose.add(0, "</a>")
                }
                "ALIGN" -> {
                    sbTagsOpen.add("<div align=\"${range.item}\">")
                    sbTagsClose.add(0, "</div>")
                }
                "LIST_ITEM" -> {
                    sbTagsOpen.add("<li>")
                    sbTagsClose.add(0, "</li>")
                }
                "QUOTE" -> {
                    sbTagsOpen.add("<blockquote>")
                    sbTagsClose.add(0, "</blockquote>")
                }
            }
            
            val openStr = sbTagsOpen.joinToString("")
            val closeStr = sbTagsClose.joinToString("")
            
            if (openStr.isNotEmpty()) {
                tags.add(Tag(range.start, true, openStr, 2))
                tags.add(Tag(range.end, false, closeStr, -1))
            }
        }
        
        tags.sortWith(compareBy({ it.offset }, { it.priority }))
        
        val sb = StringBuilder()
        var currentOffset = 0
        for (tag in tags) {
            if (tag.offset > currentOffset) {
                sb.append(escapeHtml(text.substring(currentOffset, tag.offset)))
                currentOffset = tag.offset
            }
            sb.append(tag.tag)
        }
        if (currentOffset < text.length) {
            sb.append(escapeHtml(text.substring(currentOffset)))
        }
        return sb.toString()
    }
    
    private fun escapeHtml(text: String): String {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }

    fun adjustSpans(oldApp: AnnotatedString, newText: String): AnnotatedString {
        val oldText = oldApp.text
        if (oldText == newText) {
            return oldApp
        }
        
        var prefixLen = 0
        while (prefixLen < oldText.length && prefixLen < newText.length && oldText[prefixLen] == newText[prefixLen]) {
            prefixLen++
        }
        
        var suffixLen = 0
        while (suffixLen < (oldText.length - prefixLen) && suffixLen < (newText.length - prefixLen) &&
               oldText[oldText.length - 1 - suffixLen] == newText[newText.length - 1 - suffixLen]) {
            suffixLen++
        }
        
        val deletedLength = oldText.length - prefixLen - suffixLen
        val insertedLength = newText.length - prefixLen - suffixLen
        val editStart = prefixLen
        
        return buildAnnotatedString {
            append(newText)
            
            oldApp.spanStyles.forEach { range ->
                val start = range.start
                val end = range.end
                
                val newStart: Int
                val newEnd: Int
                
                if (start < editStart) {
                    newStart = start
                } else if (start >= editStart + deletedLength) {
                    newStart = start - deletedLength + insertedLength
                } else {
                    newStart = editStart
                }
                
                if (end <= editStart) {
                    newEnd = end
                } else if (end >= editStart + deletedLength) {
                    newEnd = end - deletedLength + insertedLength
                } else {
                    newEnd = editStart
                }
                
                if (newStart < newEnd) {
                    addStyle(range.item, newStart, newEnd)
                }
            }
            
            oldApp.getStringAnnotations(0, oldApp.length).forEach { range ->
                val start = range.start
                val end = range.end
                
                val newStart: Int
                val newEnd: Int
                
                if (start < editStart) {
                    newStart = start
                } else if (start >= editStart + deletedLength) {
                    newStart = start - deletedLength + insertedLength
                } else {
                    newStart = editStart
                }
                
                if (end <= editStart) {
                    newEnd = end
                } else if (end >= editStart + deletedLength) {
                    newEnd = end - deletedLength + insertedLength
                } else {
                    newEnd = editStart
                }
                
                if (newStart < newEnd) {
                    addStringAnnotation(range.tag, range.item, newStart, newEnd)
                }
            }
        }
    }

    fun beautifyHtmlWithCursor(html: String, cursorIndex: Int): Pair<String, Int> {
        if (html.isBlank()) return "" to 0
        
        val blockTags = setOf(
            "html", "head", "body", "div", "p", "h1", "h2", "h3", "h4", "h5", "h6",
            "ul", "ol", "li", "blockquote", "section", "nav", "header", "footer",
            "metadata", "manifest", "spine", "guide", "item", "title", "style", "script",
            "table", "tr", "td", "th", "thead", "tbody", "tfoot",
            "pre", "code", "article", "aside", "caption", "dl", "dt", "dd"
        )
        val selfClosingTags = setOf("img", "br", "hr", "meta", "link", "input", "base")

        val sb = StringBuilder()
        var indentLevel = 0
        
        // Tokenizer that tracks original offsets
        val tokens = mutableListOf<Pair<String, Int>>()
        var i = 0
        while (i < html.length) {
            if (html[i] == '<') {
                val end = html.indexOf('>', i)
                if (end != -1) {
                    val tag = html.substring(i, end + 1)
                    val cleanedTag = tag.trim().replace(Regex("\\s+"), " ")
                    tokens.add(cleanedTag to i)
                    i = end + 1
                } else {
                    tokens.add(html.substring(i) to i)
                    i = html.length
                }
            } else {
                val nextTag = html.indexOf('<', i)
                if (nextTag != -1) {
                    val text = html.substring(i, nextTag)
                    if (text.isNotBlank()) tokens.add(text.trim() to i)
                    i = nextTag
                } else {
                    val text = html.substring(i)
                    if (text.isNotBlank()) tokens.add(text.trim() to i)
                    i = html.length
                }
            }
        }

        var newCursorIndex = 0
        var cursorSet = false

        fun appendToken(indent: Int, token: String, originalStart: Int) {
            if (sb.isNotEmpty() && sb.last() != '\n') {
                sb.append("\n")
            }
            val lineStart = sb.length
            sb.append("    ".repeat(indent))
            
            // If the original cursor was before or inside this token
            if (!cursorSet && cursorIndex <= originalStart + token.length) {
                // Approximate position within the beautified line
                newCursorIndex = sb.length + (cursorIndex - originalStart).coerceIn(0, token.length)
                cursorSet = true
            }
            
            sb.append(token)
        }

        for (tokenEntry in tokens) {
            val token = tokenEntry.first
            val originalStart = tokenEntry.second
            
            if (token.startsWith("<")) {
                if (token.startsWith("<!") || token.startsWith("<?")) {
                    appendToken(indentLevel, token, originalStart)
                    continue
                }
                
                val tagNameMatch = Regex("<(/?[a-zA-Z0-9:-]+)").find(token)
                val rawTagName = tagNameMatch?.groupValues?.get(1) ?: ""
                val isClosing = rawTagName.startsWith("/")
                val tagName = rawTagName.removePrefix("/").lowercase()
                val isSelfClosing = token.endsWith("/>") || tagName in selfClosingTags
                
                if (isClosing) {
                    indentLevel--
                    if (indentLevel < 0) indentLevel = 0
                    appendToken(indentLevel, token, originalStart)
                } else {
                    appendToken(indentLevel, token, originalStart)
                    if (!isSelfClosing) {
                        indentLevel++
                    }
                }
            } else {
                appendToken(indentLevel, token, originalStart)
            }
        }
        
        if (!cursorSet) newCursorIndex = sb.length
        return sb.toString().trim() to newCursorIndex
    }

    fun handleAutoIndent(oldValue: String, newValue: String, selection: Int): String? {
        // Detect if Enter was pressed
        if (newValue.length == oldValue.length + 1 && selection > 0 && newValue[selection - 1] == '\n') {
            val lineStart = newValue.substring(0, selection - 1).lastIndexOf('\n') + 1
            val prevLine = newValue.substring(lineStart, selection - 1)
            val indent = prevLine.takeWhile { it == ' ' || it == '\t' }
            
            if (indent.isNotEmpty()) {
                return newValue.substring(0, selection) + indent + newValue.substring(selection)
            }
        }
        return null
    }

    class HtmlSyntaxTransformation : androidx.compose.ui.text.input.VisualTransformation {
        override fun filter(text: AnnotatedString): androidx.compose.ui.text.input.TransformedText {
            // Disable complex syntax highlighting for very large text to prevent UI lag/crashes when typing.
            // Even simple Regexes can block the main UI thread during Compose recomposition of TextFieldValue.
            if (text.length > 2000) {
                return androidx.compose.ui.text.input.TransformedText(
                    text,
                    androidx.compose.ui.text.input.OffsetMapping.Identity
                )
            }
            return androidx.compose.ui.text.input.TransformedText(
                highlightHtml(text.text),
                androidx.compose.ui.text.input.OffsetMapping.Identity
            )
        }

        private fun highlightHtml(text: String): AnnotatedString {
            val tagColor = androidx.compose.ui.graphics.Color(0xFF569CD6) // Blue
            val attrColor = androidx.compose.ui.graphics.Color(0xFF9CDCFE) // Cyan
            val valueColor = androidx.compose.ui.graphics.Color(0xFFCE9178) // Orange
            val jsonOrNoColor = androidx.compose.ui.graphics.Color.Unspecified

            return buildAnnotatedString {
                append(text)
                
                // Very fast minimal highlighting for small strings only
                Regex("<(/?[a-zA-Z0-9:-]+)").findAll(text).forEach { result ->
                    addStyle(SpanStyle(color = tagColor), result.range.first, result.range.last + 1)
                }
            }
        }
    }
}
