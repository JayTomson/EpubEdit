package com.example.ui.screens

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.util.WordStatsHelper
import com.example.viewmodel.BookViewModel
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

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

fun htmlToPlainText(html: String): String {
    if (html.isBlank()) return ""
    var text = html
    // Replace <p> tags with space wrappers, and close tags with newlines
    text = text.replace(Regex("(?i)<p(?:\\s+[^>]*)?>"), "")
    text = text.replace(Regex("(?i)</p>"), "\n\n")
    
    // Convert <br> tags cleanly
    text = text.replace(Regex("(?i)<br\\s*/?>"), "\n")
    
    // Convert headings
    text = text.replace(Regex("(?i)<h([1-6])(?:\\s+[^>]*)?>"), "")
    text = text.replace(Regex("(?i)</h[1-6]>"), "\n\n")
    
    // Convert list items
    text = text.replace(Regex("(?i)<li(?:\\s+[^>]*)?>"), "• ")
    text = text.replace(Regex("(?i)</li>"), "\n")
    text = text.replace(Regex("(?i)</?u[l,o][^>]*>"), "\n")
    
    // Filter structural tags cleanly
    text = text.replace(Regex("(?i)</?div[^>]*>"), "\n")
    text = text.replace(Regex("(?i)</?body[^>]*>"), "")
    text = text.replace(Regex("(?i)</?html[^>]*>"), "")
    text = text.replace(Regex("(?i)<head(?:\\s+[^>]*)?>.*?</head>", RegexOption.DOT_MATCHES_ALL), "")
    
    // Clean remaining tags but retain styling inside custom entities
    text = text.replace(Regex("<[^>]*>"), "")
    
    // Reduce duplicate white empty paragraphs
    text = text.replace(Regex("\n{3,}"), "\n\n")
    
    return decodeHtmlEntities(text).trim()
}

fun plainTextToHtml(plainText: String): String {
    if (plainText.isBlank()) return "<p></p>"
    val paragraphs = plainText.split(Regex("\\n\\s*\\n+"))
    val sb = StringBuilder()
    for (paragraph in paragraphs) {
        val trimmed = paragraph.trim()
        if (trimHTMLAndPlain(trimmed).isNotEmpty()) {
            val lower = trimmed.lowercase()
            if (lower.startsWith("<p") || lower.startsWith("<h") || lower.startsWith("<div") || lower.startsWith("<blockquote") || lower.startsWith("<li")) {
                sb.append(trimmed).append("\n")
            } else {
                val formattedContent = trimmed.replace("\n", "<br/>\n")
                sb.append("<p>").append(formattedContent).append("</p>\n")
            }
        }
    }
    return sb.toString().trim()
}

private fun trimHTMLAndPlain(input: String): String {
    return input.replace(Regex("<[^>]*>"), "").trim()
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    viewModel: BookViewModel,
    chapterId: Long,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    LaunchedEffect(chapterId) {
        viewModel.selectEditingChapter(chapterId)
    }

    val chapter by viewModel.editingChapter.collectAsState()

    if (chapter == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        return
    }

    val currentChapter = chapter!!

    var chapterTitle by remember(currentChapter) { mutableStateOf(currentChapter.title) }
    
    // Tracks editing mode (false = Visual/Plain Text, true = Raw HTML)
    var isHtmlMode by remember { mutableStateOf(false) }
    
    // Is full screen mode active
    var isFullscreen by remember { mutableStateOf(false) }

    // Text editor states
    var visualTextState by remember(currentChapter) {
        val pText = htmlToPlainText(currentChapter.contentHtml)
        mutableStateOf(TextFieldValue(if (pText == "Введите текст вашей новой главы...") "" else pText))
    }
    
    var htmlTextState by remember(currentChapter) {
        val raw = currentChapter.contentHtml
        mutableStateOf(TextFieldValue(if (raw == "<p>Введите текст вашей новой главы...</p>") "" else raw))
    }

    var showUnsavedChangesDialog by remember { mutableStateOf(false) }

    // Synchronize mode contents upon toggle
    val toggleMode = {
        if (isHtmlMode) {
            // HTML to Plain Text conversion
            val convertedText = htmlToPlainText(htmlTextState.text)
            visualTextState = TextFieldValue(convertedText)
            isHtmlMode = false
        } else {
            // Plain Text to HTML conversion
            val convertedHtml = plainTextToHtml(visualTextState.text)
            htmlTextState = TextFieldValue(convertedHtml)
            isHtmlMode = true
        }
    }

    val currentContentText = {
        if (isHtmlMode) htmlTextState.text else plainTextToHtml(visualTextState.text)
    }

    val applyFormatAction = { tagOpen: String, tagClose: String ->
        val tf = if (isHtmlMode) htmlTextState else visualTextState
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
        val updatedTf = TextFieldValue(newText, androidx.compose.ui.text.TextRange(newCursorIdx))
        if (isHtmlMode) {
            htmlTextState = updatedTf
        } else {
            visualTextState = updatedTf
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val tf = if (isHtmlMode) htmlTextState else visualTextState
            if (!isCursorOnEmptyLine(tf)) {
                Toast.makeText(context, "Иллюстрацию можно вставить только на пустую строку!", Toast.LENGTH_LONG).show()
                return@let
            }
            val cachedPath = saveIllustrationLocally(context, it)
            if (cachedPath != null) {
                val fileName = File(cachedPath).name
                val imgTag = "\n<div style=\"text-align:center; margin:12px 0;\"><img src=\"$fileName\" style=\"max-width:100%;\" /></div>\n"
                applyFormatAction(imgTag, "")
                Toast.makeText(context, "Иллюстрация добавлена", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Ошибка сохранения", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val performSave = {
        viewModel.updateChapterContent(
            chapterId = chapterId,
            title = chapterTitle.trim(),
            contentHtml = currentContentText(),
            previewImagePath = currentChapter.previewImagePath
        )
        Toast.makeText(context, "Глава сохранена!", Toast.LENGTH_SHORT).show()
    }

    BackHandler {
        if (chapterTitle.trim() != currentChapter.title || currentContentText().trim() != currentChapter.contentHtml.trim()) {
            showUnsavedChangesDialog = true
        } else {
            onBackClick()
        }
    }

    if (showUnsavedChangesDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedChangesDialog = false },
            title = { Text("Несохраненные изменения") },
            text = { Text("Вы действительно хотите выйти? Все несохраненные изменения в главе будут потеряны.") },
            confirmButton = {
                Button(
                    onClick = {
                        showUnsavedChangesDialog = false
                        onBackClick()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Выйти") }
            },
            dismissButton = {
                TextButton(onClick = { showUnsavedChangesDialog = false }) {
                    Text("Отмена", color = MaterialTheme.colorScheme.outline)
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }

    Scaffold(
        topBar = {
            if (!isFullscreen) {
                TopAppBar(
                    title = { Text("Редактор ePub", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (chapterTitle.trim() != currentChapter.title || currentContentText().trim() != currentChapter.contentHtml.trim()) {
                                showUnsavedChangesDialog = true
                            } else {
                                onBackClick()
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = MaterialTheme.colorScheme.primary)
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
                        ) { Text("СОХРАНИТЬ", fontWeight = FontWeight.Bold) }
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
                    // Row of rich styling actions
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            IconButton(onClick = { applyFormatAction("<b>", "</b>") }) {
                                Icon(Icons.Default.FormatBold, "Жирный")
                            }
                            IconButton(onClick = { applyFormatAction("<i>", "</i>") }) {
                                Icon(Icons.Default.FormatItalic, "Курсив")
                            }
                            IconButton(onClick = { applyFormatAction("<u>", "</u>") }) {
                                Icon(Icons.Default.FormatUnderlined, "Подчеркнутый")
                            }
                            IconButton(onClick = { applyFormatAction("<s>", "</s>") }) {
                                Icon(Icons.Default.FormatStrikethrough, "Зачеркнутый")
                            }
                            IconButton(onClick = { applyFormatAction("<p>", "</p>") }) {
                                Icon(Icons.Default.Segment, "Абзац")
                            }
                            IconButton(onClick = {
                                val tf = if (isHtmlMode) htmlTextState else visualTextState
                                if (!isCursorOnEmptyLine(tf)) {
                                    Toast.makeText(context, "Иллюстрацию можно вставить только на пустую строку!", Toast.LENGTH_LONG).show()
                                } else {
                                    imagePickerLauncher.launch("image/*")
                                }
                            }) {
                                Icon(Icons.Default.AddPhotoAlternate, "Вставить картинку")
                            }
                        }
                        
                        // Fullscreen / Exit Fullscreen Button
                        IconButton(onClick = { isFullscreen = !isFullscreen }) {
                            Icon(
                                if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, 
                                contentDescription = if (isFullscreen) "Выйти из полного экрана" else "На весь экран",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Toggle Switch Button between normal editing and HTML code
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
                                    "Текст",
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
                                    "HTML код",
                                    color = if (isHtmlMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        }
                        
                        // Save quick access if fullscreen
                        if (isFullscreen) {
                            Button(
                                onClick = { performSave() },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                shape = RoundedCornerShape(50)
                            ) {
                                Icon(Icons.Default.Save, "Сохранить", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Сохранить", fontSize = 12.sp)
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
                Text("НАЗВАНИЕ ГЛАВЫ", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
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
                    if (isHtmlMode) "HTML-КОД ГЛАВЫ" else "ТЕКСТ ГЛАВЫ", 
                    fontSize = 11.sp, 
                    fontWeight = FontWeight.Bold, 
                    color = if (isHtmlMode) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary
                )
                val totalWords = WordStatsHelper.countWords(if (isHtmlMode) htmlTextState.text else visualTextState.text)
                Text("Слов: $totalWords", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, fontWeight = FontWeight.Medium)
            }
            Spacer(modifier = Modifier.height(6.dp))
            
            // Clean text editor sheet
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
                OutlinedTextField(
                    value = if (isHtmlMode) htmlTextState else visualTextState,
                    onValueChange = {
                        if (isHtmlMode) htmlTextState = it else visualTextState = it
                    },
                    textStyle = if (isHtmlMode) {
                        TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            color = Color(0xFFE2E2E2)
                        )
                    } else {
                        TextStyle(
                            fontFamily = FontFamily.SansSerif,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = 24.sp
                        )
                    },
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
                            if (isHtmlMode) "<h2>Заголовок</h2>\n<p>Введите HTML код...</p>" else "Введите текст здесь...",
                            color = if (isHtmlMode) Color.Gray else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                        )
                    }
                )
            }
        }
    }
}

fun saveIllustrationLocally(context: Context, uri: Uri): String? {
    val mediaDir = File(context.filesDir, "epub_media")
    if (!mediaDir.exists()) mediaDir.mkdirs()
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
    val destFile = File(mediaDir, "media_${System.currentTimeMillis()}.${ext}")
    return try {
        val ips = context.contentResolver.openInputStream(uri) ?: return null
        val ops = FileOutputStream(destFile)
        ips.use { input -> ops.use { output -> input.copyTo(output) } }
        destFile.absolutePath
    } catch (e: Exception) { null }
}
