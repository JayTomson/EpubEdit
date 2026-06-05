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
            val binaryNodes = doc.getElementsByTagNameNS("*", "binary")
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
            val coverpageNodes = doc.getElementsByTagNameNS("*", "coverpage")
            if (coverpageNodes.length > 0) {
                val imgNodes = (coverpageNodes.item(0) as Element).getElementsByTagNameNS("*", "image")
                if (imgNodes.length > 0) {
                    val imgEl = imgNodes.item(0) as Element
                    val rawTarget = getAttributeCoalesce(imgEl, "href", "l:href", "xlink:href")?.removePrefix("#")
                    if (rawTarget != null) {
                        val bytes = extractedImagesRaw[rawTarget]
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

                        // Extract content recursively
                        val htmlContent = renderNodeToHtml(section, extractedImagesRaw)

                        val cleanContent = htmlContent.trim()
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
        for (name in names) {
            val attr = el.getAttribute(name)
            if (attr.isNotEmpty()) return attr
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
                val mediaType = if (ext == "png") "image/png" else if (ext == "gif") "image/gif" else "image/jpeg"
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
}
