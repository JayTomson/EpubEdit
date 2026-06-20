package com.aistudio.epubedit.kqptxy.util

import java.io.File

object EpubMultiVolumeMerger {
    fun mergeOpfManifests(sourceDirs: List<File>, title: String?, author: String?, description: String?): String {
        val allManifestItems = mutableListOf<String>()
        val allSpineItems = mutableListOf<String>()
        var firstMetadata = ""

        sourceDirs.forEachIndexed { idx, dir ->
            val volumePrefix = "volume_${idx + 1}"
            val opfFile = dir.walk().firstOrNull { it.name.endsWith(".opf") } ?: return@forEachIndexed
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
            val itemRegex = Regex("""<item\s+[^>]*id="([^"]+)"[^>]*href="([^"]+)"[^>]*/?>""", RegexOption.IGNORE_CASE)
            itemRegex.findAll(opfContent).forEach { m ->
                val originalId = m.groupValues[1]
                val href = m.groupValues[2]
                val newId = "${volumePrefix}_$originalId"
                val newHref = "$volumePrefix/$href"
                allManifestItems.add(
                    m.value.replace("id=\"$originalId\"", "id=\"$newId\"")
                           .replace("href=\"$href\"", "href=\"$newHref\"")
                )
            }

            // Извлекаем порядок чтения из <spine>
            val spineRegex = Regex("""<itemref\s+idref="([^"]+)"[^>]*/?>""", RegexOption.IGNORE_CASE)
            spineRegex.findAll(opfContent).forEach { m ->
                val originalId = m.groupValues[1]
                allSpineItems.add("""<itemref idref="${volumePrefix}_$originalId"/>""")
            }
        }

        return """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="BookId">
$firstMetadata
<manifest>
${allManifestItems.joinToString("\n")}
</manifest>
<spine>
${allSpineItems.joinToString("\n")}
</spine>
</package>"""
    }
}
