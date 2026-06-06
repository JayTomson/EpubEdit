package com.example.util

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
    val chapters: List<ParsedChapter>
)

data class ParsedChapter(
    val title: String,
    val contentHtml: String,
    val wordCount: Int,
    val characterCount: Int,
    val previewImagePath: String? = null
)

object EpubProcessor {
    private const val TAG = "EpubProcessor"

    /**
     * Parses an EPUB file from a given content Uri or file input stream.
     * Extracts info, chapters, and cover image.
     */
    fun parseEpub(context: Context, uri: Uri, titleId: Long? = null): ParsedEpub? {
        val resolver = context.contentResolver
        val tempDir = File(context.cacheDir, "epub_unzipped_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        // 1. Unzip the whole EPUB into a temp directory to allow multi-pass lookups
        try {
            resolver.openInputStream(uri)?.use { inputStream ->
                val zipInputStream = ZipInputStream(inputStream)
                var entry: ZipEntry? = zipInputStream.getNextEntry()
                while (entry != null) {
                    val outFile = File(tempDir, entry.name)
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
        } catch (e: Exception) {
            Log.e(TAG, "Error unzipping EPUB", e)
            return null
        }

        // 2. Scan tempDir for HTML and image assets
        val htmlFiles = mutableListOf<File>()
        val imageFiles = mutableListOf<File>()

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
                    }
                }
            }
        }
        scanDir(tempDir)

        // 3. Keep extracted images persistently in a media folder
        val mediaDir = File(context.filesDir, "epub_media")
        if (!mediaDir.exists()) mediaDir.mkdirs()

        val imageMap = mutableMapOf<String, String>() // filename -> persistent absolute path
        imageFiles.forEach { file ->
            val prefix = if (titleId != null) "book_${titleId}_" else "media_${System.currentTimeMillis()}_"
            val destFile = File(mediaDir, "$prefix${file.name}")
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
                val doc = dBuilder.parse(opfFile)
                doc.documentElement.normalize()

                // Parse manifest items
                val items = doc.getElementsByTagNameNS("*", "item")
                for (i in 0 until items.length) {
                    val item = items.item(i) as org.w3c.dom.Element
                    val id = item.getAttribute("id")
                    val href = item.getAttribute("href")
                    val mediaType = item.getAttribute("media-type")
                    if (id.isNotEmpty() && href.isNotEmpty()) {
                        manifestItems[id] = ManifestItem(id, href, mediaType)
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
                Log.e(TAG, "Failed DOM reading content.opf", e)
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

        data class NcxNavPoint(val title: String, val src: String, val fileHref: String, val anchor: String?)
        val ncxNavPoints = mutableListOf<NcxNavPoint>()

        if (ncxFileResolved != null && ncxFileResolved.exists()) {
            try {
                val dbFactory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                dbFactory.isNamespaceAware = false
                val dBuilder = dbFactory.newDocumentBuilder()
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

                            ncxNavPoints.add(NcxNavPoint(title, cleanSrcAttr, fileHref, anchor))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed DOM parsing toc.ncx", e)
            }
        }

        // 4. Parse chapters (with fallbacks)
        val chaptersList = mutableListOf<ParsedChapter>()

        if (ncxNavPoints.isNotEmpty()) {
            // NCX splitting strategy (highly accurate for splitting single giant HTML files)
            ncxNavPoints.forEachIndexed { idx, item ->
                try {
                    val decodedFileHref = java.net.URLDecoder.decode(item.fileHref, "UTF-8")
                    val chapterFile = File(opfDir, decodedFileHref)
                    if (chapterFile.exists()) {
                        val fullHtml = chapterFile.readText(Charsets.UTF_8)

                        val startIdx = if (item.anchor != null) {
                            findAnchorPositionInHtml(fullHtml, item.anchor)
                        } else {
                            0
                        }

                        // Robust lookahead parsing: find the next anchor anywhere in the SAME file in any subsequent navPoints
                        var endIdx = -1
                        val finalStartIdx = if (startIdx == -1) 0 else startIdx

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

                        val words = WordStatsHelper.countWords(htmlSegment)
                        val chars = WordStatsHelper.countCharacters(htmlSegment)

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

                        chaptersList.add(ParsedChapter(
                            title = item.title,
                            contentHtml = htmlSegment,
                            wordCount = words,
                            characterCount = chars,
                            previewImagePath = chapPreviewImagePath
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
                            val chapterTitle = extractTitleFromHtml(htmlContent, chapterFile.name)

                            val words = WordStatsHelper.countWords(htmlContent)
                            val chars = WordStatsHelper.countCharacters(htmlContent)

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
                                contentHtml = htmlContent,
                                wordCount = words,
                                characterCount = chars,
                                previewImagePath = chapPreviewImagePath
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
                    val chapterTitle = extractTitleFromHtml(htmlContent, file.name)

                    val words = WordStatsHelper.countWords(htmlContent)
                    val chars = WordStatsHelper.countCharacters(htmlContent)

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
                        contentHtml = htmlContent,
                        wordCount = words,
                        characterCount = chars,
                        previewImagePath = chapPreviewImagePath
                    ))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed sorting fallback on ${file.name}", e)
                }
            }
        }

        // Clean up unzipped temporary folder
        tempDir.deleteRecursively()

        val defaultTitle = extractedTitle?.let { stripHtmlTags(it) }?.trim()
            ?: (getFileNameFromUri(context, uri)?.removeSuffix(".epub") ?: "Parsed Title")
        val defaultAuthor = extractedAuthor?.let { stripHtmlTags(it) }?.trim() ?: "Unknown Author"
        val defaultDesc = extractedDesc?.let { stripHtmlTags(it) }?.trim() ?: "No description available"

        return ParsedEpub(
            title = defaultTitle,
            author = defaultAuthor,
            description = defaultDesc,
            coverImagePath = coverImagePath,
            chapters = chaptersList
        )
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
                    return match.range.first
                }
            }
        }
        
        return -1
    }

    data class ManifestItem(val id: String, val href: String, val mediaType: String?)


    private fun extractTitleFromHtml(html: String, filename: String): String {
        // Remove comments for cleaner regex
        val cleanHtml = html.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

        val tagRegex = Regex("<(h1|h2|h3|h4|p|div|span)(\\s+[^>]*)?>(.*?)</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val matches = tagRegex.findAll(cleanHtml)
        val potentialTitles = mutableListOf<String>()
        
        for (match in matches) {
            val tagName = match.groupValues[1].lowercase()
            val attributes = match.groupValues[2] ?: ""
            val content = match.groupValues[3]
            
            val isHeader = tagName in listOf("h1", "h2", "h3", "h4")
            val hasTitleAttr = attributes.isNotEmpty() && (
                attributes.contains("title", ignoreCase = true) ||
                attributes.contains("chapter", ignoreCase = true) ||
                attributes.contains("heading", ignoreCase = true) ||
                attributes.contains("hdr", ignoreCase = true)
            )
            
            if (isHeader || hasTitleAttr) {
                val cleaned = stripHtmlTags(content).trim()
                if (cleaned.isNotEmpty() && cleaned.length < 150) {
                    val normalized = cleaned.replace(Regex("\\s+"), " ")
                    if (normalized.length > 1 && !potentialTitles.contains(normalized)) {
                        potentialTitles.add(normalized)
                    }
                }
            }
        }

        // Add fallback to <title> tag if potentialTitles is empty or doesn't have good info
        val titleRegex = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val titleMatch = titleRegex.find(cleanHtml)
        if (titleMatch != null) {
            val cleanedTitle = stripHtmlTags(titleMatch.groupValues[1]).trim().replace(Regex("\\s+"), " ")
            if (cleanedTitle.isNotEmpty() && cleanedTitle.length < 150) {
                if (!potentialTitles.contains(cleanedTitle)) {
                    potentialTitles.add(cleanedTitle)
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
        val cleanText = contentHtml.replace(Regex("<[^>]*>"), "")
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]"), "")
        
        val cleanTitle = chapterTitle.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]"), "")
            
        if (cleanTitle.isEmpty() || cleanText.isEmpty()) return false
        
        // 1. Direct contains check of normalized strings
        if (cleanText.take(400).contains(cleanTitle)) return true
        if (cleanTitle.contains(cleanText.take(20))) return true
        
        // 2. Word-by-word intersection check (e.g. "Глава 1. Пролог" vs "Пролог")
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
        titleId: Long? = null
    ): File? {
        val sanitizedFileName = if (fileName.endsWith(".epub", ignoreCase = true)) fileName else "$fileName.epub"
        
        // Define destination file inside the public Downloads directory
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadDir.exists()) {
            downloadDir.mkdirs()
        }
        val outputFile = File(downloadDir, sanitizedFileName)

        try {
            val fos = FileOutputStream(outputFile)
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
            """.trimIndent()
            zos.write(containerXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // 3. Keep track of items to add to OPF manifest and spine
            val manifestItems = StringBuilder()
            val spineItems = StringBuilder()
            val ncxNavMap = StringBuilder()

            // Add Cover image if exists
            var hasCover = false
            if (coverImagePath != null) {
                val coverFile = File(coverImagePath)
                if (coverFile.exists()) {
                    hasCover = true
                    zos.putNextEntry(ZipEntry("OEBPS/cover.jpg"))
                    coverFile.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                    manifestItems.append("<item id=\"cover-image\" href=\"cover.jpg\" media-type=\"image/jpeg\" properties=\"cover-image\"/>\n")
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

            // Loop and add chapters
            chapters.forEachIndexed { idx, chap ->
                val chapId = "chapter_$idx"
                val href = "chapter_$idx.xhtml"
                
                zos.putNextEntry(ZipEntry("OEBPS/$href"))
                
                // Smart Title check to avoid visual repetition in advanced readers
                val containsTitleHeader = containsAnyTitleRepresentation(chap.contentHtml, chap.title)
                val headerTag = if (containsTitleHeader) "" else "<h1>${escapeXml(chap.title)}</h1>\n"

                // Standard XHTML template for high readers compatibility
                val xhtmlContent = """
                    <?xml version="1.0" encoding="utf-8"?>
                    <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
                    <html xmlns="http://www.w3.org/1999/xhtml">
                    <head>
                        <title>${escapeXml(chap.title)}</title>
                        <meta http-equiv="Content-Type" content="text/html; charset=utf-8" />
                    </head>
                    <body>
                        $headerTag${chap.contentHtml}
                    </body>
                    </html>
                """.trimIndent()
                
                zos.write(xhtmlContent.toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                manifestItems.append("<item id=\"$chapId\" href=\"$href\" media-type=\"application/xhtml+xml\"/>\n")
                spineItems.append("<itemref idref=\"$chapId\"/>\n")
                ncxNavMap.append("""
                    <navPoint id="$chapId" playOrder="${idx + 1}">
                        <navLabel>
                            <text>${escapeXml(chap.title)}</text>
                        </navLabel>
                        <content src="$href"/>
                    </navPoint>
                """.trimIndent() + "\n")
            }

            // 4. content.opf
            zos.putNextEntry(ZipEntry("OEBPS/content.opf"))
            val opfContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" unique-identifier="bookid" version="2.0">
                    <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
                        <dc:title>$escapedTitle</dc:title>
                        <dc:creator>$escapedAuthor</dc:creator>
                        <dc:description>$escapedDesc</dc:description>
                        <dc:language>ru</dc:language>
                        <dc:identifier id="bookid">$bookUuid</dc:identifier>
                        ${if (hasCover) "<meta name=\"cover\" content=\"cover-image\"/>" else ""}
                    </metadata>
                    <manifest>
                        <item id="tcx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                        $manifestItems
                    </manifest>
                    <spine toc="tcx">
                        $spineItems
                    </spine>
                </package>
            """.trimIndent()
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
                        $ncxNavMap
                    </navMap>
                </ncx>
            """.trimIndent()
            zos.write(ncxContent.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.flush()
            zos.close()
            fos.close()
            return outputFile
        } catch (e: Exception) {
            Log.e(TAG, "Error generating EPUB bundle", e)
            return null
        }
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
        if (mediaDir.exists()) {
            if (titleId != null) {
                val bookPrefix = "book_${titleId}_"
                val matches = mediaDir.listFiles { _, name ->
                    val lowerName = name.lowercase()
                    lowerName.startsWith(bookPrefix.lowercase()) && 
                    (lowerName.endsWith("_$filename") || lowerName == "$bookPrefix$filename" || lowerName.endsWith("_" + filename.replace(bookPrefix, "")))
                }
                if (!matches.isNullOrEmpty()) {
                    return matches.first().absolutePath
                }
            }
            // Fallback
            val matches = mediaDir.listFiles { _, name ->
                val lowerName = name.lowercase()
                lowerName.endsWith("_$filename") || lowerName == filename
            }
            if (!matches.isNullOrEmpty()) {
                return matches.first().absolutePath
            }
        }
        return null
    }

    /**
     * Parses the HTML content of a chapter into sequential Text and Image blocks.
     */
    fun parseContentIntoBlocks(context: Context, html: String, titleId: Long? = null): List<ContentBlock> {
        val blocks = mutableListOf<ContentBlock>()
        
        // Match both HTML img tag and SVG XML image tags
        val imgPattern = Regex("<img[^>]+src\\s*=\\s*['\"]([^'\"]+)['\"][^>]*>", RegexOption.IGNORE_CASE)
        val svgImagePattern = Regex("<image[^>]+(?:xlink:)?href\\s*=\\s*['\"]([^'\"]+)['\"][^>]*>", RegexOption.IGNORE_CASE)
        
        data class FoundImage(val start: Int, val end: Int, val src: String)
        val foundImages = mutableListOf<FoundImage>()
        
        imgPattern.findAll(html).forEach { match ->
            foundImages.add(FoundImage(match.range.first, match.range.last + 1, match.groupValues[1]))
        }
        
        svgImagePattern.findAll(html).forEach { match ->
            if (foundImages.none { it.start <= match.range.first && it.end >= match.range.last }) {
                foundImages.add(FoundImage(match.range.first, match.range.last + 1, match.groupValues[1]))
            }
        }
        
        foundImages.sortBy { it.start }
        
        var lastIdx = 0
        for (img in foundImages) {
            if (img.start > lastIdx) {
                val intermediateText = html.substring(lastIdx, img.start).trim()
                if (intermediateText.isNotEmpty()) {
                    blocks.add(ContentBlock.Text(intermediateText))
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
        
        if (lastIdx < html.length) {
            val remainingText = html.substring(lastIdx).trim()
            if (remainingText.isNotEmpty()) {
                blocks.add(ContentBlock.Text(remainingText))
            }
        }
        
        return blocks
    }
}

sealed class ContentBlock {
    abstract val id: String
    data class Text(val htmlText: String, override val id: String = java.util.UUID.randomUUID().toString()) : ContentBlock()
    data class Image(val localPath: String, override val id: String = java.util.UUID.randomUUID().toString()) : ContentBlock()
}

