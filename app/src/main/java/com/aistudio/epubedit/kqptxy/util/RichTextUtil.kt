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

object RichTextUtil {
    fun htmlToAnnotatedString(html: String): AnnotatedString {
        if (html.isBlank()) return AnnotatedString("")
        val spanned = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY)
        return buildAnnotatedString {
            append(spanned.toString())
            val spans = spanned.getSpans(0, spanned.length, CharacterStyle::class.java)
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
                }
            }
        }
    }

    fun annotatedStringToHtml(annotatedString: AnnotatedString): String {
        val text = annotatedString.text
        if (text.isBlank()) return "<p></p>"
        
        val sb = StringBuilder()
        
        // We will do a simple paragraph split and line break replacement just like plainTextToHtml
        val paragraphs = text.split(Regex("\\n\\s*\\n+"))
        
        // However, mapping spans properly to HTML tags through splits is complex. 
        // A simpler way: map the whole string correctly, then replace newlines with <br/> or split to <p>.
        
        val htmlParsed = buildStringFromAnnotated(annotatedString)
        
        // Process paragraphs
        val parts = htmlParsed.split(Regex("\\n\\s*\\n+"))
        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.isNotEmpty()) {
                val brReplaced = trimmed.replace("\n", "<br/>\n")
                if (brReplaced.lowercase().startsWith("<p") || brReplaced.lowercase().startsWith("<h")) {
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
        
        // We need to insert tags at the right offset. 
        // We can sort all tags (opening and closing) by their offset, and then insert them.
        data class Tag(val offset: Int, val isOpen: Boolean, val tag: String)
        val tags = mutableListOf<Tag>()
        
        for (span in spanStyles) {
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
            if (style.textDecoration == TextDecoration.Underline) {
                sbTagsOpen.add("<u>")
                sbTagsClose.add(0, "</u>")
            }
            if (style.textDecoration == TextDecoration.LineThrough) {
                sbTagsOpen.add("<s>")
                sbTagsClose.add(0, "</s>")
            }
            
            val openStr = sbTagsOpen.joinToString("")
            val closeStr = sbTagsClose.joinToString("")
            
            if (openStr.isNotEmpty()) {
                tags.add(Tag(span.start, true, openStr))
                tags.add(Tag(span.end, false, closeStr))
            }
        }
        
        // Sort tags: first by offset. If offset is same, close tags come before open tags to avoid <b><i></i></b> overlapping issues? 
        // Actually since we push and pop, closing before opening at same index is safer.
        tags.sortWith(compareBy({ it.offset }, { if (it.isOpen) 1 else 0 }))
        
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
        }
    }
}
