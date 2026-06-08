package com.example.ui.screens

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Color
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
import com.example.util.WordStatsHelper
import com.example.viewmodel.BookViewModel
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.material3.OutlinedRichTextEditor
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditor

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

    val richTextState = rememberRichTextState()
    var isInitialLoaded by remember { mutableStateOf(false) }

    // HTML Mode specific state
    var contentHtmlTfv by remember(currentChapter) {
        val html = currentChapter.contentHtml
        mutableStateOf(TextFieldValue(if (html == "<p>Введите текст вашей новой главы...</p>") "" else html))
    }

    LaunchedEffect(currentChapter.contentHtml) {
        if (!isInitialLoaded) {
            val initialHtml = if (currentChapter.contentHtml == "<p>Введите текст вашей новой главы...</p>") "" else currentChapter.contentHtml
            richTextState.setHtml(initialHtml)
            isInitialLoaded = true
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
                                    decodeCyrillicFromHtmlEntities(richTextState.toHtml())
                                }
                                viewModel.updateChapterContent(
                                    chapterId = chapterId,
                                    title = chapterTitle.trim(),
                                    contentHtml = finalHtml,
                                    previewImagePath = currentChapter.previewImagePath
                                )
                                android.widget.Toast.makeText(context, "Глава сохранена!", android.widget.Toast.LENGTH_SHORT).show()
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
                        val isBoldActive = richTextState.currentSpanStyle.fontWeight == FontWeight.Bold
                        IconButton(
                            onClick = {
                                if (isHtmlMode) {
                                    contentHtmlTfv = insertHtmlTag(contentHtmlTfv, "<b>", "</b>")
                                } else {
                                    richTextState.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold))
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
                        val isItalicActive = richTextState.currentSpanStyle.fontStyle == FontStyle.Italic
                        IconButton(
                            onClick = {
                                if (isHtmlMode) {
                                    contentHtmlTfv = insertHtmlTag(contentHtmlTfv, "<i>", "</i>")
                                } else {
                                    richTextState.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic))
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
                        val isUnderlineActive = richTextState.currentSpanStyle.textDecoration == TextDecoration.Underline
                        IconButton(
                            onClick = {
                                if (isHtmlMode) {
                                    contentHtmlTfv = insertHtmlTag(contentHtmlTfv, "<u>", "</u>")
                                } else {
                                    richTextState.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.Underline))
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
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val hasActiveStyle = (!isHtmlMode) && (
                            richTextState.currentSpanStyle.fontWeight == FontWeight.Bold ||
                            richTextState.currentSpanStyle.fontStyle == FontStyle.Italic ||
                            richTextState.currentSpanStyle.textDecoration == TextDecoration.Underline
                        )

                        IconButton(
                            onClick = {
                                if (hasActiveStyle) {
                                    android.widget.Toast.makeText(context, "Отключите форматирование текста перед переходом в HTML режим", android.widget.Toast.LENGTH_SHORT).show()
                                    return@IconButton
                                }
                                if (isHtmlMode) {
                                    // Turning off HTML Mode -> Visual
                                    richTextState.setHtml(contentHtmlTfv.text)
                                } else {
                                    // Turning on HTML Mode -> HTML string
                                    contentHtmlTfv = TextFieldValue(text = decodeCyrillicFromHtmlEntities(richTextState.toHtml()))
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
                    OutlinedRichTextEditor(
                        state = richTextState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 400.dp),
                        textStyle = TextStyle(
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = 26.sp
                        )
                    )
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
                    // Update stats only string calculation
                    val statsHtmlText = if (isHtmlMode) contentHtmlTfv.text else decodeCyrillicFromHtmlEntities(richTextState.toHtml())
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
