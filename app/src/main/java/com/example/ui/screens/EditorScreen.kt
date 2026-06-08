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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.combinedClickable
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
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditor
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditorDefaults
import java.io.File
import java.io.FileOutputStream

class EditorBlockState(
    val id: String = java.util.UUID.randomUUID().toString(),
    val isImage: Boolean,
    val localPath: String = "",
    val richTextState: RichTextState? = null
)

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

    // Stable block state representations to completely prevent focus loss/card updates on change
    val stableBlocks = remember(chapterId) {
        val initialHtml = if (currentChapter.contentHtml == "<p>Введите текст вашей новой главы...</p>") "" else currentChapter.contentHtml
        val parsed = EpubProcessor.parseContentIntoBlocks(context, initialHtml, currentChapter.titleId, currentChapter.title)
        val initialList = parsed.map { b ->
            when (b) {
                is ContentBlock.Text -> {
                    val s = RichTextState()
                    s.setHtml(b.htmlText)
                    EditorBlockState(id = b.id, isImage = false, richTextState = s)
                }
                is ContentBlock.Image -> {
                    EditorBlockState(id = b.id, isImage = true, localPath = b.localPath)
                }
            }
        }.toMutableStateList()
        if (initialList.isEmpty()) {
            val s = RichTextState()
            s.setHtml("")
            initialList.add(EditorBlockState(isImage = false, richTextState = s))
        }
        initialList
    }

    // Since each block needs a RichTextState, we keep track of active block
    var activeBlockIndex by remember { mutableStateOf<Int?>(null) }
    
    // To allow toolbar interaction, we hold a reference to the active state
    var activeRichTextState by remember { mutableStateOf<RichTextState?>(null) }

    // HTML Mode specific state
    var contentHtmlTfv by remember(currentChapter) {
        val html = currentChapter.contentHtml
        mutableStateOf(TextFieldValue(if (html == "<p>Введите текст вашей новой главы...</p>") "" else html))
    }

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

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val cachedPath = saveIllustrationLocally(context, uri)
            if (cachedPath != null) {
                val index = activeBlockIndex ?: stableBlocks.lastIndex
                val insertPos = if (index != -1) index + 1 else stableBlocks.size
                stableBlocks.add(insertPos, EditorBlockState(isImage = true, localPath = cachedPath))
                Toast.makeText(context, "Иллюстрация добавлена!", Toast.LENGTH_SHORT).show()
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
                                val finalHtml = if (isHtmlMode) {
                                    contentHtmlTfv.text
                                } else {
                                    serializeStableBlocksToHtml(stableBlocks)
                                }
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
                                    val parsed = EpubProcessor.parseContentIntoBlocks(context, contentHtmlTfv.text, currentChapter.titleId, currentChapter.title)
                                    stableBlocks.clear()
                                    parsed.forEach { b ->
                                        if (b is ContentBlock.Text) {
                                            val s = RichTextState()
                                            s.setHtml(b.htmlText)
                                            stableBlocks.add(EditorBlockState(id = b.id, isImage = false, richTextState = s))
                                        } else if (b is ContentBlock.Image) {
                                            stableBlocks.add(EditorBlockState(id = b.id, isImage = true, localPath = b.localPath))
                                        }
                                    }
                                    if (stableBlocks.isEmpty()) {
                                        val s = RichTextState()
                                        s.setHtml("")
                                        stableBlocks.add(EditorBlockState(isImage = false, richTextState = s))
                                    }
                                } else {
                                    // Turning on HTML Mode -> HTML string
                                    contentHtmlTfv = TextFieldValue(text = serializeStableBlocksToHtml(stableBlocks))
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
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

                Text(
                    text = if (isHtmlMode) "HTML / КОРРЕКЦИЯ ТЕГАМИ" else "ВИЗУАЛЬНЫЙ ТЕКСТ главы",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(start = 4.dp)
                )

                if (isHtmlMode) {
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
                } else {
                    stableBlocks.forEachIndexed { index, block ->
                        if (block.isImage) {
                            var showDeleteConfirmDialog by remember { mutableStateOf(false) }
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .wrapContentHeight()
                                    .padding(vertical = 4.dp)
                                    .combinedClickable(
                                        onClick = { Toast.makeText(context, "Зажмите для удаления иллюстрации", Toast.LENGTH_SHORT).show() },
                                        onLongClick = { showDeleteConfirmDialog = true }
                                    )
                                    .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), RoundedCornerShape(16.dp)),
                                elevation = CardDefaults.cardElevation(2.dp)
                            ) {
                                Box(modifier = Modifier.fillMaxWidth()) {
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
                                                stableBlocks.removeAt(index)
                                                if (stableBlocks.isEmpty()) {
                                                    val s = RichTextState()
                                                    s.setHtml("")
                                                    stableBlocks.add(EditorBlockState(isImage = false, richTextState = s))
                                                }
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
                        } else {
                            val state = block.richTextState
                            if (state != null) {
                                RichTextEditor(
                                    state = state,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .onFocusChanged { focusState ->
                                            if (focusState.isFocused) {
                                                activeBlockIndex = index
                                                activeRichTextState = state
                                            }
                                        },
                                    textStyle = TextStyle(
                                        fontSize = 16.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        lineHeight = 26.sp
                                    ),
                                    colors = RichTextEditorDefaults.richTextEditorColors(
                                        containerColor = Color.Transparent,
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent,
                                        disabledIndicatorColor = Color.Transparent
                                    )
                                )
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(100.dp)) // padding for bottom stats
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 32.dp, end = 24.dp)
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    shape = RoundedCornerShape(20.dp),
                    elevation = CardDefaults.cardElevation(4.dp)
                ) {
                    val statsHtmlText = if (isHtmlMode) contentHtmlTfv.text else serializeStableBlocksToHtml(stableBlocks)
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

fun decodeCyrillicFromHtmlEntities(html: String): String {
    val cyrillicMap = mapOf(
        "&Acy;" to "А", "&acy;" to "а", "&Bcy;" to "Б", "&bcy;" to "б",
        "&Vcy;" to "В", "&vcy;" to "в", "&Gcy;" to "Г", "&gcy;" to "г",
        "&Dcy;" to "Д", "&dcy;" to "д", "&Iecy;" to "Е", "&iecy;" to "е",
        "&Yocy;" to "Ё", "&yocy;" to "ё", "&Zhcy;" to "Ж", "&zhcy;" to "ж",
        "&Zcy;" to "З", "&zcy;" to "з", "&Icy;" to "И", "&icy;" to "и",
        "&Jcy;" to "Й", "&jcy;" to "й", "&Kcy;" to "К", "&kcy;" to "к",
        "&Lcy;" to "Л", "&lcy;" to "л", "&Mcy;" to "М", "&mcy;" to "м",
        "&Ncy;" to "Н", "&ncy;" to "н", "&Ocy;" to "О", "&ocy;" to "о",
        "&Pcy;" to "П", "&pcy;" to "п", "&Rcy;" to "Р", "&rcy;" to "р",
        "&Scy;" to "С", "&scy;" to "с", "&Tcy;" to "Т", "&tcy;" to "т",
        "&Ucy;" to "У", "&ucy;" to "у", "&Fcy;" to "Ф", "&fcy;" to "ф",
        "&Hcy;" to "Х", "&hcy;" to "х", "&Ccy;" to "Ц", "&ccy;" to "ц",
        "&Chcy;" to "Ч", "&chcy;" to "ч", "&Shcy;" to "Ш", "&shcy;" to "ш",
        "&Shhcy;" to "Щ", "&shhcy;" to "щ", "&Hardcy;" to "Ъ", "&hardcy;" to "ъ",
        "&Ycy;" to "Ы", "&ycy;" to "ы", "&Softcy;" to "Ь", "&softcy;" to "ь",
        "&Ecy;" to "Э", "&ecy;" to "э", "&Yucy;" to "Ю", "&yucy;" to "ю",
        "&Yacy;" to "Я", "&yacy;" to "я", "&numero;" to "№"
    )
    var decoded = html
    for ((entity, char) in cyrillicMap) {
        decoded = decoded.replace(entity, char)
    }
    return decoded
}

fun serializeStableBlocksToHtml(blocks: List<EditorBlockState>): String {
    val sb = StringBuilder()
    blocks.forEach { b ->
        if (b.isImage) {
            val file = File(b.localPath)
            sb.append("<div style=\"text-align:center; margin:12px 0;\"><img src=\"${file.name}\" style=\"max-width:100%;\" /></div>\n")
        } else {
            val html = decodeCyrillicFromHtmlEntities(b.richTextState?.toHtml() ?: "").trim()
            if (html.isNotEmpty()) {
                if (html.startsWith("<p>") || html.startsWith("<div>") || html.startsWith("<h")) {
                    sb.append(html).append("\n")
                } else {
                    sb.append("<p>").append(html).append("</p>\n")
                }
            }
        }
    }
    return sb.toString().trim()
}
