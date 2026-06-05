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
import java.util.regex.Pattern
import java.util.zip.CRC32
import java.util.zip.InflaterInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

object BookConverter {
    private const val TAG = "BookConverter"

    /**
     * Converts an FB2 book from input stream to a valid EPUB file.
     */
    fun convertFb2ToEpub(context: Context, fb2Stream: InputStream, outputFile: File): Boolean {
        try {
            val dbFactory = DocumentBuilderFactory.newInstance()
            dbFactory.isNamespaceAware = true
            val dBuilder = dbFactory.newDocumentBuilder()
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
            val extractedImages = mutableMapOf<String, ByteArray>() // id -> bytes
            val binaryNodes = doc.getElementsByTagNameNS("*", "binary")
            for (i in 0 until binaryNodes.length) {
                val binaryNode = binaryNodes.item(i) as Element
                val id = binaryNode.getAttribute("id")
                if (id.isNotEmpty()) {
                    val base64Text = binaryNode.textContent.replace(Regex("\\s+"), "")
                    try {
                        val bytes = Base64.decode(base64Text, Base64.DEFAULT)
                        extractedImages[id] = bytes
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed decoding base64 image $id", e)
                    }
                }
            }

            // 3. Find cover image
            var coverImageId: String? = null
            val coverpageNodes = doc.getElementsByTagNameNS("*", "coverpage")
            if (coverpageNodes.length > 0) {
                val imgNodes = (coverpageNodes.item(0) as Element).getElementsByTagNameNS("*", "image")
                if (imgNodes.length > 0) {
                    val imgEl = imgNodes.item(0) as Element
                    coverImageId = getAttributeCoalesce(imgEl, "href", "l:href", "xlink:href")?.removePrefix("#")
                }
            }

            // 4. Parse Sections (Chapters)
            val chapters = mutableListOf<ParsedChapter>()
            val bodyNodes = doc.getElementsByTagNameNS("*", "body")
            
            for (b in 0 until bodyNodes.length) {
                val bodyEl = bodyNodes.item(b) as Element
                val sections = bodyEl.getElementsByTagNameNS("*", "section")
                
                if (sections.length == 0) {
                    // Try parsing body paragraphs directly as one giant chapter if no sections
                    val paragraphs = bodyEl.getElementsByTagNameNS("*", "p")
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
                        val titleEls = section.getElementsByTagNameNS("*", "title")
                        if (titleEls.length > 0) {
                            val titleEl = titleEls.item(0) as Element
                            secTitle = titleEl.textContent.trim().replace(Regex("\\s+"), " ")
                            if (secTitle.isEmpty()) {
                                secTitle = "Глава ${chapters.size + 1}"
                            }
                        }

                        // Extract content paragraphs & images
                        val htmlContent = StringBuilder()
                        val childNodes = section.childNodes
                        for (c in 0 until childNodes.length) {
                            val child = childNodes.item(c)
                            if (child is Element) {
                                when (child.localName ?: child.nodeName) {
                                    "p" -> htmlContent.append("<p>${child.textContent}</p>\n")
                                    "empty-line" -> htmlContent.append("<br/>\n")
                                    "image" -> {
                                        val href = getAttributeCoalesce(child, "href", "l:href", "xlink:href")?.removePrefix("#")
                                        if (href != null && extractedImages.containsKey(href)) {
                                            htmlContent.append("<div style=\"text-align:center;\"><img src=\"$href\" style=\"max-width:100%;\" /></div>\n")
                                        }
                                    }
                                    "subtitle" -> htmlContent.append("<h3>${child.textContent}</h3>\n")
                                }
                            }
                        }

                        val cleanContent = htmlContent.toString()
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
     * Converts a PDF book to EPUB by scanning compressed content streams and extracting plain text.
     */
    fun convertPdfToEpub(context: Context, pdfStream: InputStream, outputFile: File): Boolean {
        try {
            val pdfBytes = pdfStream.readBytes()
            if (pdfBytes.size < 10) return false

            val textPages = mutableListOf<String>()
            
            // Reconstruct plain texts using FlateDecode streams scan
            val streamPattern = Pattern.compile("stream\\r?\\n", Pattern.CASE_INSENSITIVE)
            val textMatcher = streamPattern.matcher(PdfByteSequence(pdfBytes))
            
            var matchIdx = 0
            val objBodies = mutableListOf<ByteArray>()

            while (textMatcher.find(matchIdx)) {
                val streamStart = textMatcher.end()
                
                // Find endstream
                val endstreamIdx = findSubarrayIndex(pdfBytes, "endstream".toByteArray(), streamStart)
                if (endstreamIdx != -1) {
                    val streamLen = endstreamIdx - streamStart
                    if (streamLen > 0) {
                        // Extract content preceding stream, which contains details like /Filter
                        val dictStart = findBeforeIndex(pdfBytes, "<<".toByteArray(), streamStart - 6)
                        var isFlate = false
                        if (dictStart != -1) {
                            val dictBytes = pdfBytes.copyOfRange(dictStart, streamStart)
                            val dictStr = String(dictBytes, Charsets.ISO_8859_1)
                            if (dictStr.contains("/FlateDecode", ignoreCase = true)) {
                                isFlate = true
                            }
                        }

                        val compressedBytes = pdfBytes.copyOfRange(streamStart, endstreamIdx)
                        try {
                            val decompressed = if (isFlate) {
                                decompressFlate(compressedBytes)
                            } else {
                                compressedBytes
                            }
                            if (decompressed != null && decompressed.isNotEmpty()) {
                                objBodies.add(decompressed)
                            }
                        } catch (e: Exception) {
                            // Skip broken streams
                        }
                    }
                    matchIdx = endstreamIdx + 9
                } else {
                    matchIdx = streamStart
                }
            }

            // Extract plain texts from BT ... ET streams in our decompressed bodies
            val pageTextBuilder = StringBuilder()
            var textFoundCount = 0

            for (body in objBodies) {
                val bodyStr = String(body, Charsets.ISO_8859_1)
                
                // Match BT ... ET blocks
                val btPattern = Pattern.compile("BT\\s+(.+?)\\s+ET", Pattern.DOTALL)
                val m = btPattern.matcher(bodyStr)
                val contentStreamBuilder = StringBuilder()

                while (m.find()) {
                    val textBlock = m.group(1)
                    // Match text operators: parenthesis strings (TEXT) Tj or (TEXT)TJ etc
                    val parenPattern = Pattern.compile("\\(([^)]*)\\)")
                    val pm = parenPattern.matcher(textBlock)
                    while (pm.find()) {
                        val rawStr = pm.group(1)
                        val decodedStr = decodePdfString(rawStr)
                        if (decodedStr.isNotBlank()) {
                            contentStreamBuilder.append(decodedStr)
                        }
                    }
                    contentStreamBuilder.append(" ")
                }

                val resultingText = contentStreamBuilder.toString().trim()
                if (resultingText.length > 40) {
                    // Accumulate text or split by page
                    // clean spacing symbols and double-spacing
                    val cleanText = resultingText
                        .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]"), "")
                        .replace(Regex("\\s+"), " ")

                    if (cleanText.isNotBlank()) {
                        pageTextBuilder.append("<p>$cleanText</p>\n\n")
                        textFoundCount += cleanText.length
                        
                        // Split into separate pages if length is sufficient to make a page
                        if (pageTextBuilder.length > 2500) {
                            textPages.add(pageTextBuilder.toString())
                            pageTextBuilder.setLength(0)
                        }
                    }
                }
            }

            if (pageTextBuilder.isNotEmpty()) {
                textPages.add(pageTextBuilder.toString())
            }

            // Handle scanned/empty PDF gracefully
            if (textPages.isEmpty() || textFoundCount < 100) {
                textPages.clear()
                textPages.add("""
                    <h2>Отсканированный PDF</h2>
                    <p>Этот PDF-файл содержит сканированные страницы или защищён от копирования текста.</p>
                    <p>Текст не может быть автоматически распознан без OCR-системы. Проект был создан для ручного наполнения.</p>
                """.trimIndent())
            }

            // Build chapters from extracted text blocks
            val chapters = mutableListOf<ParsedChapter>()
            textPages.forEachIndexed { idx, body ->
                chapters.add(
                    ParsedChapter(
                        title = "Страница ${idx + 1}",
                        contentHtml = body,
                        wordCount = countWords(body),
                        characterCount = countCharacters(body)
                    )
                )
            }

            val title = "PDF Book"
            val author = "PDF Exporter"
            val description = "Конвертировано из PDF."

            buildEpubZip(outputFile, title, author, description, null, emptyMap(), chapters)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error converting PDF to EPUB", e)
            return false
        }
    }

    // Helper to find index of a subarray
    private fun findSubarrayIndex(largeArray: ByteArray, subArray: ByteArray, start: Int): Int {
        if (subArray.isEmpty()) return -1
        for (i in start..largeArray.size - subArray.size) {
            var found = true
            for (j in subArray.indices) {
                if (largeArray[i + j] != subArray[j]) {
                    found = false
                    break
                }
            }
            if (found) return i
        }
        return -1
    }

    private fun findBeforeIndex(largeArray: ByteArray, subArray: ByteArray, start: Int): Int {
        val limit = Math.max(0, start - 1500) // check last 1.5 KB
        for (i in start downTo limit) {
            if (i + subArray.size <= largeArray.size) {
                var found = true
                for (j in subArray.indices) {
                    if (largeArray[i + j] != subArray[j]) {
                        found = false
                        break
                    }
                }
                if (found) return i
            }
        }
        return -1
    }

    private fun decompressFlate(compressed: ByteArray): ByteArray? {
        return try {
            val iis = InflaterInputStream(ByteArrayInputStream(compressed))
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(2048)
            var len: Int
            while (iis.read(buf).also { len = it } != -1) {
                bos.write(buf, 0, len)
            }
            bos.toByteArray()
        } catch (e: Exception) {
            null
        }
    }

    private fun decodePdfString(raw: String): String {
        val bytes = ByteArrayOutputStream()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                val next = raw[i + 1]
                if (next.isDigit() && i + 3 < raw.length && raw[i + 2].isDigit() && raw[i + 3].isDigit()) {
                    val oct = raw.substring(i + 1, i + 4)
                    val value = oct.toIntOrNull(8) ?: 0
                    bytes.write(value)
                    i += 4
                } else {
                    when (next) {
                        'n' -> bytes.write('\n'.code)
                        'r' -> bytes.write('\r'.code)
                        't' -> bytes.write('\t'.code)
                        'b' -> bytes.write('\b'.code)
                        else -> bytes.write(next.code)
                    }
                    i += 2
                }
            } else {
                bytes.write(c.code)
                i++
            }
        }

        val byteArray = bytes.toByteArray()
        val utf8Str = String(byteArray, Charsets.UTF_8)
        if (utf8Str.any { it.code in 0x0400..0x04FF }) {
            return utf8Str
        }

        val cp1251Str = try {
            String(byteArray, Charset.forName("windows-1251"))
        } catch (e: Exception) {
            null
        }
        if (cp1251Str != null && cp1251Str.any { it.code in 0x0400..0x04FF }) {
            return cp1251Str
        }

        return String(byteArray, Charsets.ISO_8859_1)
    }

    private fun getAttributeCoalesce(el: Element, vararg names: String): String? {
        for (name in names) {
            val attr = el.getAttribute(name)
            if (attr.isNotEmpty()) return attr
            
            // Check namespace variations
            val localAttr = el.getAttributeNS("*", name)
            if (localAttr.isNotEmpty()) return localAttr
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

        // 3. Write images
        var hasCover = false
        images.forEach { (id, bytes) ->
            zos.putNextEntry(ZipEntry("OEBPS/$id"))
            zos.write(bytes)
            zos.closeEntry()
            if (id == coverImageId) {
                hasCover = true
            }
        }

        val manifestItems = StringBuilder()
        val spineItems = StringBuilder()
        val ncxNavMap = StringBuilder()

        if (hasCover && coverImageId != null) {
            manifestItems.append("<item id=\"cover-image\" href=\"$coverImageId\" media-type=\"image/jpeg\" properties=\"cover-image\"/>\n")
        }

        // Add dynamically extracted images to manifest
        images.keys.forEach { id ->
            if (id != coverImageId) {
                val ext = id.substringAfterLast(".", "jpg").lowercase()
                val mediaType = if (ext == "png") "image/png" else "image/jpeg"
                manifestItems.append("<item id=\"img_$id\" href=\"$id\" media-type=\"$mediaType\"/>\n")
            }
        }

        // 4. XHTML Chapter documents
        chapters.forEachIndexed { i, pc ->
            val id = "chapter_$i"
            val fileHref = "chapter_$i.xhtml"
            zos.putNextEntry(ZipEntry("OEBPS/$fileHref"))

            val xhtml = """
                <?xml version="1.0" encoding="utf-8"?>
                <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
                <html xmlns="http://www.w3.org/1999/xhtml">
                <head>
                    <title>${pc.title}</title>
                    <meta http-equiv="Content-Type" content="text/html; charset=utf-8" />
                </head>
                <body>
                    <h1>${pc.title}</h1>
                    ${pc.contentHtml}
                </body>
                </html>
            """.trimIndent()

            zos.write(xhtml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            manifestItems.append("<item id=\"$id\" href=\"$fileHref\" media-type=\"application/xhtml+xml\"/>\n")
            spineItems.append("<itemref idref=\"$id\"/>\n")
            ncxNavMap.append("""
                <navPoint id="$id" playOrder="${i + 1}">
                    <navLabel>
                        <text>${pc.title}</text>
                    </navLabel>
                    <content src="$fileHref"/>
                </navPoint>
            """.trimIndent() + "\n")
        }

        // 5. content.opf
        zos.putNextEntry(ZipEntry("OEBPS/content.opf"))
        val opf = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" unique-identifier="bookid" version="2.0">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
                    <dc:title>$title</dc:title>
                    <dc:creator>$author</dc:creator>
                    <dc:description>$description</dc:description>
                    <dc:language>ru</dc:language>
                    <dc:identifier id="bookid">urn:uuid:${UUID.randomUUID()}</dc:identifier>
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
        zos.write(opf.toByteArray(Charsets.UTF_8))
        zos.closeEntry()

        // 6. toc.ncx
        zos.putNextEntry(ZipEntry("OEBPS/toc.ncx"))
        val ncx = """
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
        zos.write(ncx.toByteArray(Charsets.UTF_8))
        zos.closeEntry()

        zos.flush()
        zos.close()
    }

    private fun countWords(html: String): Int {
        val clean = html.replace(Regex("<[^>]*>"), " ").trim()
        if (clean.isEmpty()) return 0
        return clean.split(Regex("\\s+")).size
    }

    private fun countCharacters(html: String): Int {
        return html.replace(Regex("<[^>]*>"), "").trim().length
    }

    private class PdfByteSequence(val bytes: ByteArray) : CharSequence {
        override val length: Int get() = bytes.size
        override fun get(index: Int): Char = bytes[index].toInt().toChar()
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
            return PdfByteSequence(bytes.copyOfRange(startIndex, endIndex))
        }
    }
}
