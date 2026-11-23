package ru.eventpass.app.feature.pdfvault

import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import ru.eventpass.core.domain.importedticket.TicketWord

/** PDFBox preserves reading text, while this adapter retains a second stream of word bounds. */
internal class PdfWordStripper : PDFTextStripper() {
    val words = mutableListOf<TicketWord>()

    init { sortByPosition = true }

    override fun writeString(text: String, positions: MutableList<TextPosition>) {
        super.writeString(text, positions)
        var first: TextPosition? = null
        var last: TextPosition? = null
        val word = StringBuilder()
        fun flush() {
            val start = first
            val end = last
            if (word.isNotEmpty() && start != null && end != null && words.size < 8000) {
                words += TicketWord(word.toString(), start.xDirAdj / start.pageWidth,
                    (end.xDirAdj + end.widthDirAdj) / end.pageWidth, start.yDirAdj / start.pageHeight,
                    start.heightDir / start.pageHeight)
            }
            word.clear()
            first = null
            last = null
        }
        positions.forEach { glyph ->
            val previous = last
            if (glyph.unicode.isBlank()) {
                flush()
            } else {
                val gap = previous?.let { glyph.xDirAdj - (it.xDirAdj + it.widthDirAdj) } ?: 0f
                if (previous != null && (gap > maxOf(1.3f, previous.widthOfSpace * 0.52f)
                            || kotlin.math.abs(previous.yDirAdj - glyph.yDirAdj) > 3f)) flush()
                if (first == null) first = glyph
                last = glyph
                word.append(glyph.unicode)
            }
        }
        flush()
    }
}
