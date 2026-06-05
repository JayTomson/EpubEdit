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
    fun parseEpub(context: Context, uri: Uri): ParsedEpub? {
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
        var coverImagePath: String? = null
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
            val destFile = File(mediaDir, "media_${System.currentTimeMillis()}_${file.name}")
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

        // Find cover image (preferring files with 'cover' in name, or otherwise largest image file)
        val coverFile = imageFiles.firstOrNull { it.nameWithoutExtension.lowercase().contains("cover") }
            ?: imageFiles.maxByOrNull { it.length() }
        if (coverFile != null) {
            coverImagePath = imageMap[coverFile.name.lowercase()]
        }

        // 4. Parse chapters from HTML files
        val chaptersList = mutableListOf<ParsedChapter>()
        
        // Sort html files alphabetically to preserve the logical reading order
        htmlFiles.sortBy { it.path }

        htmlFiles.forEach { file ->
            val htmlContent = file.readText(Charsets.UTF_8)
            val chapterTitle = extractTitleFromHtml(htmlContent, file.name)
            
            val words = WordStatsHelper.countWords(htmlContent)
            val chars = WordStatsHelper.countCharacters(htmlContent)

            // Look for first <img> tag inside html to associate chapter preview image
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

        // Clean up unzipped temporary folder
        tempDir.deleteRecursively()

        val defaultTitle = getFileNameFromUri(context, uri)?.removeSuffix(".epub") ?: "Parsed Title"
        
        return ParsedEpub(
            title = defaultTitle,
            author = "Unknown Author",
            description = "No description available",
            coverImagePath = coverImagePath,
            chapters = chaptersList
        )
    }

    private fun extractTitleFromHtml(html: String, filename: String): String {
        // Remove comments for cleaner regex
        val cleanHtml = html.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

        // Priority 1: First header h1 to h4 matching (most specific)
        val headerTags = listOf("h1", "h2", "h3", "h4")
        for (tag in headerTags) {
            val regex = Regex("<$tag[^>]*>(.*?)</$tag>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            val matches = regex.findAll(cleanHtml)
            for (match in matches) {
                val content = match.groupValues[1]
                val cleaned = stripHtmlTags(content).trim()
                if (cleaned.isNotEmpty() && cleaned.length < 100) {
                    return cleaned
                }
            }
        }

        // Priority 2: Match divs/p with class containing "title" or "chapter" or "heading"
        val containerRegex = Regex("<(?:p|div|span)[^>]+(?:class|id)=\"[^\"]*(?:title|chapter|heading)[^\"]*\"[^>]*>(.*?)</(?:p|div|span)>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val containerMatches = containerRegex.findAll(cleanHtml)
        for (match in containerMatches) {
            val content = match.groupValues[1]
            val cleaned = stripHtmlTags(content).trim()
            if (cleaned.isNotEmpty() && cleaned.length < 120) {
                return cleaned
            }
        }

        // Priority 3: Fallback to <title>
        val titleRegex = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val titleMatch = titleRegex.find(cleanHtml)
        if (titleMatch != null) {
            val cleaned = stripHtmlTags(titleMatch.groupValues[1]).trim()
            if (cleaned.isNotEmpty() && cleaned.length < 100) {
                return cleaned
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
        chapters: List<ParsedChapter>
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

            // Loop and add chapters
            chapters.forEachIndexed { idx, chap ->
                val chapId = "chapter_$idx"
                val href = "chapter_$idx.xhtml"
                
                zos.putNextEntry(ZipEntry("OEBPS/$href"))
                // Standard XHTML template for high readers compatibility
                val xhtmlContent = """
                    <?xml version="1.0" encoding="utf-8"?>
                    <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
                    <html xmlns="http://www.w3.org/1999/xhtml">
                    <head>
                        <title>${chap.title}</title>
                        <meta http-equiv="Content-Type" content="text/html; charset=utf-8" />
                    </head>
                    <body>
                        <h1>${chap.title}</h1>
                        ${chap.contentHtml}
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
                            <text>${chap.title}</text>
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
                        <dc:title>$title</dc:title>
                        <dc:creator>$author</dc:creator>
                        <dc:description>$description</dc:description>
                        <dc:language>ru</dc:language>
                        <dc:identifier id="bookid">urn:uuid:${java.util.UUID.randomUUID()}</dc:identifier>
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
                        <meta name="dtb:uid" content="urn:uuid:0"/>
                        <meta name="dtb:depth" content="1"/>
                        <meta name="dtb:totalPageCount" content="0"/>
                        <meta name="dtb:maxPageNumber" content="0"/>
                    </head>
                    <docTitle>
                        <text>$title</text>
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
}
