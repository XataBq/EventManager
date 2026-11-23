"""Generate synthetic ticket PDFs. Requires reportlab, Pillow and a Cyrillic TTF."""
import argparse
from io import BytesIO
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont
from reportlab.graphics import renderPDF
from reportlab.graphics.barcode import code128, qr
from reportlab.graphics.shapes import Drawing
from reportlab.lib.utils import ImageReader
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.pdfgen import canvas

parser = argparse.ArgumentParser()
parser.add_argument("--font", required=True)
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)
pdfmetrics.registerFont(TTFont("SyntheticCyrillic", args.font))


def new_document(path):
    return canvas.Canvas(str(path), pagesize=(480, 660), invariant=1)


def lines(doc, values):
    doc.setFont("SyntheticCyrillic", 14)
    for index, value in enumerate(values):
        doc.drawString(24, 620 - index * 24, value)


def qr_code(doc, value, x=160, y=100):
    widget = qr.QrCodeWidget(value)
    low_x, low_y, high_x, high_y = widget.getBounds()
    drawing = Drawing(130, 130, transform=[130/(high_x-low_x), 0, 0, 130/(high_y-low_y), 0, 0])
    drawing.add(widget)
    renderPDF.draw(drawing, doc, x, y)


russian = new_document(args.output / "01-concert-two-pages.pdf")
for seat in ("15", "16"):
    lines(russian, [
        "Электронный билет · синтетический пример",
        "Мероприятие: Северное сияние",
        "Дата: 20.12.2026  Время: 19:30",
        "Площадка: Большой концертный зал",
        "Адрес: Длинная улица, дом 120, центральный вход",
        "Сектор: B  Ряд: 7  Место: " + seat,
        "ФИО: Тестовый Гость", "Вход: 3", "Цена: 1500 RUB", "Тариф: Студенческий",
    ])
    qr_code(russian, "AIP004-DUMMY-QR-SEAT-" + seat)
    russian.showPage()
russian.save()

english = new_document(args.output / "02-theatre-code128.pdf")
lines(english, ["Event: Winter Theatre", "Venue: City Hall", "Date: 2026-12-22",
                "Start time: 20:15", "Row: 2  Seat: 9", "Ticket number: TEST-002"])
code128.Code128("AIP004-CODE128-002", barHeight=56, barWidth=1).drawOn(english, 70, 180)
english.showPage()
english.save()

raster = Image.new("RGB", (960, 1320), "white")
draw = ImageDraw.Draw(raster)
font = ImageFont.truetype(args.font, 28)
draw.text((48, 48), "Синтетическая скан-копия", font=font, fill="black")
draw.text((48, 96), "Мероприятие: Текст только в изображении", font=font, fill="black")
image_only = new_document(args.output / "03-scan-only.pdf")
image_only.drawImage(ImageReader(raster), 0, 0, 480, 660)
qr_code(image_only, "AIP004-IMAGE-ONLY")
image_only.showPage()
image_only.save()

ambiguous = new_document(args.output / "04-two-codes.pdf")
lines(ambiguous, ["Event: Ambiguous codes", "20.12.2026", "21.12.2026"])
qr_code(ambiguous, "AIP004-CHOICE-ONE", x=38)
qr_code(ambiguous, "AIP004-CHOICE-TWO", x=302)
ambiguous.showPage()
ambiguous.save()
