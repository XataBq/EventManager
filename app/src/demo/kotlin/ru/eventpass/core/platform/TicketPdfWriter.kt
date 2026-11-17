package ru.eventpass.core.platform

import android.graphics.*
import android.graphics.pdf.PdfDocument
import java.io.OutputStream
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import ru.eventpass.core.domain.documents.DocumentSnapshot

object TicketPdfWriter {
    fun write(data: DocumentSnapshot.Ticket, output: OutputStream, check: () -> Unit = {}) {
        val pdf=PdfDocument()
        try {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface=Typeface.create("sans-serif",Typeface.NORMAL); color=Color.BLACK }
            var page: PdfDocument.Page? = null; var y=0f; var number=0
            fun newPage() { page?.let(pdf::finishPage); page=pdf.startPage(PdfDocument.PageInfo.Builder(595,842,++number).create()); y=48f }
            fun space(height: Float) { check(); if(page == null || y+height>794f) newPage() }
            fun text(value: String, size: Float) {
                paint.textSize=size
                value.split('\n').forEach { paragraph ->
                    var rest=paragraph
                    do {
                        var count=paint.breakText(rest,true,499f,null).coerceAtLeast(1).coerceAtMost(rest.length)
                        if(count<rest.length) rest.lastIndexOf(' ',count).takeIf {it>0}?.let {count=it}
                        space(size*1.5f); page!!.canvas.drawText(rest.take(count),48f,y,paint); y+=size*1.5f; rest=rest.drop(count)
                        rest=rest.trimStart()
                    } while(rest.isNotEmpty())
                }
            }
            try {
                val t=data.ticket
                text("EventPass · Билет",18f); y+=12
                text(t.eventTitle,22f); y+=12
                text(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm (VV)",Locale.forLanguageTag("ru")).withZone(ZoneId.of(t.zoneId)).format(t.startsAt),13f)
                text(t.venue,14f); y+=12; text(t.attendeeName,18f); text("Номер: ${t.displayNumber}",14f)
                // Keep QR, manual code and validity note together, including their line heights.
                space(340f)
                val pixels=TicketQrEncoder.encode(t.qrPayload,768)
                val bitmap=Bitmap.createBitmap(pixels.pixels,pixels.size,pixels.size,Bitmap.Config.ARGB_8888)
                try { page!!.canvas.drawBitmap(bitmap,null,RectF(167.5f,y,427.5f,y+260f),null) } finally { bitmap.recycle() }
                y+=280f; text("Код для ручного ввода: ${t.manualCode.value}",14f)
                text("Сохранённая копия. Актуальность билета проверяется при входе.",11f)
                page?.let(pdf::finishPage); page=null; check(); pdf.writeTo(output)
            } finally { page?.let(pdf::finishPage) }
        } finally { pdf.close() }
    }
}
