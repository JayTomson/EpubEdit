package com.aistudio.epubedit.kqptxy.util

import java.io.File

object EpubMultiVolumeMerger {
    fun mergeOpfManifests(sourceDirs: List<File>, title: String?, author: String?, description: String?, generateToc: Boolean = true): String {
        val allManifestItems = mutableListOf<String>()
        val allSpineItems = mutableListOf<String>()
        var firstMetadata = ""

        sourceDirs.forEachIndexed { idx, dir ->
            val volumePrefix = "volume_${idx + 1}"
            
            var opfRelPath = "OEBPS/content.opf"
            val containerFile = File(dir, "META-INF/container.xml")
            if (containerFile.exists()) {
                val match = Regex("""full-path\s*=\s*["']([^"']+)["']""").find(containerFile.readText())
                if (match != null) {
                    opfRelPath = match.groupValues[1]
                }
            }
            
            val opfFile = File(dir, opfRelPath)
            if (!opfFile.exists()) return@forEachIndexed
            val opfContent = opfFile.readText(Charsets.UTF_8)

            if (idx == 0) {
                firstMetadata = Regex(
                    "<metadata.*?</metadata>",
                    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
                ).find(opfContent)?.value ?: ""
                
                // Patch metadata if provided
                if (!title.isNullOrBlank()) {
                    val escaped = title.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    firstMetadata = if (firstMetadata.contains("<dc:title", ignoreCase = true)) {
                        firstMetadata.replace(
                            Regex("<dc:title(?:[^>]*)?>.*?</dc:title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
                            "<dc:title>$escaped</dc:title>"
                        )
                    } else {
                        firstMetadata.replace(
                            Regex("<metadata([^>]*)>", RegexOption.IGNORE_CASE),
                            "<metadata$1>\n<dc:title>$escaped</dc:title>"
                        )
                    }
                }
                
                if (!author.isNullOrBlank()) {
                    val escaped = author.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    firstMetadata = if (firstMetadata.contains("<dc:creator", ignoreCase = true)) {
                        firstMetadata.replace(
                            Regex("<dc:creator(?:[^>]*)?>.*?</dc:creator>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
                            "<dc:creator id=\"creator\">$escaped</dc:creator>"
                        )
                    } else {
                        firstMetadata.replace(
                            Regex("<metadata([^>]*)>", RegexOption.IGNORE_CASE),
                            "<metadata$1>\n<dc:creator id=\"creator\">$escaped</dc:creator>"
                        )
                    }
                }
                
                if (!description.isNullOrBlank()) {
                    val escaped = description.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    firstMetadata = if (firstMetadata.contains("<dc:description", ignoreCase = true)) {
                        firstMetadata.replace(
                            Regex("<dc:description(?:[^>]*)?>.*?</dc:description>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
                            "<dc:description>$escaped</dc:description>"
                        )
                    } else {
                        firstMetadata.replace(
                            Regex("<metadata([^>]*)>", RegexOption.IGNORE_CASE),
                            "<metadata$1>\n<dc:description>$escaped</dc:description>"
                        )
                    }
                }
            }

            // Извлекаем все <item> из <manifest>, добавляем префикс пути и уникализируем id
            val opfDir = if (opfRelPath.contains("/")) opfRelPath.substringBeforeLast("/") + "/" else ""
            val itemRegex = Regex("""<item([^>]+)/?>""", RegexOption.IGNORE_CASE)
            itemRegex.findAll(opfContent).forEach { m ->
                val attrs = m.groupValues[1]
                val idMatch = Regex("""id\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(attrs)
                val hrefMatch = Regex("""href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(attrs)
                
                if (idMatch != null && hrefMatch != null) {
                    val originalId = idMatch.groupValues[1]
                    val href = hrefMatch.groupValues[1]
                    val newId = "${volumePrefix}_$originalId"
                    val newHref = "$volumePrefix/$opfDir$href"
                    allManifestItems.add(
                        m.value.replace("id=\"$originalId\"", "id=\"$newId\"")
                               .replace("id='$originalId'", "id='$newId'")
                               .replace("href=\"$href\"", "href=\"$newHref\"")
                               .replace("href='$href'", "href='$newHref'")
                    )
                }
            }

            // Извлекаем порядок чтения из <spine>
            val spineRegex = Regex("""<itemref([^>]+)/?>""", RegexOption.IGNORE_CASE)
            spineRegex.findAll(opfContent).forEach { m ->
                val attrs = m.groupValues[1]
                val idRefMatch = Regex("""idref\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(attrs)
                if (idRefMatch != null) {
                    val originalId = idRefMatch.groupValues[1]
                    allSpineItems.add("""<itemref idref="${volumePrefix}_$originalId"/>""")
                }
            }
        }

        // Remove conflicting toc files from the manifest
        allManifestItems.removeAll { it.contains("properties=\"nav\"", ignoreCase = true) || it.contains("properties='nav'", ignoreCase = true) }
        allManifestItems.removeAll { it.contains("media-type=\"application/x-dtbncx+xml\"", ignoreCase = true) || it.contains("media-type='application/x-dtbncx+xml'", ignoreCase = true) }

        // Add the global merged navigation items
        allManifestItems.add("""<item id="merged_nav" href="merged_nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""")
        allManifestItems.add("""<item id="merged_ncx" href="merged_toc.ncx" media-type="application/x-dtbncx+xml"/>""")

        return """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="BookId">
$firstMetadata
<manifest>
${allManifestItems.joinToString("\n")}
</manifest>
${if (generateToc) "<spine toc=\"merged_ncx\">\n<itemref idref=\"merged_nav\" />" else "<spine toc=\"merged_ncx\">"}
${allSpineItems.joinToString("\n")}
</spine>
</package>"""
    }
}
