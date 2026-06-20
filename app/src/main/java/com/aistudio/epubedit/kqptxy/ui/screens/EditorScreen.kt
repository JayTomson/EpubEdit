package com.aistudio.epubedit.kqptxy.ui.screens

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import androidx.core.text.HtmlCompat
import coil.compose.AsyncImage
import com.aistudio.epubedit.kqptxy.util.WordStatsHelper
import com.aistudio.epubedit.kqptxy.util.EpubProcessor
import com.aistudio.epubedit.kqptxy.util.Loc
import com.aistudio.epubedit.kqptxy.viewmodel.BookViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileOutputStream

sealed class EditorBlock {
    abstract val id: String
    data class Text(val content: String, override val id: String = java.util.UUID.randomUUID().toString()) : EditorBlock()
    data class Image(val fileName: String, val localPath: String, val rawTag: String, override val id: String = java.util.UUID.randomUUID().toString()) : EditorBlock()
}

fun decodeHtmlEntities(html: String): String {
    if (!html.contains('&')) return html
    val customDecoderMap = mapOf(
        "Acy" to "А", "acy" to "а", "Bcy" to "Б", "bcy" to "б",
        "Vcy" to "В", "vcy" to "в", "Gcy" to "Г", "gcy" to "г",
        "Dcy" to "Д", "dcy" to "д", "IEcy" to "Е", "iecy" to "е",
        "IOcy" to "Ё", "iocy" to "ё", "ZHcy" to "Ж", "zhcy" to "ж",
        "Zcy" to "З", "zcy" to "з", "Icy" to "И", "icy" to "и",
        "Jcy" to "Й", "jcy" to "й", "Kcy" to "К", "kcy" to "к",
        "Lcy" to "Л", "lcy" to "л", "Mcy" to "М", "mcy" to "м",
        "Ncy" to "Н", "ncy" to "н", "Ocy" to "О", "ocy" to "о",
        "Pcy" to "П", "pcy" to "п", "Rcy" to "Р", "rcy" to "р",
        "Scy" to "С", "scy" to "с", "Tcy" to "Т", "tcy" to "т",
        "Ucy" to "У", "ucy" to "у", "Fcy" to "Ф", "fcy" to "ф",
        "KHcy" to "Х", "khcy" to "х", "TScy" to "Ц", "tscy" to "ц",
        "CHcy" to "Ч", "chcy" to "ч", "SHcy" to "Ш", "shcy" to "ш",
        "SHCHcy" to "Щ", "shchcy" to "щ", "SHHcy" to "Щ", "shhcy" to "щ",
        "HARDcy" to "Ъ", "hardcy" to "ъ", "Ycy" to "Ы", "ycy" to "ы",
        "SOFTcy" to "Ь", "softcy" to "ь", "Ecy" to "Э", "ecy" to "э",
        "YUcy" to "Ю", "yucy" to "ю", "YAcy" to "Я", "yacy" to "я",
        "Iukcy" to "І", "iukcy" to "і", "YEcy" to "Є", "yecy" to "є",
        "YIcy" to "Ї", "yicy" to "ї", "Ubrcy" to "Ў", "ubrcy" to "ў",
        "Ggcy" to "Ґ", "ggcy" to "ґ",
        "period" to ".", "comma" to ",", "lpar" to "(", "rpar" to ")",
        "excl" to "!", "quest" to "?", "colon" to ":", "semi" to ";",
        "apos" to "'", "quot" to "\"", "sol" to "/", "bsol" to "\\",
        "lowbar" to "_", "lcub" to "{", "rcub" to "}", "lbrack" to "[",
        "rbrack" to "]", "ast" to "*", "num" to "#", "percnt" to "%",
        "plus" to "+", "equals" to "=", "dollar" to "$", "commat" to "@",
        "Hat" to "^", "tilde" to "~", "nbsp" to " ", "mdash" to "—",
        "ndash" to "–", "laquo" to "«", "raquo" to "»", "ldquo" to "“",
        "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’", "hellip" to "...",
        "bull" to "•", "middot" to "·"
    )
    val regex = Regex("&([a-zA-Z0-9#x]+);")
    return regex.replace(html) { matchResult ->
        val entityBody = matchResult.groupValues[1]
        val mappedValue = customDecoderMap[entityBody]
        if (mappedValue != null) {
            mappedValue
        } else if (entityBody.startsWith("#")) {
            try {
                val isHex = entityBody.startsWith("#x", ignoreCase = true)
                val code = if (isHex) entityBody.substring(2).toInt(16) else entityBody.substring(1).toInt()
                code.toChar().toString()
            } catch (e: Exception) { matchResult.value }
        } else {
            val lower = entityBody.lowercase()
            if (lower == "lt" || lower == "gt" || lower == "amp" || lower == "quot" || lower == "apos" || lower == "nbsp") {
                matchResult.value
            } else {
                try {
                    val decoded = android.text.Html.fromHtml("&" + entityBody + ";", android.text.Html.FROM_HTML_MODE_LEGACY).toString()
                    if (decoded.isNotEmpty() && decoded != "&" + entityBody + ";") decoded else matchResult.value
                } catch (e: Exception) { matchResult.value }
            }
        }
    }
}



fun parseHtmlToEditorBlocks(
    html: String,
    context: android.content.Context,
    titleId: Long? = null
): List<EditorBlock> {
    val blocks = mutableListOf<EditorBlock>()
    if (html.isBlank()) {
        blocks.add(EditorBlock.Text(""))
        return blocks
    }
    
    // Regex matches <div...><img.../></div> or standalone <img.../> tags
    val regex = Regex("(<div(?:\\s+[^>]*)*>\\s*)?<img\\s+[^>]*src=\"([^\"]+)\"[^>]*>(\\s*</div>)?", RegexOption.IGNORE_CASE)
    
    var lastIdx = 0
    val matches = regex.findAll(html).toList()
    
    for (match in matches) {
        val start = match.range.first
        val end = match.range.last + 1
        
        if (start > lastIdx) {
            val textBefore = html.substring(lastIdx, start)
            val htmlTrimmed = textBefore.trim()
            if (htmlTrimmed.isNotEmpty()) {
                blocks.add(EditorBlock.Text(htmlTrimmed))
            }
        }
        
        val src = match.groupValues[2]
        val rawTag = match.value
        
        // Resolve path via EpubProcessor
        val resolvedPath = EpubProcessor.resolveLocalImagePath(context, src, titleId)
        val localPath = if (resolvedPath != null) {
            resolvedPath
        } else {
            val mediaDir = File(context.filesDir, "epub_media")
            val imageFile = File(mediaDir, src)
            if (imageFile.exists()) imageFile.absolutePath else ""
        }
        
        blocks.add(EditorBlock.Image(fileName = src, localPath = localPath, rawTag = rawTag))
        lastIdx = end
    }
    
    if (lastIdx < html.length) {
        val textAfter = html.substring(lastIdx)
        val htmlTrimmed = textAfter.trim()
        if (htmlTrimmed.isNotEmpty()) {
            blocks.add(EditorBlock.Text(htmlTrimmed))
        }
    }
    
    if (blocks.isEmpty()) {
        blocks.add(EditorBlock.Text(""))
    }
    
    return blocks
}

fun serializeEditorBlocksToHtml(
    blocks: List<EditorBlock>,
    blockTextFieldValues: Map<String, TextFieldValue>
): String {
    val sb = StringBuilder()
    for (block in blocks) {
        when (block) {
            is EditorBlock.Text -> {
                val latestText = blockTextFieldValues[block.id]?.annotatedString ?: com.aistudio.epubedit.kqptxy.util.RichTextUtil.htmlToAnnotatedString(block.content)
                val originalAnn = com.aistudio.epubedit.kqptxy.util.RichTextUtil.htmlToAnnotatedString(block.content)
                val valHtml = if (latestText == originalAnn) {
                    block.content
                } else {
                    com.aistudio.epubedit.kqptxy.util.RichTextUtil.annotatedStringToHtml(latestText)
                }
                if (valHtml.isNotEmpty() && valHtml != "<p></p>") {
                    sb.append(valHtml).append("\n")
                }
            }
            is EditorBlock.Image -> {
                sb.append(block.rawTag).append("\n")
            }
        }
    }
    return sb.toString().trim()
}

fun isCursorOnEmptyLine(tf: TextFieldValue): Boolean {
    if (!tf.selection.collapsed) return false
    val text = tf.text
    val cursor = tf.selection.start
    
    // Find start of current line
    var startOfLine = cursor
    while (startOfLine > 0 && text[startOfLine - 1] != '\n') {
        startOfLine--
    }
    
    // Find end of current line
    var endOfLine = cursor
    while (endOfLine < text.length && text[endOfLine] != '\n') {
        endOfLine++
    }
    
    val lineText = text.substring(startOfLine, endOfLine)
    return lineText.trim().isEmpty()
}

fun extractBodyForDisplay(html: String): String {
    if (!html.contains("<body", ignoreCase = true)) return html

    // Ищем открывающий <body ...>
    val bodyOpenRegex = Regex("<body(?:\\s+[^>]*)?>", RegexOption.IGNORE_CASE)
    val openMatch = bodyOpenRegex.find(html) ?: return html

    val contentStart = openMatch.range.last + 1

    // Ищем закрывающий </body> с конца (lastIndexOf)
    val closeTag = "</body>"
    val contentEnd = html.lowercase().lastIndexOf(closeTag)

    return if (contentEnd > contentStart) {
        html.substring(contentStart, contentEnd).trim()
    } else {
        // </body> не найден — берём всё от <body> до конца, убираем лишние теги
        html.substring(contentStart)
            .replace(Regex("</html>\\s*$", RegexOption.IGNORE_CASE), "")
            .trim()
    }
}

fun handleHtmlAutoClose(oldState: TextFieldValue, newState: TextFieldValue): TextFieldValue {
    val oldText = oldState.text
    val newText = newState.text
    val selection = newState.selection
    
    // Only trigger if we added exactly one character and it is '>'
    if (newText.length == oldText.length + 1 && selection.collapsed) {
        val cursor = selection.start
        if (cursor > 0 && newText[cursor - 1] == '>') {
            // Find the corresponding '<'
            var openIdx = -1
            for (i in cursor - 2 downTo 0) {
                val c = newText[i]
                if (c == '<') {
                    openIdx = i
                    break
                }
                if (c == '>') {
                    // Encountered a previous tag boundary, so this > is not part of a clean tag
                    break
                }
            }
            if (openIdx != -1) {
                // We have a possible tag from openIdx to cursor - 1
                val tagContent = newText.substring(openIdx + 1, cursor - 1).trim()
                if (tagContent.isNotEmpty() && !tagContent.startsWith("/") && !tagContent.endsWith("/")) {
                    // Extract tag name (first word before space)
                    val tagName = tagContent.split(Regex("\\s+"))[0]
                    // Check if tagName is simple word composed of standard characters
                    if (tagName.isNotEmpty() && tagName.all { it.isLetterOrDigit() }) {
                        val closingTag = "</$tagName>"
                        val prefix = newText.substring(0, cursor)
                        val suffix = newText.substring(cursor)
                        val updatedText = prefix + closingTag + suffix
                        // Cursor should stay where it is (between opening and closing tags)
                        return TextFieldValue(updatedText, androidx.compose.ui.text.TextRange(cursor))
                    }
                }
            }
        }
    }
    return newState
}

fun findPlainOffsetFromHtmlOffset(html: String, targetHtmlOffset: Int): Int {
    var plainCount = 0
    var inTag = false
    var htmlIdx = 0
    val limit = targetHtmlOffset.coerceAtMost(html.length)
    
    while (htmlIdx < limit) {
        val c = html[htmlIdx]
        if (c == '<') {
            inTag = true
            htmlIdx++
        } else if (c == '>') {
            inTag = false
            htmlIdx++
        } else if (inTag) {
            htmlIdx++
        } else {
            if (c == '&') {
                val semiIdx = html.indexOf(';', htmlIdx)
                if (semiIdx != -1 && semiIdx - htmlIdx in 2..7) {
                    plainCount++
                    htmlIdx = semiIdx + 1
                    continue
                }
            }
            plainCount++
            htmlIdx++
        }
    }
    return plainCount
}

fun findHtmlOffsetFromPlainOffset(html: String, targetPlainOffset: Int): Int {
    var plainCount = 0
    var inTag = false
    var htmlIdx = 0
    val len = html.length
    
    while (htmlIdx < len && plainCount < targetPlainOffset) {
        val c = html[htmlIdx]
        if (c == '<') {
            inTag = true
            htmlIdx++
        } else if (c == '>') {
            inTag = false
            htmlIdx++
        } else if (inTag) {
            htmlIdx++
        } else {
            if (c == '&') {
                val semiIdx = html.indexOf(';', htmlIdx)
                if (semiIdx != -1 && semiIdx - htmlIdx in 2..7) {
                    plainCount++
                    htmlIdx = semiIdx + 1
                    continue
                }
            }
            plainCount++
            htmlIdx++
        }
    }
    return htmlIdx
}

fun mapPlainOffsetToBlock(
    blocks: List<EditorBlock>,
    blockTextFieldValues: Map<String, TextFieldValue>,
    targetPlainOffset: Int
): Pair<Int, Int>? {
    var accumulatedPlain = 0
    for ((index, block) in blocks.withIndex()) {
        if (block is EditorBlock.Text) {
            val tf = blockTextFieldValues[block.id] ?: TextFieldValue(annotatedString = com.aistudio.epubedit.kqptxy.util.RichTextUtil.htmlToAnnotatedString(block.content))
            val textLen = tf.text.length
            
            if (targetPlainOffset <= accumulatedPlain + textLen) {
                val localOffset = (targetPlainOffset - accumulatedPlain).coerceIn(0, textLen)
                return Pair(index, localOffset)
            }
            accumulatedPlain += textLen
        }
    }
    
    val lastTextIdx = blocks.indexOfLast { it is EditorBlock.Text }
    if (lastTextIdx != -1) {
        val block = blocks[lastTextIdx] as EditorBlock.Text
        val tf = blockTextFieldValues[block.id]
        val textLen = tf?.text?.length ?: 0
        return Pair(lastTextIdx, textLen)
    }
    return null
}

@Composable
fun TextBlockItem(
    block: EditorBlock.Text,
    index: Int,
    lang: String,
    blockTextFieldValues: Map<String, TextFieldValue>,
    onFocusGain: () -> Unit,
    onValueChange: (TextFieldValue) -> Unit
) {
    val initialTfValue = blockTextFieldValues[block.id] ?: TextFieldValue(annotatedString = com.aistudio.epubedit.kqptxy.util.RichTextUtil.htmlToAnnotatedString(block.content))
    var tfValue by remember(block.id) { mutableStateOf(initialTfValue) }
    
    LaunchedEffect(blockTextFieldValues[block.id]) {
        val parentVal = blockTextFieldValues[block.id]
        if (parentVal != null && parentVal.annotatedString != tfValue.annotatedString) {
            tfValue = parentVal
        }
    }
    
    OutlinedTextField(
        value = tfValue,
        onValueChange = { newVal ->
            val adjusted = newVal.copy(
                annotatedString = com.aistudio.epubedit.kqptxy.util.RichTextUtil.adjustSpans(tfValue.annotatedString, newVal.text)
            )
            tfValue = adjusted
            onValueChange(adjusted)
        },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focusState ->
                if (focusState.isFocused) {
                    onFocusGain()
                }
            },
        textStyle = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurface,
            lineHeight = 24.sp
        ),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent
        ),
        placeholder = {
            Text(
                Loc.t("enter_text_here", lang),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
            )
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun EditorScreen(
    viewModel: BookViewModel,
    chapterId: Long,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val lazyListState = rememberLazyListState()

    LaunchedEffect(chapterId) {
        viewModel.selectEditingChapter(chapterId)
    }

    val chapter by viewModel.editingChapter.collectAsState()
    val lang by viewModel.currentLanguage.collectAsState()
    val htmlAutoCloseEnabled by viewModel.htmlAutoCloseEnabled.collectAsState()

    if (chapter == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        return
    }

    val currentChapter = chapter!!

    var initializedChapterId by remember { mutableStateOf<Long?>(null) }
    var chapterTitle by remember(chapterId) { mutableStateOf("") }
    
    val prefs = remember { context.getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE) }
    val convertSystem = remember { prefs.getBoolean("pref_convert_epub_system", false) }
    
    // Tracks editing mode (false = Visual/Plain Text blocks, true = Raw HTML)
    var isHtmlMode by remember(chapterId) { mutableStateOf(!convertSystem) }
    
    // Is full screen mode active
    var isFullscreen by remember { mutableStateOf(false) }

    var htmlPrefix by remember(chapterId) { mutableStateOf("") }
    var htmlSuffix by remember(chapterId) { mutableStateOf("") }

    // Unified blocks state in Visual mode
    val editorBlocks = remember { mutableStateListOf<EditorBlock>() }
    val blockTextFieldValues = remember { mutableStateMapOf<String, TextFieldValue>() }
    var activeBlockIndex by remember { mutableStateOf<Int?>(null) }
    
    // HTML Text editor state and Image delete states
    var htmlTextState by remember(chapterId) {
        mutableStateOf(TextFieldValue(""))
    }
    
    var imageToDeleteIndex by remember { mutableStateOf<Int?>(null) }
    var showUnsavedChangesDialog by remember { mutableStateOf(false) }
    
    // Formatting dialog and menu states
    var showLinkDialog by remember { mutableStateOf(false) }
    var linkUrlInput by remember { mutableStateOf("https://") }
    var showTextColorDialog by remember { mutableStateOf(false) }
    var showBgColorDialog by remember { mutableStateOf(false) }
    var showHeadingDialog by remember { mutableStateOf(false) }
    var showAlignmentDialog by remember { mutableStateOf(false) }

    // Load initial blocks
    LaunchedEffect(chapterId, currentChapter) {
        if (currentChapter.id == chapterId && initializedChapterId != chapterId) {
            initializedChapterId = chapterId
            val raw = currentChapter.contentHtml
            val displayHtmlFromDb = currentChapter.displayHtml
            
            var bodyContent = displayHtmlFromDb ?: extractBodyForDisplay(raw)
            
            // Still need prefix/suffix for merging back if displayHtml was extracted from a full file
            val bodyStartMatch = Regex("<body(?:\\s+[^>]*)?>", RegexOption.IGNORE_CASE).find(raw)
            val bodyCloseIdx = raw.lowercase().lastIndexOf("</body>")

            if (bodyStartMatch != null && bodyCloseIdx != -1 && bodyCloseIdx > bodyStartMatch.range.last) {
                htmlPrefix = raw.substring(0, bodyStartMatch.range.last + 1)
                htmlSuffix = raw.substring(bodyCloseIdx)  // включает </body></html>
            } else if (bodyStartMatch != null) {
                // <body> есть, но </body> не найден — берём до конца
                htmlPrefix = raw.substring(0, bodyStartMatch.range.last + 1)
                htmlSuffix = "\n</body>\n</html>"  // добавляем закрывающие теги
            } else {
                htmlPrefix = ""
                htmlSuffix = ""
            }

            val parsed = withContext(Dispatchers.IO) {
                parseHtmlToEditorBlocks(bodyContent, context, currentChapter.titleId)
            }
            editorBlocks.clear()
            editorBlocks.addAll(parsed)
            blockTextFieldValues.clear()
            parsed.forEach { b ->
                if (b is EditorBlock.Text) {
                    val ann = com.aistudio.epubedit.kqptxy.util.RichTextUtil.htmlToAnnotatedString(b.content)
                    blockTextFieldValues[b.id] = TextFieldValue(annotatedString = ann)
                }
            }
            val (beautified, _) = com.aistudio.epubedit.kqptxy.util.RichTextUtil.beautifyHtmlWithCursor(bodyContent, 0)
            htmlTextState = TextFieldValue(if (raw == "<p>Введите...</p>" || raw.contains("Введите текст вашей новой главы")) "" else beautified)
            chapterTitle = currentChapter.title
        }
    }

    // Synchronize mode contents upon toggle
    val toggleMode = {
        if (isHtmlMode) {
            // HTML to Visual Blocks
            val htmlText = htmlTextState.text
            val htmlCursor = htmlTextState.selection.start
            
            var bodyContent = htmlText
            var localCursor = htmlCursor

            val bodyStartRegex = Regex("<\\s*(?:[a-zA-Z0-9]+:)?body[^>]*>", RegexOption.IGNORE_CASE)
            val bodyEndRegex = Regex("</\\s*(?:[a-zA-Z0-9]+:)?body\\s*>", RegexOption.IGNORE_CASE)
            val startMatch = bodyStartRegex.find(htmlText)
            var endMatch = bodyEndRegex.findAll(htmlText).lastOrNull()
            
            if (endMatch == null) {
                val htmlEndRegex = Regex("</\\s*(?:[a-zA-Z0-9]+:)?html\\s*>", RegexOption.IGNORE_CASE)
                endMatch = htmlEndRegex.findAll(htmlText).lastOrNull()
            }

            if (startMatch != null) {
                val startContentIdx = startMatch.range.last + 1
                val endContentIdx = endMatch?.range?.first ?: htmlText.length
                
                if (startContentIdx <= endContentIdx) {
                    htmlPrefix = htmlText.substring(0, startContentIdx)
                    bodyContent = htmlText.substring(startContentIdx, endContentIdx)
                    htmlSuffix = if (endMatch != null) htmlText.substring(endContentIdx) else ""
                    
                    localCursor = htmlCursor - startContentIdx
                    if (localCursor < 0) localCursor = 0
                    if (localCursor > bodyContent.length) localCursor = bodyContent.length
                }
            } else {
                // If the user didn't type <body>, they are editing the body content.
                // Do not clear the existing htmlPrefix or htmlSuffix! Maintain the envelope.
                bodyContent = htmlText
                localCursor = htmlCursor
            }
            
            val parsed = parseHtmlToEditorBlocks(bodyContent, context, currentChapter.titleId)
            editorBlocks.clear()
            editorBlocks.addAll(parsed)
            blockTextFieldValues.clear()
            parsed.forEach { b ->
                if (b is EditorBlock.Text) {
                    val ann = com.aistudio.epubedit.kqptxy.util.RichTextUtil.htmlToAnnotatedString(b.content)
                    blockTextFieldValues[b.id] = TextFieldValue(annotatedString = ann)
                }
            }
            
            // Map cursor from HTML to Visual
            val plainOffset = findPlainOffsetFromHtmlOffset(bodyContent, localCursor)
            val blockMapping = mapPlainOffsetToBlock(parsed, blockTextFieldValues, plainOffset)
            if (blockMapping != null) {
                val (blockIdx, localOffsetInner) = blockMapping
                activeBlockIndex = blockIdx
                val block = parsed[blockIdx]
                if (block is EditorBlock.Text) {
                    val currentTf = blockTextFieldValues[block.id]
                    if (currentTf != null) {
                        blockTextFieldValues[block.id] = currentTf.copy(selection = androidx.compose.ui.text.TextRange(localOffsetInner))
                    }
                }
                
                // Scroll Visual list to active block with delay to allow rendering
                coroutineScope.launch {
                    delay(100L)
                    try {
                        lazyListState.animateScrollToItem(blockIdx)
                    } catch (e: Exception) {
                        try {
                            lazyListState.scrollToItem(blockIdx)
                        } catch (ex: Exception) {}
                    }
                }
            }
            
            isHtmlMode = false
        } else {
            // Visual Blocks to HTML
            val activeIdx = activeBlockIndex
            val convertedBodyHtml = serializeEditorBlocksToHtml(editorBlocks, blockTextFieldValues)
            
            val fullHtml = if (htmlPrefix.isNotEmpty() || htmlSuffix.isNotEmpty()) {
                htmlPrefix + convertedBodyHtml + htmlSuffix
            } else {
                convertedBodyHtml
            }
            
            var bodyOffsetHtml = 0
            if (activeIdx != null && activeIdx in editorBlocks.indices) {
                val activeBlock = editorBlocks[activeIdx]
                if (activeBlock is EditorBlock.Text) {
                    val tf = blockTextFieldValues[activeBlock.id] ?: TextFieldValue("")
                    val blockCursor = tf.selection.start
                    
                    var accumulatedPlain = 0
                    for (i in 0 until activeIdx) {
                        when (val b = editorBlocks[i]) {
                            is EditorBlock.Text -> {
                                val tfVal = blockTextFieldValues[b.id]
                                val tLen = tfVal?.text?.length ?: b.content.length
                                accumulatedPlain += tLen + 1 // +1 for newline between blocks
                            }
                            is EditorBlock.Image -> {
                                accumulatedPlain += 1 // image block tag itself has 0 plain count but \n adds 1 plain char
                            }
                        }
                    }
                    accumulatedPlain += blockCursor
                    
                    bodyOffsetHtml = findHtmlOffsetFromPlainOffset(convertedBodyHtml, accumulatedPlain)
                } else {
                    bodyOffsetHtml = convertedBodyHtml.length
                }
            } else {
                bodyOffsetHtml = convertedBodyHtml.length
            }
            
            val (beautifiedFull, _) = com.aistudio.epubedit.kqptxy.util.RichTextUtil.beautifyHtmlWithCursor(fullHtml, 0)
            htmlTextState = TextFieldValue(beautifiedFull, androidx.compose.ui.text.TextRange(beautifiedFull.length))
            
            isHtmlMode = true
        }
    }

    val currentContentText = {
        if (isHtmlMode) htmlTextState.text else {
            val convertedBodyHtml = serializeEditorBlocksToHtml(editorBlocks, blockTextFieldValues)
            if (htmlPrefix.isNotEmpty() || htmlSuffix.isNotEmpty()) {
                htmlPrefix + convertedBodyHtml + htmlSuffix
            } else {
                convertedBodyHtml
            }
        }
    }

    val totalWords by remember {
        derivedStateOf {
            val text = if (isHtmlMode) {
                htmlTextState.text
            } else {
                serializeEditorBlocksToHtml(editorBlocks, blockTextFieldValues)
            }
            WordStatsHelper.countWords(text)
        }
    }

    val applyFormatAction = { tagOpen: String, tagClose: String ->
        if (isHtmlMode) {
            val tf = htmlTextState
            val text = tf.text
            val selection = tf.selection
            val newText: String
            val newCursorIdx: Int
            if (!selection.collapsed) {
                val selectedText = text.substring(selection.start, selection.end)
                newText = text.substring(0, selection.start) + tagOpen + selectedText + tagClose + text.substring(selection.end)
                newCursorIdx = selection.start + tagOpen.length + selectedText.length + tagClose.length
            } else {
                val cursor = selection.start
                newText = text.substring(0, cursor) + tagOpen + tagClose + text.substring(cursor)
                newCursorIdx = cursor + tagOpen.length
            }
            htmlTextState = TextFieldValue(newText, androidx.compose.ui.text.TextRange(newCursorIdx))
        } else {
            val idx = activeBlockIndex ?: -1
            if (idx >= 0 && idx < editorBlocks.size) {
                val block = editorBlocks[idx]
                if (block is EditorBlock.Text) {
                    val currentTf = blockTextFieldValues[block.id] ?: TextFieldValue(annotatedString = com.aistudio.epubedit.kqptxy.util.RichTextUtil.htmlToAnnotatedString(block.content))
                    val selection = currentTf.selection
                    if (!selection.collapsed) {
                        val start = selection.min
                        val end = selection.max
                        
                        val newAnn = androidx.compose.ui.text.buildAnnotatedString {
                            append(currentTf.annotatedString)
                            
                            when (tagOpen.lowercase()) {
                                "<b>", "<strong>" -> addStyle(androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), start, end)
                                "<i>", "<em>" -> addStyle(androidx.compose.ui.text.SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic), start, end)
                                "<u>" -> addStyle(androidx.compose.ui.text.SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline), start, end)
                                "<s>", "<strike>", "<del>" -> addStyle(androidx.compose.ui.text.SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough), start, end)
                                "<sup>" -> addStyle(androidx.compose.ui.text.SpanStyle(baselineShift = androidx.compose.ui.text.style.BaselineShift.Superscript), start, end)
                                "<sub>" -> addStyle(androidx.compose.ui.text.SpanStyle(baselineShift = androidx.compose.ui.text.style.BaselineShift.Subscript), start, end)
                                "<h1>" -> addStyle(androidx.compose.ui.text.SpanStyle(fontSize = 1.5f.em), start, end)
                                "<h2>" -> addStyle(androidx.compose.ui.text.SpanStyle(fontSize = 1.4f.em), start, end)
                                "<h3>" -> addStyle(androidx.compose.ui.text.SpanStyle(fontSize = 1.3f.em), start, end)
                                "<h4>" -> addStyle(androidx.compose.ui.text.SpanStyle(fontSize = 1.2f.em), start, end)
                                "<h5>" -> addStyle(androidx.compose.ui.text.SpanStyle(fontSize = 1.1f.em), start, end)
                                "<li>" -> addStringAnnotation(tag = "LIST_ITEM", annotation = "bullet", start = start, end = end)
                                "<blockquote>" -> {
                                    addStringAnnotation(tag = "QUOTE", annotation = "quote", start = start, end = end)
                                    addStyle(androidx.compose.ui.text.SpanStyle(background = androidx.compose.ui.graphics.Color.LightGray.copy(alpha = 0.2f), fontStyle = androidx.compose.ui.text.font.FontStyle.Italic), start, end)
                                }
                                else -> {
                                    val tagLower = tagOpen.lowercase()
                                    if (tagLower.startsWith("<span style=\"color:")) {
                                        try {
                                            val colorHex = tagOpen.substringAfter("color:").substringBefore('"').trim().removeSuffix(";").trim()
                                            val colorInt = android.graphics.Color.parseColor(colorHex)
                                            addStyle(androidx.compose.ui.text.SpanStyle(color = androidx.compose.ui.graphics.Color(colorInt)), start, end)
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        }
                                    } else if (tagLower.startsWith("<span style=\"background-color:")) {
                                        try {
                                            val bgHex = tagOpen.substringAfter("background-color:").substringBefore('"').trim().removeSuffix(";").trim()
                                            val colorInt = android.graphics.Color.parseColor(bgHex)
                                            addStyle(androidx.compose.ui.text.SpanStyle(background = androidx.compose.ui.graphics.Color(colorInt)), start, end)
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        }
                                    } else if (tagLower.startsWith("<a href=")) {
                                        try {
                                            val url = tagOpen.substringAfter("href=\"").substringBefore('"')
                                            addStringAnnotation(tag = "URL", annotation = url, start = start, end = end)
                                            addStyle(androidx.compose.ui.text.SpanStyle(color = androidx.compose.ui.graphics.Color.Blue, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline), start, end)
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        }
                                    } else if (tagLower.startsWith("<div align=")) {
                                        try {
                                            val alignDir = tagOpen.substringAfter("align=\"").substringBefore('"')
                                            addStringAnnotation(tag = "ALIGN", annotation = alignDir, start = start, end = end)
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        }
                                    }
                                }
                            }
                        }
                        
                        val updatedTf = currentTf.copy(annotatedString = newAnn)
                        blockTextFieldValues[block.id] = updatedTf
                        editorBlocks[idx] = EditorBlock.Text(com.aistudio.epubedit.kqptxy.util.RichTextUtil.annotatedStringToHtml(newAnn), block.id)
                    } else {
                        Toast.makeText(context, Loc.t("select_text_first", lang), Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                Toast.makeText(context, Loc.t("focus_text_block_first", lang), Toast.LENGTH_SHORT).show()
            }
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val cachedPath = saveIllustrationLocally(context, it, currentChapter.titleId)
            if (cachedPath != null) {
                val fileName = File(cachedPath).name
                val imgTag = "<div style=\"text-align:center; margin:12px 0;\"><img src=\"$fileName\" style=\"max-width:100%;\" /></div>"
                
                if (isHtmlMode) {
                    val tf = htmlTextState
                    val text = tf.text
                    val selection = tf.selection
                    val doubleNewLineTag = "\n" + imgTag + "\n"
                    val newText = text.substring(0, selection.start) + doubleNewLineTag + text.substring(selection.end)
                    htmlTextState = TextFieldValue(newText, androidx.compose.ui.text.TextRange(selection.start + doubleNewLineTag.length))
                } else {
                    // Split the text block at the empty cursor line
                    val focusedIdx = activeBlockIndex ?: 0
                    if (focusedIdx >= 0 && focusedIdx < editorBlocks.size) {
                        val block = editorBlocks[focusedIdx]
                        if (block is EditorBlock.Text) {
                            val tf = blockTextFieldValues[block.id] ?: TextFieldValue(annotatedString = com.aistudio.epubedit.kqptxy.util.RichTextUtil.htmlToAnnotatedString(block.content))
                            val text = tf.text
                            val cursor = tf.selection.start
                            
                            val textBefore = text.substring(0, cursor).trimEnd('\n')
                            val textAfter = text.substring(cursor).trimStart('\n')
                            
                            editorBlocks.removeAt(focusedIdx)
                            
                            var insertPosition = focusedIdx
                            if (textBefore.isNotEmpty()) {
                                val annBefore = tf.annotatedString.subSequence(0, cursor)
                                val htmlBefore = com.aistudio.epubedit.kqptxy.util.RichTextUtil.annotatedStringToHtml(annBefore)
                                editorBlocks.add(insertPosition, EditorBlock.Text(htmlBefore, block.id))
                                blockTextFieldValues[block.id] = TextFieldValue(annotatedString = annBefore, selection = androidx.compose.ui.text.TextRange(annBefore.length))
                                insertPosition++
                            }
                            
                            val imgBlock = EditorBlock.Image(fileName = fileName, localPath = cachedPath, rawTag = imgTag)
                            editorBlocks.add(insertPosition, imgBlock)
                            insertPosition++
                            
                            val annAfter = tf.annotatedString.subSequence(cursor, tf.text.length)
                            val htmlAfter = com.aistudio.epubedit.kqptxy.util.RichTextUtil.annotatedStringToHtml(annAfter)
                            val afterBlock = EditorBlock.Text(htmlAfter)
                            editorBlocks.add(insertPosition, afterBlock)
                            blockTextFieldValues[afterBlock.id] = TextFieldValue(annotatedString = annAfter, selection = androidx.compose.ui.text.TextRange(0))
                            
                            activeBlockIndex = insertPosition
                        }
                    } else {
                        // Just append
                        val imgBlock = EditorBlock.Image(fileName = fileName, localPath = cachedPath, rawTag = imgTag)
                        editorBlocks.add(imgBlock)
                        val afterBlock = EditorBlock.Text("")
                        editorBlocks.add(afterBlock)
                        blockTextFieldValues[afterBlock.id] = TextFieldValue("")
                    }
                }
                Toast.makeText(context, Loc.t("illustration_added", lang), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, Loc.t("illustration_error", lang), Toast.LENGTH_SHORT).show()
            }
        }
    }

    val performSave = {
        val editedBody = if (isHtmlMode) {
            val full = htmlTextState.text
            // Используем ту же надёжную функцию что и при инициализации
            if (full.contains("<body", ignoreCase = true)) {
                extractBodyForDisplay(full)
            } else {
                full
            }
        } else {
            serializeEditorBlocksToHtml(editorBlocks, blockTextFieldValues)
        }

        val finalFullHtml = if (htmlPrefix.isNotEmpty() && htmlSuffix.isNotEmpty()) {
            // Финальная защита: проверяем что editedBody не содержит html-обёртку
            // (на случай если beautifyHtml вернул полный файл в htmlTextState)
            val cleanBody = if (editedBody.contains("<html", ignoreCase = true) ||
                                editedBody.contains("</body>", ignoreCase = true) ||
                                editedBody.contains("</html>", ignoreCase = true)) {
                extractBodyForDisplay(editedBody)
            } else {
                editedBody
            }
            "$htmlPrefix\n$cleanBody\n$htmlSuffix"
        } else {
            editedBody
        }

        viewModel.updateChapterContent(
            chapterId = chapterId,
            title = chapterTitle.trim(),
            contentHtml = finalFullHtml,
            previewImagePath = currentChapter.previewImagePath,
            displayHtml = editedBody
        )
        Toast.makeText(context, Loc.t("chapter_saved", lang), Toast.LENGTH_SHORT).show()
    }

    BackHandler {
        val currentContent = currentContentText().trim()
        val originalContent = currentChapter.displayHtml?.trim() ?: extractBodyForDisplay(currentChapter.contentHtml).trim()
        
        if (chapterTitle.trim() != currentChapter.title || currentContent != originalContent) {
            showUnsavedChangesDialog = true
        } else {
            onBackClick()
        }
    }

    // Unsaved changes warning
    if (showUnsavedChangesDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedChangesDialog = false },
            title = { Text(Loc.t("unsaved_changes", lang)) },
            text = { Text(Loc.t("unsaved_changes_msg", lang)) },
            confirmButton = {
                Button(
                    onClick = {
                        showUnsavedChangesDialog = false
                        onBackClick()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(Loc.t("exit", lang)) }
            },
            dismissButton = {
                TextButton(onClick = { showUnsavedChangesDialog = false }) {
                    Text(Loc.t("cancel", lang), color = MaterialTheme.colorScheme.outline)
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }

    // Image Deletion dialog
    if (imageToDeleteIndex != null) {
        AlertDialog(
            onDismissRequest = { imageToDeleteIndex = null },
            title = { Text(Loc.t("delete_illustration", lang)) },
            text = { Text(Loc.t("delete_illustration_msg", lang)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val idx = imageToDeleteIndex!!
                        if (idx >= 0 && idx < editorBlocks.size) {
                            val prevIdx = idx - 1
                            val nextIdx = idx + 1
                            if (prevIdx >= 0 && nextIdx < editorBlocks.size && 
                                editorBlocks[prevIdx] is EditorBlock.Text && 
                                editorBlocks[nextIdx] is EditorBlock.Text) {
                                
                                val prevBlock = editorBlocks[prevIdx] as EditorBlock.Text
                                val nextBlock = editorBlocks[nextIdx] as EditorBlock.Text
                                
                                val prevText = prevBlock.content
                                val nextText = nextBlock.content
                                
                                val tfPrev = blockTextFieldValues[prevBlock.id] ?: TextFieldValue(annotatedString = com.aistudio.epubedit.kqptxy.util.RichTextUtil.htmlToAnnotatedString(prevBlock.content))
                                val tfNext = blockTextFieldValues[nextBlock.id] ?: TextFieldValue(annotatedString = com.aistudio.epubedit.kqptxy.util.RichTextUtil.htmlToAnnotatedString(nextBlock.content))
                                
                                val mergedAnn = if (tfPrev.text.isEmpty()) tfNext.annotatedString else if (tfNext.text.isEmpty()) tfPrev.annotatedString else {
                                    androidx.compose.ui.text.buildAnnotatedString {
                                        append(tfPrev.annotatedString)
                                        append("\n")
                                        append(tfNext.annotatedString)
                                    }
                                }
                                val mergedHtml = com.aistudio.epubedit.kqptxy.util.RichTextUtil.annotatedStringToHtml(mergedAnn)
                                
                                editorBlocks[prevIdx] = EditorBlock.Text(mergedHtml, prevBlock.id)
                                blockTextFieldValues[prevBlock.id] = TextFieldValue(annotatedString = mergedAnn, selection = androidx.compose.ui.text.TextRange(tfPrev.text.length))
                                
                                editorBlocks.removeAt(nextIdx)
                                editorBlocks.removeAt(idx)
                            } else {
                                editorBlocks.removeAt(idx)
                            }
                            
                            if (editorBlocks.isEmpty()) {
                                editorBlocks.add(EditorBlock.Text(""))
                            }
                        }
                        imageToDeleteIndex = null
                        Toast.makeText(context, Loc.t("illustration_deleted", lang), Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text(Loc.t("delete", lang), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { imageToDeleteIndex = null }) {
                    Text(Loc.t("cancel", lang), color = MaterialTheme.colorScheme.outline)
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }

    Scaffold(
        topBar = {
            if (!isFullscreen) {
                TopAppBar(
                    title = { Text(Loc.t("epub_editor", lang), fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (chapterTitle.trim() != currentChapter.title || currentContentText().trim() != currentChapter.contentHtml.trim()) {
                                showUnsavedChangesDialog = true
                            } else {
                                onBackClick()
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, Loc.t("back", lang), tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                    actions = {
                        Button(
                            onClick = { performSave() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text(Loc.t("save_upper", lang), fontWeight = FontWeight.Bold) }
                    }
                )
            }
        },
        bottomBar = {
            Card(
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                elevation = CardDefaults.cardElevation(8.dp),
                modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.ime)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { applyFormatAction("<b>", "</b>") }
                            ) {
                                Icon(Icons.Default.FormatBold, Loc.t("bold", lang))
                            }
                            IconButton(
                                onClick = { applyFormatAction("<i>", "</i>") }
                            ) {
                                Icon(Icons.Default.FormatItalic, Loc.t("italic", lang))
                            }
                            IconButton(
                                onClick = { applyFormatAction("<u>", "</u>") }
                            ) {
                                Icon(Icons.Default.FormatUnderlined, Loc.t("underlined", lang))
                            }
                            IconButton(
                                onClick = { applyFormatAction("<s>", "</s>") }
                            ) {
                                Icon(Icons.Default.FormatStrikethrough, Loc.t("strikethrough", lang))
                            }
                            IconButton(
                                onClick = { showHeadingDialog = true }
                            ) {
                                Icon(Icons.Default.Title, Loc.t("heading_choose", lang))
                            }
                            IconButton(
                                onClick = { applyFormatAction("<sup>", "</sup>") }
                            ) {
                                Text(
                                    text = "X²",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(
                                onClick = { applyFormatAction("<sub>", "</sub>") }
                            ) {
                                Text(
                                    text = "X₂",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(
                                onClick = { showTextColorDialog = true }
                            ) {
                                Icon(Icons.Default.FormatColorText, Loc.t("text_color", lang))
                            }
                            IconButton(
                                onClick = { showBgColorDialog = true }
                            ) {
                                Icon(Icons.Default.FormatColorFill, Loc.t("bg_color", lang))
                            }
                            IconButton(
                                onClick = { showLinkDialog = true }
                            ) {
                                Icon(Icons.Default.Link, Loc.t("link", lang))
                            }
                            IconButton(
                                onClick = { showAlignmentDialog = true }
                            ) {
                                Icon(Icons.Default.FormatAlignLeft, Loc.t("alignment", lang))
                            }
                            IconButton(
                                onClick = { applyFormatAction("<li>", "</li>") }
                            ) {
                                Icon(Icons.Default.FormatListBulleted, "List")
                            }
                            IconButton(
                                onClick = { applyFormatAction("<blockquote>", "</blockquote>") }
                            ) {
                                Icon(Icons.Default.FormatQuote, "Quote")
                            }
                            IconButton(
                                onClick = { applyFormatAction("<p>", "</p>") }
                            ) {
                                Icon(Icons.Default.Segment, Loc.t("paragraph", lang))
                            }
                            IconButton(onClick = {
                                val tf = if (isHtmlMode) {
                                    htmlTextState
                                } else {
                                    val idx = activeBlockIndex ?: 0
                                    if (idx >= 0 && idx < editorBlocks.size) {
                                        val block = editorBlocks[idx]
                                        if (block is EditorBlock.Text) {
                                            blockTextFieldValues[block.id] ?: TextFieldValue(annotatedString = com.aistudio.epubedit.kqptxy.util.RichTextUtil.htmlToAnnotatedString(block.content))
                                        } else {
                                            TextFieldValue("")
                                        }
                                    } else {
                                        TextFieldValue("")
                                    }
                                }
                                if (!isCursorOnEmptyLine(tf)) {
                                    Toast.makeText(context, Loc.t("illustration_empty_line_only", lang), Toast.LENGTH_LONG).show()
                                } else {
                                    imagePickerLauncher.launch("image/*")
                                }
                            }) {
                                Icon(Icons.Default.AddPhotoAlternate, Loc.t("insert_image", lang))
                            }
                            
                            if (isHtmlMode) {
                                IconButton(onClick = {
                                    val (formatted, newCursor) = com.aistudio.epubedit.kqptxy.util.RichTextUtil.beautifyHtmlWithCursor(
                                        htmlTextState.text,
                                        htmlTextState.selection.start
                                    )
                                    htmlTextState = TextFieldValue(
                                        text = formatted,
                                        selection = androidx.compose.ui.text.TextRange(newCursor)
                                    )
                                    Toast.makeText(context, Loc.t("beautify", lang), Toast.LENGTH_SHORT).show()
                                }) {
                                    Icon(Icons.Default.AutoFixHigh, Loc.t("beautify", lang), tint = MaterialTheme.colorScheme.tertiary)
                                }
                            }
                        }
                        
                        IconButton(onClick = { isFullscreen = !isFullscreen }) {
                            Icon(
                                if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, 
                                contentDescription = if (isFullscreen) Loc.t("exit_fullscreen", lang) else Loc.t("enter_fullscreen", lang),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(24.dp))
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .background(
                                        if (!isHtmlMode) MaterialTheme.colorScheme.primary else Color.Transparent,
                                        RoundedCornerShape(20.dp)
                                    )
                                    .clickable { if (isHtmlMode) toggleMode() }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    Loc.t("visual", lang),
                                    color = if (!isHtmlMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .background(
                                        if (isHtmlMode) MaterialTheme.colorScheme.primary else Color.Transparent,
                                        RoundedCornerShape(20.dp)
                                    )
                                    .clickable { if (!isHtmlMode) toggleMode() }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    Loc.t("html_code", lang),
                                    color = if (isHtmlMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        }
                        
                        if (isFullscreen) {
                            Button(
                                onClick = { performSave() },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                shape = RoundedCornerShape(50)
                            ) {
                                Icon(Icons.Default.Save, Loc.t("save", lang), modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(Loc.t("save", lang), fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            if (!isFullscreen) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(Loc.t("chapter_title", lang), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                OutlinedTextField(
                    value = chapterTitle,
                    onValueChange = { chapterTitle = it },
                    textStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
            } else {
                Spacer(modifier = Modifier.height(8.dp))
            }
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (isHtmlMode) Loc.t("chapter_html", lang) else Loc.t("chapter_text", lang), 
                    fontSize = 11.sp, 
                    fontWeight = FontWeight.Bold, 
                    color = if (isHtmlMode) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary
                )
                Text(Loc.t("words", lang) + ": $totalWords", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, fontWeight = FontWeight.Medium)
            }
            Spacer(modifier = Modifier.height(6.dp))
            
            // Clean text editor container
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(bottom = 12.dp)
                    .background(
                        if (isHtmlMode) Color(0xFF1E1E24) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        RoundedCornerShape(16.dp)
                    )
                    .border(
                        1.dp,
                        if (isHtmlMode) Color(0xFF2E2E38) else MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                        RoundedCornerShape(16.dp)
                    )
                    .padding(12.dp)
            ) {
                if (isHtmlMode) {
                    OutlinedTextField(
                        value = htmlTextState,
                        onValueChange = { newVal ->
                            val withAutoClose = if (htmlAutoCloseEnabled) {
                                handleHtmlAutoClose(htmlTextState, newVal)
                            } else {
                                newVal
                            }
                            
                            val autoIndented = com.aistudio.epubedit.kqptxy.util.RichTextUtil.handleAutoIndent(
                                htmlTextState.text,
                                withAutoClose.text,
                                withAutoClose.selection.start
                            )
                            
                            htmlTextState = if (autoIndented != null) {
                                val newSel = withAutoClose.selection.start + (autoIndented.length - withAutoClose.text.length)
                                TextFieldValue(autoIndented, androidx.compose.ui.text.TextRange(newSel))
                            } else {
                                withAutoClose
                            }
                        },
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            color = Color(0xFFE2E2E2)
                        ),
                        visualTransformation = com.aistudio.epubedit.kqptxy.util.RichTextUtil.HtmlSyntaxTransformation(),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        modifier = Modifier.fillMaxSize(),
                        placeholder = {
                            Text(
                                Loc.t("enter_html_here", lang),
                                color = Color.Gray
                            )
                        }
                    )
                } else {
                    // Visual Blocks List rendering text fields and images inline
                    LazyColumn(
                        state = lazyListState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(
                            items = editorBlocks,
                            key = { _, block -> block.id }
                        ) { index, block ->
                            when (block) {
                                is EditorBlock.Text -> {
                                    TextBlockItem(
                                        block = block,
                                        index = index,
                                        lang = lang,
                                        blockTextFieldValues = blockTextFieldValues,
                                        onFocusGain = {
                                            activeBlockIndex = index
                                        },
                                        onValueChange = { newVal ->
                                            val oldVal = blockTextFieldValues[block.id]
                                            blockTextFieldValues[block.id] = newVal
                                            if (oldVal == null || oldVal.text != newVal.text) {
                                                editorBlocks[index] = EditorBlock.Text(com.aistudio.epubedit.kqptxy.util.RichTextUtil.annotatedStringToHtml(newVal.annotatedString), block.id)
                                            }
                                        }
                                    )
                                }
                                is EditorBlock.Image -> {
                                    val isImageValid = block.localPath.isNotEmpty() && File(block.localPath).exists()
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 12.dp)
                                            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                                            .combinedClickable(
                                                onLongClick = {
                                                    imageToDeleteIndex = index
                                                },
                                                onClick = {}
                                            )
                                    ) {
                                        if (isImageValid) {
                                            AsyncImage(
                                                model = File(block.localPath),
                                                contentDescription = Loc.t("chapter_illustration", lang),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .heightIn(max = 280.dp)
                                                    .align(Alignment.Center)
                                                    .padding(8.dp),
                                                contentScale = ContentScale.Fit
                                            )
                                        } else {
                                            // Fallback/Error state beautifully styled
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(24.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.BrokenImage,
                                                    contentDescription = Loc.t("image_load_error", lang),
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(48.dp)
                                                )
                                                Spacer(modifier = Modifier.height(8.dp))
                                                Text(
                                                    Loc.t("image_not_found_local", lang),
                                                    color = MaterialTheme.colorScheme.error,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    block.fileName,
                                                    color = MaterialTheme.colorScheme.outline,
                                                    fontSize = 11.sp,
                                                    fontFamily = FontFamily.Monospace
                                                )
                                            }
                                        }
                                        
                                        // Trash delete button overlay at the top right
                                        IconButton(
                                            onClick = {
                                                imageToDeleteIndex = index
                                            },
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(6.dp)
                                                .background(
                                                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f),
                                                    shape = RoundedCornerShape(8.dp)
                                                )
                                                .size(36.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = Loc.t("delete_illustration_cd", lang),
                                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        
                                        // Information Badge at the bottom start or bottom end
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.BottomStart)
                                                .padding(8.dp)
                                                .background(
                                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                                                    shape = RoundedCornerShape(4.dp)
                                                )
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            val shortName = File(block.fileName).name
                                            Text(
                                                Loc.t("file_label", lang) + shortName,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            // Format overlay dialogs
            if (showLinkDialog) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showLinkDialog = false },
                    title = { Text(Loc.t("link", lang), fontWeight = FontWeight.Bold) },
                    text = {
                        Column {
                            Text(Loc.t("enter_url", lang), modifier = Modifier.padding(bottom = 8.dp))
                            OutlinedTextField(
                                value = linkUrlInput,
                                onValueChange = { linkUrlInput = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                shape = RoundedCornerShape(8.dp)
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                if (linkUrlInput.isNotBlank()) {
                                    applyFormatAction("<a href=\"$linkUrlInput\">", "</a>")
                                }
                                showLinkDialog = false
                            }
                        ) {
                            Text(Loc.t("create", lang), fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showLinkDialog = false }) {
                            Text(Loc.t("cancel", lang))
                        }
                    }
                )
            }

            if (showTextColorDialog) {
                val colorsList = listOf(
                    "#333333" to "Default",
                    "#E74C3C" to "Coral",
                    "#E67E22" to "Orange",
                    "#F1C40F" to "Yellow",
                    "#2ECC71" to "Green",
                    "#3498DB" to "Blue",
                    "#9B59B6" to "Purple",
                    "#FF6B81" to "Pink",
                    "#8D6E63" to "Brown",
                    "#7F8C8D" to "Grey"
                )
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showTextColorDialog = false },
                    title = { Text(Loc.t("text_color", lang), fontWeight = FontWeight.Bold) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                                columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(5),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.heightIn(max = 110.dp)
                            ) {
                                items(colorsList.size) { i ->
                                    val (hex, name) = colorsList[i]
                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .background(Color(android.graphics.Color.parseColor(hex)), androidx.compose.foundation.shape.CircleShape)
                                            .border(2.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), androidx.compose.foundation.shape.CircleShape)
                                            .clickable {
                                                applyFormatAction("<span style=\"color:$hex\">", "</span>")
                                                showTextColorDialog = false
                                            }
                                    )
                                }
                            }
                            
                            var customHex by remember { mutableStateOf("#") }
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(Loc.t("custom_hex", lang), style = MaterialTheme.typography.bodyMedium)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    OutlinedTextField(
                                        value = customHex,
                                        onValueChange = { input ->
                                            val filtered = input.uppercase().filter { it.isDigit() || it in 'A'..'F' || it == '#' }
                                            customHex = if (filtered.startsWith("#")) filtered else "#$filtered"
                                        },
                                        modifier = Modifier.weight(1f),
                                        placeholder = { Text("#000000") },
                                        singleLine = true,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    
                                    val parsedColor = remember(customHex) {
                                        try {
                                            val cleaned = if (customHex.startsWith("#")) customHex else "#$customHex"
                                            if (cleaned.length == 4 || cleaned.length == 7 || cleaned.length == 9) {
                                                Color(android.graphics.Color.parseColor(cleaned))
                                            } else {
                                                Color.Transparent
                                            }
                                        } catch (e: Exception) {
                                            Color.Transparent
                                        }
                                    }
                                    
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .background(parsedColor, androidx.compose.foundation.shape.CircleShape)
                                            .border(2.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), androidx.compose.foundation.shape.CircleShape)
                                    )
                                    
                                    Button(
                                        onClick = {
                                            val cleaned = if (customHex.startsWith("#")) customHex else "#$customHex"
                                            try {
                                                android.graphics.Color.parseColor(cleaned)
                                                applyFormatAction("<span style=\"color:$cleaned\">", "</span>")
                                                showTextColorDialog = false
                                            } catch (e: Exception) {
                                            }
                                        },
                                        enabled = parsedColor != Color.Transparent,
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(Loc.t("apply", lang), fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showTextColorDialog = false }) {
                            Text(Loc.t("close", lang))
                        }
                    }
                )
            }

            if (showBgColorDialog) {
                val bgColorsList = listOf(
                    "#FFFFFF" to "White",
                    "#F5F5F5" to "Alabaster",
                    "#FFF2CC" to "Sand",
                    "#E2EFDA" to "Mint",
                    "#FCE4D6" to "Peach",
                    "#D9E1F2" to "Frost",
                    "#EDEDED" to "Light Grey",
                    "#F9CB9C" to "Warm Yellow",
                    "#EA9999" to "Soft Red",
                    "#C9DAF8" to "Soft Blue"
                )
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showBgColorDialog = false },
                    title = { Text(Loc.t("bg_color", lang), fontWeight = FontWeight.Bold) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                                columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(5),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.heightIn(max = 110.dp)
                            ) {
                                items(bgColorsList.size) { i ->
                                    val (hex, name) = bgColorsList[i]
                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .background(Color(android.graphics.Color.parseColor(hex)), androidx.compose.foundation.shape.CircleShape)
                                            .border(2.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), androidx.compose.foundation.shape.CircleShape)
                                            .clickable {
                                                applyFormatAction("<span style=\"background-color:$hex\">", "</span>")
                                                showBgColorDialog = false
                                            }
                                    )
                                }
                            }
                            
                            var customHex by remember { mutableStateOf("#") }
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(Loc.t("custom_hex", lang), style = MaterialTheme.typography.bodyMedium)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    OutlinedTextField(
                                        value = customHex,
                                        onValueChange = { input ->
                                            val filtered = input.uppercase().filter { it.isDigit() || it in 'A'..'F' || it == '#' }
                                            customHex = if (filtered.startsWith("#")) filtered else "#$filtered"
                                        },
                                        modifier = Modifier.weight(1f),
                                        placeholder = { Text("#000000") },
                                        singleLine = true,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    
                                    val parsedColor = remember(customHex) {
                                        try {
                                            val cleaned = if (customHex.startsWith("#")) customHex else "#$customHex"
                                            if (cleaned.length == 4 || cleaned.length == 7 || cleaned.length == 9) {
                                                Color(android.graphics.Color.parseColor(cleaned))
                                            } else {
                                                Color.Transparent
                                            }
                                        } catch (e: Exception) {
                                            Color.Transparent
                                        }
                                    }
                                    
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .background(parsedColor, androidx.compose.foundation.shape.CircleShape)
                                            .border(2.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), androidx.compose.foundation.shape.CircleShape)
                                    )
                                    
                                    Button(
                                        onClick = {
                                            val cleaned = if (customHex.startsWith("#")) customHex else "#$customHex"
                                            try {
                                                android.graphics.Color.parseColor(cleaned)
                                                applyFormatAction("<span style=\"background-color:$cleaned\">", "</span>")
                                                showBgColorDialog = false
                                            } catch (e: Exception) {
                                            }
                                        },
                                        enabled = parsedColor != Color.Transparent,
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(Loc.t("apply", lang), fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showBgColorDialog = false }) {
                            Text(Loc.t("close", lang))
                        }
                    }
                )
            }

            if (showHeadingDialog) {
                val headings = listOf(
                    "h1" to "H1",
                    "h2" to "H2",
                    "h3" to "H3",
                    "h4" to "H4",
                    "h5" to "H5"
                )
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showHeadingDialog = false },
                    title = { Text(Loc.t("heading_choose", lang), fontWeight = FontWeight.Bold) },
                    text = {
                        Column(modifier = Modifier.padding(top = 8.dp)) {
                            headings.forEach { (tag, label) ->
                                TextButton(
                                    onClick = {
                                        applyFormatAction("<$tag>", "</$tag>")
                                        showHeadingDialog = false
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    val displayText = Loc.t("heading_styled", lang).replace("{num}", tag.removePrefix("h")) + " (${tag.uppercase()})"
                                    Text(
                                        text = displayText,
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier.fillMaxWidth(),
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Start
                                    )
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showHeadingDialog = false }) {
                            Text(Loc.t("cancel", lang))
                        }
                    }
                )
            }

            if (showAlignmentDialog) {
                val alignments = listOf(
                    "left" to Loc.t("align_left", lang),
                    "center" to Loc.t("align_center", lang),
                    "right" to Loc.t("align_right", lang),
                    "justify" to Loc.t("align_justify", lang)
                )
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showAlignmentDialog = false },
                    title = { Text(Loc.t("alignment", lang), fontWeight = FontWeight.Bold) },
                    text = {
                        Column(modifier = Modifier.padding(top = 8.dp)) {
                            alignments.forEach { (dir, label) ->
                                TextButton(
                                    onClick = {
                                        applyFormatAction("<div align=\"$dir\">", "</div>")
                                        showAlignmentDialog = false
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier.fillMaxWidth(),
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Start
                                    )
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showAlignmentDialog = false }) {
                            Text(Loc.t("cancel", lang))
                        }
                    }
                )
            }
        }
    }
}

fun saveIllustrationLocally(context: Context, uri: Uri, titleId: Long? = null): String? {
    val mediaDir = File(context.filesDir, "epub_media")
    val bookMediaDir = if (titleId != null) File(mediaDir, "book_$titleId") else mediaDir
    if (!bookMediaDir.exists()) bookMediaDir.mkdirs()
    var ext = "jpg"
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIdx != -1 && cursor.moveToFirst()) {
                val name = cursor.getString(nameIdx)
                if (!name.isNullOrEmpty()) {
                    ext = name.substringAfterLast(".", "jpg").trim().lowercase()
                }
            }
        }
    } catch (e: Exception) {
        Log.e("EditorScreen", "Error getting extension", e)
    }
    val destName = if (titleId != null) "${System.currentTimeMillis()}.${ext}" else "media_${System.currentTimeMillis()}.${ext}"
    val destFile = File(bookMediaDir, destName)
    return try {
        val ips = context.contentResolver.openInputStream(uri) ?: return null
        val ops = FileOutputStream(destFile)
        ips.use { input -> ops.use { output -> input.copyTo(output) } }
        destFile.absolutePath
    } catch (e: Exception) { null }
}
