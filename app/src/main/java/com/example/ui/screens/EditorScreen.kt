package com.example.ui.screens

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.util.ContentBlock
import com.example.util.EpubProcessor
import com.example.util.WordStatsHelper
import com.example.viewmodel.BookViewModel
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        return
    }

    val currentChapter = chapter!!

    // Local mutable state fields synchronized on load
    var chapterTitle by remember(currentChapter) { mutableStateOf(currentChapter.title) }
    var contentHtml by remember(currentChapter) { mutableStateOf(currentChapter.contentHtml) }

    // Visual Rich block list of sequential text and image nodes
    val editorBlocks = remember(currentChapter) {
        val parsed = EpubProcessor.parseContentIntoBlocks(context, currentChapter.contentHtml, currentChapter.titleId, currentChapter.title).toMutableStateList()
        if (parsed.isEmpty()) {
            parsed.add(ContentBlock.Text(""))
        }
        parsed
    }

    var isHtmlMode by remember { mutableStateOf(false) } // False: Visual format, True: HTML raw edit
    var isFocusMode by remember { mutableStateOf(false) } // Distraction-free focus writing mode

    // For supporting visual rich formatting helper actions (cursor placement or appends)
    var activeBlockIndex by remember { mutableStateOf<Int?>(null) }
    var activeTextFieldValue by remember { mutableStateOf(TextFieldValue("")) }

    // Copied local file saver
    fun saveIllustrationLocally(context: Context, uri: Uri): String? {
        val mediaDir = File(context.filesDir, "epub_media")
        if (!mediaDir.exists()) {
            mediaDir.mkdirs()
        }
        var ext = "jpg"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIdx != -1 && cursor.moveToFirst()) {
                val name = cursor.getString(nameIdx)
                ext = name.substringAfterLast(".", "jpg").lowercase()
            }
        }
        
        val destFile = File(mediaDir, "media_${System.currentTimeMillis()}.${ext}")
        return try {
            val ips = context.contentResolver.openInputStream(uri) ?: return null
            val ops = FileOutputStream(destFile)
            ips.use { input ->
                ops.use { output ->
                    input.copyTo(output)
                }
            }
            destFile.absolutePath
        } catch (e: Exception) {
            Log.e("EditorScreen", "Failed caching illustration", e)
            null
        }
    }

    // Helper checking if cursor is currently on a clean line with zero characters (excluding whitespace)
    fun isCursorOnEmptyLine(value: TextFieldValue): Boolean {
        val text = value.text
        val selection = value.selection
        if (selection.collapsed) {
            val cursor = selection.start
            var lineStart = cursor
            while (lineStart > 0 && text[lineStart - 1] != '\n') {
                lineStart--
            }
            var lineEnd = cursor
            while (lineEnd < text.length && text[lineEnd] != '\n') {
                lineEnd++
            }
            val lineText = text.substring(lineStart, lineEnd)
            return lineText.trim().isEmpty()
        }
        return false
    }

    // Image picker launcher for illustration insertion
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val index = activeBlockIndex
            if (index != null && index in editorBlocks.indices) {
                if (isCursorOnEmptyLine(activeTextFieldValue)) {
                    val cachedPath = saveIllustrationLocally(context, uri)
                    if (cachedPath != null) {
                        val cursor = activeTextFieldValue.selection.start
                        val fullText = activeTextFieldValue.text

                        // Determine the current empty line bounds to split text cleanly
                        var lineStart = cursor
                        while (lineStart > 0 && fullText[lineStart - 1] != '\n') {
                            lineStart--
                        }
                        var lineEnd = cursor
                        while (lineEnd < fullText.length && fullText[lineEnd] != '\n') {
                            lineEnd++
                        }

                        val partLeft = fullText.substring(0, lineStart).trim()
                        val partRight = fullText.substring(lineEnd).trim()

                        // Remove existing text module at index, insert parts surrounding new image
                        editorBlocks.removeAt(index)
                        var insertPos = index
                        if (partLeft.isNotEmpty()) {
                            editorBlocks.add(insertPos, ContentBlock.Text(partLeft))
                            insertPos++
                        }
                        editorBlocks.add(insertPos, ContentBlock.Image(cachedPath))
                        insertPos++
                        if (partRight.isNotEmpty()) {
                            editorBlocks.add(insertPos, ContentBlock.Text(partRight))
                        }

                        // Reset selection focal states
                        activeBlockIndex = null
                        activeTextFieldValue = TextFieldValue("")
                        Toast.makeText(context, "Иллюстрация добавлена!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Ошибка сохранения файла", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(
                        context,
                        "Иллюстрацию можно добавить только на пустой строке! Спуститесь на чистую строчку.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                Toast.makeText(context, "Выберите текстовый абзац для добавления иллюстрации", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            AnimatedVisibility(
                visible = !isFocusMode,
                enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
            ) {
                TopAppBar(
                    title = {
                        Text(
                            text = "Редактор",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Назад",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    actions = {
                        Button(
                            onClick = {
                                val finalHtml = if (isHtmlMode) contentHtml else serializeBlocksToHtml(editorBlocks)
                                viewModel.updateChapterContent(
                                    chapterId = chapterId,
                                    title = chapterTitle.trim(),
                                    contentHtml = finalHtml,
                                    previewImagePath = currentChapter.previewImagePath
                                )
                                Toast.makeText(context, "Глава сохранена!", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text("СОХРАНИТЬ", fontWeight = FontWeight.Bold)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            // Visual helper quick formatting toolbar
            Card(
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                elevation = CardDefaults.cardElevation(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                val activeText = if (isHtmlMode) {
                    contentHtml
                } else {
                    val idx = activeBlockIndex
                    if (idx != null && idx in editorBlocks.indices) {
                        (editorBlocks[idx] as? ContentBlock.Text)?.htmlText ?: ""
                    } else {
                        ""
                    }
                }

                val updateActiveText = { newText: String ->
                    if (isHtmlMode) {
                        contentHtml = newText
                    } else {
                        val idx = activeBlockIndex
                        if (idx != null && idx in editorBlocks.indices) {
                            if (editorBlocks[idx] is ContentBlock.Text) {
                                editorBlocks[idx] = ContentBlock.Text(newText)
                                activeTextFieldValue = activeTextFieldValue.copy(text = newText)
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Quick tag insertions or helpers
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { updateActiveText(insertHtmlTag(activeText, "<b>", "</b>")) },
                            colors = IconButtonDefaults.iconButtonColors(
                                contentColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(imageVector = Icons.Default.FormatBold, contentDescription = "Жирный")
                        }

                        IconButton(
                            onClick = { updateActiveText(insertHtmlTag(activeText, "<i>", "</i>")) },
                            colors = IconButtonDefaults.iconButtonColors(
                                contentColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(imageVector = Icons.Default.FormatItalic, contentDescription = "Курсив")
                        }

                        IconButton(
                            onClick = { updateActiveText(insertHtmlTag(activeText, "<u>", "</u>")) },
                            colors = IconButtonDefaults.iconButtonColors(
                                contentColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(imageVector = Icons.Default.FormatUnderlined, contentDescription = "Подчеркнутый")
                        }

                        IconButton(
                            onClick = { updateActiveText(insertHtmlTag(activeText, "<p>", "</p>")) },
                            colors = IconButtonDefaults.iconButtonColors(
                                contentColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(imageVector = Icons.Default.Notes, contentDescription = "Абзац")
                        }

                        // Illustration Picker Trigger Button (Next to standard formatting buttons)
                        IconButton(
                            onClick = {
                                if (activeBlockIndex != null) {
                                    if (isCursorOnEmptyLine(activeTextFieldValue)) {
                                        imagePickerLauncher.launch("image/*")
                                    } else {
                                        Toast.makeText(
                                            context,
                                            "Иллюстрацию можно добавить только на пустой строке! Спуститесь на чистую строчку.",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                } else {
                                    Toast.makeText(
                                        context,
                                        "Выберите текстовый абзац и перейдите на пустую строчку",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            },
                            colors = IconButtonDefaults.iconButtonColors(
                                contentColor = if (activeBlockIndex != null && isCursorOnEmptyLine(activeTextFieldValue)) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                                }
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.AddPhotoAlternate,
                                contentDescription = "Добавить иллюстрацию"
                            )
                        }
                    }

                    // Mode togglers
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Switch between Visual format view or Code raw tag view
                        IconButton(
                            onClick = {
                                if (isHtmlMode) {
                                    // Turning off HTML Mode: parse contentHtml into editorBlocks
                                    editorBlocks.clear()
                                    editorBlocks.addAll(EpubProcessor.parseContentIntoBlocks(context, contentHtml, currentChapter.titleId, currentChapter.title))
                                    if (editorBlocks.isEmpty()) {
                                        editorBlocks.add(ContentBlock.Text(""))
                                    }
                                } else {
                                    // Turning on HTML Mode: serialize editorBlocks into contentHtml
                                    contentHtml = serializeBlocksToHtml(editorBlocks)
                                }
                                isHtmlMode = !isHtmlMode
                            },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = if (isHtmlMode) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                contentColor = if (isHtmlMode) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(imageVector = Icons.Default.Code, contentDescription = "HTML код")
                        }

                        // Distraction-free target
                        IconButton(
                            onClick = { isFocusMode = !isFocusMode },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = if (isFocusMode) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                                contentColor = if (isFocusMode) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(
                                imageVector = if (isFocusMode) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                contentDescription = "Фокус режим"
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    // Large Chapter Title Input
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "НАЗВАНИЕ ГЛАВЫ",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                        OutlinedTextField(
                            value = chapterTitle,
                            onValueChange = { chapterTitle = it },
                            textStyle = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            placeholder = { Text("Куда уходит тень...") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                item {
                    // Title label for the content editor area
                    Text(
                        text = if (isHtmlMode) "HTML / КОРРЕКЦИЯ ТЕГАМИ" else "ВИЗУАЛЬНЫЙ ТЕКСТ главы (с поддержкой иллюстраций)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }

                if (isHtmlMode) {
                    item {
                        // Advanced Code-Editor Style Raw Html inputs
                        OutlinedTextField(
                            value = contentHtml,
                            onValueChange = { contentHtml = it },
                            placeholder = { Text("<p>Напишите содержание главы здесь...</p>") },
                            textStyle = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.secondary,
                                lineHeight = 20.sp
                            ),
                            minLines = 15,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    itemsIndexed(
                        items = editorBlocks,
                        key = { _, block -> block.id }
                    ) { index, block ->
                        key(block.id) {
                            when (block) {
                                is ContentBlock.Text -> {
                                    val cleanText = remember(block.id) {
                                        block.htmlText
                                            .replace(Regex("<[^>]*>"), "")
                                            .replace("&nbsp;", " ")
                                            .replace("&amp;", "&")
                                            .replace("&lt;", "<")
                                            .replace("&gt;", ">")
                                            .replace("&quot;", "\"")
                                            .replace("&apos;", "'")
                                    }

                                    var tfValue by remember(block.id) {
                                        mutableStateOf(TextFieldValue(cleanText))
                                    }

                                    // Synchronize formatting button updates to the local TextFieldValue
                                    LaunchedEffect(activeTextFieldValue) {
                                        if (activeBlockIndex == index && tfValue.text != activeTextFieldValue.text) {
                                            tfValue = activeTextFieldValue
                                            editorBlocks[index] = ContentBlock.Text(tfValue.text, block.id)
                                        }
                                    }

                                    OutlinedTextField(
                                        value = tfValue,
                                        onValueChange = { newValue ->
                                            tfValue = newValue
                                            editorBlocks[index] = ContentBlock.Text(newValue.text, block.id)
                                            if (activeBlockIndex == index) {
                                                activeTextFieldValue = newValue
                                            }
                                        },
                                        placeholder = { Text("Введите text абзаца главы...") },
                                        textStyle = TextStyle(
                                            fontSize = 16.sp,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            lineHeight = 26.sp
                                        ),
                                        minLines = 3,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .onFocusChanged { focusState ->
                                                if (focusState.isFocused) {
                                                    activeBlockIndex = index
                                                    activeTextFieldValue = tfValue
                                                }
                                            }
                                    )
                                }
                                is ContentBlock.Image -> {
                                    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

                                    Card(
                                        shape = RoundedCornerShape(16.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .wrapContentHeight()
                                            .padding(vertical = 4.dp)
                                            .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), RoundedCornerShape(16.dp)),
                                        elevation = CardDefaults.cardElevation(2.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .combinedClickable(
                                                    onClick = {
                                                        Toast.makeText(context, "Зажмите для удаления иллюстрации", Toast.LENGTH_SHORT).show()
                                                    },
                                                    onLongClick = {
                                                        showDeleteConfirmDialog = true
                                                    }
                                                )
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(8.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                val file = File(block.localPath)
                                                if (file.exists()) {
                                                    AsyncImage(
                                                        model = file,
                                                        contentDescription = "Иллюстрация главы",
                                                        contentScale = ContentScale.FillWidth,
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clip(RoundedCornerShape(12.dp))
                                                    )
                                                } else {
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(16.dp),
                                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.BrokenImage,
                                                            contentDescription = "Файл изображения отсутствует",
                                                            tint = MaterialTheme.colorScheme.error
                                                        )
                                                        Text(
                                                            text = "Изображение '${file.name}' не найдено",
                                                            color = MaterialTheme.colorScheme.error,
                                                            fontSize = 13.sp
                                                        )
                                                    }
                                                }

                                                Text(
                                                    text = "ИЛЛЮСТРАЦИЯ (Зажмите для удаления)",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.secondary,
                                                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                                                )
                                            }
                                        }
                                    }

                                    if (showDeleteConfirmDialog) {
                                        AlertDialog(
                                            onDismissRequest = { showDeleteConfirmDialog = false },
                                            title = { Text("Удалить иллюстрацию?") },
                                            text = { Text("Вы действительно хотите удалить эту иллюстрацию из главы?") },
                                            confirmButton = {
                                                Button(
                                                    onClick = {
                                                        editorBlocks.removeAt(index)
                                                        
                                                        // Post-deletion: merge adjacent ContentBlock.Text items
                                                        val mergedList = mutableListOf<ContentBlock>()
                                                        for (b in editorBlocks) {
                                                            if (mergedList.isNotEmpty() && mergedList.last() is ContentBlock.Text && b is ContentBlock.Text) {
                                                                val lastText = (mergedList.last() as ContentBlock.Text).htmlText
                                                                val currentText = b.htmlText
                                                                val combined = if (lastText.trim().isEmpty()) {
                                                                    currentText
                                                                } else if (currentText.trim().isEmpty()) {
                                                                    lastText
                                                                } else {
                                                                    "$lastText\n$currentText"
                                                                }
                                                                // Keep the ID of the first text item to avoid focus/state rebuild
                                                                mergedList[mergedList.lastIndex] = ContentBlock.Text(combined, mergedList.last().id)
                                                            } else {
                                                                mergedList.add(b)
                                                            }
                                                        }
                                                        editorBlocks.clear()
                                                        editorBlocks.addAll(mergedList)
                                                        if (editorBlocks.isEmpty()) {
                                                            editorBlocks.add(ContentBlock.Text(""))
                                                        }

                                                        activeBlockIndex = null
                                                        activeTextFieldValue = TextFieldValue("")
                                                        showDeleteConfirmDialog = false
                                                        Toast.makeText(context, "Иллюстрация удалена!", Toast.LENGTH_SHORT).show()
                                                    },
                                                    colors = ButtonDefaults.buttonColors(
                                                        containerColor = MaterialTheme.colorScheme.error
                                                    )
                                                ) {
                                                    Text("Удалить")
                                                }
                                            },
                                            dismissButton = {
                                                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                                                    Text("Отмена", color = MaterialTheme.colorScheme.outline)
                                                }
                                            },
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                            shape = RoundedCornerShape(24.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(80.dp))
                }
            }

            // Word count / character count Floating active badge
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 32.dp, end = 24.dp)
            ) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    shape = RoundedCornerShape(20.dp),
                    elevation = CardDefaults.cardElevation(4.dp)
                ) {
                    val statsText = if (isHtmlMode) contentHtml else serializeBlocksToHtml(editorBlocks)
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Слов",
                                fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.outline,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = formatStatsNumber(WordStatsHelper.countWords(statsText)),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(20.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant)
                        )
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Символов",
                                fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.outline,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = formatStatsNumber(WordStatsHelper.countCharacters(statsText)),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Inserts a pair of tags around selection, or standard appends them
 */
private fun insertHtmlTag(originalText: String, startTag: String, endTag: String): String {
    return if (originalText.isBlank()) {
        "$startTag$endTag"
    } else {
        "$originalText\n$startTag$endTag"
    }
}

private fun formatStatsNumber(number: Int): String {
    return java.text.DecimalFormat("#,###").format(number)
}

fun htmlToPlainText(html: String): String {
    if (html.isBlank()) return ""
    
    val bodyRegex = Regex("<body[^>]*>(.*?)</body>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    val bodyMatch = bodyRegex.find(html)
    val bodyContent = bodyMatch?.groupValues?.get(1) ?: html

    var clean = bodyContent
        .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("<style[^>]*>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
        .replace(Regex("<script[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")

    clean = clean
        .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n\n")
        .replace(Regex("</div>", RegexOption.IGNORE_CASE), "\n\n")
        .replace(Regex("</h1>|</h2>|</h3>|</h4>|</h5>|</h6>|</td>|</tr>", RegexOption.IGNORE_CASE), "\n\n")
        .replace(Regex("<br[^>]*>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<li[^>]*>", RegexOption.IGNORE_CASE), "\n• ")
        .replace(Regex("</li>", RegexOption.IGNORE_CASE), "\n")

    clean = clean.replace(Regex("<[^>]*>"), "")

    clean = clean
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")

    val lines = clean.split("\n").map { it.trim() }
    val result = lines.joinToString("\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
        
    return result
}

fun plainTextToHtml(plainText: String): String {
    if (plainText.isBlank()) return ""
    val lines = plainText.split(Regex("\n+"))
    val sb = java.lang.StringBuilder()
    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.isNotEmpty()) {
            val escaped = trimmed
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;")
            sb.append("<p>").append(escaped).append("</p>\n")
        }
    }
    return sb.toString().trim()
}

fun serializeBlocksToHtml(blocks: List<ContentBlock>): String {
    val sb = StringBuilder()
    blocks.forEach { block ->
        when (block) {
            is ContentBlock.Text -> {
                val text = block.htmlText.trim()
                if (text.isNotEmpty()) {
                    if (text.startsWith("<p>") || text.startsWith("<div>") || text.startsWith("<h")) {
                        sb.append(text).append("\n")
                    } else {
                        text.split("\n").forEach { line ->
                            val trimmedLine = line.trim()
                            if (trimmedLine.isNotEmpty()) {
                                sb.append("<p>").append(trimmedLine).append("</p>\n")
                            }
                        }
                    }
                }
            }
            is ContentBlock.Image -> {
                val file = File(block.localPath)
                sb.append("<div style=\"text-align:center; margin:12px 0;\"><img src=\"${file.name}\" style=\"max-width:100%;\" /></div>\n")
            }
        }
    }
    return sb.toString().trim()
}

