package com.aistudio.epubedit.kqptxy

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DecodeTest {
    fun unescapeHtmlEntities(html: String): String {
        val regex = Regex("&[a-zA-Z0-9#]+;")
        return regex.replace(html) { matchResult ->
            val entity = matchResult.value
            val lowerEntity = entity.lowercase()
            if (lowerEntity == "&lt;" || lowerEntity == "&gt;" || lowerEntity == "&amp;" || lowerEntity == "&quot;" || lowerEntity == "&apos;" || lowerEntity == "&nbsp;") {
                entity
            } else {
                val decoded = android.text.Html.fromHtml(entity, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
                if (decoded.isNotEmpty() && decoded != " ") decoded else entity
            }
        }
    }

    @Test
    fun testUnescape() {
        val input = "<p><span style=\"font-size: 1.5em;\"><b>&Pcy;&ocy;&scy;&lcy;&iecy;&scy;&lcy;&ocy;&vcy;&icy;&iecy; &kcy;&ocy;&mcy;&acy;&ncy;&dcy;&ycy;</b></span></p>"
        println("DECODE_TEST_RESULT: " + unescapeHtmlEntities(input))
    }
}
