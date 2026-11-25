package com.mahatech.flutter_pax_printer_utility;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Lays out one print job the way the PAX printer's own text engine did, so that
/// receipts written for the old line-by-line API keep their shape.
///
/// The POSLink Peripheries SDK only prints whole bitmaps. This class collects text,
/// paper feeds and images into blocks with exact pixel positions; PageRenderer then
/// draws them into the bitmap that is printed. It uses no Android classes, so the
/// layout rules are unit-tested on the JVM.
///
/// The rules follow the PAX printer:
/// - A font is a fixed cell, named FONT_<width>_<height> in dots (FONT_12_24 is 12 x 24).
///   Latin-1 characters (ASCII plus accented letters and the no-break space that
///   currency formats use) take the ASCII font; all others, such as CJK, take the
///   extended-code font.
/// - wordSpace dots follow every character; lineSpace dots follow every line.
/// - Text wraps at the paper edge, character by character. "\n" ends a line; a line
///   with no text is as tall as the ASCII font.
/// - leftIndent dots start every line and image.
/// - Double width / height double the cell of ASCII and extended-code characters
///   separately.
/// - step(n) feeds n dots of blank paper; images that are wider than the space left
///   after the indent are scaled down to fit.
///
/// @param <I> the image type (android.graphics.Bitmap on the device).
final class PageLayout<I> {

    /// One character in its cell. x is from the left edge of the paper.
    static final class Glyph {
        final String text;
        final int x;
        final int width;
        final int height;
        final boolean invert;

        Glyph(String text, int x, int width, int height, boolean invert) {
            this.text = text;
            this.x = x;
            this.width = width;
            this.height = height;
            this.invert = invert;
        }
    }

    /// A part of the page, [height] dots tall, starting [top] dots from its top.
    abstract static class Block {
        final int top;
        final int height;

        Block(int top, int height) {
            this.top = top;
            this.height = height;
        }
    }

    /// A line of text. Glyphs sit on the bottom of the line (textHeight), and the
    /// line spacing follows below it.
    static final class TextLine extends Block {
        final List<Glyph> glyphs;
        final int textHeight;

        TextLine(int top, List<Glyph> glyphs, int textHeight, int lineSpace) {
            super(top, textHeight + lineSpace);
            this.glyphs = Collections.unmodifiableList(glyphs);
            this.textHeight = textHeight;
        }
    }

    /// Blank paper from step().
    static final class Feed extends Block {
        Feed(int top, int height) {
            super(top, height);
        }
    }

    /// An image drawn at [x], scaled to width x height.
    static final class Image<I> extends Block {
        final I image;
        final int x;
        final int width;

        Image(int top, I image, int x, int width, int height) {
            super(top, height);
            this.image = image;
            this.x = x;
            this.width = width;
        }
    }

    private static final Pattern FONT_NAME = Pattern.compile("FONT_(\\d+)_(\\d+)");

    /// The cell size {width, height} of a font named FONT_<width>_<height>.
    static int[] fontCell(String name) {
        if (name != null) {
            final Matcher m = FONT_NAME.matcher(name);
            if (m.matches()) {
                return new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
            }
        }
        throw new IllegalArgumentException("Unknown font " + name);
    }

    /// The fonts and spacing the PAX printer starts with after init().
    static final String DEFAULT_ASCII_FONT = "FONT_8_16";
    static final String DEFAULT_EXT_FONT = "FONT_16_16";

    private final int paperWidth;
    private final List<Block> blocks = new ArrayList<>();
    private int height;

    private int asciiWidth;
    private int asciiHeight;
    private int extWidth;
    private int extHeight;
    private int wordSpace;
    private int lineSpace;
    private int indent;
    private boolean asciiDoubleWidth;
    private boolean extDoubleWidth;
    private boolean asciiDoubleHeight;
    private boolean extDoubleHeight;
    private boolean invert;

    /// The line being written; flushed by "\n", wrapping, step, an image or finish.
    private List<Glyph> line = new ArrayList<>();
    private int lineX;
    private int lineHeight;

    PageLayout(int paperWidth) {
        if (paperWidth <= 0) {
            throw new IllegalArgumentException("Paper width must be positive, was " + paperWidth);
        }
        this.paperWidth = paperWidth;
        resetStyle();
    }

    int paperWidth() {
        return paperWidth;
    }

    /// Fonts, spacing, indent, double size and invert back to the printer's defaults.
    void resetStyle() {
        setFont(DEFAULT_ASCII_FONT, DEFAULT_EXT_FONT);
        wordSpace = 0;
        lineSpace = 0;
        indent = 0;
        asciiDoubleWidth = false;
        extDoubleWidth = false;
        asciiDoubleHeight = false;
        extDoubleHeight = false;
        invert = false;
    }

    void setFont(String asciiFont, String extFont) {
        final int[] ascii = fontCell(asciiFont);
        final int[] ext = fontCell(extFont);
        asciiWidth = ascii[0];
        asciiHeight = ascii[1];
        extWidth = ext[0];
        extHeight = ext[1];
    }

    void setSpacing(int wordSpace, int lineSpace) {
        this.wordSpace = requireRange("wordSpace", wordSpace, 0, 255);
        this.lineSpace = requireRange("lineSpace", lineSpace, 0, 255);
    }

    /// Indent for lines and images that start after this call.
    void setIndent(int indent) {
        this.indent = requireRange("indent", indent, 0, paperWidth - 1);
    }

    void setDoubleWidth(boolean ascii, boolean ext) {
        asciiDoubleWidth = ascii;
        extDoubleWidth = ext;
    }

    void setDoubleHeight(boolean ascii, boolean ext) {
        asciiDoubleHeight = ascii;
        extDoubleHeight = ext;
    }

    void setInvert(boolean invert) {
        this.invert = invert;
    }

    void addText(String text) {
        if (text == null) {
            return;
        }
        int i = 0;
        while (i < text.length()) {
            final int codePoint = text.codePointAt(i);
            final int length = Character.charCount(codePoint);
            if (codePoint == '\n') {
                endLine();
            } else if (codePoint != '\r') {
                addGlyph(text.substring(i, i + length), codePoint < 0x100);
            }
            i += length;
        }
    }

    private void addGlyph(String text, boolean ascii) {
        final int w = (ascii ? asciiWidth : extWidth) * ((ascii ? asciiDoubleWidth : extDoubleWidth) ? 2 : 1);
        final int h = (ascii ? asciiHeight : extHeight) * ((ascii ? asciiDoubleHeight : extDoubleHeight) ? 2 : 1);
        if (line.isEmpty()) {
            lineX = indent;
        } else if (lineX + w > paperWidth) {
            endLine();
            lineX = indent;
        }
        line.add(new Glyph(text, lineX, w, h, invert));
        lineHeight = Math.max(lineHeight, h);
        lineX += w + wordSpace;
    }

    /// Ends the current line; an empty line is as tall as the ASCII font.
    private void endLine() {
        final int textHeight = line.isEmpty()
                ? asciiHeight * (asciiDoubleHeight ? 2 : 1)
                : lineHeight;
        add(new TextLine(height, line, textHeight, lineSpace));
        line = new ArrayList<>();
        lineHeight = 0;
    }

    /// Ends a line that has text in it; does nothing between lines.
    private void flushLine() {
        if (!line.isEmpty()) {
            endLine();
        }
    }

    /// Feeds [dots] of blank paper.
    void step(int dots) {
        requireRange("step", dots, 0, Integer.MAX_VALUE);
        flushLine();
        if (dots > 0) {
            add(new Feed(height, dots));
        }
    }

    /// Adds an image of imageWidth x imageHeight dots at the indent, scaled down
    /// (keeping its shape) if it is wider than the paper left after the indent.
    void addImage(I image, int imageWidth, int imageHeight) {
        if (imageWidth <= 0 || imageHeight <= 0) {
            throw new IllegalArgumentException("Image has no size: " + imageWidth + "x" + imageHeight);
        }
        flushLine();
        final int room = paperWidth - indent;
        int w = imageWidth;
        int h = imageHeight;
        if (w > room) {
            h = Math.max(1, Math.round(imageHeight * (room / (float) imageWidth)));
            w = room;
        }
        add(new Image<>(height, image, indent, w, h));
    }

    private void add(Block block) {
        blocks.add(block);
        height += block.height;
    }

    /// Ends any unfinished line and returns the page's blocks, top to bottom.
    List<Block> finish() {
        flushLine();
        return Collections.unmodifiableList(new ArrayList<>(blocks));
    }

    /// Height of the page in dots, counting an unfinished line.
    int height() {
        return height + (line.isEmpty() ? 0 : lineHeight + lineSpace);
    }

    boolean isEmpty() {
        return blocks.isEmpty() && line.isEmpty();
    }

    private static int requireRange(String name, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " must be " + min + "-" + max + ", was " + value);
        }
        return value;
    }
}
