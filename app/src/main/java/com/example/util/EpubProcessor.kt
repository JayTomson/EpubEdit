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
    val characterCount: Int
)

object EpubProcessor {
    private const val TAG = "EpubProcessor"

    /**
     * Parses an EPUB file from a given content Uri or file input stream.
     * Extracts info, chapters, and cover image.
     */
    fun parseEpub(context: Context, uri: Uri): ParsedEpub? {
        val resolver = context.contentResolver
        val tempDir = File(context.cacheDir, "epub_extracted_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        var title: String? = null
        var author: String? = null
        var description: String? = null
        var coverImagePath: String? = null
        val chaptersList = mutableListOf<ParsedChapter>()

        try {
            resolver.openInputStream(uri)?.use { inputStream ->
                val zipInputStream = ZipInputStream(inputStream)
                var entry: ZipEntry? = zipInputStream.getNextEntry()

                while (entry != null) {
                    val name = entry.name
                    if (!entry.isDirectory) {
                        if (name.endsWith(".html", ignoreCase = true) || 
                            name.endsWith(".xhtml", ignoreCase = true) || 
                            name.endsWith(".htm", ignoreCase = true)) {
                            
                            // Read HTML content
                            val bytes = zipInputStream.readBytes()
                            val htmlContent = String(bytes, Charsets.UTF_8)
                            
                            // Simple extraction of title
                            val chapterTitle = extractTitleFromHtml(htmlContent, name)
                            
                            val words = WordStatsHelper.countWords(htmlContent)
                            val chars = WordStatsHelper.countCharacters(htmlContent)
                            
                            chaptersList.add(ParsedChapter(
                                title = chapterTitle,
                                contentHtml = htmlContent,
                                wordCount = words,
                                characterCount = chars
                            ))
                        } else if (name.endsWith(".jpg", ignoreCase = true) || 
                                   name.endsWith(".jpeg", ignoreCase = true) || 
                                   name.endsWith(".png", ignoreCase = true)) {
                            // Extract possible cover image (first large image or containing "cover")
                            if (coverImagePath == null || name.contains("cover", ignoreCase = true)) {
                                val imgFile = File(tempDir, "extracted_cover_${System.currentTimeMillis()}.jpg")
                                val output = FileOutputStream(imgFile)
                                output.write(zipInputStream.readBytes())
                                output.close()
                                coverImagePath = imgFile.absolutePath
                            }
                        }
                    }
                    zipInputStream.closeEntry()
                    entry = zipInputStream.getNextEntry()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing EPUB", e)
            return null
        }

        // Clean up or keep cache
        val defaultTitle = getFileNameFromUri(context, uri)?.removeSuffix(".epub") ?: "Parsed Title"
        
        return ParsedEpub(
            title = title ?: defaultTitle,
            author = author ?: "Unknown Author",
            description = description ?: "No description available",
            coverImagePath = coverImagePath,
            chapters = chaptersList.sortedWith(compareBy { it.title })
        )
    }

    private fun extractTitleFromHtml(html: String, filename: String): String {
        // Try extracting <title> content
        val titleRegex = Regex("<title>([^<]*)</title>", RegexOption.IGNORE_CASE)
        val titleMatch = titleRegex.find(html)
        if (titleMatch != null) {
            val matched = titleMatch.groupValues[1].trim()
            if (matched.isNotEmpty()) return matched
        }

        // Try extracting first h1 or h2 tag
        val h1Regex = Regex("<h[12][^>]*>([^<]*)</h[12]>", RegexOption.IGNORE_CASE)
        val h1Match = h1Regex.find(html)
        if (h1Match != null) {
            val matched = h1Match.groupValues[1].trim()
            if (matched.isNotEmpty()) return matched
        }

        // Simple fallback to pretty filename
        val baseName = File(filename).nameWithoutExtension
        return baseName.replace('_', ' ')
            .replace('-', ' ')
            .split(' ')
            .joinToString(" ") { it.replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase() else it } }
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
