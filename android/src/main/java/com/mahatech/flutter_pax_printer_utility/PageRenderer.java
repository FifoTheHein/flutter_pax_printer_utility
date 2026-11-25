package com.mahatech.flutter_pax_printer_utility;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;

import java.util.List;

/// Draws a PageLayout into the bitmap the POSLink printer prints.
///
/// Text uses a bold monospace typeface fitted into each character's cell, so a
/// FONT_12_24 character fills 12 x 24 dots like the printer's own font did.
/// Antialiasing is off: the printer has only black and white dots, and grey edges
/// print as ragged dots.
final class PageRenderer {
    private PageRenderer() {}

    private static final Typeface TYPEFACE = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);

    /// Renders [blocks] (from PageLayout.finish()) onto a white bitmap of
    /// paperWidth x height dots.
    static Bitmap render(List<PageLayout.Block> blocks, int paperWidth, int height) {
        final Bitmap page = Bitmap.createBitmap(paperWidth, Math.max(1, height), Bitmap.Config.ARGB_8888);
        page.eraseColor(Color.WHITE);
        final Canvas canvas = new Canvas(page);
        final Paint text = new Paint();
        text.setTypeface(TYPEFACE);
        text.setAntiAlias(false);
        final Paint fill = new Paint();
        fill.setColor(Color.BLACK);
        final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG);

        for (PageLayout.Block block : blocks) {
            if (block instanceof PageLayout.TextLine) {
                drawLine(canvas, (PageLayout.TextLine) block, text, fill);
            } else if (block instanceof PageLayout.Image) {
                final PageLayout.Image<?> img = (PageLayout.Image<?>) block;
                canvas.drawBitmap((Bitmap) img.image, null,
                        new Rect(img.x, img.top, img.x + img.width, img.top + img.height), image);
            }
        }
        return page;
    }

    private static void drawLine(Canvas canvas, PageLayout.TextLine line, Paint text, Paint fill) {
        for (PageLayout.Glyph glyph : line.glyphs) {
            // Glyphs stand on the bottom of the line, like mixed font sizes on the printer.
            final int cellTop = line.top + line.textHeight - glyph.height;
            if (glyph.invert) {
                canvas.drawRect(glyph.x, cellTop, glyph.x + glyph.width, cellTop + glyph.height, fill);
                text.setColor(Color.WHITE);
            } else {
                text.setColor(Color.BLACK);
            }
            fitToCell(text, glyph.width, glyph.height);
            final Paint.FontMetrics metrics = text.getFontMetrics();
            final float glyphHeight = metrics.descent - metrics.ascent;
            // Centre the font's full height (ascent to descent) in the cell.
            final float baseline = cellTop + (glyph.height - glyphHeight) / 2f - metrics.ascent;
            canvas.drawText(glyph.text, glyph.x, baseline, text);
        }
    }

    /// Sizes [paint] so one monospace character is width x height dots: the height
    /// sets the text size, then the characters are squeezed or stretched to the width.
    static void fitToCell(Paint paint, int width, int height) {
        paint.setTextScaleX(1f);
        paint.setTextSize(height);
        final Paint.FontMetrics metrics = paint.getFontMetrics();
        final float fullHeight = metrics.descent - metrics.ascent;
        paint.setTextSize(height * height / fullHeight);
        final float advance = paint.measureText("M");
        paint.setTextScaleX(advance > 0 ? width / advance : 1f);
    }
}
