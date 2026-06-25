package com.aistudio.epubedit.kqptxy.util

import java.io.File
import java.util.UUID
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

object EpubMultiVolumeMerger {
    private const val TAG = "EpubMultiVolumeMerger"

    fun mergeOpfManifests(
        sourceDirs: List<File>,
        title: String?,
        author: String?,
        description: String?,
        generateToc: Boolean = true
    ): String {
        val manifestBuilder = StringBuilder()
        val spineBuilder = StringBuilder()
        val bookUuid = "urn:uuid:${UUID.randomUUID()}"

        sourceDirs.forEachIndexed { index, sourceDir ->
            val volumePrefix = "volume_${index + 1}"
            
            var opfRelPath = "OEBPS/content.opf"
            val container = File(sourceDir, "META-INF/container.xml")
            if (container.exists()) {
                val m = Regex("""full-path\s*=\s*["']([^"']+)["']""").find(container.readText())
                if (m != null) opfRelPath = m.groupValues[1]
            }
            val opfFile = File(sourceDir, opfRelPath)
            if (!opfFile.exists()) return@forEachIndexed

            val opfText = opfFile.readText(Charsets.UTF_8)
            val opfFolder = if (opfRelPath.contains("/")) opfRelPath.substringBeforeLast("/") else ""

            val itemRegex = Regex("""<item\s+([^>]+)/?>""", RegexOption.IGNORE_CASE)
            val idRegex = Regex("""id\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            val hrefRegex = Regex("""href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            val mediaTypeRegex = Regex("""media-type\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            val propertiesRegex = Regex("""properties\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

            val itemIdMap = mutableMapOf<String, String>()

            itemRegex.findAll(opfText).forEach { match ->
                val attrs = match.groupValues[1]
                val id = idRegex.find(attrs)?.groupValues?.get(1) ?: ""
                val href = hrefRegex.find(attrs)?.groupValues?.get(1) ?: ""
                val mediaType = mediaTypeRegex.find(attrs)?.groupValues?.get(1) ?: ""
                val properties = propertiesRegex.find(attrs)?.groupValues?.get(2) ?: propertiesRegex.find(attrs)?.groupValues?.get(1)

                if (id.isNotEmpty() && href.isNotEmpty()) {
                    val uniqueId = "v${index + 1}_$id"
                    itemIdMap[id] = uniqueId

                    val resolvedHref = if (opfFolder.isNotEmpty()) {
                        "$volumePrefix/$opfFolder/$href"
                    } else {
                        "$volumePrefix/$href"
                    }
                    val cleanHref = normalizePath(resolvedHref)

                    val propertiesAttr = if (properties != null) " properties=\"$properties\"" else ""
                    manifestBuilder.append("        <item id=\"$uniqueId\" href=\"$cleanHref\" media-type=\"$mediaType\"$propertiesAttr />\n")
                }
            }

            val itemrefRegex = Regex("""<itemref\s+([^>]+)/?>""", RegexOption.IGNORE_CASE)
            val idrefRegex = Regex("""idref\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            val linearRegex = Regex("""linear\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

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

    private fun escapeXml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
