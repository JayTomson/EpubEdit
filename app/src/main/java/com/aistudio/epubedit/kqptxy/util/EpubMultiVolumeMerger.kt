package com.aistudio.epubedit.kqptxy.util

import android.content.Context
import android.util.Log
import java.io.*
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class ContentType {
    CHAPTER,
    TOC,
    NAV,
    TITLE_PAGE,
    COVER,
    INDEX,
    FOOTNOTES,
    COPYRIGHT,
    UNKNOWN
}

data class MergedXhtml(
    val id: String,
    val originalFile: File,
    val volumeIndex: Int,
    val originalRelPath: String,
    val title: String,
    val targetFileName: String,
    val contentType: ContentType,
    var contentHtml: String = ""
)

object EpubMultiVolumeMerger {
    private const val TAG = "EpubMultiVolumeMerger"

    class ManifestItem(val id: String, val href: String, val mediaType: String, val properties: String?)

    fun parseManifest(opfText: String): Map<String, ManifestItem> {
        val items = mutableMapOf<String, ManifestItem>()
        val itemRegex = Regex("""<item\s+([^>]+)/?>""", RegexOption.IGNORE_CASE)
        val idRegex = Regex("""id\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val hrefRegex = Regex("""href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val mediaTypeRegex = Regex("""media-type\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val propertiesRegex = Regex("""properties\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

        itemRegex.findAll(opfText).forEach { match ->
            val attrs = match.groupValues[1]
            val id = idRegex.find(attrs)?.groupValues?.get(1) ?: ""
            val href = hrefRegex.find(attrs)?.groupValues?.get(1) ?: ""
            val mediaType = mediaTypeRegex.find(attrs)?.groupValues?.get(1) ?: ""
            val properties = propertiesRegex.find(attrs)?.groupValues?.get(1)
            if (id.isNotEmpty() && href.isNotEmpty()) {
                items[href] = ManifestItem(id, href, mediaType, properties)
            }
        }
        return items
    }

    /**
     * Complete streaming, low-memory multi-volume EPUB Merge Pipeline.
     */
    fun mergeEpubVolumes(
        context: Context,
        dirsToProcess: List<File>,
        chapters: List<ParsedChapter>,
        bookTitle: String,
        bookAuthor: String,
        bookDescription: String,
        coverImagePath: String?,
        generateToc: Boolean,
        tempOutputFile: File
    ) {
        val xhtmlRegistry = mutableListOf<MergedXhtml>()
        val xhtmlPathMap = mutableMapOf<String, String>() // "volumeIndex:relPath" -> "vIndex_filename.xhtml"
        val bodyHashes = mutableSetOf<String>()

        // CSS registries
        val cssRegistry = mutableMapOf<String, String>() // md5 -> "css/style_[md5].css"
        val cssPathMap = mutableMapOf<String, String>() // "volumeIndex:relPath" -> "css/style_[md5].css"

        // Image registries
        val imageRegistry = mutableMapOf<String, String>() // md5 -> "images/img_[md5].ext"
        val imagePathMap = mutableMapOf<String, String>() // "volumeIndex:relPath" -> "images/img_[md5].ext"

        // Other assets (fonts, etc.)
        val otherRegistry = mutableMapOf<String, ByteArray>() // relPath -> bytes

        // Phase 1: Classification & Scanning
        dirsToProcess.forEachIndexed { volumeIndex, sourceDir ->
            val volPrefix = "volume_${volumeIndex + 1}"
            val allFiles = sourceDir.walkTopDown().filter { it.isFile }.toList()

            allFiles.forEach { file ->
                val relPathRaw = file.relativeTo(sourceDir).path.replace('\\', '/')
                if (relPathRaw == "mimetype" || relPathRaw.startsWith("META-INF/")) return@forEach
                if (relPathRaw.endsWith(".opf", ignoreCase = true)) return@forEach

                val ext = file.extension.lowercase()

                when {
                    ext in listOf("xhtml", "html", "htm") -> {
                        val content = file.readText(Charsets.UTF_8)
                        val contentType = classifyFile(file.name, content)

                        // Skip administrative pages from spine merge
                        if (contentType == ContentType.TOC || contentType == ContentType.NAV ||
                            contentType == ContentType.COVER || contentType == ContentType.TITLE_PAGE) {
                            return@forEach
                        }

                        // Deduplicate body text hash to prevent duplicated prologs/chapters
                        val bodyText = stripHtml(content)
                        val textHash = calculateSha256(bodyText)
                        if (bodyHashes.contains(textHash) && bodyText.length > 50) {
                            return@forEach
                        }
                        bodyHashes.add(textHash)

                        val targetName = "v${volumeIndex + 1}_${file.name.replace(".html", ".xhtml")}"
                        val merged = MergedXhtml(
                            id = "v${volumeIndex + 1}_${file.nameWithoutExtension.replace("[^a-zA-Z0-9]".toRegex(), "_")}",
                            originalFile = file,
                            volumeIndex = volumeIndex,
                            originalRelPath = relPathRaw,
                            title = extractTitle(content, file.nameWithoutExtension),
                            targetFileName = targetName,
                            contentType = contentType,
                            contentHtml = content
                        )
                        xhtmlRegistry.add(merged)
                        xhtmlPathMap["$volumeIndex:$relPathRaw"] = targetName
                    }
                    ext == "css" -> {
                        val cssBytes = file.readBytes()
                        val hash = calculateMd5(cssBytes)
                        val targetPath = if (cssRegistry.containsKey(hash)) {
                            cssRegistry[hash]!!
                        } else {
                            val path = "css/style_$hash.css"
                            cssRegistry[hash] = path
                            path
                        }
                        cssPathMap["$volumeIndex:$relPathRaw"] = targetPath
                    }
                    ext in listOf("jpg", "jpeg", "png", "webp", "gif", "svg") -> {
                        val imgBytes = file.readBytes()
                        val hash = calculateMd5(imgBytes)
                        val targetPath = if (imageRegistry.containsKey(hash)) {
                            imageRegistry[hash]!!
                        } else {
                            val path = "images/img_$hash.$ext"
                            imageRegistry[hash] = path
                            path
                        }
                        imagePathMap["$volumeIndex:$relPathRaw"] = targetPath
                    }
                    ext in listOf("ttf", "otf", "woff", "woff2") -> {
                        val path = "fonts/${file.name}"
                        otherRegistry[path] = file.readBytes()
                    }
                }
            }
        }

        // Phase 2: Path & Link Rewriting, ID Collision & Footnote Resolution
        xhtmlRegistry.forEach { merged ->
            var content = merged.contentHtml
            val volIndex = merged.volumeIndex
            val fileRelPath = merged.originalRelPath

            // 1. Namespace-prefix id="..." and name="..."
            content = content.replace(Regex("""\bid\s*=\s*(["'])(.*?)\1""", RegexOption.IGNORE_CASE)) { m ->
                val quote = m.groupValues[1]
                val idVal = m.groupValues[2]
                "id=$quote" + "v${volIndex + 1}_$idVal$quote"
            }
            content = content.replace(Regex("""\bname\s*=\s*(["'])(.*?)\1""", RegexOption.IGNORE_CASE)) { m ->
                val quote = m.groupValues[1]
                val nameVal = m.groupValues[2]
                "name=$quote" + "v${volIndex + 1}_$nameVal$quote"
            }

            // 2. Rewrite <img src="..."> and <image href="...">
            content = content.replace(Regex("""<img([^>]+)src\s*=\s*(["'])(.*?)\2""", RegexOption.IGNORE_CASE)) { m ->
                val attrs = m.groupValues[1]
                val quote = m.groupValues[2]
                val src = m.groupValues[3]
                val resolved = resolveRelativePath(fileRelPath, src)
                val targetPath = imagePathMap["$volIndex:$resolved"] ?: imagePathMap.entries.find { it.key.endsWith(File(src).name.lowercase()) }?.value ?: src
                "<img$attrs" + "src=$quote$targetPath$quote"
            }

            content = content.replace(Regex("""<image([^>]+)(?:xlink:)?href\s*=\s*(["'])(.*?)\2""", RegexOption.IGNORE_CASE)) { m ->
                val attrs = m.groupValues[1]
                val quote = m.groupValues[2]
                val href = m.groupValues[3]
                val resolved = resolveRelativePath(fileRelPath, href)
                val targetPath = imagePathMap["$volIndex:$resolved"] ?: imagePathMap.entries.find { it.key.endsWith(File(href).name.lowercase()) }?.value ?: href
                "<image$attrs" + "href=$quote$targetPath$quote"
            }

            // 3. Rewrite CSS href="..."
            content = content.replace(Regex("""<link([^>]+)href\s*=\s*(["'])(.*?)\2""", RegexOption.IGNORE_CASE)) { m ->
                val attrs = m.groupValues[1]
                val quote = m.groupValues[2]
                val href = m.groupValues[3]
                val resolved = resolveRelativePath(fileRelPath, href)
                val targetPath = cssPathMap["$volIndex:$resolved"] ?: cssPathMap.entries.find { it.key.endsWith(File(href).name.lowercase()) }?.value ?: href
                "<link$attrs" + "href=$quote$targetPath$quote"
            }

            // 4. Rewrite internal hyperlinks <a href="...">
            content = content.replace(Regex("""<a([^>]+)href\s*=\s*(["'])(.*?)\2""", RegexOption.IGNORE_CASE)) { m ->
                val attrs = m.groupValues[1]
                val quote = m.groupValues[2]
                val href = m.groupValues[3]

                if (href.startsWith("http://") || href.startsWith("https://") || href.startsWith("mailto:")) {
                    m.value
                } else if (href.startsWith("#")) {
                    val anchor = href.substring(1)
                    "<a$attrs" + "href=$quote#v${volIndex + 1}_$anchor$quote"
                } else {
                    val parts = href.split("#")
                    val filePart = parts[0]
                    val anchorPart = if (parts.size > 1) parts[1] else null
                    val resolvedFile = resolveRelativePath(fileRelPath, filePart)
                    val targetFileName = xhtmlPathMap["$volIndex:$resolvedFile"]

                    if (targetFileName != null) {
                        val newHref = if (anchorPart != null) {
                            "$targetFileName#v${volIndex + 1}_$anchorPart"
                        } else {
                            targetFileName
                        }
                        "<a$attrs" + "href=$quote$newHref$quote"
                    } else {
                        m.value
                    }
                }
            }

            // Ensure UTF-8 explicit headers
            if (!content.contains("<?xml", ignoreCase = true)) {
                content = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" + content
            }

            merged.contentHtml = content
        }

        // Phase 3: Auto Split Giant Chapters (>350KB)
        val finalXhtmlList = mutableListOf<MergedXhtml>()
        xhtmlRegistry.forEach { merged ->
            finalXhtmlList.addAll(splitGiantXhtml(merged))
        }

        // Phase 4: Cover Handling
        var finalCoverPathInZip: String? = null
        val coverFile = coverImagePath?.let { File(it) }
        if (coverFile != null && coverFile.exists()) {
            finalCoverPathInZip = "images/cover.jpg"
        } else {
            val coverEntry = imagePathMap.entries.find { it.key.lowercase().contains("cover") }
            if (coverEntry != null) {
                finalCoverPathInZip = coverEntry.value
            }
        }

        // Phase 5: Generate Double Navigation (NCX + NAV)
        val ncxNavMap = StringBuilder()
        var playOrder = 1
        finalXhtmlList.forEach { merged ->
            val safeTitle = escapeXml(merged.title)
            ncxNavMap.append("""
                <navPoint id="navPoint_${playOrder}" playOrder="${playOrder}">
                    <navLabel><text>$safeTitle</text></navLabel>
                    <content src="${merged.targetFileName}"/>
                </navPoint>
            """.trimIndent() + "\n")
            playOrder++
        }

        val ncxContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                <head>
                    <meta name="dtb:uid" content="urn:uuid:${UUID.randomUUID()}"/>
                    <meta name="dtb:depth" content="1"/>
                    <meta name="dtb:totalPageCount" content="0"/>
                    <meta name="dtb:maxPageNumber" content="0"/>
                </head>
                <docTitle><text>${escapeXml(bookTitle)}</text></docTitle>
                <docAuthor><text>${escapeXml(bookAuthor)}</text></docAuthor>
                <navMap>
                    $ncxNavMap
                </navMap>
            </ncx>
        """.trimIndent().trim()

        val navList = StringBuilder()
        finalXhtmlList.forEach { merged ->
            val safeTitle = escapeXml(merged.title)
            navList.append("                <li><a href=\"${merged.targetFileName}\">$safeTitle</a></li>\n")
        }

        val navContent = """
            <?xml version="1.0" encoding="utf-8"?>
            <!DOCTYPE html>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
            <head>
                <title>Navigation</title>
                <meta charset="utf-8" />
            </head>
            <body>
                <nav epub:type="toc" id="toc">
                    <h1>${escapeXml(bookTitle)}</h1>
                    <ol>
                        $navList                    </ol>
                </nav>
            </body>
            </html>
        """.trimIndent().trim()

        // Phase 6: Build unified OPF content
        val manifestBuilder = StringBuilder()
        val spineBuilder = StringBuilder()

        if (finalCoverPathInZip != null) {
            val ext = finalCoverPathInZip.substringAfterLast(".", "jpg").lowercase()
            val mediaType = when (ext) {
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                "svg" -> "image/svg+xml"
                else -> "image/jpeg"
            }
            manifestBuilder.append("        <item id=\"cover-image\" href=\"$finalCoverPathInZip\" media-type=\"$mediaType\" properties=\"cover-image\" />\n")
        }

        manifestBuilder.append("        <item id=\"ncx\" href=\"merged_toc.ncx\" media-type=\"application/x-dtbncx+xml\" />\n")
        manifestBuilder.append("        <item id=\"nav\" href=\"merged_nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\" />\n")

        cssRegistry.values.forEachIndexed { idx, path ->
            manifestBuilder.append("        <item id=\"css_$idx\" href=\"$path\" media-type=\"text/css\" />\n")
        }

        imageRegistry.values.forEachIndexed { idx, path ->
            if (path != finalCoverPathInZip) {
                val ext = path.substringAfterLast(".", "jpg").lowercase()
                val mediaType = when (ext) {
                    "png" -> "image/png"
                    "webp" -> "image/webp"
                    "gif" -> "image/gif"
                    "svg" -> "image/svg+xml"
                    else -> "image/jpeg"
                }
                manifestBuilder.append("        <item id=\"img_$idx\" href=\"$path\" media-type=\"$mediaType\" />\n")
            }
        }

        otherRegistry.keys.forEachIndexed { idx, path ->
            val ext = path.substringAfterLast(".", "woff2").lowercase()
            val mediaType = when (ext) {
                "woff" -> "font/woff"
                "woff2" -> "font/woff2"
                "ttf" -> "font/ttf"
                "otf" -> "font/otf"
                else -> "application/octet-stream"
            }
            manifestBuilder.append("        <item id=\"font_$idx\" href=\"$path\" media-type=\"$mediaType\" />\n")
        }

        finalXhtmlList.forEach { merged ->
            manifestBuilder.append("        <item id=\"${merged.id}\" href=\"${merged.targetFileName}\" media-type=\"application/xhtml+xml\" />\n")
            spineBuilder.append("        <itemref idref=\"${merged.id}\" />\n")
        }

        val escapedTitle = escapeXml(bookTitle)
        val escapedAuthor = escapeXml(bookAuthor)
        val escapedDesc = escapeXml(bookDescription)
        val timeStr = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        val bookUuid = "urn:uuid:${UUID.randomUUID()}"

        val opfContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" unique-identifier="bookid" version="3.0">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
                    <dc:title>$escapedTitle</dc:title>
                    <dc:creator id="creator">$escapedAuthor</dc:creator>
                    <dc:description>$escapedDesc</dc:description>
                    <dc:language>ru</dc:language>
                    <dc:identifier id="bookid">$bookUuid</dc:identifier>
                    <meta property="dcterms:modified">$timeStr</meta>
                    ${if (finalCoverPathInZip != null) "<meta name=\"cover\" content=\"cover-image\" />" else ""}
                </metadata>
                <manifest>
                    $manifestBuilder
                </manifest>
                <spine toc="ncx">
                    $spineBuilder
                </spine>
            </package>
        """.trimIndent().trim()

        // Phase 7: Incremental ZIP Generation
        ZipOutputStream(BufferedOutputStream(FileOutputStream(tempOutputFile))).use { zos ->
            // mimetype
            val mimeEntry = ZipEntry("mimetype")
            mimeEntry.method = ZipEntry.STORED
            val mimeBytes = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            mimeEntry.size = mimeBytes.size.toLong()
            mimeEntry.compressedSize = mimeBytes.size.toLong()
            val crc = CRC32()
            crc.update(mimeBytes)
            mimeEntry.crc = crc.value
            zos.putNextEntry(mimeEntry)
            zos.write(mimeBytes)
            zos.closeEntry()

            // container.xml
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

            // content.opf
            zos.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zos.write(opfContent.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // merged_toc.ncx
            zos.putNextEntry(ZipEntry("OEBPS/merged_toc.ncx"))
            zos.write(ncxContent.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // merged_nav.xhtml
            zos.putNextEntry(ZipEntry("OEBPS/merged_nav.xhtml"))
            zos.write(navContent.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // Cover Image
            if (coverFile != null && coverFile.exists()) {
                zos.putNextEntry(ZipEntry("OEBPS/images/cover.jpg"))
                coverFile.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }

            // CSS Files
            cssRegistry.forEach { (_, zipPath) ->
                val origPathMap = cssPathMap.entries.find { it.value == zipPath }
                if (origPathMap != null) {
                    val volIdx = origPathMap.key.substringBefore(":").toInt()
                    val relPath = origPathMap.key.substringAfter(":")
                    val origFile = File(dirsToProcess[volIdx], relPath)
                    if (origFile.exists()) {
                        zos.putNextEntry(ZipEntry("OEBPS/$zipPath"))
                        origFile.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }

            // Image Files
            imageRegistry.forEach { (_, zipPath) ->
                if (zipPath != "images/cover.jpg") {
                    val origPathMap = imagePathMap.entries.find { it.value == zipPath }
                    if (origPathMap != null) {
                        val volIdx = origPathMap.key.substringBefore(":").toInt()
                        val relPath = origPathMap.key.substringAfter(":")
                        val origFile = File(dirsToProcess[volIdx], relPath)
                        if (origFile.exists()) {
                            zos.putNextEntry(ZipEntry("OEBPS/$zipPath"))
                            origFile.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                }
            }

            // Other assets (fonts)
            otherRegistry.forEach { (zipPath, bytes) ->
                zos.putNextEntry(ZipEntry("OEBPS/$zipPath"))
                zos.write(bytes)
                zos.closeEntry()
            }

            // XHTML chapters
            finalXhtmlList.forEach { merged ->
                zos.putNextEntry(ZipEntry("OEBPS/${merged.targetFileName}"))
                zos.write(merged.contentHtml.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
    }

    fun classifyFile(fileName: String, content: String): ContentType {
        val nameLower = fileName.lowercase()
        val contentLower = content.lowercase()

        if (nameLower.contains("nav") || nameLower.contains("toc") || nameLower.contains("contents") || nameLower.contains("table_of_contents")) {
            return ContentType.TOC
        }
        if (contentLower.contains("epub:type=\"toc\"") || contentLower.contains("role=\"doc-toc\"") || (contentLower.contains("<nav") && contentLower.contains("epub:type"))) {
            return ContentType.TOC
        }

        if (nameLower.contains("cover")) {
            return ContentType.COVER
        }
        if (contentLower.contains("epub:type=\"cover\"") || contentLower.contains("class=\"cover\"") || contentLower.contains("id=\"cover\"")) {
            return ContentType.COVER
        }

        if (nameLower.contains("title") || nameLower.contains("promo")) {
            return ContentType.TITLE_PAGE
        }
        if (contentLower.contains("epub:type=\"titlepage\"") || contentLower.contains("class=\"titlepage\"")) {
            return ContentType.TITLE_PAGE
        }

        if (nameLower.contains("copyright") || nameLower.contains("legal") || nameLower.contains("license") || nameLower.contains("copyr")) {
            return ContentType.COPYRIGHT
        }
        if (contentLower.contains("epub:type=\"copyright\"") || contentLower.contains("copyright &copy;") || contentLower.contains("copyright ©")) {
            return ContentType.COPYRIGHT
        }

        if (nameLower.contains("footnote") || nameLower.contains("note") || nameLower.contains("anno")) {
            return ContentType.FOOTNOTES
        }
        if (contentLower.contains("epub:type=\"footnote\"") || contentLower.contains("epub:type=\"footnotes\"") || contentLower.contains("class=\"footnote\"")) {
            return ContentType.FOOTNOTES
        }

        if (nameLower.contains("index")) {
            return ContentType.INDEX
        }
        if (contentLower.contains("epub:type=\"index\"")) {
            return ContentType.INDEX
        }

        return ContentType.CHAPTER
    }

    private fun stripHtml(html: String): String {
        val bodyMatcher = Regex("<body[^>]*>([\\s\\S]*?)</body>", RegexOption.IGNORE_CASE).find(html)
        val bodyText = bodyMatcher?.groupValues?.get(1) ?: html
        return bodyText.replace(Regex("<[^>]*>"), "").replace(Regex("\\s+"), " ").trim()
    }

    private fun calculateSha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(text.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    private fun calculateMd5(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5")
        val hashBytes = digest.digest(bytes)
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    private fun extractTitle(html: String, fallback: String): String {
        val titleMatch = Regex("<title[^>]*>(.*?)</title>", RegexOption.IGNORE_CASE).find(html)
        if (titleMatch != null && titleMatch.groupValues[1].isNotBlank()) {
            return stripHtmlTags(titleMatch.groupValues[1]).trim()
        }
        val h1Match = Regex("<h1[^>]*>(.*?)</h1>", RegexOption.IGNORE_CASE).find(html)
        if (h1Match != null && h1Match.groupValues[1].isNotBlank()) {
            return stripHtmlTags(h1Match.groupValues[1]).trim()
        }
        return fallback.replace('_', ' ').replace('-', ' ').trim()
    }

    private fun stripHtmlTags(html: String): String {
        return html.replace(Regex("<[^>]*>"), "")
    }

    private fun resolveRelativePath(baseRelPath: String, relativeRef: String): String {
        if (relativeRef.startsWith("http://") || relativeRef.startsWith("https://") || relativeRef.startsWith("mailto:")) return relativeRef
        val cleanRef = relativeRef.split("#")[0]
        val parts = baseRelPath.replace('\\', '/').split("/")
        val refParts = cleanRef.replace('\\', '/').split("/")
        val resolved = mutableListOf<String>()
        if (parts.size > 1) {
            resolved.addAll(parts.subList(0, parts.size - 1))
        }
        for (part in refParts) {
            if (part == "." || part.isEmpty()) continue
            if (part == "..") {
                if (resolved.isNotEmpty()) resolved.removeAt(resolved.size - 1)
            } else {
                resolved.add(part)
            }
        }
        return resolved.joinToString("/")
    }

    private fun splitGiantXhtml(merged: MergedXhtml): List<MergedXhtml> {
        val text = merged.contentHtml
        if (text.length <= 350000) return listOf(merged)

        val parts = mutableListOf<String>()
        val bodyStartMatch = Regex("<body[^>]*>", RegexOption.IGNORE_CASE).find(text)
        val bodyEndMatch = Regex("</body>", RegexOption.IGNORE_CASE).find(text)

        if (bodyStartMatch == null || bodyEndMatch == null) {
            val numParts = (text.length + 300000) / 300000
            for (i in 0 until numParts) {
                val start = i * 300000
                val end = minOf(start + 300000, text.length)
                parts.add(text.substring(start, end))
            }
        } else {
            val header = text.substring(0, bodyStartMatch.range.last + 1)
            val footer = text.substring(bodyEndMatch.range.first)
            val bodyInner = text.substring(bodyStartMatch.range.last + 1, bodyEndMatch.range.first)

            var startIdx = 0
            while (startIdx < bodyInner.length) {
                val endIdx = startIdx + 300000
                if (endIdx >= bodyInner.length) {
                    parts.add(header + bodyInner.substring(startIdx) + footer)
                    break
                } else {
                    var splitAt = bodyInner.indexOf("</p>", endIdx - 20000)
                    if (splitAt == -1 || splitAt > endIdx + 20000) {
                        splitAt = bodyInner.indexOf("</div>", endIdx - 20000)
                    }
                    if (splitAt == -1 || splitAt > endIdx + 20000) {
                        splitAt = bodyInner.indexOf(">", endIdx) + 1
                    }
                    if (splitAt <= 0) {
                        splitAt = endIdx
                    } else {
                        splitAt += 4
                    }

                    parts.add(header + bodyInner.substring(startIdx, minOf(splitAt, bodyInner.length)) + footer)
                    startIdx = splitAt
                }
            }
        }

        return parts.mapIndexed { idx, part ->
            val suffix = "_part${idx + 1}"
            MergedXhtml(
                id = "${merged.id}$suffix",
                originalFile = merged.originalFile,
                volumeIndex = merged.volumeIndex,
                originalRelPath = merged.originalRelPath,
                title = if (idx == 0) merged.title else "${merged.title} ($idx)",
                targetFileName = merged.targetFileName.replace(".xhtml", "$suffix.xhtml").replace(".html", "$suffix.html"),
                contentType = merged.contentType,
                contentHtml = part
            )
        }
    }

    private fun countWords(text: String): Int {
        val clean = text.trim()
        if (clean.isEmpty()) return 0
        return clean.split(Regex("\\s+")).size
    }

    private fun escapeXml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    fun mergeOpfManifests(
        sourceDirs: List<File>,
        title: String?,
        author: String?,
        description: String?,
        generateToc: Boolean = true
    ): String {
        val manifestBuilder = java.lang.StringBuilder()
        val spineBuilder = java.lang.StringBuilder()
        val bookUuid = "urn:uuid:" + UUID.randomUUID()
        sourceDirs.forEachIndexed { index, sourceDir ->
            val volumePrefix = "volume_${index + 1}"
            var opfRelPath = "OEBPS/content.opf"
            val container = File(sourceDir, "META-INF/container.xml")
            if (container.exists()) {
                val m = Regex("""full-path\s*=\s*["']([^"']+)["']""").find(container.readText())
                if (m != null) {
                    opfRelPath = m.groupValues[1]
                }
            }
            val opfFile = File(sourceDir, opfRelPath)
            if (opfFile.exists()) {
                val opfText = opfFile.readText(Charsets.UTF_8)
                val opfFolder = if (opfRelPath.contains("/")) opfRelPath.substringBeforeLast("/") else ""
                val itemRegex = Regex("<item\\s+([^>]+)/?>", RegexOption.IGNORE_CASE)
                val idRegex = Regex("id\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                val hrefRegex = Regex("href\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                val mediaTypeRegex = Regex("media-type\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                val propertiesRegex = Regex("properties\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                val itemIdMap = mutableMapOf<String, String>()
                itemRegex.findAll(opfText).forEach { match ->
                    val attrs = match.groupValues[1]
                    val id = idRegex.find(attrs)?.groupValues?.get(1) ?: ""
                    val href = hrefRegex.find(attrs)?.groupValues?.get(1) ?: ""
                    val mediaType = mediaTypeRegex.find(attrs)?.groupValues?.get(1) ?: ""
                    val properties = propertiesRegex.find(attrs)?.groupValues?.get(1)
                    if (id.isNotEmpty() && href.isNotEmpty()) {
                        val uniqueId = "v${index + 1}_$id"
                        itemIdMap[id] = uniqueId
                        val resolvedHref = if (opfFolder.isNotEmpty()) {
                            "$volumePrefix/$opfFolder/$href"
                        } else {
                            "$volumePrefix/$href"
                        }
                        val cleanHref = resolveRelativePath(opfRelPath, resolvedHref)
                        val propertiesAttr = if (properties != null) " properties=\"$properties\"" else ""
                        manifestBuilder.append("        <item id=\"$uniqueId\" href=\"$cleanHref\" media-type=\"$mediaType\"$propertiesAttr />\n")
                    }
                }
                val itemrefRegex = Regex("<itemref\\s+([^>]+)/?>", RegexOption.IGNORE_CASE)
                val idrefRegex = Regex("idref\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                val linearRegex = Regex("linear\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                itemrefRegex.findAll(opfText).forEach { match ->
                    val attrs = match.groupValues[1]
                    val idref = idrefRegex.find(attrs)?.groupValues?.get(1) ?: ""
                    val linear = linearRegex.find(attrs)?.groupValues?.get(1)
                    if (idref.isNotEmpty()) {
                        val uniqueIdref = itemIdMap[idref] ?: "v${index + 1}_$idref"
                        val linearAttr = if (linear != null) " linear=\"$linear\"" else ""
                        spineBuilder.append("        <itemref idref=\"$uniqueIdref\"$linearAttr />\n")
                    }
                }
            }
        }
        manifestBuilder.append("        <item id=\"merged_ncx\" href=\"merged_toc.ncx\" media-type=\"application/x-dtbncx+xml\" />\n")
        manifestBuilder.append("        <item id=\"merged_nav\" href=\"merged_nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\" />\n")
        val escapedTitle = escapeXml(title ?: "Untitled")
        val escapedAuthor = escapeXml(author ?: "Unknown")
        val escapedDesc = escapeXml(description ?: "")
        val timeStr = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        return """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" unique-identifier="bookid" version="3.0">
    <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
        <dc:title>$escapedTitle</dc:title>
        <dc:creator id="creator">$escapedAuthor</dc:creator>
        <dc:description>$escapedDesc</dc:description>
        <dc:language>ru</dc:language>
        <dc:identifier id="bookid">$bookUuid</dc:identifier>
        <meta property="dcterms:modified">$timeStr</meta>
    </metadata>
    <manifest>
$manifestBuilder    </manifest>
    <spine toc="merged_ncx">
$spineBuilder    </spine>
</package>""".trimIndent()
    }
}
