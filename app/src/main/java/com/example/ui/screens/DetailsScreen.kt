package com.example.ui.screens

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.toArgb
import android.widget.TextView
import android.text.Html
import coil.compose.AsyncImage
import com.example.data.Chapter
import com.example.data.SourceFile
import com.example.data.Title
import com.example.util.*
import com.example.viewmodel.BookViewModel
import java.io.File
import java.text.DecimalFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsScreen(
    viewModel: BookViewModel,
    titleId: Long,
    onBackClick: () -> Unit,
    onChapterEditClick: (Long) -> Unit
) {
    val context = LocalContext.current
    LaunchedEffect(titleId) {
        viewModel.selectTitle(titleId)
    }

    val title by viewModel.selectedTitle.collectAsState()
    val sourceFiles by viewModel.sourceFiles.collectAsState()
    val chapters by viewModel.chapters.collectAsState()

    var activeTab by remember { mutableStateOf(0) } // 0: Files, 1: Chapters, 2: Info, 3: Stats
    val tabNames = listOf("Файлы", "Главы", "Инфо", "Статистика")

    if (title == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        return
    }

    val currentTitle = title!!

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = currentTitle.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Segmented Tabs Row
            TabRow(
                selectedTabIndex = activeTab,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.primary,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[activeTab]),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            ) {
                tabNames.forEachIndexed { index, name ->
                    Tab(
                        selected = activeTab == index,
                        onClick = { activeTab = index },
                        text = {
                            Text(
                                text = name,
                                fontWeight = if (activeTab == index) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 14.sp
                            )
                        }
                    )
                }
            }

            // Tabs Content Area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (activeTab) {
                    0 -> FilesTabContent(
                        context = context,
                        viewModel = viewModel,
                        titleId = titleId,
                        sourceFiles = sourceFiles
                    )
                    1 -> ChaptersTabContent(
                        viewModel = viewModel,
                        titleId = titleId,
                        chapters = chapters,
                        sourceFiles = sourceFiles,
                        onChapterEditClick = onChapterEditClick
                    )
                    2 -> InfoTabContent(
                        context = context,
                        viewModel = viewModel,
                        title = currentTitle
                    )
                    3 -> StatsTabContent(
                        title = currentTitle,
                        sourceFiles = sourceFiles,
                        chapters = chapters
                    )
                }
            }
        }
    }
}

// ------------------- FILES TAB -------------------
@Composable
fun FilesTabContent(
    context: Context,
    viewModel: BookViewModel,
    titleId: Long,
    sourceFiles: List<SourceFile>
) {
    var fileToRename by remember { mutableStateOf<SourceFile?>(null) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameInputText by remember { mutableStateOf("") }
    var showImportHubDialog by remember { mutableStateOf(false) }

    // Unified launcher
    val unifiedLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        uris.forEach { uri ->
            val fileName = getFileNameFromUri(context, uri) ?: "imported_file"
            val fileSize = getFileSizeFromUri(context, uri)
            val ext = fileName.substringAfterLast(".", "").lowercase()
            when (ext) {
                "epub" -> {
                    viewModel.importEpub(context, titleId, uri, fileName, fileSize)
                    Toast.makeText(context, "Импорт EPUB тома запущен...", Toast.LENGTH_SHORT).show()
                }
                "fb2" -> {
                    Toast.makeText(context, "Конвертация книги $fileName запущена...", Toast.LENGTH_LONG).show()
                    viewModel.convertAndImportFile(context, titleId, uri, fileName, fileSize)
                }
                else -> {
                    Toast.makeText(context, "Конвертер: Формат .$ext не поддерживается. Выберите EPUB или FB2.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Individual standard launchers for maximum Android compatibility
    val epubLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        uris.forEach { uri ->
            val fileName = getFileNameFromUri(context, uri) ?: "book.epub"
            val fileSize = getFileSizeFromUri(context, uri)
            viewModel.importEpub(context, titleId, uri, fileName, fileSize)
        }
        if (uris.isNotEmpty()) {
            Toast.makeText(context, "Импорт ${uris.size} EPUB томов запущен...", Toast.LENGTH_SHORT).show()
        }
    }

    val fb2Launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        uris.forEach { uri ->
            val fileName = getFileNameFromUri(context, uri) ?: "book.fb2"
            val fileSize = getFileSizeFromUri(context, uri)
            Toast.makeText(context, "Конвертация FB2 $fileName в EPUB...", Toast.LENGTH_SHORT).show()
            viewModel.convertAndImportFile(context, titleId, uri, fileName, fileSize)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (sourceFiles.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CloudUpload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                    modifier = Modifier.size(80.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Нет загруженных файлов",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Импортируйте книги EPUB напрямую или загрузите FB2 для автоматической конвертации в EPUB.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(sourceFiles, key = { it.id }) { item ->
                    FileCard(
                        item = item,
                        onRename = {
                            fileToRename = item
                            renameInputText = item.fileName
                            showRenameDialog = true
                        },
                        onMoveUp = {
                            val idx = sourceFiles.indexOf(item)
                            if (idx > 0) {
                                val list = sourceFiles.toMutableList()
                                list.removeAt(idx)
                                list.add(idx - 1, item)
                                viewModel.reorderSourceFiles(list)
                            }
                        },
                        onMoveDown = {
                            val idx = sourceFiles.indexOf(item)
                            if (idx < sourceFiles.size - 1) {
                                val list = sourceFiles.toMutableList()
                                list.removeAt(idx)
                                list.add(idx + 1, item)
                                viewModel.reorderSourceFiles(list)
                            }
                        },
                        onDelete = {
                            viewModel.deleteSourceFile(item)
                        }
                    )
                }
            }
        }

        // Floating action button triggers the advanced conversion hub dialog
        FloatingActionButton(
            onClick = { showImportHubDialog = true },
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp)
        ) {
            Icon(
                imageVector = Icons.Default.CloudUpload,
                contentDescription = "Импорт книг"
            )
        }
    }

    // Beautiful Hub modal dialog with premium, clean Material 3 design and conversion cards
    if (showImportHubDialog) {
        Dialog(onDismissRequest = { showImportHubDialog = false }) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Column {
                            Text(
                                text = "Импорт и Конвертация",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Встроенный конвертер книг",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Выберите книгу. Формат FB2 будет автоматически адаптирован, разделен на главы и скомпилирован в EPUB для удобного редактирования.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Start
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Mode 1: EPUB Card
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showImportHubDialog = false
                                epubLauncher.launch("application/epub+zip")
                            },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        border = RowBorder(Color.Blue.copy(alpha = 0.1f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Book,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Импортировать EPUB",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Считывание оригинальных файлов .epub напрямую",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Mode 2: FB2 Card
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showImportHubDialog = false
                                // fb2 often picked with text/xml, application/xml, or wildcard
                                fb2Launcher.launch("*/*")
                            },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        border = RowBorder(Color.Green.copy(alpha = 0.1f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.MenuBook,
                                contentDescription = null,
                                tint = Color(0xFF2E7D32),
                                modifier = Modifier.size(32.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Конвертировать FB2 в EPUB",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Преобразование XML тегов, разделов и иллюстраций",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    TextButton(
                        onClick = { showImportHubDialog = false },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Закрыть", color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
    }

    if (showRenameDialog && fileToRename != null) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Переименовать файл") },
            text = {
                OutlinedTextField(
                    value = renameInputText,
                    onValueChange = { renameInputText = it },
                    label = { Text("Имя файла") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (renameInputText.isNotBlank()) {
                            viewModel.renameSourceFile(fileToRename!!, renameInputText.trim())
                            showRenameDialog = false
                        }
                    }
                ) {
                    Text("Готово")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Отмена", color = MaterialTheme.colorScheme.outline)
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(24.dp)
        )
    }
}

@Composable
private fun RowBorder(color: Color) = androidx.compose.foundation.BorderStroke(1.dp, color)

@Composable
fun FileCard(
    item: SourceFile,
    onRename: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Drag indicators / Move actions
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                IconButton(onClick = onMoveUp, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = "Вверх",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(16.dp)
                )
                IconButton(onClick = onMoveDown, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Вниз",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.fileName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Размер: ${formatBytes(item.fileSize)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Actions
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = onRename) {
                    Icon(
                        imageVector = Icons.Default.EditNote,
                        contentDescription = "Изменить имя",
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Удалить",
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}


// ------------------- CHAPTERS TAB -------------------
@Composable
fun ChaptersTabContent(
    viewModel: BookViewModel,
    titleId: Long,
    chapters: List<Chapter>,
    sourceFiles: List<SourceFile>,
    onChapterEditClick: (Long) -> Unit
) {
    val context = LocalContext.current
    var isSelectionMode by remember { mutableStateOf(false) }
    val selectedChapters = remember { mutableStateListOf<Long>() }

    var previewChWithHtml by remember { mutableStateOf<Chapter?>(null) }
    var chapterToDelete by remember { mutableStateOf<Chapter?>(null) }
    var showAddManualDialog by remember { mutableStateOf(false) }
    var newManualChapterTitle by remember { mutableStateOf("") }
    var isMergingExporting by remember { mutableStateOf(false) }
    var showExportOptions by remember { mutableStateOf(false) }
    var isGenerateToc by remember { mutableStateOf(true) }

    // Stable optimized callbacks for high scrolling performance (no lambda churn on scroll)
    val onToggleSelection = remember(selectedChapters) {
        { id: Long ->
            if (selectedChapters.contains(id)) {
                selectedChapters.remove(id)
            } else {
                selectedChapters.add(id)
            }
            Unit
        }
    }
    val onMoveUpChecked = remember(chapters) {
        { item: Chapter ->
            val idx = chapters.indexOf(item)
            if (idx > 0) {
                val list = chapters.toMutableList()
                list.removeAt(idx)
                list.add(idx - 1, item)
                viewModel.reorderChapters(list)
            }
        }
    }
    val onMoveDownChecked = remember(chapters) {
        { item: Chapter ->
            val idx = chapters.indexOf(item)
            if (idx < chapters.size - 1) {
                val list = chapters.toMutableList()
                list.removeAt(idx)
                list.add(idx + 1, item)
                viewModel.reorderChapters(list)
            }
        }
    }
    val onPreviewClickChecked = remember {
        { item: Chapter ->
            previewChWithHtml = item
        }
    }
    val onEditClickChecked = remember {
        { id: Long ->
            onChapterEditClick(id)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Control button row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isSelectionMode) {
                    val allSelected = selectedChapters.size == chapters.size && chapters.isNotEmpty()
                    TextButton(onClick = {
                        if (allSelected) {
                            selectedChapters.clear()
                        } else {
                            selectedChapters.clear()
                            selectedChapters.addAll(chapters.map { it.id })
                        }
                    }) {
                        Text(
                            text = if (allSelected) "Снять выбор" else "Выбрать все (${chapters.size})",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                } else {
                    Text(
                        text = "Всего глав: ${chapters.size}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                TextButton(onClick = {
                    isSelectionMode = !isSelectionMode
                    selectedChapters.clear()
                }) {
                    Text(
                        text = if (isSelectionMode) "Отмена" else "Управление",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (chapters.isEmpty()) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.MenuBook,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                        modifier = Modifier.size(80.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Глав пока нет",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Откройте вкладку 'Файлы' и загрузите EPUB архивы, или создайте главу вручную.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 100.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    items(
                        items = chapters,
                        key = { it.id },
                        contentType = { "chapter_row" }
                    ) { item ->
                        val isSelected = selectedChapters.contains(item.id)
                        ChapterRowItem(
                            item = item,
                            isSelectionMode = isSelectionMode,
                            isSelected = isSelected,
                            onToggleSelection = { onToggleSelection(item.id) },
                            onMoveUp = { onMoveUpChecked(item) },
                            onMoveDown = { onMoveDownChecked(item) },
                            onPreviewClick = { onPreviewClickChecked(item) },
                            onEditClick = { onEditClickChecked(item.id) },
                            onLongClick = { chapterToDelete = item }
                        )
                    }
                }
            }
        }

        // Floating Action Buttons Section
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // MERGE FILE CHIP
            if (chapters.isNotEmpty() && !isSelectionMode) {
                val hasMultipleFiles = sourceFiles.size >= 2
                val buttonText = if (hasMultipleFiles) "Слить воедино" else "Экспорт EPUB"
                
                ExtendedFloatingActionButton(
                    onClick = { showExportOptions = true },
                    icon = { Icon(Icons.Default.Save, contentDescription = null) },
                    text = { Text(buttonText) },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.height(48.dp)
                )
            }

            if (!isSelectionMode) {
                FloatingActionButton(
                    onClick = { showAddManualDialog = true },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Добавить главу вручную"
                    )
                }
            }
        }

        // Export Options Dialog
        if (showExportOptions) {
            AlertDialog(
                onDismissRequest = { showExportOptions = false },
                title = { Text("Экспорт EPUB", fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Настройки сохранения:")
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { isGenerateToc = !isGenerateToc }
                                .padding(vertical = 4.dp)
                        ) {
                            Checkbox(
                                checked = isGenerateToc,
                                onCheckedChange = { isGenerateToc = it }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Генерировать файл содержания",
                                fontSize = 14.sp
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showExportOptions = false
                            isMergingExporting = true
                            viewModel.exportMergedEpub(context, titleId, isGenerateToc) { file ->
                                isMergingExporting = false
                                if (file != null) {
                                    val msg = if (sourceFiles.size >= 2) {
                                        "EPUB успешно слит в папку Download: ${file.name}"
                                    } else {
                                        "EPUB успешно сохранен в папку Download: ${file.name}"
                                    }
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(context, "Ошибка сборки EPUB", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    ) {
                        Text("СОХРАНИТЬ")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showExportOptions = false }) {
                        Text("Отмена", color = MaterialTheme.colorScheme.outline)
                    }
                },
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(24.dp)
            )
        }

        // Selection Actions Bar
        AnimatedVisibility(
            visible = isSelectionMode && selectedChapters.isNotEmpty(),
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f)
                ),
                elevation = CardDefaults.cardElevation(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Выделено элементов: ${selectedChapters.size}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = {
                                viewModel.keepSelectedChapters(selectedChapters.toList())
                                isSelectionMode = false
                                selectedChapters.clear()
                                Toast.makeText(context, "Лишние главы удалены!", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Оставить выдел.")
                        }

                        Button(
                            onClick = {
                                viewModel.deleteSelectedChapters(selectedChapters.toList())
                                isSelectionMode = false
                                selectedChapters.clear()
                                Toast.makeText(context, "Выделенные главы удалены!", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Удалить выдел.")
                        }
                    }
                }
            }
        }
    }

    // REVIEW PREVIEW DIALOG
    if (previewChWithHtml != null) {
        val chapter = previewChWithHtml!!
        Dialog(onDismissRequest = { previewChWithHtml = null }) {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(24.dp))
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Header
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 20.dp, start = 20.dp, end = 20.dp)
                    ) {
                        Text(
                            text = chapter.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.align(Alignment.CenterStart)
                        )
                        IconButton(
                            onClick = { previewChWithHtml = null },
                            modifier = Modifier.align(Alignment.CenterEnd)
                        ) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "Закрыть")
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    val contentBlocks = remember(chapter.contentHtml, chapter.title) {
                        EpubProcessor.parseContentIntoBlocks(context, chapter.contentHtml, chapter.titleId, chapter.title)
                    }

                    // Chapter content rendering with support for multiple inline illustrations
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                    ) {
                        items(contentBlocks) { block ->
                            when (block) {
                                is ContentBlock.Text -> {
                                    val rawHtml = block.htmlText
                                    if (rawHtml.isNotBlank()) {
                                        val textColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
                                        AndroidView(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 8.dp),
                                            factory = { ctx ->
                                                TextView(ctx).apply {
                                                    textSize = 18f
                                                    setLineSpacing(6f, 1.3f)
                                                }
                                            },
                                            update = { textView ->
                                                textView.setTextColor(textColor.toArgb())
                                                textView.text = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                                                    Html.fromHtml(rawHtml, Html.FROM_HTML_MODE_LEGACY)
                                                } else {
                                                    @Suppress("DEPRECATION")
                                                    Html.fromHtml(rawHtml)
                                                }
                                            }
                                        )
                                    }
                                }
                                is ContentBlock.Image -> {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .wrapContentHeight()
                                            .padding(vertical = 12.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        AsyncImage(
                                            model = File(block.localPath),
                                            contentDescription = "Иллюстрация главы",
                                            contentScale = ContentScale.FillWidth,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }

    if (showAddManualDialog) {
        AlertDialog(
            onDismissRequest = { showAddManualDialog = false },
            title = { Text("Новая глава") },
            text = {
                OutlinedTextField(
                    value = newManualChapterTitle,
                    onValueChange = { newManualChapterTitle = it },
                    label = { Text("Название главы") },
                    singleLine = true,
                    placeholder = { Text("Глава 1: ...") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newManualChapterTitle.isNotBlank()) {
                            viewModel.addManualChapter(titleId, newManualChapterTitle.trim())
                            showAddManualDialog = false
                            newManualChapterTitle = ""
                        }
                    }
                ) {
                    Text("Создать")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddManualDialog = false }) {
                    Text("Отмена", color = MaterialTheme.colorScheme.outline)
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(24.dp)
        )
    }

    if (chapterToDelete != null) {
        AlertDialog(
            onDismissRequest = { chapterToDelete = null },
            title = { Text("Удалить главу?") },
            text = { Text("Вы действительно хотите удалить главу \"${chapterToDelete?.title}\"? Это действие нельзя будет отменить.") },
            confirmButton = {
                Button(
                    onClick = {
                        chapterToDelete?.let {
                            viewModel.deleteSelectedChapters(listOf(it.id))
                        }
                        chapterToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Удалить")
                }
            },
            dismissButton = {
                TextButton(onClick = { chapterToDelete = null }) {
                    Text("Отмена", color = MaterialTheme.colorScheme.outline)
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(24.dp)
        )
    }

    if (isMergingExporting) {
        Dialog(onDismissRequest = {}) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .wrapContentHeight(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 4.dp,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = if (sourceFiles.size >= 2) "Идет слияние томов..." else "Идет экспорт в EPUB...",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Пожалуйста, подождите. Собираем главы и упаковываем книгу в итоговый файл EPUB.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChapterRowItem(
    item: Chapter,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onToggleSelection: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onPreviewClick: () -> Unit,
    onEditClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
            else MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (isSelectionMode) {
                        onToggleSelection()
                    } else {
                        onEditClick()
                    }
                },
                onLongClick = onLongClick
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelection() },
                    colors = CheckboxDefaults.colors(
                        checkedColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.padding(end = 8.dp)
                )
            } else {
                // Drag handle move directions
                Column(
                    modifier = Modifier.padding(end = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    IconButton(onClick = onMoveUp, modifier = Modifier.size(24.dp)) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowUp,
                            contentDescription = "Вверх",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = onMoveDown, modifier = Modifier.size(24.dp)) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Вниз",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${item.wordCount} слов",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        text = "${item.characterCount} симв.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    if (!item.previewImagePath.isNullOrEmpty()) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = "Содержит иллюстрации",
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            // Actions
            if (!isSelectionMode) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onPreviewClick) {
                        Icon(
                            imageVector = Icons.Default.Visibility,
                            contentDescription = "Просмотр",
                            tint = MaterialTheme.colorScheme.outline
                        )
                    }
                    IconButton(onClick = onEditClick) {
                        Icon(
                            imageVector = Icons.Default.EditNote,
                            contentDescription = "Редактировать",
                            tint = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }
        }
    }
}


// ------------------- INFO TAB -------------------
@Composable
fun InfoTabContent(
    context: Context,
    viewModel: BookViewModel,
    title: Title
) {
    val initialAuthor = title.author?.let { if (it == "Unknown Author" || it == "Автор") "" else it } ?: ""
    val initialDesc = title.description?.let { 
        if (it == "No description available" || it == "FB2 Converted EPUB Book" || it == "Описание отсутствует...") "" else it 
    } ?: ""
    
    var titleName by remember(title) { mutableStateOf(title.name) }
    var authorName by remember(title) { mutableStateOf(initialAuthor) }
    var description by remember(title) { mutableStateOf(initialDesc) }
    var outputFileName by remember(title) { mutableStateOf(title.outputFileName ?: "") }
    var coverPath by remember(title) { mutableStateOf(title.coverImage) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val localPath = viewModel.saveCoverImageLocally(context, uri)
            coverPath = localPath
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // Oblozhka Cover Image Card
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(150.dp)
                        .height(210.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { imagePickerLauncher.launch("image/*") }
                ) {
                    if (!coverPath.isNullOrEmpty()) {
                        AsyncImage(
                            model = File(coverPath!!),
                            contentDescription = "Обложка тайтла",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        // Styled cover placeholder
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.PhotoCamera,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(36.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Выбрать обложку",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 8.dp)
                                )
                            }
                        }
                    }

                    // Hover badge if cover loaded
                    if (!coverPath.isNullOrEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.3f)),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            Text(
                                text = "Изменить",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier
                                    .padding(bottom = 8.dp)
                                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }

        // Title Name Input
        item {
            OutlinedTextField(
                value = titleName,
                onValueChange = { titleName = it },
                label = { Text("Название тайтла") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Author Name Input
        item {
            OutlinedTextField(
                value = authorName,
                onValueChange = { authorName = it },
                label = { Text("Имя автора / переводчика") },
                placeholder = { Text("Автор") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Export/Output name input
        item {
            OutlinedTextField(
                value = outputFileName,
                onValueChange = { outputFileName = it },
                label = { Text("Имя результирующего EPUB файла") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Description Bio Textarea
        item {
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Описание / Синопсис") },
                placeholder = { Text("Описание отсутствует...") },
                minLines = 4,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Button save
        item {
            Button(
                onClick = {
                    viewModel.updateTitleInfo(
                        titleId = title.id,
                        name = titleName.trim(),
                        author = authorName.trim(),
                        description = description.trim(),
                        coverImage = coverPath,
                        outputFileName = outputFileName.trim()
                    )
                    Toast.makeText(context, "Информация успешно сохранена!", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Сохранить изменения", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}


// ------------------- STATS TAB -------------------
@Composable
fun StatsTabContent(
    title: Title,
    sourceFiles: List<SourceFile>,
    chapters: List<Chapter>
) {
    val totalWords = chapters.sumOf { it.wordCount }
    val totalCharacters = chapters.sumOf { it.characterCount }
    val totalComponentSize = sourceFiles.sumOf { it.fileSize }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // Words & Chars Summary Card
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Words
                Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Всего слов",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = formatStatsNumber(totalWords),
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Точный подсчет",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                // Characters
                Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Символов",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = formatStatsNumber(totalCharacters),
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Без учета тегов HTML",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }

        // Export Estimated output size
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Save,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Column {
                            Text(
                                text = "Общий вес томов",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Входной объем EPUB компонентов",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Text(
                        text = formatBytes(totalComponentSize),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // List individual files list sizes
        if (sourceFiles.isNotEmpty()) {
            item {
                Text(
                    text = "Составляющие EPUB тома",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                )
            }

            items(sourceFiles) { file ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FilePresent,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Column {
                            Text(
                                text = file.fileName,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 200.dp)
                            )
                            Text(
                                text = "Том ${sourceFiles.indexOf(file) + 1}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }

                    Text(
                        text = formatBytes(file.fileSize),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.background,
                                RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }

        // Visual Custom Canvas graph to display atmospheric statistics
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Вклад по главам (Слова)",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    val statsColors = listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.secondary,
                        MaterialTheme.colorScheme.tertiary
                    )
                    
                    // Canvas chart
                    Canvas(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        val canvasWidth = size.width
                        val canvasHeight = size.height
                        
                        // Limit to at most last 10 chapters
                        val subset = chapters.takeLast(10)
                        if (subset.isNotEmpty()) {
                            val maxWords = subset.maxOf { it.wordCount }.coerceAtLeast(100).toFloat()
                            val barWidth = (canvasWidth / (subset.size * 2))
                            val spacing = barWidth
                            
                            subset.forEachIndexed { i, ch ->
                                val x = spacing + (i * 2 * barWidth)
                                val barHeight = (ch.wordCount.toFloat() / maxWords) * canvasHeight
                                val y = canvasHeight - barHeight
                                
                                drawRoundRect(
                                    color = statsColors[i % statsColors.size].copy(alpha = 0.8f),
                                    topLeft = androidx.compose.ui.geometry.Offset(x, y),
                                    size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f)
                                )
                            }
                        } else {
                            // Empty stats display line
                            drawLine(
                                color = Color.White.copy(alpha = 0.2f),
                                start = androidx.compose.ui.geometry.Offset(0f, canvasHeight / 2),
                                end = androidx.compose.ui.geometry.Offset(canvasWidth, canvasHeight / 2),
                                strokeWidth = 4f
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Пропорциональная активность в последних разделах",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}


// ------------------- HELPERS -------------------
private fun getFileNameFromUri(context: Context, uri: Uri): String? {
    var name: String? = null
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (idx != -1 && cursor.moveToFirst()) {
            name = cursor.getString(idx)
        }
    }
    return name ?: uri.lastPathSegment
}

private fun getFileSizeFromUri(context: Context, uri: Uri): Long {
    var size: Long = 0
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
        if (idx != -1 && cursor.moveToFirst()) {
            size = cursor.getLong(idx)
        }
    }
    return size
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 Б"
    val units = arrayOf("Б", "КБ", "МБ", "ГБ")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    return DecimalFormat("#,##0.#").format(bytes / Math.pow(1024.0, digitGroups.toDouble())) + " " + units[digitGroups]
}

private fun formatStatsNumber(number: Int): String {
    return DecimalFormat("#,###").format(number)
}
