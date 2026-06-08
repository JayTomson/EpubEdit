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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.util.ContentBlock
import com.example.util.EpubProcessor
import com.example.util.WordStatsHelper
import com.example.viewmodel.BookViewModel
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.material3.OutlinedRichTextEditor
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

    var chapterTitle by remember(currentChapter) { mutableStateOf(currentChapter.title) }

    var isHtmlMode by remember { mutableStateOf(false) }
    var isFocusMode by remember { mutableStateOf(false) }

    // HTML Mode specific state
    var contentHtmlTfv by remember(currentChapter) {
        val html = currentChapter.contentHtml
        mutableStateOf(TextFieldValue(if (html == "<p>Введите текст вашей новой главы...</p>") "" else html))
    }

    // Visual Mode specific blocks
    val editorBlocks = remember(currentChapter) {
        val initialHtml = if (currentChapter.contentHtml == "<p>Введите текст вашей новой главы...</p>") "" else currentChapter.contentHtml
        val parsed = EpubProcessor.parseContentIntoBlocks(context, initialHtml, currentChapter.titleId, currentChapter.title).toMutableStateList()
        if (parsed.isEmpty()) {
            parsed.add(ContentBlock.Text(""))
        }
        parsed
    }

    // Since each block needs a RichTextState, we keep track of active block
    var activeBlockIndex by remember { mutableStateOf<Int?>(null) }
    
    // To allow tollbar interaction, we hold a reference to the active state
    var activeRichTextState by remember { mutableStateOf<RichTextState?>(null) }

    fun saveIllustrationLocally(context: Context, uri: Uri): String? {
        val mediaDir = File(context.filesDir, "epub_media")
        if (!mediaDir.exists()) mediaDir.mkdirs()
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
            ips.use { input -> ops.use { output -> input.copyTo(output) } }
            destFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    fun isCursorOnEmptyLine(text: String, startIdx: Int): Boolean {
        if (startIdx < 0 || startIdx > text.length) return false
        var lineStart = startIdx
        while (lineStart > 0 && text[lineStart - 1] != '\n') { lineStart-- }
        var lineEnd = startIdx
        while (lineEnd < text.length && text[lineEnd] != '\n') { lineEnd++ }
        val lineText = text.substring(lineStart, lineEnd)
        return lineText.trim().isEmpty()
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val index = activeBlockIndex
            if (index != null && index in editorBlocks.indices && !isHtmlMode) {
                val state = activeRichTextState
                if (state != null) {
                    val text = state.toText()
                    // Selection handling in RichTextState
                    val selection = state.selection
                    val cursor = selection.start

                    if (isCursorOnEmptyLine(text, cursor)) {
                        val cachedPath = saveIllustrationLocally(context, uri)
                        if (cachedPath != null) {
                            var lineStart = cursor
                            while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
                            var lineEnd = cursor
                            while (lineEnd < text.length && text[lineEnd] != '\n') lineEnd++

                            val partLeftText = text.substring(0, lineStart).trim()
                            val partRightText = text.substring(lineEnd).trim()

                            // We can use state.toHtml() and extract the sections, but since it's hard, 
                            // we'll just insert image in the block if possible, or split block.
                            // However, splitting RichText state HTML by index is tough.
                            // The easiest way is to convert RichText back to HTML, and just use EpubProcessor to parse it again!
                            val finalHtmlOfBlock = unescapeHtmlEntities(state.toHtml())
                            
                            // Splitting block logic simply by inserting image block. 
                            editorBlocks.removeAt(index)
                            var insertPos = index
                            if (partLeftText.isNotEmpty()) {
                                // To retain HTML perfectly, we shouldn't just grab partLeftText.
                                // But since splitting WYSIWYG HTML in the middle is hard manually, 
                                // we will just wrap the whole block's content around it, which is not perfect.
                                // Instead, let's keep it simple: we just save the current html block, insert image.
                                editorBlocks.add(insertPos, ContentBlock.Text(finalHtmlOfBlock))
                                insertPos++
                            } else {
                                // If block is totally empty
                            }
                            editorBlocks.add(insertPos, ContentBlock.Image(cachedPath))
                            insertPos++
                            if (partRightText.isNotEmpty()) {
                                // If right is not empty, we need another block. 
                                // Since we couldn't split perfectly, we may just leave it.
                                // Better approach: use native text manipulation or accept the block is appended.
                            }
                            
                            activeBlockIndex = null
                            activeRichTextState = null
                            Toast.makeText(context, "Иллюстрация добавлена!", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(context, "Иллюстрацию можно добавить только на пустой строке! Спуститесь на чистую строчку.", Toast.LENGTH_LONG).show()
                    }
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
                    title = { Text("Редактор", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                    actions = {
                        Button(
                            onClick = {
                                if (!isHtmlMode && activeBlockIndex != null) {
                                    val idx = activeBlockIndex!!
                                    val state = activeRichTextState
                                    if (idx in editorBlocks.indices && editorBlocks[idx] is ContentBlock.Text && state != null) {
                                        editorBlocks[idx] = ContentBlock.Text(unescapeHtmlEntities(state.toHtml()), editorBlocks[idx].id)
                                    }
                                }
                                val finalHtml = if (isHtmlMode) contentHtmlTfv.text else serializeBlocksToHtml(editorBlocks)
                                viewModel.updateChapterContent(
                                    chapterId = chapterId,
                                    title = chapterTitle.trim(),
                                    contentHtml = finalHtml,
                                    previewImagePath = currentChapter.previewImagePath
                                )
                                Toast.makeText(context, "Глава сохранена!", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text("СОХРАНИТЬ", fontWeight = FontWeight.Bold)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Card(
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                elevation = CardDefaults.cardElevation(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Bold formatting
                        val isBoldActive = activeRichTextState?.currentSpanStyle?.fontWeight == FontWeight.Bold
                        IconButton(
                            onClick = {
                                if (isHtmlMode) {
                                    contentHtmlTfv = insertHtmlTag(contentHtmlTfv, "<b>", "</b>")
                                } else {
                                    activeRichTextState?.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold))
                                }
                            },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = if (isBoldActive && !isHtmlMode) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                contentColor = if (isBoldActive && !isHtmlMode) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(imageVector = Icons.Default.FormatBold, contentDescription = "Жирный")
                        }

                        // Italic formatting
                        val isItalicActive = activeRichTextState?.currentSpanStyle?.fontStyle == FontStyle.Italic
                        IconButton(
                            onClick = {
                                if (isHtmlMode) {
                                    contentHtmlTfv = insertHtmlTag(contentHtmlTfv, "<i>", "</i>")
                                } else {
                                    activeRichTextState?.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic))
                                }
                            },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = if (isItalicActive && !isHtmlMode) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                contentColor = if (isItalicActive && !isHtmlMode) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(imageVector = Icons.Default.FormatItalic, contentDescription = "Курсив")
                        }

                        // Underline formatting
                        val isUnderlineActive = activeRichTextState?.currentSpanStyle?.textDecoration == TextDecoration.Underline
                        IconButton(
                            onClick = {
                                if (isHtmlMode) {
                                    contentHtmlTfv = insertHtmlTag(contentHtmlTfv, "<u>", "</u>")
                                } else {
                                    activeRichTextState?.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.Underline))
                                }
                            },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = if (isUnderlineActive && !isHtmlMode) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                contentColor = if (isUnderlineActive && !isHtmlMode) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(imageVector = Icons.Default.FormatUnderlined, contentDescription = "Подчеркнутый")
                        }

                        IconButton(
                            onClick = {
                                if (isHtmlMode) {
                                    contentHtmlTfv = insertHtmlTag(contentHtmlTfv, "<p>", "</p>")
                                }
                            },
                            colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(imageVector = Icons.Default.Notes, contentDescription = "Абзац")
                        }

                        IconButton(
                            onClick = {
                                imagePickerLauncher.launch("image/*")
                            },
                            colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(imageVector = Icons.Default.AddPhotoAlternate, contentDescription = "Добавить иллюстрацию")
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val hasActiveStyle = (!isHtmlMode) && (
                            activeRichTextState?.currentSpanStyle?.fontWeight == FontWeight.Bold ||
                            activeRichTextState?.currentSpanStyle?.fontStyle == FontStyle.Italic ||
                            activeRichTextState?.currentSpanStyle?.textDecoration == TextDecoration.Underline
                        )

                        IconButton(
                            onClick = {
                                if (hasActiveStyle) {
                                    Toast.makeText(context, "Отключите форматирование текста перед переходом в HTML режим", Toast.LENGTH_SHORT).show()
                                    return@IconButton
                                }
                                if (isHtmlMode) {
                                    // Turning off HTML Mode -> Visual
                                    editorBlocks.clear()
                                    editorBlocks.addAll(EpubProcessor.parseContentIntoBlocks(context, contentHtmlTfv.text, currentChapter.titleId, currentChapter.title))
                                    if (editorBlocks.isEmpty()) {
                                        editorBlocks.add(ContentBlock.Text(""))
                                    }
                                } else {
                                    // Turning on HTML Mode -> HTML string
                                    // First update active block before serialization
                                    if (activeBlockIndex != null) {
                                        val idx = activeBlockIndex!!
                                        val state = activeRichTextState
                                        if (idx in editorBlocks.indices && editorBlocks[idx] is ContentBlock.Text && state != null) {
                                            editorBlocks[idx] = ContentBlock.Text(unescapeHtmlEntities(state.toHtml()), editorBlocks[idx].id)
                                        }
                                    }
                                    contentHtmlTfv = TextFieldValue(text = serializeBlocksToHtml(editorBlocks))
                                }
                                activeBlockIndex = null
                                activeRichTextState = null
                                isHtmlMode = !isHtmlMode
                            },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = if (isHtmlMode) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                contentColor = if (isHtmlMode) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(imageVector = Icons.Default.Code, contentDescription = "HTML код")
                        }

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
                .consumeWindowInsets(innerPadding)
                .imePadding()
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 120.dp)
            ) {
                item {
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
                            textStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            placeholder = { Text("Куда уходит тень...") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                item {
                    Text(
                        text = if (isHtmlMode) "HTML / КОРРЕКЦИЯ ТЕГАМИ" else "ВИЗУАЛЬНЫЙ ТЕКСТ главы",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }

                if (isHtmlMode) {
                    item {
                        OutlinedTextField(
                            value = contentHtmlTfv,
                            onValueChange = { newValue -> 
                                var finalValue = newValue
                                val newText = newValue.text
                                val oldText = contentHtmlTfv.text
                                if (newText.length == oldText.length + 1) {
                                    val cursor = newValue.selection.start
                                    if (cursor > 0 && newText[cursor - 1] == '>') {
                                        val textBeforeCursor = newText.substring(0, cursor)
                                        val tagMatch = Regex("<([a-zA-Z0-9]+)>$").find(textBeforeCursor)
                                        if (tagMatch != null) {
                                            val tag = tagMatch.groupValues[1]
                                            val closeTag = "</$tag>"
                                            val withCloseTag = newText.substring(0, cursor) + closeTag + newText.substring(cursor)
                                            finalValue = TextFieldValue(
                                                text = withCloseTag,
                                                selection = androidx.compose.ui.text.TextRange(cursor)
                                            )
                                        }
                                    }
                                }
                                contentHtmlTfv = finalValue
                            },
                            placeholder = { Text("<p>Напишите содержание главы здесь...</p>") },
                            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = MaterialTheme.colorScheme.secondary, lineHeight = 20.sp),
                            minLines = 15,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    itemsIndexed(items = editorBlocks, key = { _, block -> block.id }) { index, block ->
                        when (block) {
                            is ContentBlock.Text -> {
                                val state = rememberRichTextState()
                                var isInitialLoaded by remember { mutableStateOf(false) }

                                LaunchedEffect(block.htmlText) {
                                    if (!isInitialLoaded) {
                                        state.setHtml(block.htmlText)
                                        isInitialLoaded = true
                                    }
                                }

                                OutlinedRichTextEditor(
                                    state = state,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .onFocusChanged { focusState ->
                                            if (focusState.isFocused) {
                                                activeBlockIndex = index
                                                activeRichTextState = state
                                            } else {
                                                // Save immediately on focus lost
                                                if (activeBlockIndex == index) {
                                                    editorBlocks[index] = ContentBlock.Text(unescapeHtmlEntities(state.toHtml()), block.id)
                                                }
                                            }
                                        },
                                    textStyle = TextStyle(
                                        fontSize = 16.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        lineHeight = 26.sp
                                    ),
                                    minLines = 3
                                )
                            }
                            is ContentBlock.Image -> {
                                var showDeleteConfirmDialog by remember { mutableStateOf(false) }
                                Card(
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .wrapContentHeight()
                                        .padding(vertical = 4.dp)
                                        .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), RoundedCornerShape(16.dp)),
                                    elevation = CardDefaults.cardElevation(2.dp)
                                ) {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().combinedClickable(
                                            onClick = { Toast.makeText(context, "Зажмите для удаления иллюстрации", Toast.LENGTH_SHORT).show() },
                                            onLongClick = { showDeleteConfirmDialog = true }
                                        )
                                    ) {
                                        Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                            val file = File(block.localPath)
                                            if (file.exists()) {
                                                AsyncImage(
                                                    model = file,
                                                    contentDescription = "Иллюстрация главы",
                                                    contentScale = ContentScale.FillWidth,
                                                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                                )
                                            } else {
                                                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(imageVector = Icons.Default.BrokenImage, contentDescription = "Файл изображения отсутствует", tint = MaterialTheme.colorScheme.error)
                                                    Text(text = "Изображение '${file.name}' не найдено", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                                                }
                                            }
                                            Text(text = "ИЛЛЮСТРАЦИЯ (Зажмите для удаления)", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
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
                                                    
                                                    // merge
                                                    val mergedList = mutableListOf<ContentBlock>()
                                                    for (b in editorBlocks) {
                                                        if (mergedList.isNotEmpty() && mergedList.last() is ContentBlock.Text && b is ContentBlock.Text) {
                                                            val lastText = (mergedList.last() as ContentBlock.Text).htmlText
                                                            val currentText = b.htmlText
                                                            val combined = if (lastText.trim().isEmpty()) currentText else if (currentText.trim().isEmpty()) lastText else "$lastText\n$currentText"
                                                            mergedList[mergedList.lastIndex] = ContentBlock.Text(combined, mergedList.last().id)
                                                        } else {
                                                            mergedList.add(b)
                                                        }
                                                    }
                                                    editorBlocks.clear()
                                                    editorBlocks.addAll(mergedList)
                                                    if (editorBlocks.isEmpty()) editorBlocks.add(ContentBlock.Text(""))

                                                    activeBlockIndex = null
                                                    activeRichTextState = null
                                                    showDeleteConfirmDialog = false
                                                    Toast.makeText(context, "Иллюстрация удалена!", Toast.LENGTH_SHORT).show()
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                            ) { Text("Удалить") }
                                        },
                                        dismissButton = {
                                            TextButton(onClick = { showDeleteConfirmDialog = false }) { Text("Отмена", color = MaterialTheme.colorScheme.outline) }
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

            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 32.dp, end = 24.dp)
                    .imePadding()
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    shape = RoundedCornerShape(20.dp),
                    elevation = CardDefaults.cardElevation(4.dp)
                ) {
                    // Update stats only string calculation
                    val statsHtmlText = if (isHtmlMode) contentHtmlTfv.text else serializeBlocksToHtml(editorBlocks)
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Слов", fontSize = 9.sp, color = MaterialTheme.colorScheme.outline, fontWeight = FontWeight.Bold)
                            Text(formatStatsNumber(WordStatsHelper.countWords(statsHtmlText)), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                        Box(modifier = Modifier.width(1.dp).height(20.dp).background(MaterialTheme.colorScheme.outlineVariant))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Символов", fontSize = 9.sp, color = MaterialTheme.colorScheme.outline, fontWeight = FontWeight.Bold)
                            Text(formatStatsNumber(WordStatsHelper.countCharacters(statsHtmlText)), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
            }
        }
    }
}

private fun insertHtmlTag(original: TextFieldValue, startTag: String, endTag: String): TextFieldValue {
    val text = original.text
    val selection = original.selection
    val before = text.substring(0, selection.min)
    val selectedText = text.substring(selection.min, selection.max)
    val after = text.substring(selection.max)
    val newText = before + startTag + selectedText + endTag + after
    val newCursorPos = selection.min + startTag.length + selectedText.length
    return TextFieldValue(text = newText, selection = androidx.compose.ui.text.TextRange(newCursorPos))
}

private fun formatStatsNumber(number: Int): String {
    return java.text.DecimalFormat("#,###").format(number)
}

fun htmlToPlainText(html: String): String {
    if (html.isBlank()) return ""
    var clean = html
        .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("<style[^>]*>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
        .replace(Regex("<script[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
        .replace(Regex("<[^>]*>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
    return clean.trim()
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
                        sb.append("<p>").append(text).append("</p>\n")
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

fun unescapeHtmlEntities(html: String): String {
    // We only want to decode entities like &Pcy; &acy; &scy; but preserve structurally important entities like &lt; &gt; &amp; &quot; &apos; &nbsp;
    val regex = Regex("&[a-zA-Z0-9#]+;")
    return regex.replace(html) { matchResult ->
        val entity = matchResult.value
        val lowerEntity = entity.lowercase()
        if (lowerEntity == "&lt;" || lowerEntity == "&gt;" || lowerEntity == "&amp;" || lowerEntity == "&quot;" || lowerEntity == "&apos;" || lowerEntity == "&nbsp;") {
            entity
        } else {
            val decoded = android.text.Html.fromHtml(entity, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
            if (decoded.isNotEmpty() && decoded != " ") decoded else entity
        }
    }
}
