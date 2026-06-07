package com.example.util

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.example.util.ParsedChapter
import org.w3c.dom.Element
import java.io.*
import java.nio.charset.Charset
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

object BookConverter {
    private const val TAG = "BookConverter"

    /**
     * Ensures an image ID has a standard image file extension (.jpg, .png, etc.).
     * FB2 image IDs often lack extensions, which prevents standard EPUB parsing.
     */
    fun ensureImageExtension(id: String, bytes: ByteArray? = null): String {
        val lower = id.lowercase()
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".gif")) {
            return id
        }
        if (bytes != null && bytes.size > 4) {
            // Match PNG magic bytes
            if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) {
                return "$id.png"
            }
            // Match GIF magic bytes
            if (bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte()) {
                return "$id.gif"
            }
        }
        return "$id.jpg"
    }

    /**
     * Converts an FB2 book from input stream to a valid EPUB file.
     */
    fun convertFb2ToEpub(context: Context, fb2Stream: InputStream, outputFile: File): Boolean {
        try {
            val dbFactory = DocumentBuilderFactory.newInstance()
            dbFactory.isNamespaceAware = true
            val dBuilder = dbFactory.newDocumentBuilder()
            dBuilder.setEntityResolver { _, _ -> org.xml.sax.InputSource(java.io.StringReader("")) }
            val doc = dBuilder.parse(fb2Stream)
            doc.documentElement.normalize()

            // 1. Parse Metadata
            var title = "FB2 Imported Book"
            val titleNodes = doc.getElementsByTagNameNS("*", "book-title")
            if (titleNodes.length > 0) {
                title = titleNodes.item(0).textContent.trim()
            }

            var author = "Unknown Author"
            val authorNodes = doc.getElementsByTagNameNS("*", "author")
            if (authorNodes.length > 0) {
                val authorEl = authorNodes.item(0) as Element
                val firstName = authorEl.getElementsByTagNameNS("*", "first-name").item(0)?.textContent?.trim() ?: ""
                val lastName = authorEl.getElementsByTagNameNS("*", "last-name").item(0)?.textContent?.trim() ?: ""
                author = "$firstName $lastName".trim()
                if (author.isEmpty()) {
                    author = "Unknown Author"
                }
            }

            var description = "FB2 Converted EPUB Book"
            val annotationNodes = doc.getElementsByTagNameNS("*", "annotation")
            if (annotationNodes.length > 0) {
                description = annotationNodes.item(0).textContent.trim()
            }

            // 2. Extract Binaries (images)
            val extractedImagesRaw = mutableMapOf<String, ByteArray>() // id -> bytes
            var binaryNodes = doc.getElementsByTagNameNS("*", "binary")
            if (binaryNodes.length == 0) {
                binaryNodes = doc.getElementsByTagName("binary")
            }
            if (binaryNodes.length == 0) {
                binaryNodes = doc.getElementsByTagName("fb2:binary")
            }
            for (i in 0 until binaryNodes.length) {
                val binaryNode = binaryNodes.item(i) as Element
                val id = binaryNode.getAttribute("id")
                if (id.isNotEmpty()) {
                    val base64Text = binaryNode.textContent.replace(Regex("\\s+"), "")
                    try {
                        val bytes = Base64.decode(base64Text, Base64.DEFAULT)
                        extractedImagesRaw[id] = bytes
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed decoding base64 image $id", e)
                    }
                }
            }

            // Map image names to guaranteed extensions
            val extractedImages = mutableMapOf<String, ByteArray>()
            extractedImagesRaw.forEach { (id, bytes) ->
                val extId = ensureImageExtension(id, bytes)
                extractedImages[extId] = bytes
            }

            // 3. Find cover image
            var coverImageId: String? = null
            var coverpageNodes = doc.getElementsByTagNameNS("*", "coverpage")
            if (coverpageNodes.length == 0) {
                coverpageNodes = doc.getElementsByTagName("coverpage")
            }
            if (coverpageNodes.length > 0) {
                var imgNodes = (coverpageNodes.item(0) as Element).getElementsByTagNameNS("*", "image")
                if (imgNodes.length == 0) {
                    imgNodes = (coverpageNodes.item(0) as Element).getElementsByTagName("image")
                }
                if (imgNodes.length > 0) {
                    val imgEl = imgNodes.item(0) as Element
                    val rawTarget = getAttributeCoalesce(imgEl, "href", "l:href", "xlink:href")?.removePrefix("#")?.trim()
                    if (rawTarget != null) {
                        val bytes = extractedImagesRaw[rawTarget] 
                            ?: extractedImagesRaw[rawTarget.lowercase()]
                            ?: extractedImagesRaw.entries.firstOrNull { it.key.equals(rawTarget, ignoreCase = true) }?.value
                        if (bytes != null) {
                            coverImageId = ensureImageExtension(rawTarget, bytes)
                        }
                    }
                }
            }
            if (coverImageId == null && extractedImages.isNotEmpty()) {
                val foundCoverKey = extractedImages.keys.firstOrNull { it.lowercase().contains("cover") }
                    ?: extractedImages.keys.first()
                coverImageId = foundCoverKey
            }

            // 4. Parse Sections recursively (Chapters)
            val chapters = mutableListOf<ParsedChapter>()
            var bodyNodes = doc.getElementsByTagNameNS("*", "body")
            if (bodyNodes.length == 0) {
                bodyNodes = doc.getElementsByTagName("body")
            }
            
            for (b in 0 until bodyNodes.length) {
                val bodyEl = bodyNodes.item(b) as Element
                var sections = bodyEl.getElementsByTagNameNS("*", "section")
                if (sections.length == 0) {
                    sections = bodyEl.getElementsByTagName("section")
                }
                
                if (sections.length == 0) {
                    // Try parsing body paragraphs directly as one giant chapter if no sections
                    var paragraphs = bodyEl.getElementsByTagNameNS("*", "p")
                    if (paragraphs.length == 0) {
                        paragraphs = bodyEl.getElementsByTagName("p")
                    }
                    val htmlContent = StringBuilder()
                    for (p in 0 until paragraphs.length) {
                        htmlContent.append("<p>${paragraphs.item(p).textContent}</p>\n")
                    }
                    chapters.add(ParsedChapter("Книга", htmlContent.toString(), countWords(htmlContent.toString()), countCharacters(htmlContent.toString())))
                } else {
                    for (s in 0 until sections.length) {
                        val section = sections.item(s) as Element
                        
                        // Only handle direct child sections or first-level sections to avoid nested duplicates
                        if (section.parentNode != bodyEl) continue

                        // Extract section title
                        var secTitle = "Глава ${chapters.size + 1}"
                        var titleEls = section.getElementsByTagNameNS("*", "title")
                        if (titleEls.length == 0) {
                            titleEls = section.getElementsByTagName("title")
                        }
                        if (titleEls.length > 0) {
                            val titleEl = titleEls.item(0) as Element
                            secTitle = titleEl.textContent.trim().replace(Regex("\\s+"), " ")
                            if (secTitle.isEmpty()) {
                                secTitle = "Глава ${chapters.size + 1}"
                            }
                        }

                        // Extract content recursively
                        val htmlContent = renderNodeToHtml(section, extractedImagesRaw)
                        val cleanContent = htmlContent.trim()

                        // If title is just a number or very short, try to pull a more informative title from the HTML content
                        val isNumeric = secTitle.matches(Regex("\\d+"))
                        val isVeryShort = secTitle.length <= 2 && !isNumeric
                        val lowerSecTitle = secTitle.lowercase()
                        val isUninformative = isNumeric || isVeryShort ||
                            lowerSecTitle in setOf("untitled", "untitled chapter", "chapter", "glava", "глава", "часть", "номер") ||
                            lowerSecTitle.matches(Regex("(chapter|chap|ch|sec|section|part|page|vol|volume)[_\\-\\s]*\\d+"))
                        
                        if (isUninformative && cleanContent.isNotBlank()) {
                            // Find the first heading or bold/subtitle element in cleanContent
                            val headingRegex = Regex("<(h1|h2|h3|h4|h5|subtitle)(?:\\s+[^>]*)?>(.*?)</\\1>", RegexOption.IGNORE_CASE)
                            val headingMatch = headingRegex.find(cleanContent)
                            if (headingMatch != null) {
                                val extracted = headingMatch.groupValues[2].replace(Regex("<[^>]*>"), "").replace(Regex("&nbsp;"), " ").trim()
                                if (extracted.length in 2..120 && extracted.lowercase() != lowerSecTitle && extracted.isNotEmpty() && !extracted.matches(Regex("[\\s\\p{Punct}\\d]+"))) {
                                    secTitle = extracted.replace(Regex("\\s+"), " ")
                                }
                            } else {
                                // Find any <p> that has bold or title class attributes
                                val pClassRegex = Regex("<p\\s+[^>]*(?:class|id)\\s*=\\s*['\"][^'\"]*(?:title|chapter|header|heading|subject|name|caption|h_)[^'\"]*['\"][^>]*>(.*?)</p>", RegexOption.IGNORE_CASE)
                                val pClassMatch = pClassRegex.find(cleanContent)
                                if (pClassMatch != null) {
                                    val extracted = pClassMatch.groupValues[1].replace(Regex("<[^>]*>"), "").replace(Regex("&nbsp;"), " ").trim()
                                    if (extracted.length in 2..120 && extracted.lowercase() != lowerSecTitle && extracted.isNotEmpty() && !extracted.matches(Regex("[\\s\\p{Punct}\\d]+"))) {
                                        secTitle = extracted.replace(Regex("\\s+"), " ")
                                    }
                                } else {
                                    // Or find the first paragraph that might be a bold chapter title or subtitle
                                    val boldPRegex = Regex("<p(?:\\s+[^>]*)?>\\s*<(b|strong)(?:\\s+[^>]*)?>(.*?)</\\1>\\s*</p>", RegexOption.IGNORE_CASE)
                                    val boldPMatch = boldPRegex.find(cleanContent.take(1000))
                                    if (boldPMatch != null) {
                                        val extracted = boldPMatch.groupValues[2].replace(Regex("<[^>]*>"), "").replace(Regex("&nbsp;"), " ").trim()
                                        if (extracted.length in 2..120 && extracted.lowercase() != lowerSecTitle && !extracted.matches(Regex("\\d+")) && extracted.isNotEmpty() && !extracted.matches(Regex("[\\s\\p{Punct}\\d]+"))) {
                                            secTitle = extracted.replace(Regex("\\s+"), " ")
                                        }
                                    } else {
                                        // Try any short first paragraph (<60 chars) that is not uninformative/numeric
                                        val pRegex = Regex("<p(?:\\s+[^>]*)?>(.*?)</p>", RegexOption.IGNORE_CASE)
                                        val firstP = pRegex.findAll(cleanContent).take(5).firstOrNull { pm ->
                                            val text = pm.groupValues[1].replace(Regex("<[^>]*>"), "").replace(Regex("&nbsp;"), " ").trim()
                                            text.length in 3..60 && !text.matches(Regex("[\\s\\p{Punct}\\d]+")) && text.lowercase() != lowerSecTitle
                                        }
                                        if (firstP != null) {
                                            secTitle = firstP.groupValues[1].replace(Regex("<[^>]*>"), "").replace(Regex("&nbsp;"), " ").trim().replace(Regex("\\s+"), " ")
                                        }
                                    }
                                }
                            }
                        }

                        if (cleanContent.isNotBlank()) {
                            chapters.add(
                                ParsedChapter(
                                    title = secTitle,
                                    contentHtml = cleanContent,
                                    wordCount = countWords(cleanContent),
                                    characterCount = countCharacters(cleanContent)
                                )
                            )
                        }
                    }
                }
            }

            if (chapters.isEmpty()) {
                chapters.add(ParsedChapter("Начало", "<p>Текст отсутствует в FB2 файле.</p>", 4, 30))
            }

            // 5. Build EPUB structure
            buildEpubZip(outputFile, title, author, description, coverImageId, extractedImages, chapters)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error converting FB2 to EPUB", e)
            return false
        }
    }

    /**
     * Recursively renders a section's XML nodes into responsive, clean XHTML tags.
     */
    private fun renderNodeToHtml(node: org.w3c.dom.Node, extractedImagesRaw: Map<String, ByteArray>): String {
        val sb = java.lang.StringBuilder()
        val children = node.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeType == org.w3c.dom.Node.TEXT_NODE) {
                sb.append(child.textContent)
            } else if (child is Element) {
                val name = (child.localName ?: child.nodeName).lowercase()
                when (name) {
                    "title" -> {
                        // Skip rendering title element internally as we already extract it for the chapter header
                    }
                    "p" -> {
                        sb.append("<p>").append(renderNodeToHtml(child, extractedImagesRaw)).append("</p>\n")
                    }
                    "emphasis" -> {
                        sb.append("<i>").append(renderNodeToHtml(child, extractedImagesRaw)).append("</i>")
                    }
                    "strong" -> {
                        sb.append("<b>").append(renderNodeToHtml(child, extractedImagesRaw)).append("</b>")
                    }
                    "image" -> {
                        val href = getAttributeCoalesce(child, "href", "l:href", "xlink:href")?.removePrefix("#")
                        if (href != null) {
                            val rawBytes = extractedImagesRaw[href]
                                ?: extractedImagesRaw.entries.firstOrNull { it.key.equals(href, ignoreCase = true) }?.value
                            if (rawBytes != null) {
                                val finalHref = ensureImageExtension(href, rawBytes)
                                sb.append("<div style=\"text-align:center; margin: 12px 0;\"><img src=\"$finalHref\" style=\"max-width:100%;\" /></div>\n")
                            }
                        }
                    }
                    "empty-line" -> {
                        sb.append("<br/>\n")
                    }
                    "subtitle" -> {
                        sb.append("<h3>").append(renderNodeToHtml(child, extractedImagesRaw)).append("</h3>\n")
                    }
                    "cite" -> {
                        sb.append("<blockquote style=\"font-style: italic; margin: 10px 20px;\">")
                          .append(renderNodeToHtml(child, extractedImagesRaw))
                          .append("</blockquote>\n")
                    }
                    "poem" -> {
                        sb.append("<div style=\"margin: 10px 0; font-style: italic;\">")
                          .append(renderNodeToHtml(child, extractedImagesRaw))
                          .append("</div>\n")
                    }
                    "stanza" -> {
                        sb.append("<div style=\"margin: 5px 0;\">")
                          .append(renderNodeToHtml(child, extractedImagesRaw))
                          .append("</div>\n")
                    }
                    "v" -> {
                        sb.append("<p style=\"margin: 2px 0; text-indent: 0;\">")
                          .append(renderNodeToHtml(child, extractedImagesRaw))
                          .append("</p>\n")
                    }
                    "epigraph" -> {
                        sb.append("<div style=\"text-align: right; margin-left: 30%; font-style: italic; margin-bottom: 15px;\">")
                          .append(renderNodeToHtml(child, extractedImagesRaw))
                          .append("</div>\n")
                    }
                    else -> {
                        sb.append(renderNodeToHtml(child, extractedImagesRaw))
                    }
                }
            }
        }
        return sb.toString()
    }

    private fun getAttributeCoalesce(el: Element, vararg names: String): String? {
        val attributes = el.attributes
        for (i in 0 until attributes.length) {
            val attr = attributes.item(i)
            val nodeName = attr.nodeName
            val localName = attr.localName ?: nodeName.substringAfterLast(":")
            for (name in names) {
                if (nodeName == name || localName == name || localName == name.substringAfterLast(":")) {
                    return attr.nodeValue
                }
            }
        }
        return null
    }

    private fun buildEpubZip(
        outputFile: File,
        title: String,
        author: String,
        description: String,
        coverImageId: String?,
        images: Map<String, ByteArray>,
        chapters: List<ParsedChapter>
    ) {
        val zos = ZipOutputStream(BufferedOutputStream(FileOutputStream(outputFile)))

        // 1. mimetype (Uncompressed, FIRST)
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

        // 3. Write images with valid file names & extensions
        var hasCover = false
        images.forEach { (id, bytes) ->
            zos.putNextEntry(ZipEntry("OEBPS/$id"))
            zos.write(bytes)
            zos.closeEntry()
            if (id == coverImageId) {
                hasCover = true
            }
        }

        val bookUuid = "urn:uuid:${java.util.UUID.randomUUID()}"
        val escapedTitle = escapeXml(title)
        val escapedAuthor = escapeXml(author)
        val escapedDesc = escapeXml(description)

        val manifestItems = StringBuilder()
        val spineItems = StringBuilder()
        val ncxNavMap = StringBuilder()

        if (hasCover && coverImageId != null) {
            val ext = coverImageId.substringAfterLast(".", "jpg").lowercase()
            val coverMediaType = if (ext == "png") "image/png" else if (ext == "gif") "image/gif" else "image/jpeg"
            manifestItems.append("<item id=\"cover-image\" href=\"$coverImageId\" media-type=\"$coverMediaType\" properties=\"cover-image\"/>\n")
        }

        // Add dynamically extracted images to manifest
        images.keys.forEach { id ->
            if (id != coverImageId) {
                val ext = id.substringAfterLast(".", "jpg").lowercase()
                val mediaType = if (ext == "png") "image/png" else if (ext == "gif") "image/gif" else "image/jpeg"
                val safeId = "img_" + id.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                manifestItems.append("<item id=\"$safeId\" href=\"$id\" media-type=\"$mediaType\"/>\n")
            }
        }

        // 4. XHTML Chapter documents
        val epub3NavList = StringBuilder()
        chapters.forEachIndexed { i, pc ->
            val id = "chapter_$i"
            val paddedIdx = i.toString().padStart(4, '0')
            val fileHref = "chapter_$paddedIdx.xhtml"
            zos.putNextEntry(ZipEntry("OEBPS/$fileHref"))

            val containsTitleHeader = containsAnyTitleRepresentation(pc.contentHtml, pc.title)
            val headerTag = if (containsTitleHeader) "" else "<h2 class=\"chapter-header\">${escapeXml(pc.title)}</h2>\n"

            val xhtml = """
                <?xml version="1.0" encoding="utf-8"?>
                <!DOCTYPE html>
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                <head>
                    <title>${escapeXml(pc.title)}</title>
                    <meta charset="utf-8" />
                    <style type="text/css">
                        body { font-family: sans-serif; line-height: 1.6; padding: 2%; margin: 0; }
                        p { text-indent: 1.5em; margin-top: 0.2em; margin-bottom: 0.2em; text-align: justify; }
                        .chapter-header { text-align: center; font-size: 1.5em; font-weight: bold; margin-bottom: 1.5em; margin-top: 1em; }
                        img { max-width: 100%; height: auto; display: block; margin: 1em auto; }
                    </style>
                </head>
                <body>
                    $headerTag${pc.contentHtml}
                </body>
                </html>
            """.trimIndent()

            zos.write(xhtml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            var safeChapTitle = escapeXml((pc.title ?: "").replace(Regex("<[^>]*>"), "")).trim()
            if (safeChapTitle.isEmpty()) {
                safeChapTitle = "Chapter ${i + 1}"
            }

            manifestItems.append("<item id=\"$id\" href=\"$fileHref\" media-type=\"application/xhtml+xml\"/>\n")
            spineItems.append("<itemref idref=\"$id\"/>\n")
            epub3NavList.append("<li><a href=\"$fileHref\">$safeChapTitle</a></li>\n")
            ncxNavMap.append("""
                <navPoint id="$id" playOrder="${i + 1}">
                    <navLabel>
                        <text>$safeChapTitle</text>
                    </navLabel>
                    <content src="$fileHref"/>
                </navPoint>
            """.trimIndent() + "\n")
        }

        // 4b. EPUB 3 nav.xhtml Navigation document
        val navHref = "nav.xhtml"
        zos.putNextEntry(ZipEntry("OEBPS/$navHref"))
        val navXhtml = """
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
                        $epub3NavList
                    </ol>
                </nav>
            </body>
            </html>
        """.trimIndent()
        zos.write(navXhtml.toByteArray(Charsets.UTF_8))
        zos.closeEntry()

        // 5. content.opf
        zos.putNextEntry(ZipEntry("OEBPS/content.opf"))
        val opf = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" unique-identifier="bookid" version="3.0">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
                    <dc:title>$escapedTitle</dc:title>
                    <dc:creator id="creator">$escapedAuthor</dc:creator>
                    <meta refines="#creator" property="role" scheme="marc:relators">aut</meta>
                    <dc:description>$escapedDesc</dc:description>
                    <dc:language>ru</dc:language>
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
                    $spineItems
                </spine>
            </package>
        """.trimIndent()
        zos.write(opf.toByteArray(Charsets.UTF_8))
        zos.closeEntry()

        // 6. toc.ncx
        zos.putNextEntry(ZipEntry("OEBPS/toc.ncx"))
        val ncx = """
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
        zos.write(ncx.toByteArray(Charsets.UTF_8))
        zos.closeEntry()

        zos.flush()
        zos.close()
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

    private fun countWords(html: String): Int {
        val clean = html.replace(Regex("<[^>]*>"), " ").trim()
        if (clean.isEmpty()) return 0
        return clean.split(Regex("\\s+")).size
    }

    private fun countCharacters(html: String): Int {
        return html.replace(Regex("<[^>]*>"), "").trim().length
    }
}
