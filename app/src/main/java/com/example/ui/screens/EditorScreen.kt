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

fun formatHtmlForEditing(html: String): String {
    if (html.count { it == '\n' } > 3) return html // already has some multiline formatting
    return html
        .replace(Regex("</(p|div|h[1-6]|ul|ol|li|blockquote|section)>", RegexOption.IGNORE_CASE), "</$1>\n\n")
        .replace(Regex("<(p|div|h[1-6]|ul|ol|li|blockquote|section)", RegexOption.IGNORE_CASE), "\n<$1")
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "<br/>\n")
        .trim()
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
    
    var contentHtml by remember(currentChapter) { 
        val raw = currentChapter.contentHtml
        mutableStateOf(if (raw == "<p>Введите текст вашей новой главы...</p>") "" else formatHtmlForEditing(raw))
    }

    var htmlTextFieldValue by remember(contentHtml) {
        mutableStateOf(TextFieldValue(contentHtml))
    }

    var showUnsavedChangesDialog by remember { mutableStateOf(false) }

    val applyFormatAction = { tagOpen: String, tagClose: String ->
        val tf = htmlTextFieldValue
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
        htmlTextFieldValue = updatedTf
        contentHtml = newText
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
            Log.e("EditorScreen", "Failed querying name for URI: $uri", e)
        }
        val destFile = File(mediaDir, "media_${System.currentTimeMillis()}.${ext}")
        return try {
            val ips = context.contentResolver.openInputStream(uri) ?: return null
            val ops = FileOutputStream(destFile)
            ips.use { input -> ops.use { output -> input.copyTo(output) } }
            destFile.absolutePath
        } catch (e: Exception) { null }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
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

    BackHandler {
        if (chapterTitle.trim() != currentChapter.title || htmlTextFieldValue.text.trim() != currentChapter.contentHtml.trim()) {
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
            TopAppBar(
                title = { Text("Редактор HTML", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (chapterTitle.trim() != currentChapter.title || htmlTextFieldValue.text.trim() != currentChapter.contentHtml.trim()) {
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
                        onClick = {
                            viewModel.updateChapterContent(
                                chapterId = chapterId,
                                title = chapterTitle.trim(),
                                contentHtml = htmlTextFieldValue.text,
                                previewImagePath = currentChapter.previewImagePath
                            )
                            Toast.makeText(context, "Глава сохранена!", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("СОХРАНИТЬ", fontWeight = FontWeight.Bold) }
                }
            )
        },
        bottomBar = {
            Card(
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                elevation = CardDefaults.cardElevation(8.dp),
                modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.ime)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(onClick = { applyFormatAction("<b>", "</b>") }) {
                            Icon(Icons.Default.FormatBold, "Жирный")
                        }
                        IconButton(onClick = { applyFormatAction("<i>", "</i>") }) {
                            Icon(Icons.Default.FormatItalic, "Курсив")
                        }
                        IconButton(onClick = { applyFormatAction("<u>", "</u>") }) {
                            Icon(Icons.Default.FormatUnderlined, "Подчеркнутый")
                        }
                        IconButton(onClick = { applyFormatAction("<p>", "</p>") }) {
                            Icon(Icons.Default.Segment, "Абзац")
                        }
                        IconButton(onClick = { imagePickerLauncher.launch("image/*") }) {
                            Icon(Icons.Default.AddPhotoAlternate, "Вставить картинку")
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
            Spacer(modifier = Modifier.height(16.dp))
            Text("НАЗВАНИЕ", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            OutlinedTextField(
                value = chapterTitle,
                onValueChange = { chapterTitle = it },
                textStyle = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("HTML КОД ГЛАВЫ", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
                val words = try { WordStatsHelper.countWords(htmlTextFieldValue.text) } catch (e: Exception) { 0 }
                Text("Слов: $words", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
            }
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = htmlTextFieldValue,
                onValueChange = { htmlTextFieldValue = it },
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(bottom = 16.dp)
            )
        }
    }
}
