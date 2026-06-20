package com.aistudio.epubedit.kqptxy.util

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class ParsedEpub(
    val title: String?,
    val author: String?,
    val description: String?,
    val coverImagePath: String?,
    val chapters: List<ParsedChapter>,
    val originalEpubDirPath: String? = null,
    val originalOpfRelativePath: String? = null
)

data class ParsedChapter(
    val title: String,
    val contentHtml: String,
    val wordCount: Int,
    val characterCount: Int,
    val previewImagePath: String? = null,
    val originalFilePath: String? = null,
    val anchorStart: String? = null,
    val anchorEnd: String? = null,
    val displayHtml: String? = null,
    val sourceFileId: Long? = null
)

object EpubProcessor {
    private const val TAG = "EpubProcessor"

    /**
     * Parses an EPUB file from a given content Uri or file input stream.
     * Extracts info, chapters, and cover image.
     */
    fun parseEpub(context: Context, uri: Uri, titleId: Long? = null, sourceFileId: Long? = null): ParsedEpub? {
        val keepOriginal = !context.getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE).getBoolean("pref_convert_epub_system", false)
        val resolver = context.contentResolver
        val tempDir = File(context.cacheDir, "epub_unzipped_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        // 1. Unzip the whole EPUB into a temp directory to allow multi-pass lookups
        try {
            resolver.openInputStream(uri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zipInputStream ->
                    var entry: ZipEntry? = zipInputStream.getNextEntry()
                    while (entry != null) {
                        val outFile = File(tempDir, entry.name)
                        
                        // Prevent Zip Slip vulnerability
                        val canonicalDestPath = outFile.canonicalPath
                        val canonicalDirPath = tempDir.canonicalPath
                        if (!canonicalDestPath.startsWith(canonicalDirPath + File.separator)) {
                            throw SecurityException("Zip Slip Vulnerability: Entry is outside of the target dir: ${entry.name}")
                        }
                        
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { fos ->
                                zipInputStream.copyTo(fos)
                            }
                        }
                        zipInputStream.closeEntry()
                        entry = zipInputStream.getNextEntry()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unzipping EPUB", e)
            tempDir.deleteRecursively()
            return null
        }

        var savedOriginalEpubDirPath: String? = null
        if (keepOriginal && titleId != null) {
            try {
                val originalEpubDir = if (sourceFileId != null) {
                    File(context.filesDir, "epub_originals/book_${titleId}/source_${sourceFileId}")
                } else {
                    File(context.filesDir, "epub_originals/book_$titleId")
                }
                
                if (originalEpubDir.exists()) originalEpubDir.deleteRecursively()
                originalEpubDir.mkdirs()
                
                fun copyRecursively(src: File, dst: File) {
                    if (src.isDirectory) {
                        dst.mkdirs()
                        src.listFiles()?.forEach { copyRecursively(it, File(dst, it.name)) }
                    } else {
                        src.copyTo(dst, overwrite = true)
                    }
                }
                copyRecursively(tempDir, originalEpubDir)
                savedOriginalEpubDirPath = originalEpubDir.absolutePath
                Log.d(TAG, "Saved 100% original EPUB structure to $savedOriginalEpubDirPath")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save original epub structure", e)
            }
        }

        try {
            // 2. Scan tempDir for HTML, image and CSS assets
            val htmlFiles = mutableListOf<File>()
            val imageFiles = mutableListOf<File>()
            val cssFiles = mutableListOf<File>()
            val otherFiles = mutableListOf<File>()

            fun scanDir(dir: File) {
                dir.listFiles()?.forEach { file ->
                    if (file.isDirectory) {
                        scanDir(file)
                    } else {
                        val ext = file.extension.lowercase()
                        if (ext in listOf("html", "xhtml", "htm")) {
                            htmlFiles.add(file)
                        } else if (ext in listOf("jpg", "jpeg", "png", "webp", "gif")) {
                            imageFiles.add(file)
                        } else if (ext == "css") {
                            cssFiles.add(file)
                        } else if (ext in listOf("ttf", "otf", "woff", "woff2", "js", "mp3", "mp4", "m4a", "ogg", "wav", "xml")) {
                            otherFiles.add(file)
                        }
                    }
                }
            }
            scanDir(tempDir)

            // 3. Keep extracted assets persistently
            val mediaDir = File(context.filesDir, "epub_media")
            val bookMediaDir = if (titleId != null) File(mediaDir, "book_$titleId") else mediaDir
            if (!bookMediaDir.exists()) bookMediaDir.mkdirs()

            // 3a. Copy and save CSS files persistently
            val cssDir = File(bookMediaDir, "css_files")
            if (cssDir.exists()) cssDir.deleteRecursively()
            cssDir.mkdirs()

            cssFiles.forEach { file ->
                try {
                    val relativePath = file.relativeTo(tempDir).path.replace('\\', '/')
                    val destFile = File(cssDir, relativePath)
                    destFile.parentFile?.mkdirs()
                    file.copyTo(destFile, overwrite = true)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to copy CSS: ${file.name}", e)
                }
            }

            // 3b. Copy other assets persistently (fonts, js, etc)
            val otherAssetsDir = File(bookMediaDir, "other_assets")
            if (otherAssetsDir.exists()) otherAssetsDir.deleteRecursively()
            otherAssetsDir.mkdirs()

            otherFiles.forEach { file ->
                try {
                    val relativePath = file.relativeTo(tempDir).path.replace('\\', '/')
                    val destFile = File(otherAssetsDir, relativePath)
                    destFile.parentFile?.mkdirs()
                    file.copyTo(destFile, overwrite = true)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to copy asset: ${file.name}", e)
                }
            }

            val imageMap = mutableMapOf<String, String>() // filename -> persistent absolute path
        imageFiles.forEach { file ->
            val destName = if (titleId != null) file.name else "media_${System.currentTimeMillis()}_${file.name}"
            val destFile = File(bookMediaDir, destName)
            try {
                file.copyTo(destFile, overwrite = true)
                imageMap[file.name.lowercase()] = destFile.absolutePath
                // Also store complete key using relative path in lower case
                val relativePath = file.relativeTo(tempDir).path.lowercase().replace('\\', '/')
                imageMap[relativePath] = destFile.absolutePath
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy image ${file.name}", e)
            }
        }

        // Find the OPF file path from container.xml
        val containerFile = File(tempDir, "META-INF/container.xml")
        var opfPath = "OEBPS/content.opf" // fallback default
        if (containerFile.exists()) {
            try {
                val dbFactory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                dbFactory.isNamespaceAware = false
                val dBuilder = dbFactory.newDocumentBuilder()
                dBuilder.setEntityResolver { _, _ -> org.xml.sax.InputSource(java.io.StringReader("")) }
                val doc = dBuilder.parse(containerFile)
                val rootfiles = doc.getElementsByTagName("rootfile")
                if (rootfiles.length > 0) {
                    val rootfile = rootfiles.item(0) as org.w3c.dom.Element
                    val path = rootfile.getAttribute("full-path")
                    if (path.isNotEmpty()) {
                        opfPath = path
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error DOM parsing container.xml", e)
            }
        }
        val opfFile = File(tempDir, opfPath)
        val opfRelativePath = try { opfFile.relativeTo(tempDir).path.replace('\\', '/') } catch(e: Exception) { opfPath }
        val opfDir = opfFile.parentFile ?: tempDir

        // Extract metadata, manifest, and spine from OPF
        val manifestItems = mutableMapOf<String, ManifestItem>() // id -> ManifestItem
        val spineItems = mutableListOf<String>() // ordered idrefs
        var extractedTitle: String? = null
        var extractedAuthor: String? = null
        var extractedDesc: String? = null

        if (opfFile.exists()) {
            try {
                val dbFactory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                dbFactory.isNamespaceAware = true
                val dBuilder = dbFactory.newDocumentBuilder()
                dBuilder.setEntityResolver { _, _ -> org.xml.sax.InputSource(java.io.StringReader("")) }
                val doc = dBuilder.parse(opfFile)
                doc.documentElement.normalize()

                // Parse manifest items
                val items = doc.getElementsByTagNameNS("*", "item")
                for (i in 0 until items.length) {
                    val item = items.item(i) as org.w3c.dom.Element
                    val id = item.getAttribute("id")
                    val href = item.getAttribute("href")
                    val mediaType = item.getAttribute("media-type")
                    val properties = item.getAttribute("properties").ifEmpty { null }
                    if (id.isNotEmpty() && href.isNotEmpty()) {
                        manifestItems[id] = ManifestItem(id, href, mediaType, properties)
                    }
                }

                // Parse spine items
                val itemrefs = doc.getElementsByTagNameNS("*", "itemref")
                for (i in 0 until itemrefs.length) {
                    val itemref = itemrefs.item(i) as org.w3c.dom.Element
                    val idref = itemref.getAttribute("idref")
                    if (idref.isNotEmpty()) {
                        spineItems.add(idref)
                    }
                }

                // Extract Metadata
                val titles = doc.getElementsByTagNameNS("*", "title")
                if (titles.length > 0) extractedTitle = titles.item(0).textContent

                val creators = doc.getElementsByTagNameNS("*", "creator")
                if (creators.length > 0) extractedAuthor = creators.item(0).textContent

                val descriptions = doc.getElementsByTagNameNS("*", "description")
                if (descriptions.length > 0) extractedDesc = descriptions.item(0).textContent

            } catch (e: Exception) {
                Log.e(TAG, "Failed DOM reading content.opf, falling back to Regex parsing", e)
                try {
                    val opfText = opfFile.readText(Charsets.UTF_8)
                    
                    // Parse manifest items using Regex
                    val itemRegex = Regex("<item\\s+([^>]*)\\s*/?>", RegexOption.IGNORE_CASE)
                    val idRegex = Regex("id\\s*=\\s*['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE)
                    val hrefRegex = Regex("href\\s*=\\s*['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE)
                    val mediaTypeRegex = Regex("media-type\\s*=\\s*['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE)
                    val propertiesRegex = Regex("properties\\s*=\\s*['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE)
                    
                    itemRegex.find(opfText) // trigger parsing
                    itemRegex.findAll(opfText).forEach { match ->
                        val attrs = match.groupValues[1]
                        val id = idRegex.find(attrs)?.groupValues?.get(1) ?: ""
                        val href = hrefRegex.find(attrs)?.groupValues?.get(1) ?: ""
                        val mediaType = mediaTypeRegex.find(attrs)?.groupValues?.get(1) ?: ""
                        val properties = propertiesRegex.find(attrs)?.groupValues?.get(1)
                        if (id.isNotEmpty() && href.isNotEmpty()) {
                            manifestItems[id] = ManifestItem(id, href, mediaType, properties)
                        }
                    }
                    
                    // Parse spine items using Regex
                    val itemrefRegex = Regex("<itemref\\s+([^>]*)\\s*/?>", RegexOption.IGNORE_CASE)
                    val idrefRegex = Regex("idref\\s*=\\s*['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE)
                    itemrefRegex.findAll(opfText).forEach { match ->
                        val attrs = match.groupValues[1]
                        val idref = idrefRegex.find(attrs)?.groupValues?.get(1) ?: ""
                        if (idref.isNotEmpty()) {
                            spineItems.add(idref)
                        }
                    }

                    // Parse metadata tags
                    val titleRegex = Regex("<dc:title(?:[^>]*)?>(.*?)</dc:title>", RegexOption.IGNORE_CASE)
                    extractedTitle = titleRegex.find(opfText)?.groupValues?.get(1) ?: extractedTitle

                    val creatorRegex = Regex("<dc:creator(?:[^>]*)?>(.*?)</dc:creator>", RegexOption.IGNORE_CASE)
                    extractedAuthor = creatorRegex.find(opfText)?.groupValues?.get(1) ?: extractedAuthor

                    val descRegex = Regex("<dc:description(?:[^>]*)?>(.*?)</dc:description>", RegexOption.IGNORE_CASE)
                    extractedDesc = descRegex.find(opfText)?.groupValues?.get(1) ?: extractedDesc
                } catch (ex: Exception) {
                    Log.e(TAG, "Regex fallback for content.opf, fatal error", ex)
                }
            }
        }

        // Resolving Cover Image from OPF or Fallback name matching
        var coverImagePath: String? = null
        if (opfFile.exists() && manifestItems.isNotEmpty()) {
            try {
                val opfContent = opfFile.readText(Charsets.UTF_8)
                val coverMetaRegex = Regex("<meta[^>]+name=\"cover\"[^>]+content=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
                val coverMetaMatch = coverMetaRegex.find(opfContent)
                val coverMetaId = coverMetaMatch?.groupValues?.get(1)
                if (coverMetaId != null) {
                    val item = manifestItems[coverMetaId]
                    if (item != null) {
                        val decodedCoverHref = java.net.URLDecoder.decode(item.href, "UTF-8")
                        val coverFile = File(opfDir, decodedCoverHref)
                        if (coverFile.exists()) {
                            coverImagePath = imageMap[coverFile.name.lowercase()]
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error looking up cover in OPF", e)
            }
        }
        if (coverImagePath == null) {
            val coverFile = imageFiles.firstOrNull { it.nameWithoutExtension.lowercase().contains("cover") }
                ?: imageFiles.maxByOrNull { it.length() }
            if (coverFile != null) {
                coverImagePath = imageMap[coverFile.name.lowercase()]
            }
        }

        // Parse toc.ncx Table of Contents for splitting pages/chapters
        val ncxItem = manifestItems.values.firstOrNull {
            it.mediaType?.lowercase() == "application/x-dtbncx+xml" || it.href.lowercase().endsWith(".ncx")
        }
        var ncxFileResolved = if (ncxItem != null) File(opfDir, ncxItem.href) else null
        if (ncxFileResolved == null || !ncxFileResolved.exists()) {
            var foundNcx: File? = null
            fun findNcx(dir: File) {
                dir.listFiles()?.forEach { file ->
                    if (file.isDirectory) findNcx(file)
                    else if (file.extension.lowercase() == "ncx") foundNcx = file
                }
            }
            findNcx(tempDir)
            ncxFileResolved = foundNcx
        }

        data class NcxNavPoint(val title: String, val src: String, val fileHref: String, val anchor: String?, val playOrder: Int)
        val ncxNavPoints = mutableListOf<NcxNavPoint>()

        if (ncxFileResolved != null && ncxFileResolved.exists()) {
            try {
                val dbFactory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                dbFactory.isNamespaceAware = false
                val dBuilder = dbFactory.newDocumentBuilder()
                dBuilder.setEntityResolver { _, _ -> org.xml.sax.InputSource(java.io.StringReader("")) }
                val doc = dBuilder.parse(ncxFileResolved)
                doc.documentElement.normalize()

                val navPoints = doc.getElementsByTagName("navPoint")
                for (i in 0 until navPoints.length) {
                    val node = navPoints.item(i)
                    if (node is org.w3c.dom.Element) {
                        var title = ""
                        val navLabels = node.getElementsByTagName("navLabel")
                        if (navLabels.length > 0) {
                            val labelEl = navLabels.item(0) as org.w3c.dom.Element
                            val texts = labelEl.getElementsByTagName("text")
                            if (texts.length > 0) {
                                title = texts.item(0).textContent ?: ""
                            }
                        }
                        title = stripHtmlTags(title).trim()
                        if (title.isBlank()) {
                            title = "Untitled Chapter"
                        }

                        val playOrderStr = node.getAttribute("playOrder")
                        val playOrder = playOrderStr.toIntOrNull() ?: i

                        var srcAttr: String? = null
                        val contents = node.getElementsByTagName("content")
                        if (contents.length > 0) {
                            val contentEl = contents.item(0) as org.w3c.dom.Element
                            srcAttr = contentEl.getAttribute("src")
                        }

                        if (srcAttr != null && srcAttr.isNotEmpty()) {
                            val cleanSrcAttr = srcAttr
                                .replace("&amp;", "&")
                                .replace("&quot;", "\"")
                                .replace("&lt;", "<")
                                .replace("&gt;", ">")

                            val hashIdx = cleanSrcAttr.indexOf('#')
                            val fileHref = if (hashIdx != -1) cleanSrcAttr.substring(0, hashIdx) else cleanSrcAttr
                            val anchor = if (hashIdx != -1) cleanSrcAttr.substring(hashIdx + 1) else null

                            ncxNavPoints.add(NcxNavPoint(title, cleanSrcAttr, fileHref, anchor, playOrder))
                        }
                    }
                }
                ncxNavPoints.sortBy { it.playOrder }
            } catch (e: Exception) {
                Log.e(TAG, "Failed DOM parsing toc.ncx, falling back to Regex parsing", e)
                try {
                    val ncxText = ncxFileResolved.readText(Charsets.UTF_8)
                    val navPointBlockRegex = Regex("<navPoint[^>]*>(.*?)</navPoint>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    val playOrderRegex = Regex("playOrder\\s*=\\s*['\"](\\d+)['\"]", RegexOption.IGNORE_CASE)
                    val textRegex = Regex("<text[^>]*>(.*?)</text>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    val contentSrcRegex = Regex("<content\\s+[^>]*src\\s*=\\s*['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE)
                    
                    var orderCounter = 0
                    navPointBlockRegex.findAll(ncxText).forEach { blockMatch ->
                        val blockInner = blockMatch.groupValues[1]
                        val fullTag = blockMatch.value
                        
                        val playOrderStr = playOrderRegex.find(fullTag)?.groupValues?.get(1)
                        val playOrder = playOrderStr?.toIntOrNull() ?: orderCounter++
                        
                        val textMatch = textRegex.find(blockInner)
                        var title = textMatch?.groupValues?.get(1) ?: "Untitled Chapter"
                        title = stripHtmlTags(title).trim()
                        if (title.isBlank()) {
                            title = "Chapter ${orderCounter}"
                        }
                        
                        val srcMatch = contentSrcRegex.find(blockInner)
                        val srcAttr = srcMatch?.groupValues?.get(1)
                        
                        if (srcAttr != null && srcAttr.isNotEmpty()) {
                            val cleanSrcAttr = srcAttr
                                .replace("&amp;", "&")
                                .replace("&quot;", "\"")
                                .replace("&lt;", "<")
                                .replace("&gt;", ">")

                            val hashIdx = cleanSrcAttr.indexOf('#')
                            val fileHref = if (hashIdx != -1) cleanSrcAttr.substring(0, hashIdx) else cleanSrcAttr
                            val anchor = if (hashIdx != -1) cleanSrcAttr.substring(hashIdx + 1) else null

                            ncxNavPoints.add(NcxNavPoint(title, cleanSrcAttr, fileHref, anchor, playOrder))
                        }
                    }
                    ncxNavPoints.sortBy { it.playOrder }
                } catch (ex: Exception) {
                    Log.e(TAG, "Regex fallback for toc.ncx failed", ex)
                }
            }
        }

        // ── EPUB 3: nav.xhtml fallback ──────────────────────────────────────
        // Если NCX не найден или пуст — ищем EPUB3 nav.xhtml и парсим его TOC
        if (ncxNavPoints.isEmpty()) {
            val navManifestItem = manifestItems.values.firstOrNull { item ->
                item.properties?.split(" ")?.contains("nav") == true
            } ?: manifestItems.values.firstOrNull { item ->
                item.mediaType?.lowercase()?.contains("xhtml") == true &&
                (item.href.lowercase().contains("nav") || 
                 item.href.lowercase().endsWith("nav.xhtml"))
            } ?: run {
                var found: ManifestItem? = null
                fun findNav(dir: File) {
                    dir.listFiles()?.forEach { f ->
                        if (f.isDirectory) {
                            findNav(f)
                        } else if (f.name.lowercase() == "nav.xhtml" || f.name.lowercase() == "nav.htm") {
                            val relativePath = try {
                                f.relativeTo(opfDir).path
                            } catch (e: Exception) {
                                f.relativeTo(tempDir).path
                            }
                            found = ManifestItem(f.name, relativePath, "application/xhtml+xml")
                        }
                    }
                }
                findNav(tempDir)
                found
            }

            if (navManifestItem != null) {
                val navFile = File(opfDir, navManifestItem.href)
                if (navFile.exists()) {
                    try {
                        val navText = navFile.readText(Charsets.UTF_8)
                        val linkRegex = Regex(
                            """<a\s+[^>]*href\s*=\s*['"]([^'"]+)['"][^>]*>(.*?)</a>""",
                            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
                        )
                        var order = 0
                        linkRegex.findAll(navText).forEach { match ->
                            val rawHref = match.groupValues[1]
                                .replace("&amp;", "&").replace("&quot;", "\"")
                            val title = stripHtmlTags(match.groupValues[2]).trim()
                                .ifBlank { "Untitled Chapter" }

                            val hashIdx = rawHref.indexOf('#')
                            val fileHref = if (hashIdx != -1) rawHref.substring(0, hashIdx) else rawHref
                            val anchor = if (hashIdx != -1) rawHref.substring(hashIdx + 1) else null

                            val lc = title.lowercase()
                            if (lc !in setOf("toc", "contents", "navigation", "оглавление", "содержание")) {
                                ncxNavPoints.add(
                                    NcxNavPoint(title, rawHref, fileHref, anchor, order++)
                                )
                            }
                        }
                        Log.d(TAG, "EPUB3 nav.xhtml parsed: ${ncxNavPoints.size} chapters found")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed parsing EPUB3 nav.xhtml", e)
                    }
                }
            }
        }

        // Deduplicate navPoints to prevent redundant duplicate sections or loops
        if (ncxNavPoints.isNotEmpty()) {
            val seen = mutableSetOf<String>()
            val deduped = ncxNavPoints.filter { point ->
                val key = "${point.fileHref}#${point.anchor ?: ""}".lowercase()
                seen.add(key)
            }
            ncxNavPoints.clear()
            ncxNavPoints.addAll(deduped)
        }

        // 4. Parse chapters (with fallbacks)
        val chaptersList = mutableListOf<ParsedChapter>()

        if (ncxNavPoints.isNotEmpty()) {
            // NCX splitting strategy (highly accurate for splitting single giant HTML files)
            val fileIndexCursor = mutableMapOf<String, Int>()

            ncxNavPoints.forEachIndexed { idx, item ->
                try {
                    val decodedFileHref = java.net.URLDecoder.decode(item.fileHref, "UTF-8")
                    val chapterFile = File(opfDir, decodedFileHref)
                    if (chapterFile.exists()) {
                        val fullHtml = chapterFile.readText(Charsets.UTF_8)

                        val lastCursor = fileIndexCursor[decodedFileHref] ?: 0
                        val startIdx = if (item.anchor != null) {
                            val pos = findAnchorPositionInHtml(fullHtml, item.anchor)
                            if (pos != -1) pos else lastCursor
                        } else {
                            lastCursor
                        }

                        // Robust lookahead parsing: find the next anchor anywhere in the SAME file in any subsequent navPoints
                        var endIdx = -1
                        val finalStartIdx = startIdx

                        for (nextIdx in (idx + 1) until ncxNavPoints.size) {
                            val nextItem = ncxNavPoints[nextIdx]
                            if (nextItem.fileHref == item.fileHref && nextItem.anchor != null) {
                                val pos = findAnchorPositionInHtml(fullHtml, nextItem.anchor)
                                if (pos != -1 && pos > finalStartIdx) {
                                    endIdx = pos
                                    break
                                }
                            }
                        }

                        val finalEndIdx = if (endIdx == -1 || endIdx <= finalStartIdx) fullHtml.length else endIdx
                        val htmlSegment = fullHtml.substring(finalStartIdx, finalEndIdx)
                        
                        fileIndexCursor[decodedFileHref] = finalEndIdx
                        
                        val cleanedHtmlSegment = cleanChapterHtml(htmlSegment, keepOriginal)

                        val relPath = try { chapterFile.relativeTo(tempDir).path.replace('\\', '/') } catch(e: Exception) { null }
                        val isFirstFromFile = chaptersList.none { it.originalFilePath == relPath }
                        
                        // Check if this file is split into multiple navPoints
                        val isSplitFile = ncxNavPoints.count { it.fileHref == item.fileHref } > 1

                        val storedHtml = if (keepOriginal && isFirstFromFile && !isSplitFile) {
                            fullHtml
                        } else {
                            cleanedHtmlSegment
                        }

                        val words = WordStatsHelper.countWords(cleanedHtmlSegment)
                        val chars = WordStatsHelper.countCharacters(cleanedHtmlSegment)

                        var chapPreviewImagePath: String? = null
                        val imgRegex = Regex("<img[^>]+src\\s*=\\s*['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE)
                        val match = imgRegex.find(htmlSegment)
                        if (match != null) {
                            val srcAttr = match.groupValues[1]
                            val decodedSrc = try {
                                java.net.URLDecoder.decode(srcAttr, "UTF-8")
                            } catch (e: Exception) {
                                srcAttr
                            }
                            val imgFileName = File(decodedSrc).name.lowercase()
                            chapPreviewImagePath = imageMap[imgFileName]
                        }

                        var bestTitle = item.title.trim()
                        val isNumeric = bestTitle.matches(Regex("\\d+"))
                        val isGenericShort = bestTitle.lowercase() in setOf("гл", "гл.", "ch", "ch.", "chapter", "глава", "часть", "том")
                        val isVeryShort = bestTitle.length <= 2 && !isNumeric
                        
                        val lowerTitle = bestTitle.lowercase()
                        val isUninformative = isNumeric || isGenericShort || isVeryShort ||
                            lowerTitle.matches(Regex("(chapter|chap|ch|sec|section|part|page|vol|volume|xhtml|html)[_\\-\\s]*\\d+")) ||
                            lowerTitle in setOf("untitled", "untitled chapter", "chapter", "chapter-title", "cover", "title", "titlepage", "toc", "index", "navigation", "navpoint") ||
                            lowerTitle.endsWith(".xhtml") || lowerTitle.endsWith(".html") || lowerTitle.endsWith(".htm")
                        
                        if (isUninformative) {
                            val extracted = extractTitleFromHtml(htmlSegment, decodedFileHref)
                            if (extracted.isNotEmpty() && extracted != "Untitled Chapter" && extracted.length > 2) {
                                bestTitle = extracted
                            }
                        }

                        chaptersList.add(ParsedChapter(
                            title = bestTitle,
                            contentHtml = storedHtml,
                            wordCount = words,
                            characterCount = chars,
                            previewImagePath = chapPreviewImagePath,
                            originalFilePath = relPath,
                            anchorStart = item.anchor,
                            anchorEnd = if (endIdx != -1) findAnchorIdAtPosition(fullHtml, endIdx) else null,
                            displayHtml = cleanedHtmlSegment
                        ))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed parsing segment: ${item.title}", e)
                }
            }
        }

        // Fallback level 2: OPF spine ordering
        if (chaptersList.isEmpty() && spineItems.isNotEmpty()) {
            spineItems.forEach { idref ->
                val manifestItem = manifestItems[idref]
                if (manifestItem != null) {
                    try {
                        val decodedHref = java.net.URLDecoder.decode(manifestItem.href, "UTF-8")
                        val chapterFile = File(opfDir, decodedHref)
                        if (chapterFile.exists()) {
                            val htmlContent = chapterFile.readText(Charsets.UTF_8)
                            val cleanedHtmlContent = cleanChapterHtml(htmlContent, keepOriginal)
                            val chapterTitle = extractTitleFromHtml(htmlContent, chapterFile.name)

                            val words = WordStatsHelper.countWords(cleanedHtmlContent)
                            val chars = WordStatsHelper.countCharacters(cleanedHtmlContent)

                            var chapPreviewImagePath: String? = null
                            val imgRegex = Regex("<img[^>]+src=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
                            val match = imgRegex.find(htmlContent)
                            if (match != null) {
                                val srcAttr = match.groupValues[1]
                                val imgFileName = File(srcAttr).name.lowercase()
                                chapPreviewImagePath = imageMap[imgFileName]
                            }

                            chaptersList.add(ParsedChapter(
                                title = chapterTitle,
                                contentHtml = cleanedHtmlContent,
                                wordCount = words,
                                characterCount = chars,
                                previewImagePath = chapPreviewImagePath,
                                originalFilePath = try { chapterFile.relativeTo(tempDir).path.replace('\\', '/') } catch(e: Exception) { null }
                            ))
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed parsing spine chapter $idref", e)
                    }
                }
            }
        }

        // Fallback level 3: Alphabetical sort of all files
        if (chaptersList.isEmpty()) {
            htmlFiles.sortBy { it.path }
            htmlFiles.forEach { file ->
                try {
                    val htmlContent = file.readText(Charsets.UTF_8)
                    val cleanedHtmlContent = cleanChapterHtml(htmlContent, keepOriginal)
                    val chapterTitle = extractTitleFromHtml(htmlContent, file.name)

                    val words = WordStatsHelper.countWords(cleanedHtmlContent)
                    val chars = WordStatsHelper.countCharacters(cleanedHtmlContent)

                    var chapPreviewImagePath: String? = null
                    val imgRegex = Regex("<img[^>]+src=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
                    val match = imgRegex.find(htmlContent)
                    if (match != null) {
                        val srcAttr = match.groupValues[1]
                        val imgFileName = File(srcAttr).name.lowercase()
                        chapPreviewImagePath = imageMap[imgFileName]
                    }

                    chaptersList.add(ParsedChapter(
                        title = chapterTitle,
                        contentHtml = cleanedHtmlContent,
                        wordCount = words,
                        characterCount = chars,
                        previewImagePath = chapPreviewImagePath,
                        originalFilePath = try { file.relativeTo(tempDir).path.replace('\\', '/') } catch(e: Exception) { null }
                    ))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed sorting fallback on ${file.name}", e)
                }
            }
        }

            val defaultTitle = extractedTitle?.let { stripHtmlTags(it) }?.trim()
                ?: (getFileNameFromUri(context, uri)?.removeSuffix(".epub") ?: "Parsed Title")
            val defaultAuthor = extractedAuthor?.let { stripHtmlTags(it) }?.trim() ?: ""
            val defaultDesc = extractedDesc?.let { stripHtmlTags(it) }?.trim() ?: ""

            return ParsedEpub(
                title = defaultTitle,
                author = defaultAuthor,
                description = defaultDesc,
                coverImagePath = coverImagePath,
                chapters = chaptersList,
                originalEpubDirPath = savedOriginalEpubDirPath,
                originalOpfRelativePath = opfRelativePath
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing unzipped EPUB", e)
            return null
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun findAnchorPositionInHtml(html: String, anchor: String): Int {
        if (anchor.isBlank()) return -1
        
        // Try multiple variations of the anchor to be resilient to encoding differences
        val variations = LinkedHashSet<String>()
        variations.add(anchor)
        try {
            variations.add(java.net.URLDecoder.decode(anchor, "UTF-8"))
        } catch (e: Exception) {}
        try {
            variations.add(java.net.URLEncoder.encode(anchor, "UTF-8"))
        } catch (e: Exception) {}
        
        for (v in variations) {
            val escaped = Regex.escape(v)
            val regexes = listOf(
                Regex("id\\s*=\\s*['\"]" + escaped + "['\"]", RegexOption.IGNORE_CASE),
                Regex("name\\s*=\\s*['\"]" + escaped + "['\"]", RegexOption.IGNORE_CASE),
                Regex("id\\s*=\\s*" + escaped + "(?:\\s|>)", RegexOption.IGNORE_CASE),
                Regex("name\\s*=\\s*" + escaped + "(?:\\s|>)", RegexOption.IGNORE_CASE)
            )
            for (regex in regexes) {
                val match = regex.find(html)
                if (match != null) {
                    val attrPos = match.range.first
                    val tagStart = html.lastIndexOf('<', attrPos)
                    return if (tagStart != -1) tagStart else attrPos
                }
            }
        }
        
        return -1
    }

    private fun findAnchorIdAtPosition(html: String, position: Int): String? {
        // Ищем ближайший тег с id= перед указанной позицией
        val idRegex = Regex("""id\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        var lastId: String? = null
        for (match in idRegex.findAll(html)) {
            if (match.range.first >= position) break
            lastId = match.groupValues[1]
        }
        return lastId
    }

    data class ManifestItem(val id: String, val href: String, val mediaType: String?, val properties: String? = null)

    fun cleanChapterHtml(html: String, keepOriginal: Boolean = false): String {
        if (keepOriginal) {
            return html.trim()
        }
        // 1. Remove comments
        var cleaned = html.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        
        // 2. Remove script & style tags completely
        cleaned = cleaned.replace(Regex("<script(?:\\s+[^>]*)?>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
        cleaned = cleaned.replace(Regex("<style(?:\\s+[^>]*)?>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
        
        // 3. Extract only the contents of <body> if it exists, otherwise strip <head>/<html> wrappers
        val bodyRegex = Regex("<body(?:\\s+[^>]*)?>(.*?)</body>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val bodyMatch = bodyRegex.find(cleaned)
        if (bodyMatch != null) {
            cleaned = bodyMatch.groupValues[1]
        } else {
            cleaned = cleaned.replace(Regex("<head(?:\\s+[^>]*)?>.*?</head>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            cleaned = cleaned.replace(Regex("</?html(?:\\s+[^>]*)?>", RegexOption.IGNORE_CASE), "")
            cleaned = cleaned.replace(Regex("<meta(?:\\s+[^>]*)?>", RegexOption.IGNORE_CASE), "")
            cleaned = cleaned.replace(Regex("<link(?:\\s+[^>]*)?>", RegexOption.IGNORE_CASE), "")
        }
        
        return cleaned.trim()
    }


    private fun isGoodCandidate(text: String): Boolean {
        if (text.isEmpty() || text.length > 120 || text.length < 2) return false
        val lower = text.lowercase()
        if (lower.matches(Regex("[\\s\\p{Punct}\\d]+"))) return false
        if (lower in setOf("untitled", "untitled chapter", "chapter", "glava", "глава", "navigation", "toc", "index", "cover", "annotation", "аннотация")) return false
        return true
    }

    private fun extractTitleFromHtml(html: String, filename: String): String {
        // Remove comments, head, scripts, styles for clean regex
        var cleanHtml = html.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<head(?:\\s+[^>]*)?>.*?</head>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            .replace(Regex("<style(?:\\s+[^>]*)?>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            .replace(Regex("<script(?:\\s+[^>]*)?>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")

        val potentialTitles = mutableListOf<String>()

        // 1. Check <title> tag inside <head> if it exists and is informative
        val headTitleRegex = Regex("<title(?:\\s+[^>]*)?>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val headTitleMatch = headTitleRegex.find(html) // Search original to include head if not stripped before
        if (headTitleMatch != null) {
            val headTitle = stripHtmlTags(headTitleMatch.groupValues[1]).trim()
            if (isGoodCandidate(headTitle)) {
                potentialTitles.add(headTitle.replace(Regex("\\s+"), " "))
            }
        }

        // 2. Try to find headers <h1> to <h6>
        val headerRegex = Regex("<(h1|h2|h3|h4|h5|h6)(?:\\s+[^>]*)?>(.*?)</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        for (match in headerRegex.findAll(cleanHtml)) {
            val text = stripHtmlTags(match.groupValues[2]).trim()
            if (isGoodCandidate(text)) {
                potentialTitles.add(text)
            }
        }

        // 3. Try to find any <p> or <div/span> with clear title attributes
        val classAttrRegex = Regex("<(p|div|span)\\s+[^>]*(?:class|id)\\s*=\\s*['\"][^'\"]*(?:title|chapter|header|heading|subject|name|caption|h_)[^'\"]*['\"][^>]*>(.*?)</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        for (match in classAttrRegex.findAll(cleanHtml)) {
            val text = stripHtmlTags(match.groupValues[2]).trim()
            if (isGoodCandidate(text)) {
                potentialTitles.add(text)
            }
        }

        // 4. Try <p> tags starting with standard Chapter keywords or just short bold tags
        val pRegex = Regex("<p(?:\\s+[^>]*)?>(.*?)</p>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val pMatches = pRegex.findAll(cleanHtml).take(15).toList() // limit to first 15 paragraphs of the document
        for (match in pMatches) {
            val inner = match.groupValues[1]
            val text = stripHtmlTags(inner).trim()
            
            val startsWithChapterKeyword = text.isNotEmpty() && (
                text.lowercase().startsWith("глава") ||
                text.lowercase().startsWith("chapter") ||
                text.lowercase().startsWith("часть") ||
                text.lowercase().startsWith("part") ||
                text.lowercase().startsWith("пролог") ||
                text.lowercase().startsWith("prologue") ||
                text.lowercase().startsWith("эпилог") ||
                text.lowercase().startsWith("epilogue") ||
                text.lowercase().startsWith("интерлюдия") ||
                text.lowercase().startsWith("interlude") ||
                text.lowercase().startsWith("послесловие") ||
                text.lowercase().startsWith("afterword")
            )
            
            if (startsWithChapterKeyword && isGoodCandidate(text)) {
                potentialTitles.add(text)
            } else {
                // Check if the entire paragraph is bold, meaning it's likely a subtitle/header
                val boldRegex = Regex("^\\s*<(b|strong|h[1-6]|span)(?:\\s+[^>]*)?>(.*?)</\\1>\\s*$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                if (boldRegex.matches(inner.trim())) {
                    if (isGoodCandidate(text) && text.length in 3..60) {
                        potentialTitles.add(text)
                    }
                }
            }
        }

        // 5. Fallback to any first couple of short paragraphs
        if (potentialTitles.isEmpty()) {
            for (match in pMatches) {
                val text = stripHtmlTags(match.groupValues[1]).trim()
                if (isGoodCandidate(text) && text.length in 3..60) {
                    potentialTitles.add(text)
                    break // use the first short paragraph found
                }
            }
        }

        if (potentialTitles.isNotEmpty()) {
            val junkWords = setOf("глава", "chapter", "том", "volume", "vol", "книга", "part", "часть")
            val filteredTitles = potentialTitles.filter { t ->
                val lower = t.lowercase().trim()
                lower !in junkWords && lower.length > 2
            }

            val titlesToUse = if (filteredTitles.isNotEmpty()) filteredTitles else potentialTitles

            var volumeTitle: String? = null
            var chapterTitle: String? = null
            var otherTitle: String? = null

            for (title in titlesToUse) {
                val lower = title.lowercase()
                val isVol = lower.startsWith("том") || lower.contains("том ") || lower.contains("том\u00a0") || lower.contains("volume ") || lower.contains("книга ") || lower.contains("vol ")
                val isChap = lower.contains("глава ") || lower.contains("глава\u00a0") || lower.contains("chapter ") || lower.contains("пролог") || 
                             lower.contains("prologue") || lower.contains("эпилог") || lower.contains("epilogue") || lower.contains("интерлюдия") || 
                             lower.contains("interlude") || lower.contains("послесловие") || lower.contains("afterword") || lower.contains("часть ")
                
                if (isVol) {
                    if (volumeTitle == null) volumeTitle = title
                } else if (isChap) {
                    if (chapterTitle == null) chapterTitle = title
                } else {
                    if (otherTitle == null) otherTitle = title
                }
            }

            if (volumeTitle != null && chapterTitle != null) {
                if (volumeTitle.lowercase() != chapterTitle.lowercase()) {
                    return "$volumeTitle - $chapterTitle"
                } else {
                    return chapterTitle
                }
            } else if (volumeTitle != null && otherTitle != null) {
                if (volumeTitle.lowercase() != otherTitle.lowercase()) {
                    return "$volumeTitle - $otherTitle"
                } else {
                    return otherTitle
                }
            } else if (chapterTitle != null) {
                return chapterTitle
            } else if (otherTitle != null) {
                return otherTitle
            } else if (volumeTitle != null) {
                return volumeTitle
            } else {
                return titlesToUse.first()
            }
        }

        // Priority 4: Hard fallback to pretty filename
        val baseName = File(filename).nameWithoutExtension
        return baseName.replace('_', ' ')
            .replace('-', ' ')
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { it.replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase() else it } }
    }

    private fun stripHtmlTags(html: String): String {
        return html.replace(Regex("<[^>]*>"), "")
            .replace(Regex("&nbsp;"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun escapeXml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun containsAnyTitleRepresentation(contentHtml: String, chapterTitle: String): Boolean {
        val cleanTitle = chapterTitle.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "").trim()
        if (cleanTitle.isEmpty()) return false

        // Extract text inside the first XML element if it's a heading (H1-H6)
        val firstTagRegex = Regex("^\\s*<(h[1-6])(?:\\s+[^>]*)?>(.*?)</\\1>", RegexOption.IGNORE_CASE)
        val firstTagMatch = firstTagRegex.find(contentHtml)
        if (firstTagMatch != null) {
            val headingText = firstTagMatch.groupValues[2].replace(Regex("<[^>]*>"), "").lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "").trim()
            if (headingText.contains(cleanTitle) || cleanTitle.contains(headingText)) {
                return true
            }
        }
        
        // Also check if any H1-H4 exists in the first 500 characters
        val anyHeaderRegex = Regex("<(h1|h2|h3|h4)(?:\\s+[^>]*)?>(.*?)</\\1>", RegexOption.IGNORE_CASE)
        val anyHeaderMatches = anyHeaderRegex.findAll(contentHtml.take(500))
        for (m in anyHeaderMatches) {
            val hText = m.groupValues[2].replace(Regex("<[^>]*>"), "").lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "").trim()
            if (hText.contains(cleanTitle) || cleanTitle.contains(hText)) {
                return true
            }
        }

        // Standard direct contains check of the initial normalized text
        val cleanText = contentHtml.replace(Regex("<[^>]*>"), "")
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]"), "")
            .trim()
            
        if (cleanText.isEmpty()) return false
        
        val initialSegment = cleanText.take(200)
        if (initialSegment.contains(cleanTitle)) return true
        
        if (cleanTitle.contains(initialSegment.take(30)) && initialSegment.take(30).length >= 10) return true
        
        // Word intersection
        val titleWords = chapterTitle.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length > 2 && it != "глава" && it != "chapter" }
            
        if (titleWords.isNotEmpty()) {
            val first100Words = contentHtml.replace(Regex("<[^>]*>"), " ")
                .lowercase()
                .split(Regex("[^\\p{L}\\p{N}]+"))
                .take(100)
                
            for (w in titleWords) {
                if (first100Words.contains(w)) return true
            }
        }
        
        return false
    }

    private fun getFileNameFromUri(context: Context, uri: Uri): String? {
        var name: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIdx != -1 && cursor.moveToFirst()) {
                name = cursor.getString(nameIdx)
            }
        }
        return name ?: uri.lastPathSegment
    }

    private fun cleanContentHtmlForExport(html: String): String {
        var cleaned = html
        val bodyStartIdx = cleaned.indexOf("<body", ignoreCase = true)
        if (bodyStartIdx != -1) {
            val bodyEndIdx = cleaned.indexOf(">", bodyStartIdx)
            if (bodyEndIdx != -1) {
                val endBodyIdx = cleaned.lastIndexOf("</body>", ignoreCase = true)
                if (endBodyIdx > bodyEndIdx) {
                    cleaned = cleaned.substring(bodyEndIdx + 1, endBodyIdx)
                }
            }
        }

        cleaned = cleaned.replace(Regex("(?i)<\\?xml[^>]*>"), "")
        cleaned = cleaned.replace(Regex("(?i)<!DOCTYPE[^>]*>"), "")
        cleaned = cleaned.replace(Regex("(?i)</?html[^>]*>"), "")
        cleaned = cleaned.replace(Regex("(?i)<head[^>]*>[\\s\\S]*?</head>"), "")
        
        return cleaned.trim()
    }

    /**
     * Packages a collection of chapters into a valid EPUB zip file and saves it
     * into the public Download directory.
     */
    fun exportToEpub(
        context: Context,
        fileName: String,
        title: String,
        author: String,
        description: String,
        coverImagePath: String?,
        chapters: List<ParsedChapter>,
        titleId: Long? = null,
        generateToc: Boolean = true
    ): File? {
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val keepOriginalSetting = !prefs.getBoolean("pref_convert_epub_system", false)
        if (keepOriginalSetting && titleId != null) {
            val originalEpubDir = File(context.filesDir, "epub_originals/book_$titleId")
            if (!originalEpubDir.exists()) {
                Log.w(TAG, "exportToEpub: Original archive missing for book_$titleId, cannot preserve original")
                throw IllegalStateException("ORIGINAL_ARCHIVE_MISSING")
            }
            val result = exportFromOriginalArchive(context, fileName, titleId, chapters, title, author, description, coverImagePath)
            if (result != null) return result
            Log.e(TAG, "exportFromOriginalArchive failed despite dir existing")
            throw IllegalStateException("EXPORT_FROM_ORIGINAL_FAILED")
        }

        val bookLanguage = prefs.getString("pref_language", "ru") ?: "ru"
        val sanitizedFileName = if (fileName.endsWith(".epub", ignoreCase = true)) fileName else "$fileName.epub"
        
        val tempOutputFile = File(context.cacheDir, "temp_export_${System.currentTimeMillis()}_$sanitizedFileName")

        try {
            val fos = FileOutputStream(tempOutputFile)
            val zos = ZipOutputStream(BufferedOutputStream(fos))

            // 1. mimetype (Must be FIRST and STORED uncompressed)
            val mimeEntry = ZipEntry("mimetype")
            mimeEntry.method = ZipEntry.STORED
            val mimeBytes = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            mimeEntry.size = mimeBytes.size.toLong()
            mimeEntry.compressedSize = mimeBytes.size.toLong()
            val crc = java.util.zip.CRC32()
            crc.update(mimeBytes)
            mimeEntry.crc = crc.value

            zos.putNextEntry(mimeEntry)
            zos.write(mimeBytes)
            zos.closeEntry()

            // 2. container.xml
            zos.putNextEntry(ZipEntry("META-INF/container.xml"))
            val containerXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                    <rootfiles>
                        <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                    </rootfiles>
                </container>
            """.trimIndent().trim()
            zos.write(containerXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // 3. Keep track of items to add to OPF manifest and spine
            val manifestItems = StringBuilder()
            val spineItems = StringBuilder()
            val ncxNavMap = StringBuilder()

            // Add Cover image if exists
            var hasCover = false
            var coverExt = "jpg"
            if (coverImagePath != null) {
                val coverFile = File(coverImagePath)
                if (coverFile.exists()) {
                    hasCover = true
                    coverExt = coverFile.extension.lowercase().let { if (it.isEmpty()) "jpg" else it }
                    val mediaType = when (coverExt) {
                        "png" -> "image/png"
                        "webp" -> "image/webp"
                        "gif" -> "image/gif"
                        else -> "image/jpeg"
                    }
                    zos.putNextEntry(ZipEntry("OEBPS/cover.$coverExt"))
                    coverFile.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                    manifestItems.append("<item id=\"cover-image\" href=\"cover.$coverExt\" media-type=\"$mediaType\" properties=\"cover-image\"/>\n")
                }
            }

            // Extract and package illustrations/images referenced in chapters
            val referencedImages = mutableSetOf<String>()
            val imgPattern = Regex("<img[^>]+src\\s*=\\s*['\"]([^'\"]+)['\"][^>]*>", RegexOption.IGNORE_CASE)
            val svgImagePattern = Regex("<image[^>]+(?:xlink:)?href\\s*=\\s*['\"]([^'\"]+)['\"][^>]*>", RegexOption.IGNORE_CASE)

            chapters.forEach { chap ->
                imgPattern.findAll(chap.contentHtml).forEach { match ->
                    referencedImages.add(match.groupValues[1])
                }
                svgImagePattern.findAll(chap.contentHtml).forEach { match ->
                    referencedImages.add(match.groupValues[1])
                }
            }

            referencedImages.forEach { src ->
                val resolvedPath = resolveLocalImagePath(context, src, titleId)
                if (resolvedPath != null) {
                    val imgFile = File(resolvedPath)
                    if (imgFile.exists()) {
                        try {
                            zos.putNextEntry(ZipEntry("OEBPS/$src"))
                            imgFile.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()

                            val ext = src.substringAfterLast(".", "jpg").lowercase()
                            val mediaType = when (ext) {
                                "png" -> "image/png"
                                "gif" -> "image/gif"
                                "webp" -> "image/webp"
                                else -> "image/jpeg"
                            }
                            val manifestId = "img_${src.replace("[^a-zA-Z0-9]".toRegex(), "_")}"
                            manifestItems.append("<item id=\"$manifestId\" href=\"$src\" media-type=\"$mediaType\"/>\n")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed packing image $src to EPUB", e)
                        }
                    }
                }
            }

            val bookUuid = "urn:uuid:${java.util.UUID.randomUUID()}"
            val escapedTitle = escapeXml(title)
            val escapedAuthor = escapeXml(author)
            val escapedDesc = escapeXml(description)

            val navList = StringBuilder()
            // Loop and add chapters
            chapters.forEachIndexed { idx, chap ->
                val chapId = "chapter_$idx"
                val paddedIdx = idx.toString().padStart(4, '0')
                val href = "chapter_$paddedIdx.xhtml"
                
                zos.putNextEntry(ZipEntry("OEBPS/$href"))
                
                // Smart Title check to avoid visual repetition in advanced readers
                val cleanedHtml = cleanContentHtmlForExport(chap.contentHtml)
                val containsTitleHeader = containsAnyTitleRepresentation(cleanedHtml, chap.title)
                    val headerTag = if (containsTitleHeader) "" else "<h2 class=\"chapter-header\">${escapeXml(chap.title)}</h2>\n"

                    // Standard XHTML template for high readers compatibility
                    val xhtmlContent = """
                        <?xml version="1.0" encoding="utf-8"?>
                        <!DOCTYPE html>
                        <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                        <head>
                            <title>${escapeXml(chap.title)}</title>
                            <meta charset="utf-8" />
                            <style type="text/css">
                                body {
                                    font-family: sans-serif;
                                    line-height: 1.6;
                                    padding: 2%;
                                    margin: 0;
                                }
                                p {
                                    text-indent: 1.5em;
                                    margin-top: 0.2em;
                                    margin-bottom: 0.2em;
                                    text-align: justify;
                                }
                                .chapter-header {
                                    text-align: center;
                                    font-size: 1.5em;
                                    font-weight: bold;
                                    margin-bottom: 1.5em;
                                    margin-top: 1em;
                                }
                                img {
                                    max-width: 100%;
                                    height: auto;
                                    display: block;
                                    margin: 1em auto;
                                }
                            </style>
                        </head>
                        <body>
                            $headerTag${cleanedHtml}
                        </body>
                        </html>
                    """.trimIndent().trim()
                    
                    zos.write(xhtmlContent.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()

                var safeChapTitle = escapeXml(stripHtmlTags(chap.title ?: "")).trim()
                if (safeChapTitle.isEmpty()) {
                    safeChapTitle = "Chapter ${idx + 1}"
                }

                manifestItems.append("<item id=\"$chapId\" href=\"$href\" media-type=\"application/xhtml+xml\"/>\n")
                spineItems.append("<itemref idref=\"$chapId\"/>\n")

                navList.append("<li><a href=\"$href\">$safeChapTitle</a></li>\n")
                ncxNavMap.append("""
                    <navPoint id="$chapId" playOrder="${idx + 1}">
                        <navLabel>
                            <text>$safeChapTitle</text>
                        </navLabel>
                        <content src="$href"/>
                    </navPoint>
                """.trimIndent() + "\n")
            }

            // Fallbacks for empty TOC required by EPUB spec
            val finalNavList = if (navList.isEmpty()) "<li><a href=\"chapter_0.xhtml\">Начало</a></li>" else navList.toString()
            val finalNcxNavMap = if (ncxNavMap.isEmpty()) """
                <navPoint id="chapter_0" playOrder="1">
                    <navLabel>
                        <text>Начало</text>
                    </navLabel>
                    <content src="chapter_0.xhtml"/>
                </navPoint>
            """.trimIndent() else ncxNavMap.toString()

            // 3b. EPUB 3 nav.xhtml Navigation document
            val navHref = "nav.xhtml"
            zos.putNextEntry(ZipEntry("OEBPS/$navHref"))
            val navXhtmlContent = """
                <?xml version="1.0" encoding="utf-8"?>
                <!DOCTYPE html>
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                <head>
                    <title>Navigation</title>
                    <meta charset="utf-8" />
                </head>
                <body>
                    <nav epub:type="toc" id="toc">
                        <h1>${escapeXml(title)}</h1>
                        <ol>
                            $finalNavList
                        </ol>
                    </nav>
                </body>
                </html>
            """.trimIndent().trim()
            zos.write(navXhtmlContent.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // 4. content.opf
            zos.putNextEntry(ZipEntry("OEBPS/content.opf"))
            val opfContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" unique-identifier="bookid" version="3.0">
                    <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
                        <dc:title>$escapedTitle</dc:title>
                        <dc:creator id="creator">$escapedAuthor</dc:creator>
                        <dc:description>$escapedDesc</dc:description>
                        <dc:language>$bookLanguage</dc:language>
                        <dc:identifier id="bookid">$bookUuid</dc:identifier>
                        <meta property="dcterms:modified">${java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date())}</meta>
                        ${if (hasCover) "<meta name=\"cover\" content=\"cover-image\"/>" else ""}
                    </metadata>
                    <manifest>
                        <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                        <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                        $manifestItems
                    </manifest>
                    <spine toc="ncx">
                        ${if (generateToc) "<itemref idref=\"nav\" linear=\"yes\"/>\n" else ""}                        $spineItems
                    </spine>
                </package>
            """.trimIndent().trim()
            zos.write(opfContent.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // 5. toc.ncx
            zos.putNextEntry(ZipEntry("OEBPS/toc.ncx"))
            val ncxContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE ncx PUBLIC "-//NISO//DTD NCX 2005-1//EN" "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd">
                <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                    <head>
                        <meta name="dtb:uid" content="$bookUuid"/>
                        <meta name="dtb:depth" content="1"/>
                        <meta name="dtb:totalPageCount" content="0"/>
                        <meta name="dtb:maxPageNumber" content="0"/>
                    </head>
                    <docTitle>
                        <text>$escapedTitle</text>
                    </docTitle>
                    <navMap>
                        $finalNcxNavMap
                    </navMap>
                </ncx>
            """.trimIndent().trim()
            zos.write(ncxContent.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.flush()
            zos.close()
            fos.close()
            
            val savedFile = saveFileToPublicDownloads(context, tempOutputFile, sanitizedFileName)
            try {
                if (tempOutputFile.exists()) {
                    tempOutputFile.delete()
                }
            } catch (ignored: Exception) {}
            return savedFile
        } catch (e: Exception) {
            Log.e(TAG, "Error generating EPUB bundle", e)
            try {
                if (tempOutputFile.exists()) {
                    tempOutputFile.delete()
                }
            } catch (ignored: Exception) {}
            return null
        }
    }

    private fun isCoverFile(relPath: String, opfText: String): Boolean {
        val coverItemRegex = Regex(
            """<item[^>]*properties="[^"]*cover-image[^"]*"[^>]*href="([^"]+)"""",
            RegexOption.IGNORE_CASE
        )
        val coverHref = coverItemRegex.find(opfText)?.groupValues?.get(1) ?: return false
        return relPath.endsWith(coverHref, ignoreCase = true)
    }

    private fun removeDeletedChaptersFromToc(
        ncxText: String?,
        navText: String?,
        deletedChapters: List<Pair<String, String?>>
    ): Pair<String?, String?> {
        var ncx = ncxText
        var nav = navText

        deletedChapters.forEach { (filePath, anchor) ->
            val fileNameOnly = filePath.substringAfterLast("/")
            val srcPattern = if (anchor != null) "$fileNameOnly#$anchor" else fileNameOnly

            if (ncx != null) {
                val navPointRegex = Regex(
                    "<navPoint[^>]*>\\s*<navLabel>.*?</navLabel>\\s*<content\\s+src=\"[^\"]*$srcPattern[^\"]*\"\\s*/?>\\s*</navPoint>",
                    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
                )
                ncx = navPointRegex.replace(ncx!!, "")
            }
            if (nav != null) {
                val liRegex = Regex(
                    "<li>\\s*<a\\s+href=\"[^\"]*$srcPattern[^\"]*\"[^>]*>.*?</a>\\s*</li>",
                    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
                )
                nav = liRegex.replace(nav!!, "")
            }
        }
        return ncx to nav
    }

    private fun extractAllTocEntries(ncxText: String?, navText: String?): Set<String> {
        val entries = mutableSetOf<String>()

        if (ncxText != null) {
            val contentSrcRegex = Regex("<content\\s+src=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
            contentSrcRegex.findAll(ncxText).forEach { m ->
                entries.add(m.groupValues[1])
            }
        }
        if (navText != null) {
            val aHrefRegex = Regex("<a\\s+href=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
            aHrefRegex.findAll(navText).forEach { m ->
                entries.add(m.groupValues[1])
            }
        }
        return entries
    }

    private fun computeDeletedChapters(
        tocEntries: Set<String>,
        existingChapters: List<ParsedChapter>
    ): List<Pair<String, String?>> {
        val keptSignatures = existingChapters.mapNotNull { chapter ->
            val path = chapter.originalFilePath ?: return@mapNotNull null
            val fileNameOnly = path.substringAfterLast("/")
            if (chapter.anchorStart != null) "$fileNameOnly#${chapter.anchorStart}" else fileNameOnly
        }.toSet()

        val deleted = mutableListOf<Pair<String, String?>>()
        tocEntries.forEach { src ->
            val fileNameOnly = src.substringAfterLast("/").substringBefore("#")
            val anchor = src.substringAfter("#", "").ifEmpty { null }
            val signature = if (anchor != null) "$fileNameOnly#$anchor" else fileNameOnly

            val stillExists = keptSignatures.contains(signature) ||
                              (anchor == null && keptSignatures.any { it.startsWith("$fileNameOnly#") }) ||
                              (anchor != null && keptSignatures.contains(fileNameOnly))

            if (!stillExists) {
                deleted.add(fileNameOnly to anchor)
            }
        }
        return deleted
    }

    private fun rebuildSplitFileContent(
        originalFile: File,
        chaptersForFile: List<ParsedChapter>
    ): String {
        val originalHtml = originalFile.readText(Charsets.UTF_8)

        val sortedChapters = chaptersForFile.sortedBy { chapter ->
            val anchor = chapter.anchorStart
            if (anchor == null) {
                0
            } else {
                val idRegex = Regex("""(?:id|name)\s*=\s*["']${Regex.escape(anchor)}["']""", RegexOption.IGNORE_CASE)
                idRegex.find(originalHtml)?.range?.first ?: Int.MAX_VALUE
            }
        }

        val result = StringBuilder()
        var cursor = 0

        sortedChapters.forEachIndexed { index, chapter ->
            val anchor = chapter.anchorStart
            val startIdx = if (anchor == null) {
                cursor
            } else {
                val idRegex = Regex("""(?:id|name)\s*=\s*["']${Regex.escape(anchor)}["']""", RegexOption.IGNORE_CASE)
                val match = idRegex.find(originalHtml, cursor)
                if (match != null) originalHtml.lastIndexOf('<', match.range.first).takeIf { it >= 0 } ?: cursor else cursor
            }

            val endIdx = chapter.anchorEnd?.let { endAnchor ->
                val idRegex = Regex("""(?:id|name)\s*=\s*["']${Regex.escape(endAnchor)}["']""", RegexOption.IGNORE_CASE)
                val match = idRegex.find(originalHtml, startIdx)
                if (match != null) originalHtml.lastIndexOf('<', match.range.first).takeIf { it >= 0 } ?: originalHtml.length else originalHtml.length
            } ?: run {
                val nextChapter = sortedChapters.getOrNull(index + 1)
                val nextAnchor = nextChapter?.anchorStart
                if (nextAnchor != null) {
                    val idRegex = Regex("""(?:id|name)\s*=\s*["']${Regex.escape(nextAnchor)}["']""", RegexOption.IGNORE_CASE)
                    val match = idRegex.find(originalHtml, startIdx)
                    if (match != null) originalHtml.lastIndexOf('<', match.range.first).takeIf { it >= 0 } ?: originalHtml.length else originalHtml.length
                } else {
                    originalHtml.length
                }
            }

            if (startIdx > cursor) {
                result.append(originalHtml.substring(cursor, startIdx))
            }

            val editedFragment = chapter.displayHtml ?: chapter.contentHtml
            result.append(editedFragment)

            cursor = endIdx
        }

        if (cursor < originalHtml.length) {
            result.append(originalHtml.substring(cursor))
        }

        return result.toString()
    }

    private fun exportFromOriginalArchive(
        context: Context,
        fileName: String,
        titleId: Long,
        chapters: List<ParsedChapter>,
        bookTitle: String,
        bookAuthor: String,
        bookDescription: String,
        coverImagePath: String?
    ): File? {
        val baseOriginalsDir = File(context.filesDir, "epub_originals/book_$titleId")
        if (!baseOriginalsDir.exists()) {
            Log.d(TAG, "exportFromOriginalArchive: no originals for book_$titleId")
            return null
        }

        val sourceDirs = baseOriginalsDir.listFiles { f -> f.isDirectory && f.name.startsWith("source_") }
            ?.sortedBy { it.name } ?: emptyList()

        // Fallback to old format logic if needed, but since we are replacing all logic...
        // Let's implement single volume using the new robust logic with just one volume iteration.
        val dirsToProcess = if (sourceDirs.isEmpty()) listOf(baseOriginalsDir) else sourceDirs

        val existingChapters = chapters.filter { it.originalFilePath != null }
        val newChapters = chapters.filter { it.originalFilePath == null }

        // Find which files were deleted (they exist in original files but not in chapters list,
        // actually easier to just not copy them if they are in standard OEBPS path and not used?
        // Wait, the prompt says for deleted chapters, we remove them from NCX/NAV).
        // Let's assume some deleted chapters mechanism... We don't have exactly deletedChapters list passed!
        // We can just rely on `chapters` filtering.

        val sanitizedFileName = if (fileName.endsWith(".epub", ignoreCase = true)) fileName else "$fileName.epub"
        val tempOutputFile = File(context.cacheDir, "temp_export_${System.currentTimeMillis()}_$sanitizedFileName")

        ZipOutputStream(BufferedOutputStream(FileOutputStream(tempOutputFile))).use { zos ->
            val mimeEntry = ZipEntry("mimetype")
            mimeEntry.method = ZipEntry.STORED
            val mimeBytes = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            mimeEntry.size = mimeBytes.size.toLong()
            val crc = java.util.zip.CRC32()
            crc.update(mimeBytes)
            mimeEntry.crc = crc.value
            zos.putNextEntry(mimeEntry)
            zos.write(mimeBytes)
            zos.closeEntry()

            if (dirsToProcess.size > 1) {
                // Multi-volume merge
                val chaptersBySource = chapters.groupBy { it.sourceFileId }
                
                // Write merged OPF
                // (Omitted in zip structure currently because we have to overwrite it?)
                // Actually the prompt says "Для каждого source-файла копируем его структуру в отдельную подпапку"
                // This means volume_1/ and volume_2/...
                
                dirsToProcess.forEachIndexed { volumeIndex, sourceDir ->
                    val sourceFileId = sourceDir.name.removePrefix("source_").toLongOrNull()
                    val volumePrefix = "volume_${volumeIndex + 1}"
                    val volumeChapters = if (sourceFileId != null) { chaptersBySource[sourceFileId] ?: emptyList() } else { existingChapters }

                    fun walk(dir: File, relBase: String) {
                        dir.listFiles()?.forEach { f ->
                            val relPathRaw = f.relativeTo(sourceDir).path.replace('\\', '/')
                            val relPath = "$volumePrefix/$relPathRaw"
                            if (f.isDirectory) {
                                walk(f, relPath)
                            } else {
                                if (f.name == "mimetype") return@forEach

                                zos.putNextEntry(ZipEntry(relPath))
                                val chaptersForThisFile = volumeChapters.filter {
                                    it.originalFilePath == relPathRaw
                                }
                                if (chaptersForThisFile.isEmpty()) {
                                    f.inputStream().use { it.copyTo(zos) }
                                } else if (chaptersForThisFile.size == 1 && chaptersForThisFile.first().anchorStart == null) {
                                    val override = chaptersForThisFile.first()
                                    zos.write((override.displayHtml ?: override.contentHtml).toByteArray(Charsets.UTF_8))
                                } else {
                                    val rebuiltContent = rebuildSplitFileContent(f, chaptersForThisFile)
                                    zos.write(rebuiltContent.toByteArray(Charsets.UTF_8))
                                }
                                zos.closeEntry()
                            }
                        }
                    }
                    walk(sourceDir, "")
                }
                
                // Then write the merged OPF
                val mergedOpf = EpubMultiVolumeMerger.mergeOpfManifests(dirsToProcess, bookTitle, bookAuthor, bookDescription)
                zos.putNextEntry(ZipEntry("merged_content.opf"))
                zos.write(mergedOpf.toByteArray())
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("META-INF/container.xml"))
                zos.write("""<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
    <rootfiles>
        <rootfile full-path="merged_content.opf" media-type="application/oebps-package+xml"/>
    </rootfiles>
</container>""".toByteArray())
                zos.closeEntry()
                
            } else {
                // Single volume (exact logic from bug instructions for Single)
                val sourceDir = dirsToProcess.first()
                
                // Identify OPF path
                var opfRelPath = "OEBPS/content.opf"
                val container = File(sourceDir, "META-INF/container.xml")
                if (container.exists()) {
                    val m = Regex("full-path\\s*=\\s*\"([^\"]+)\"").find(container.readText())
                    if (m != null) opfRelPath = m.groupValues[1]
                }
                val opfFile = File(sourceDir, opfRelPath)
                val opfTextRaw = if (opfFile.exists()) opfFile.readText() else ""
                
                // 1. Update NCX and NAV Texts
                // Find NCX and NAV paths
                val ncxItemMatch = Regex("<item[^>]*media-type=\"application/x-dtbncx\\+xml\"[^>]*href=\"([^\"]+)\"").find(opfTextRaw)
                    ?: Regex("<item[^>]*href=\"([^\"]+)\"[^>]*media-type=\"application/x-dtbncx\\+xml\"").find(opfTextRaw)
                val opfFolder = if (opfRelPath.contains("/")) opfRelPath.substringBeforeLast("/") else ""
                val ncxHref = ncxItemMatch?.groupValues?.get(1)
                val ncxPath = if (ncxHref != null) if (opfFolder.isNotEmpty()) "$opfFolder/$ncxHref" else ncxHref else null
                
                val navItemMatch = Regex("<item[^>]*properties=\"[^\"]*nav[^\"]*\"[^>]*href=\"([^\"]+)\"").find(opfTextRaw)
                    ?: Regex("<item[^>]*href=\"([^\"]+)\"[^>]*properties=\"[^\"]*nav[^\"]*\"").find(opfTextRaw)
                val navHref = navItemMatch?.groupValues?.get(1)
                val navPath = if (navHref != null) if (opfFolder.isNotEmpty()) "$opfFolder/$navHref" else navHref else null
                
                var ncxText = if (ncxPath != null) File(sourceDir, ncxPath).takeIf { it.exists() }?.readText() else null
                var navText = if (navPath != null) File(sourceDir, navPath).takeIf { it.exists() }?.readText() else null

                // Update NCX/NAV for renamed chapters
                if (ncxText != null) {
                    existingChapters.forEach { chapter ->
                        val filePath = chapter.originalFilePath ?: return@forEach
                        val fileNameOnly = filePath.substringAfterLast("/")
                        val anchor = chapter.anchorStart
                        val srcPattern = if (anchor != null) "$fileNameOnly#$anchor" else fileNameOnly
                        val navPointRegex = Regex(
                            "(<navPoint[^>]*>\\s*<navLabel>\\s*<text>)(.*?)(</text>\\s*</navLabel>\\s*<content\\s+src=\"[^\"]*$srcPattern[^\"]*\"\\s*/?>)",
                            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
                        )
                        ncxText = navPointRegex.replace(ncxText!!) { m ->
                            "${m.groupValues[1]}${escapeXml(chapter.title)}${m.groupValues[3]}"
                        }
                    }
                }
                
                if (navText != null) {
                    existingChapters.forEach { chapter ->
                        val filePath = chapter.originalFilePath ?: return@forEach
                        val fileNameOnly = filePath.substringAfterLast("/")
                        val anchor = chapter.anchorStart
                        val srcPattern = if (anchor != null) "$fileNameOnly#$anchor" else fileNameOnly
                        val navLiRegex = Regex(
                            "(<a\\s+href=\"[^\"]*$srcPattern[^\"]*\"[^>]*>)(.*?)(</a>)",
                            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
                        )
                        navText = navLiRegex.replace(navText!!) { m ->
                            "${m.groupValues[1]}${escapeXml(chapter.title)}${m.groupValues[3]}"
                        }
                    }
                }

                val tocEntries = extractAllTocEntries(ncxText, navText)
                val deletedChapters = computeDeletedChapters(tocEntries, existingChapters)
                if (deletedChapters.isNotEmpty()) {
                    val (cleanedNcx, cleanedNav) = removeDeletedChaptersFromToc(ncxText, navText, deletedChapters)
                    ncxText = cleanedNcx
                    navText = cleanedNav
                }

                fun walk(dir: File) {
                    dir.listFiles()?.forEach { f ->
                        val relPath = f.relativeTo(sourceDir).path.replace('\\', '/')
                        if (f.isDirectory) {
                            walk(f)
                        } else {
                            if (relPath == "mimetype") return@forEach

                            val isOrphaned = deletedChapters.any { (fileName, _) -> f.name == fileName } &&
                                              existingChapters.none { it.originalFilePath?.endsWith(f.name) == true }
                            if (isOrphaned && f.extension.lowercase() in setOf("xhtml", "html", "htm")) {
                                return@forEach
                            }

                            zos.putNextEntry(ZipEntry(relPath))

                            if (f.name.endsWith(".opf", ignoreCase = true)) {
                                val originalOpf = f.readText(Charsets.UTF_8)
                                val patchedOpf = EpubMultiVolumeMerger.mergeOpfManifests(listOf(sourceDir), bookTitle, bookAuthor, bookDescription)
                                // wait, mergeOpfManifests returns a completely assembled xml which works.
                                // Actually, for single volume, it's safer to just regex replace the metadata.
                                var result = originalOpf
                                if (bookTitle.isNotBlank()) {
                                    val escaped = bookTitle.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                                    result = if (result.contains("<dc:title", ignoreCase = true)) {
                                        result.replace(Regex("<dc:title(?:[^>]*)?>.*?</dc:title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "<dc:title>$escaped</dc:title>")
                                    } else result.replace(Regex("<metadata([^>]*)>", RegexOption.IGNORE_CASE), "<metadata$1>\n<dc:title>$escaped</dc:title>")
                                }
                                if (bookAuthor.isNotBlank()) {
                                    val escaped = bookAuthor.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                                    result = if (result.contains("<dc:creator", ignoreCase = true)) {
                                        result.replace(Regex("<dc:creator(?:[^>]*)?>.*?</dc:creator>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "<dc:creator id=\"creator\">$escaped</dc:creator>")
                                    } else result.replace(Regex("<metadata([^>]*)>", RegexOption.IGNORE_CASE), "<metadata$1>\n<dc:creator id=\"creator\">$escaped</dc:creator>")
                                }
                                if (bookDescription.isNotBlank()) {
                                    val escaped = bookDescription.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                                    result = if (result.contains("<dc:description", ignoreCase = true)) {
                                        result.replace(Regex("<dc:description(?:[^>]*)?>.*?</dc:description>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "<dc:description>$escaped</dc:description>")
                                    } else result.replace(Regex("<metadata([^>]*)>", RegexOption.IGNORE_CASE), "<metadata$1>\n<dc:description>$escaped</dc:description>")
                                }
                                zos.write(result.toByteArray(Charsets.UTF_8))
                            } else if (relPath == ncxPath && ncxText != null) {
                                zos.write(ncxText!!.toByteArray(Charsets.UTF_8))
                            } else if (relPath == navPath && navText != null) {
                                zos.write(navText!!.toByteArray(Charsets.UTF_8))
                            } else if (coverImagePath != null && isCoverFile(relPath, opfTextRaw)) {
                                val coverFile = File(coverImagePath)
                                if (coverFile.exists()) {
                                    coverFile.inputStream().use { it.copyTo(zos) }
                                } else {
                                    f.inputStream().use { it.copyTo(zos) }
                                }
                            } else {
                                val chaptersForThisFile = existingChapters.filter { it.originalFilePath == relPath }
                                if (chaptersForThisFile.isEmpty()) {
                                    f.inputStream().use { it.copyTo(zos) }
                                } else if (chaptersForThisFile.size == 1 && chaptersForThisFile.first().anchorStart == null) {
                                    val override = chaptersForThisFile.first()
                                    zos.write((override.displayHtml ?: override.contentHtml).toByteArray(Charsets.UTF_8))
                                } else {
                                    val rebuiltContent = rebuildSplitFileContent(f, chaptersForThisFile)
                                    zos.write(rebuiltContent.toByteArray(Charsets.UTF_8))
                                }
                            }
                            zos.closeEntry()
                        }
                    }
                }
                walk(sourceDir)
            }

            // New handwritten chapters
            newChapters.forEachIndexed { index, chapter ->
                zos.putNextEntry(ZipEntry("new_chapters/new_chapter_${index + 1}.xhtml"))
                val html = """
                    <?xml version="1.0" encoding="utf-8"?>
                    <html xmlns="http://www.w3.org/1999/xhtml">
                    <head><title>${escapeXml(chapter.title)}</title></head>
                    <body>${chapter.contentHtml}</body>
                    </html>
                """.trimIndent()
                zos.write(html.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }

        return saveFileToPublicDownloads(context, tempOutputFile, sanitizedFileName)
    }

    private fun saveFileToPublicDownloads(context: Context, sourceFile: File, displayName: String): File? {
        val resolver = context.contentResolver
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/epub+zip")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val collectionUri = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
            var uri: android.net.Uri? = null
            try {
                uri = resolver.insert(collectionUri, contentValues)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { outStream ->
                        sourceFile.inputStream().use { inStream ->
                            inStream.copyTo(outStream)
                        }
                    }
                    return File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), displayName)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving to Downloads via MediaStore, trying fallback", e)
                if (uri != null) {
                    try { resolver.delete(uri, null, null) } catch (ignored: Exception) {}
                }
            }
        }
        
        try {
            val publicDownloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!publicDownloadDir.exists()) {
                publicDownloadDir.mkdirs()
            }
            val destFile = File(publicDownloadDir, displayName)
            sourceFile.inputStream().use { inSt ->
                destFile.outputStream().use { outSt ->
                    inSt.copyTo(outSt)
                }
            }
            return destFile
        } catch (e: Exception) {
            Log.e(TAG, "Standard file copy to public Downloads failed", e)
            try {
                val appExternalDownloads = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                if (appExternalDownloads != null) {
                    if (!appExternalDownloads.exists()) appExternalDownloads.mkdirs()
                    val destFile = File(appExternalDownloads, displayName)
                    sourceFile.inputStream().use { inSt ->
                        destFile.outputStream().use { outSt ->
                            inSt.copyTo(outSt)
                        }
                    }
                    Log.i(TAG, "Saved instead to app-specific external downloads: ${destFile.absolutePath}")
                    return destFile
                }
            } catch (ex: Exception) {
                Log.e(TAG, "Failsafe app-specific external downloads copy failed", ex)
            }
            try {
                val persistentFile = File(context.filesDir, displayName)
                sourceFile.inputStream().use { inSt ->
                    persistentFile.outputStream().use { outSt ->
                        inSt.copyTo(outSt)
                    }
                }
                Log.i(TAG, "Saved instead to internal files folder: ${persistentFile.absolutePath}")
                return persistentFile
            } catch (ex: Exception) {
                Log.e(TAG, "Failsafe internal files copy failed", ex)
            }
        }
        return null
    }

    /**
     * Attempts to resolve an image's src attribute to its cached local file path.
     */
    fun resolveLocalImagePath(context: Context, src: String, titleId: Long? = null): String? {
        val decodedSrc = try {
            java.net.URLDecoder.decode(src, "UTF-8")
        } catch (e: Exception) {
            src
        }
        val filename = File(decodedSrc.lowercase()).name
        val mediaDir = File(context.filesDir, "epub_media")
        val searchDir = if (titleId != null) File(mediaDir, "book_$titleId") else mediaDir
        if (!searchDir.exists()) return null

        val localFile = File(searchDir, filename)
        if (localFile.exists()) return localFile.absolutePath

        val matches = searchDir.listFiles { _, name ->
            name.lowercase().endsWith(filename)
        }
        if (!matches.isNullOrEmpty()) {
            return matches.first().absolutePath
        }
        return null
    }

    /**
     * Parses the HTML content of a chapter into sequential Text and Image blocks.
     */
    fun removeLeadingTitleFromHtml(html: String, chapterTitle: String): String {
        if (chapterTitle.isBlank()) return html
        
        val cleanTitle = chapterTitle.lowercase().trim().replace(Regex("[^\\p{L}\\p{N}]"), "")
        if (cleanTitle.isEmpty()) return html
        
        // Match the first structural block tag like h1-h6, p, or div
        val regex = Regex("<(h[1-6]|p|div)(?:\\s+[^>]*)?>(.*?)</\\1>", RegexOption.IGNORE_CASE)
        val matchResult = regex.find(html) ?: return html
        
        val tagInnerHtml = matchResult.groupValues[2]
        val tagPlainText = tagInnerHtml.replace(Regex("<[^>]*>"), "")
            .lowercase()
            .trim()
            .replace(Regex("[^\\p{L}\\p{N}]"), "")
            
        // Check if the first structural tag matches the chapter title
        if (tagPlainText == cleanTitle || 
            (tagPlainText.length > 2 && cleanTitle.contains(tagPlainText)) ||
            (cleanTitle.length > 2 && tagPlainText.contains(cleanTitle) && cleanTitle.length > 0 && tagPlainText.length - cleanTitle.length < 5)) {
            
            // Only remove the matched tag block, preserving all subsequent html tags, newlines, formatting, etc.
            val before = html.substring(0, matchResult.range.first)
            val after = html.substring(matchResult.range.last + 1)
            return before + after
        }
        
        return html
    }

    /**
     * Parses the HTML content of a chapter into sequential Text and Image blocks.
     */
    fun parseContentIntoBlocks(
        context: Context, 
        html: String, 
        titleId: Long? = null, 
        chapterTitle: String? = null
    ): List<ContentBlock> {
        val cleanedHtml = cleanHtmlForParser(html)
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<style(?:\\s+[^>]*)?>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            .replace(Regex("<script(?:\\s+[^>]*)?>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            .replace(Regex("<head(?:\\s+[^>]*)?>.*?</head>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")

        val blocks = mutableListOf<ContentBlock>()
        
        // Match both HTML img tag and SVG XML image tags
        val imgPattern = Regex("<img[^>]+src\\s*=\\s*['\"]([^'\"]+)['\"][^>]*>", RegexOption.IGNORE_CASE)
        val svgImagePattern = Regex("<image[^>]+(?:xlink:)?href\\s*=\\s*['\"]([^'\"]+)['\"][^>]*>", RegexOption.IGNORE_CASE)
        
        data class FoundImage(val start: Int, val end: Int, val src: String)
        val foundImages = mutableListOf<FoundImage>()
        
        imgPattern.findAll(cleanedHtml).forEach { match ->
            foundImages.add(FoundImage(match.range.first, match.range.last + 1, match.groupValues[1]))
        }
        
        svgImagePattern.findAll(cleanedHtml).forEach { match ->
            if (foundImages.none { it.start <= match.range.first && it.end >= match.range.last }) {
                foundImages.add(FoundImage(match.range.first, match.range.last + 1, match.groupValues[1]))
            }
        }
        
        foundImages.sortBy { it.start }
        
        var lastIdx = 0
        for (img in foundImages) {
            if (img.start > lastIdx) {
                val intermediateText = cleanedHtml.substring(lastIdx, img.start).trim()
                if (intermediateText.isNotEmpty()) {
                    val innerBlocks = splitHtmlIntoBlocks(intermediateText)
                    innerBlocks.forEach { blockText ->
                        blocks.add(ContentBlock.Text(blockText))
                    }
                }
            }
            
            val resolvedPath = resolveLocalImagePath(context, img.src, titleId)
            if (resolvedPath != null) {
                blocks.add(ContentBlock.Image(resolvedPath))
            } else {
                Log.d("EpubProcessor", "Could not resolve image path: ${img.src}")
            }
            
            lastIdx = img.end
        }
        
        if (lastIdx < cleanedHtml.length) {
            val remainingText = cleanedHtml.substring(lastIdx).trim()
            if (remainingText.isNotEmpty()) {
                val innerBlocks = splitHtmlIntoBlocks(remainingText)
                innerBlocks.forEach { blockText ->
                    blocks.add(ContentBlock.Text(blockText))
                }
            }
        }
        
        return blocks
    }

    private fun splitHtmlIntoBlocks(htmlText: String): List<String> {
        val result = mutableListOf<String>()
        val blockRegex = Regex("<(p|div|h1|h2|h3|h4|h5|h6|blockquote|pre)(?:\\s+[^>]*)?>.*?</\\1>|<hr\\s*/?>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        var lastIdx = 0
        blockRegex.findAll(htmlText).forEach { match ->
            if (match.range.first > lastIdx) {
                val intermediate = htmlText.substring(lastIdx, match.range.first).trim()
                if (intermediate.isNotEmpty()) {
                    result.add(intermediate)
                }
            }
            result.add(match.value)
            lastIdx = match.range.last + 1
        }
        if (lastIdx < htmlText.length) {
            val remaining = htmlText.substring(lastIdx).trim()
            if (remaining.isNotEmpty()) {
                result.add(remaining)
            }
        }
        return result
    }
}

sealed class ContentBlock {
    abstract val id: String
    data class Text(val htmlText: String, override val id: String = java.util.UUID.randomUUID().toString()) : ContentBlock()
    data class Image(val localPath: String, override val id: String = java.util.UUID.randomUUID().toString()) : ContentBlock()
}

fun cleanHtmlForParser(html: String): String {
    var cleaned = html.trim()
    // Strip empty lines/whitespace specifically between sibling block tags to prevent parser failure
    cleaned = cleaned.replace(Regex("</(p|div|h[1-6]|ul|ol|li|blockquote|section)>\\s*<(p|div|h[1-6]|ul|ol|li|blockquote|section|img|image|hr|div)", RegexOption.IGNORE_CASE)) { matchResult ->
        val closingTag = matchResult.groupValues[1]
        val openingTag = matchResult.groupValues[2]
        "</$closingTag><$openingTag"
    }
    // Also remove newlines inside <p> or general tags that might have been added by spacing
    cleaned = cleaned.replace(Regex("<br\\s*/?>\\s*\\n+\\s*<(p|div|h[1-6]|ul|ol|li|blockquote|section)", RegexOption.IGNORE_CASE)) { matchResult ->
        val openingTag = matchResult.groupValues[1]
        "<br /><$openingTag"
    }
    return cleaned
}

