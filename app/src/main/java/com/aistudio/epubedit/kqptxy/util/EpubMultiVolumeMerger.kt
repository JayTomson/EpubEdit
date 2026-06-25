package com.aistudio.epubedit.kqptxy.util

import android.content.Context
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import java.util.UUID
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

class VolumeResource(
    val volumeIndex: Int,
    val originalFile: File,
    val relPath: String, // e.g. "OEBPS/chapter1.xhtml"
    val fileExtension: String,
    var contentType: ContentType = ContentType.UNKNOWN,
    var contentHash: String = "",
    var newRelPath: String = "",
    var isDuplicate: Boolean = false,
    var canonicalResource: VolumeResource? = null
)

object EpubMultiVolumeMerger {
    private const val TAG = "EpubMultiVolumeMerger"

    fun mergeVolumes(
        context: Context,
        sourceDirs: List<File>,
        outputZipFile: File,
        bookTitle: String,
        bookAuthor: String,
        bookDescription: String,
        coverImagePath: String?,
        generateToc: Boolean
    ): Boolean {
        try {
            Log.d(TAG, "Starting merge/rebuild of ${sourceDirs.size} volumes into: ${outputZipFile.absolutePath}")

            // 1. Discover all files and parse manifest items
            val allResources = mutableListOf<VolumeResource>()
            val allMappedResources = mutableMapOf<String, VolumeResource>() // key: "${volIdx}_${lowercase_relPath}"

            sourceDirs.forEachIndexed { volIdx, sourceDir ->
                var opfRelPath = "OEBPS/content.opf"
                val containerFile = File(sourceDir, "META-INF/container.xml")
                if (containerFile.exists()) {
                    val match = Regex("""full-path\s*=\s*["']([^"']+)["']""").find(containerFile.readText())
                    if (match != null) {
                        opfRelPath = match.groupValues[1]
                    }
                }
                val opfFile = File(sourceDir, opfRelPath)
                if (!opfFile.exists()) {
                    Log.w(TAG, "OPF not found in volume ${volIdx + 1}: ${opfFile.absolutePath}")
                    return@forEachIndexed
                }

                val opfContent = opfFile.readText(Charsets.UTF_8)
                val opfDir = if (opfRelPath.contains("/")) opfRelPath.substringBeforeLast("/") + "/" else ""

                // Extract items using regex to be completely resilient
                val itemRegex = Regex("""<item\s+([^>]+)/?>""", RegexOption.IGNORE_CASE)
                val idRegex = Regex("""id\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                val hrefRegex = Regex("""href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

                itemRegex.findAll(opfContent).forEach { match ->
                    val attrs = match.groupValues[1]
                    val id = idRegex.find(attrs)?.groupValues?.get(1) ?: ""
                    val href = hrefRegex.find(attrs)?.groupValues?.get(1) ?: ""

                    if (id.isNotEmpty() && href.isNotEmpty()) {
                        val decodedHref = try {
                            java.net.URLDecoder.decode(href, "UTF-8")
                        } catch (e: Exception) {
                            href
                        }
                        val relPath = if (opfDir.isNotEmpty()) "$opfDir$decodedHref" else decodedHref
                        val cleanRelPath = normalizePath(relPath)
                        val file = File(sourceDir, cleanRelPath)

                        if (file.exists() && !file.isDirectory) {
                            val ext = file.extension.lowercase()
                            val resource = VolumeResource(
                                volumeIndex = volIdx,
                                originalFile = file,
                                relPath = cleanRelPath,
                                fileExtension = ext
                            )
                            allResources.add(resource)
                            allMappedResources["${volIdx}_${cleanRelPath}".lowercase()] = resource
                        }
                    }
                }
            }

            // 2. Classify resources
            allResources.forEach { res ->
                if (res.fileExtension in listOf("xhtml", "html", "htm")) {
                    val content = try { res.originalFile.readText(Charsets.UTF_8) } catch (e: Exception) { "" }
                    res.contentType = classifyResource(res.relPath, content)
                }
            }

            // 3. Compute hashes and Deduplicate (CSS, Images, Fonts, XHTML)
            val seenHashes = mutableMapOf<String, VolumeResource>() // key: "type_hash" -> first encountered resource

            allResources.forEach { res ->
                if (res.contentType in listOf(ContentType.TOC, ContentType.NAV)) {
                    // Do not merge or copy old NAV/TOCs, we generate fresh ones!
                    res.isDuplicate = true
                    return@forEach
                }

                val category = when (res.fileExtension) {
                    "css" -> "css"
                    "xhtml", "html", "htm" -> "xhtml"
                    "ttf", "otf", "woff", "woff2" -> "font"
                    in listOf("jpg", "jpeg", "png", "webp", "gif", "svg") -> "image"
                    else -> "misc"
                }

                if (category == "css") {
                    val text = try { res.originalFile.readText(Charsets.UTF_8).replace(Regex("\\s+"), "") } catch (e: Exception) { "" }
                    res.contentHash = computeStringHash(text)
                } else if (category == "xhtml") {
                    val text = try { res.originalFile.readText(Charsets.UTF_8) } catch (e: Exception) { "" }
                    val stripped = text.replace(Regex("<[^>]*>"), "").replace(Regex("\\s+"), "").trim().lowercase()
                    // Deduplicate only substantial chapters to avoid false positives on blank pages
                    if (stripped.length > 50) {
                        res.contentHash = computeStringHash(stripped)
                    }
                } else {
                    res.contentHash = try { computeFileHash(res.originalFile) } catch (e: Exception) { "" }
                }

                if (res.contentHash.isNotEmpty()) {
                    val hashKey = "${category}_${res.contentHash}"
                    val existing = seenHashes[hashKey]
                    if (existing != null) {
                        res.isDuplicate = true
                        res.canonicalResource = existing
                    } else {
                        seenHashes[hashKey] = res
                    }
                }
            }

            // 4. Resolve Canonical Cover Image
            var canonicalCoverRes: VolumeResource? = null
            if (coverImagePath != null) {
                val coverFile = File(coverImagePath)
                if (coverFile.exists()) {
                    val ext = coverFile.extension.lowercase().ifEmpty { "jpg" }
                    canonicalCoverRes = VolumeResource(
                        volumeIndex = -1,
                        originalFile = coverFile,
                        relPath = "OEBPS/cover.$ext",
                        fileExtension = ext,
                        contentType = ContentType.COVER,
                        newRelPath = "OEBPS/cover.$ext"
                    )
                }
            }
            if (canonicalCoverRes == null) {
                // Find cover images in existing resources and pick the largest file size
                val coverImages = allResources.filter {
                    !it.isDuplicate && (it.contentType == ContentType.COVER || it.relPath.lowercase().contains("cover")) &&
                            it.fileExtension in listOf("jpg", "jpeg", "png", "webp")
                }
                canonicalCoverRes = coverImages.maxByOrNull { it.originalFile.length() }
            }

            // Remove other cover images from final manifest/spine if we have a canonical one
            if (canonicalCoverRes != null) {
                allResources.forEach { res ->
                    if (res.volumeIndex != canonicalCoverRes.volumeIndex &&
                        (res.contentType == ContentType.COVER || res.relPath.lowercase().contains("cover")) &&
                        res.fileExtension in listOf("jpg", "jpeg", "png", "webp")
                    ) {
                        res.isDuplicate = true
                        res.canonicalResource = canonicalCoverRes
                    }
                }
            }

            // 5. Target Path Re-Mapping
            var cssCounter = 1
            var imgCounter = 1
            var fontCounter = 1
            var miscCounter = 1

            allResources.forEach { res ->
                if (res.isDuplicate) return@forEach

                val name = res.originalFile.name
                val ext = res.fileExtension

                res.newRelPath = when {
                    ext == "css" -> "OEBPS/css/style_${cssCounter++}.css"
                    ext in listOf("jpg", "jpeg", "png", "webp", "gif", "svg") -> "OEBPS/images/img_${imgCounter++}.$ext"
                    ext in listOf("ttf", "otf", "woff", "woff2") -> "OEBPS/fonts/font_${fontCounter++}.$ext"
                    ext in listOf("xhtml", "html", "htm") -> {
                        if (sourceDirs.size > 1) {
                            "OEBPS/volume_${res.volumeIndex + 1}/$name"
                        } else {
                            "OEBPS/$name"
                        }
                    }
                    else -> "OEBPS/misc/file_${miscCounter++}.$ext"
                }
            }

            if (canonicalCoverRes != null && canonicalCoverRes.volumeIndex == -1) {
                canonicalCoverRes.newRelPath = "OEBPS/cover.${canonicalCoverRes.fileExtension}"
            }

            // 6. Split Giant XHTML Files & Index Anchor IDs
            val outputEntries = mutableListOf<OutputEntry>()
            val anchorRegistry = mutableMapOf<String, String>() // key: "volIdx_origPath#anchor" -> "newPath#prefixedAnchor"

            allResources.forEach { res ->
                if (res.isDuplicate || res.fileExtension !in listOf("xhtml", "html", "htm")) return@forEach

                val content = try { res.originalFile.readText(Charsets.UTF_8) } catch (e: Exception) { "" }
                val volumePrefix = "b${res.volumeIndex}_"

                // Extract and index all original anchors
                val idRegex = Regex("""\b(id|name)\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
                val anchors = idRegex.findAll(content).map { it.groupValues[2] }.toSet()

                val parts = splitLargeXhtml(content, 300 * 1024)
                val newBaseName = res.newRelPath.substringBeforeLast(".")

                parts.forEachIndexed { partIdx, partContent ->
                    val partRelPath = if (parts.size > 1) "${newBaseName}_part${partIdx + 1}.xhtml" else res.newRelPath
                    
                    // Index anchors contained in this part
                    anchors.forEach { anchor ->
                        val pattern = Regex("""\b(id|name)\s*=\s*["']${Regex.escape(anchor)}["']""", RegexOption.IGNORE_CASE)
                        if (pattern.containsMatchIn(partContent)) {
                            anchorRegistry["${res.volumeIndex}_${res.relPath}#$anchor".lowercase()] = "$partRelPath#$volumePrefix$anchor"
                        }
                    }

                    outputEntries.add(
                        OutputEntry(
                            relPath = partRelPath,
                            contentBytes = partContent.toByteArray(Charsets.UTF_8), // Replaced later after rewriting
                            mediaType = "application/xhtml+xml",
                            isSpine = (res.contentType in listOf(ContentType.CHAPTER, ContentType.UNKNOWN, ContentType.TITLE_PAGE)),
                            isChapter = (res.contentType in listOf(ContentType.CHAPTER, ContentType.UNKNOWN)),
                            originalResource = res,
                            partIndex = partIdx,
                            rawPartContent = partContent
                        )
                    )
                }
            }

            // 7. Rewrite References inside XHTML files
            outputEntries.forEach { entry ->
                val res = entry.originalResource ?: return@forEach
                val volumePrefix = "b${res.volumeIndex}_"
                val rewritten = rewriteXhtmlContent(
                    resource = res,
                    partIndex = entry.partIndex,
                    partContent = entry.rawPartContent,
                    allMappedResources = allMappedResources,
                    anchorRegistry = anchorRegistry,
                    volumePrefix = volumePrefix
                )
                entry.contentBytes = rewritten.toByteArray(Charsets.UTF_8)
            }

            // 8. Assemble Clean, Fresh Unified Spine
            val finalSpineEntries = mutableListOf<OutputEntry>()

            // 8a. Add cover if exists
            if (canonicalCoverRes != null) {
                // Generate a canonical cover HTML wrapper if cover image is set
                val coverImgRel = getRelativePath("OEBPS/cover.xhtml", canonicalCoverRes.newRelPath)
                val coverXhtml = """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
<head>
    <title>Cover</title>
    <style type="text/css">
        body { margin: 0; padding: 0; text-align: center; background-color: #ffffff; }
        img { max-width: 100%; height: auto; max-height: 100%; display: block; margin: 0 auto; }
    </style>
</head>
<body>
    <div><img src="$coverImgRel" alt="Cover" /></div>
</body>
</html>"""
                outputEntries.add(
                    OutputEntry(
                        relPath = "OEBPS/cover.xhtml",
                        contentBytes = coverXhtml.toByteArray(Charsets.UTF_8),
                        mediaType = "application/xhtml+xml",
                        isSpine = true,
                        spineLinear = false
                    )
                )
            }

            // 8b. Add title pages
            val titlePageEntries = outputEntries.filter { it.isSpine && it.originalResource?.contentType == ContentType.TITLE_PAGE }
            finalSpineEntries.addAll(titlePageEntries)

            // 8c. Add NAV (TOC XHTML) in spine if requested
            if (generateToc) {
                outputEntries.add(
                    OutputEntry(
                        relPath = "OEBPS/nav.xhtml",
                        contentBytes = ByteArray(0), // Populated later
                        mediaType = "application/xhtml+xml",
                        isSpine = true,
                        properties = "nav"
                    )
                )
            }

            // 8d. Add chapters in sequential volume order
            val chapterEntries = outputEntries.filter { it.isChapter }.sortedWith(
                compareBy<OutputEntry> { it.originalResource?.volumeIndex ?: 0 }
                    .thenBy { it.originalResource?.relPath ?: "" }
                    .thenBy { it.partIndex }
            )
            finalSpineEntries.addAll(chapterEntries)

            // 9. Generate Fresh, Unified TOC (toc.ncx) and NAV (nav.xhtml)
            val bookUuid = "urn:uuid:${UUID.randomUUID()}"
            val escapedTitle = escapeXml(bookTitle)
            val escapedAuthor = escapeXml(bookAuthor)
            val escapedDesc = escapeXml(bookDescription)

            val ncxNavMap = StringBuilder()
            val navList = StringBuilder()
            var chapterIndex = 1

            finalSpineEntries.forEach { entry ->
                if (entry.originalResource?.contentType == ContentType.TITLE_PAGE || entry.isChapter) {
                    val res = entry.originalResource
                    var title = ""
                    if (res != null) {
                        val content = try { res.originalFile.readText(Charsets.UTF_8) } catch (e: Exception) { "" }
                        title = extractTitleFromHtml(content, res.originalFile.name)
                    }
                    if (title.isBlank()) {
                        title = "Chapter $chapterIndex"
                    }
                    val safeTitle = escapeXml(stripHtmlTags(title)).trim()
                    val hrefFromOpf = getRelativePath("OEBPS/content.opf", entry.relPath)

                    ncxNavMap.append("""
        <navPoint id="chap_$chapterIndex" playOrder="$chapterIndex">
            <navLabel>
                <text>$safeTitle</text>
            </navLabel>
            <content src="$hrefFromOpf"/>
        </navPoint>""").append("\n")

                    navList.append("                <li><a href=\"${getRelativePath("OEBPS/nav.xhtml", entry.relPath)}\">$safeTitle</a></li>\n")
                    chapterIndex++
                }
            }

            // Build NCX
            val ncxContent = """<?xml version="1.0" encoding="UTF-8"?>
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
</ncx>"""
            outputEntries.add(
                OutputEntry(
                    relPath = "OEBPS/toc.ncx",
                    contentBytes = ncxContent.toByteArray(Charsets.UTF_8),
                    mediaType = "application/x-dtbncx+xml"
                )
            )

            // Build NAV (TOC)
            val navContent = """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head>
    <title>Navigation</title>
    <meta charset="utf-8" />
</head>
<body>
    <nav epub:type="toc" id="toc">
        <h1>$escapedTitle</h1>
        <ol>
$navList        </ol>
    </nav>
</body>
</html>"""
            outputEntries.find { it.relPath == "OEBPS/nav.xhtml" }?.contentBytes = navContent.toByteArray(Charsets.UTF_8)

            // 10. Generate content.opf
            val manifestItems = StringBuilder()
            val spineItems = StringBuilder()

            // Add cover HTML & Image to manifest
            if (canonicalCoverRes != null) {
                manifestItems.append("        <item id=\"cover\" href=\"cover.xhtml\" media-type=\"application/xhtml+xml\"/>\n")
                val coverExt = canonicalCoverRes.fileExtension
                val coverMediaType = when (coverExt) {
                    "png" -> "image/png"
                    "webp" -> "image/webp"
                    "gif" -> "image/gif"
                    else -> "image/jpeg"
                }
                manifestItems.append("        <item id=\"cover-image\" href=\"${getRelativePath("OEBPS/content.opf", canonicalCoverRes.newRelPath)}\" media-type=\"$coverMediaType\" properties=\"cover-image\"/>\n")
            }

            // Add all unique non-XHTML resources to manifest
            allResources.filter { !it.isDuplicate && it.fileExtension !in listOf("xhtml", "html", "htm") }.forEachIndexed { idx, res ->
                val mediaType = when (res.fileExtension) {
                    "css" -> "text/css"
                    "ttf" -> "application/x-font-ttf"
                    "otf" -> "application/x-font-opentype"
                    "woff" -> "font/woff"
                    "woff2" -> "font/woff2"
                    "png" -> "image/png"
                    "gif" -> "image/gif"
                    "webp" -> "image/webp"
                    "svg" -> "image/svg+xml"
                    else -> "image/jpeg"
                }
                val manifestId = "res_${res.fileExtension}_$idx"
                manifestItems.append("        <item id=\"$manifestId\" href=\"${getRelativePath("OEBPS/content.opf", res.newRelPath)}\" media-type=\"$mediaType\"/>\n")
            }

            // Add all output entries to manifest & spine
            outputEntries.forEachIndexed { idx, entry ->
                val manifestId = "out_entry_$idx"
                val href = getRelativePath("OEBPS/content.opf", entry.relPath)
                val propertiesAttr = if (entry.properties != null) " properties=\"${entry.properties}\"" else ""
                manifestItems.append("        <item id=\"$manifestId\" href=\"$href\" media-type=\"${entry.mediaType}\"$propertiesAttr/>\n")
                
                if (entry.isSpine) {
                    val linearAttr = if (!entry.spineLinear) " linear=\"no\"" else ""
                    spineItems.append("        <itemref idref=\"$manifestId\"$linearAttr/>\n")
                }
            }

            val opfContent = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" unique-identifier="bookid" version="3.0">
    <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
        <dc:title>$escapedTitle</dc:title>
        <dc:creator id="creator">$escapedAuthor</dc:creator>
        <dc:description>$escapedDesc</dc:description>
        <dc:language>ru</dc:language>
        <dc:identifier id="bookid">$bookUuid</dc:identifier>
        <meta property="dcterms:modified">${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())}</meta>
        ${if (canonicalCoverRes != null) "<meta name=\"cover\" content=\"cover-image\"/>" else ""}
    </metadata>
    <manifest>
$manifestItems    </manifest>
    <spine toc="out_entry_${outputEntries.indexOfFirst { it.relPath == "OEBPS/toc.ncx" }}">
$spineItems    </spine>
</package>"""

            // 11. Write ZIP structure safely
            if (outputZipFile.exists()) {
                outputZipFile.delete()
            }
            outputZipFile.parentFile?.mkdirs()

            val zos = ZipOutputStream(BufferedOutputStream(FileOutputStream(outputZipFile)))

            // 11a. mimetype must be FIRST and STORED uncompressed
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

            // 11b. Write META-INF/container.xml
            zos.putNextEntry(ZipEntry("META-INF/container.xml"))
            val containerXml = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
    <rootfiles>
        <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
    </rootfiles>
</container>"""
            zos.write(containerXml.trimIndent().trim().toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // 11c. Write content.opf
            zos.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zos.write(opfContent.trim().toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // 11d. Write cover image
            if (canonicalCoverRes != null) {
                zos.putNextEntry(ZipEntry(canonicalCoverRes.newRelPath))
                canonicalCoverRes.originalFile.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }

            // 11e. Write all unique non-XHTML resources (CSS, fonts, normal images)
            allResources.filter { !it.isDuplicate && it.fileExtension !in listOf("xhtml", "html", "htm") }.forEach { res ->
                zos.putNextEntry(ZipEntry(res.newRelPath))
                res.originalFile.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }

            // 11f. Write generated XHTML resources and split parts
            outputEntries.forEach { entry ->
                zos.putNextEntry(ZipEntry(entry.relPath))
                zos.write(entry.contentBytes)
                zos.closeEntry()
            }

            zos.flush()
            zos.close()

            Log.d(TAG, "Successfully generated valid EPUB in: ${outputZipFile.absolutePath}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error performing volume merge/export", e)
            return false
        }
    }

    private fun normalizePath(path: String): String {
        val parts = path.replace('\\', '/').split("/")
        val resolved = mutableListOf<String>()
        for (part in parts) {
            if (part == "." || part.isEmpty()) continue
            if (part == "..") {
                if (resolved.isNotEmpty()) resolved.removeAt(resolved.size - 1)
            } else {
                resolved.add(part)
            }
        }
        return resolved.joinToString("/")
    }

    private fun classifyResource(relPath: String, contentText: String): ContentType {
        val lowerPath = relPath.lowercase()
        val filename = lowerPath.substringAfterLast("/")

        if (lowerPath.contains("nav.xhtml") || lowerPath.contains("nav.html")) return ContentType.NAV
        if (lowerPath.contains("toc.ncx")) return ContentType.TOC
        if (filename.contains("toc") || filename.contains("contents") || filename.contains("table_of_contents") || filename.contains("navigation")) {
            return ContentType.TOC
        }
        if (filename.contains("cover")) return ContentType.COVER
        if (filename.contains("titlepage") || filename.contains("title_page") || filename == "title.xhtml" || filename == "title.html") {
            return ContentType.TITLE_PAGE
        }
        if (filename.contains("copyright") || filename.contains("license") || filename.contains("about") || filename.contains("legal")) {
            return ContentType.COPYRIGHT
        }
        if (filename.contains("index")) return ContentType.INDEX
        if (filename.contains("footnote") || filename.contains("footnotes") || filename.contains("notes")) return ContentType.FOOTNOTES

        if (contentText.contains("<nav epub:type=\"toc\"", ignoreCase = true) || contentText.contains("epub:type=\"toc\"", ignoreCase = true)) {
            return ContentType.NAV
        }
        if (contentText.contains("<nav ", ignoreCase = true) && contentText.contains("class=\"toc\"", ignoreCase = true)) {
            return ContentType.NAV
        }

        if (lowerPath.endsWith(".xhtml") || lowerPath.endsWith(".html") || lowerPath.endsWith(".htm")) {
            return ContentType.CHAPTER
        }

        return ContentType.UNKNOWN
    }

    private fun computeStringHash(text: String): String {
        if (text.isEmpty()) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = text.toByteArray(Charsets.UTF_8)
        digest.update(bytes)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun computeFileHash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead = input.read(buffer)
            while (bytesRead != -1) {
                digest.update(buffer, 0, bytesRead)
                bytesRead = input.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun resolveRelativePath(baseRelPath: String, targetHref: String): String {
        if (targetHref.contains("://") || targetHref.startsWith("mailto:") || targetHref.startsWith("data:")) {
            return targetHref
        }
        val baseDir = if (baseRelPath.contains("/")) baseRelPath.substringBeforeLast("/") else ""
        val cleanTarget = targetHref.substringBefore("#")
        val anchor = if (targetHref.contains("#")) "#" + targetHref.substringAfter("#") else ""

        if (cleanTarget.isEmpty()) {
            return anchor
        }

        val parts = (if (baseDir.isNotEmpty()) "$baseDir/$cleanTarget" else cleanTarget).split("/")
        val resolvedParts = mutableListOf<String>()
        for (part in parts) {
            if (part == "." || part.isEmpty()) continue
            if (part == "..") {
                if (resolvedParts.isNotEmpty()) resolvedParts.removeAt(resolvedParts.size - 1)
            } else {
                resolvedParts.add(part)
            }
        }
        return resolvedParts.joinToString("/") + anchor
    }

    private fun getRelativePath(fromRelPath: String, toRelPath: String): String {
        if (toRelPath.contains("://") || toRelPath.startsWith("mailto:") || toRelPath.startsWith("data:")) {
            return toRelPath
        }
        val fromParts = fromRelPath.substringBeforeLast("/").split("/").filter { it.isNotEmpty() }
        val toParts = toRelPath.split("/").filter { it.isNotEmpty() }

        var commonIndex = 0
        while (commonIndex < fromParts.size && commonIndex < toParts.size && fromParts[commonIndex] == toParts[commonIndex]) {
            commonIndex++
        }

        val parents = fromParts.size - commonIndex
        val relative = StringBuilder()
        for (i in 0 until parents) {
            relative.append("../")
        }
        for (i in commonIndex until toParts.size) {
            relative.append(toParts[i]).append("/")
        }
        if (relative.endsWith("/")) {
            relative.setLength(relative.length - 1)
        }
        return relative.toString()
    }

    private fun splitLargeXhtml(originalContent: String, maxSize: Int): List<String> {
        if (originalContent.length <= maxSize) {
            return listOf(originalContent)
        }

        val bodyStartMatch = Regex("<body(?:\\s+[^>]*)?>", RegexOption.IGNORE_CASE).find(originalContent)
        val bodyEndMatch = Regex("</body>", RegexOption.IGNORE_CASE).find(originalContent)

        val headPart = if (bodyStartMatch != null) {
            originalContent.substring(0, bodyStartMatch.range.last + 1)
        } else {
            """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>Chapter</title></head>
<body>"""
        }

        val footerPart = "</body></html>"

        val bodyInner = if (bodyStartMatch != null && bodyEndMatch != null && bodyEndMatch.range.first > bodyStartMatch.range.last) {
            originalContent.substring(bodyStartMatch.range.last + 1, bodyEndMatch.range.first)
        } else {
            originalContent
        }

        val tagRegex = Regex("(<(?:p|div|h1|h2|h3|h4|h5|h6|section|blockquote)\\b[^>]*>)", RegexOption.IGNORE_CASE)
        val parts = mutableListOf<String>()
        val currentPart = StringBuilder()

        var lastIdx = 0
        tagRegex.findAll(bodyInner).forEach { match ->
            val start = match.range.first
            if (start > lastIdx) {
                currentPart.append(bodyInner.substring(lastIdx, start))
            }

            if (currentPart.length >= maxSize) {
                parts.add(headPart + currentPart.toString() + footerPart)
                currentPart.setLength(0)
            }

            currentPart.append(match.value)
            lastIdx = match.range.last + 1
        }
        if (lastIdx < bodyInner.length) {
            currentPart.append(bodyInner.substring(lastIdx))
        }
        if (currentPart.isNotEmpty()) {
            parts.add(headPart + currentPart.toString() + footerPart)
        }

        return parts
    }

    private fun rewriteXhtmlContent(
        resource: VolumeResource,
        partIndex: Int,
        partContent: String,
        allMappedResources: Map<String, VolumeResource>,
        anchorRegistry: Map<String, String>,
        volumePrefix: String
    ): String {
        var content = partContent

        // 1. Sanitizer: fix unescaped ampersands
        val entityRegex = Regex("&(?!(amp|lt|gt|quot|apos|#[0-9]+|#x[0-9a-fA-F]+);)")
        content = content.replace(entityRegex, "&amp;")

        // 2. Sanitizer: fix self-closing void tags
        val voidTags = listOf("br", "hr", "img", "image", "meta", "link")
        for (tag in voidTags) {
            val pattern = Regex("<$tag\\b([^>]*?)(?<!/)>", RegexOption.IGNORE_CASE)
            content = content.replace(pattern) { match ->
                val attrs = match.groupValues[1]
                "<$tag$attrs />"
            }
        }

        // 3. Rewrite link references
        val hrefRegex = Regex("""\b(href|src|xlink:href)\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
        content = content.replace(hrefRegex) { match ->
            val attrName = match.groupValues[1]
            val originalValue = match.groupValues[2]

            if (originalValue.startsWith("#")) {
                val anchorName = originalValue.substring(1)
                """$attrName="#$volumePrefix$anchorName""""
            } else if (originalValue.contains("://") || originalValue.startsWith("mailto:") || originalValue.startsWith("data:")) {
                match.value
            } else {
                val anchor = if (originalValue.contains("#")) "#" + originalValue.substringAfter("#") else ""
                val rawLink = originalValue.substringBefore("#")

                val origResolved = resolveRelativePath(resource.relPath, rawLink)
                val lookupKey = "${resource.volumeIndex}_$origResolved".lowercase()
                val targetRes = allMappedResources[lookupKey]

                if (targetRes != null) {
                    val canonRes = targetRes.canonicalResource ?: targetRes
                    if (anchor.isNotEmpty()) {
                        val anchorName = anchor.substring(1)
                        val registryKey = "${targetRes.volumeIndex}_${targetRes.relPath}#$anchorName".lowercase()
                        val registeredTarget = anchorRegistry[registryKey]
                        if (registeredTarget != null) {
                            val relativeHref = getRelativePath(resource.newRelPath, registeredTarget)
                            """$attrName="$relativeHref""""
                        } else {
                            val relativeHref = getRelativePath(resource.newRelPath, canonRes.newRelPath) + "#$volumePrefix$anchorName"
                            """$attrName="$relativeHref""""
                        }
                    } else {
                        val relativeHref = getRelativePath(resource.newRelPath, canonRes.newRelPath)
                        """$attrName="$relativeHref""""
                    }
                } else {
                    match.value
                }
            }
        }

        // 4. Prefix elements IDs to prevent collision
        val idRegex = Regex("""\b(id|name)\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
        content = content.replace(idRegex) { match ->
            val attrName = match.groupValues[1]
            val idValue = match.groupValues[2]
            if (idValue.startsWith("epub:") || idValue in listOf("toc", "ncx", "nav")) {
                match.value
            } else {
                """$attrName="$volumePrefix$idValue""""
            }
        }

        return content
    }

    private fun extractTitleFromHtml(html: String, filename: String): String {
        val titleMatch = Regex("<title(?:\\s+[^>]*)?>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)
        if (titleMatch != null) {
            val t = stripHtmlTags(titleMatch.groupValues[1]).trim()
            if (t.isNotEmpty() && t.length < 120) return t
        }
        val h1Match = Regex("<h1(?:\\s+[^>]*)?>(.*?)</h1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)
        if (h1Match != null) {
            val t = stripHtmlTags(h1Match.groupValues[1]).trim()
            if (t.isNotEmpty() && t.length < 120) return t
        }
        val h2Match = Regex("<h2(?:\\s+[^>]*)?>(.*?)</h2>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)
        if (h2Match != null) {
            val t = stripHtmlTags(h2Match.groupValues[1]).trim()
            if (t.isNotEmpty() && t.length < 120) return t
        }
        return filename.substringBeforeLast(".")
    }

    private fun stripHtmlTags(html: String): String {
        return html.replace(Regex("<[^>]*>"), "")
    }

    private fun escapeXml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}

class OutputEntry(
    val relPath: String,
    var contentBytes: ByteArray,
    val mediaType: String,
    val isSpine: Boolean = false,
    val spineLinear: Boolean = true,
    val properties: String? = null,
    val isChapter: Boolean = false,
    val originalResource: VolumeResource? = null,
    val partIndex: Int = 0,
    val rawPartContent: String = ""
)
