package com.mahatech.flutter_pax_printer_utility;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class PageLayoutTest {

    private static PageLayout<String> page() {
        return new PageLayout<>(384);
    }

    private static PageLayout.TextLine line(List<PageLayout.Block> blocks, int index) {
        return (PageLayout.TextLine) blocks.get(index);
    }

    @Test
    public void fontNameGivesCellSize() {
        int[] cell = PageLayout.fontCell("FONT_12_24");
        assertEquals(12, cell[0]);
        assertEquals(24, cell[1]);
        assertThrows(IllegalArgumentException.class, () -> PageLayout.fontCell("FONT_BIG"));
    }

    @Test
    public void thirtyTwoCharactersOfTwelveDotsFillA58mmLine() {
        PageLayout<String> page = page();
        page.setFont("FONT_12_24", "FONT_16_16");
        page.addText("********************************\n");
        List<PageLayout.Block> blocks = page.finish();
        assertEquals(1, blocks.size());
        PageLayout.TextLine line = line(blocks, 0);
        assertEquals(32, line.glyphs.size());
        assertEquals(31 * 12, line.glyphs.get(31).x);
        assertEquals(24, line.textHeight);
    }

    @Test
    public void textWrapsAtThePaperEdge() {
        PageLayout<String> page = page();
        page.setFont("FONT_12_24", "FONT_16_16");
        page.addText("123456789012345678901234567890123\n");
        List<PageLayout.Block> blocks = page.finish();
        assertEquals(2, blocks.size());
        assertEquals(32, line(blocks, 0).glyphs.size());
        assertEquals("3", line(blocks, 1).glyphs.get(0).text);
        assertEquals(0, line(blocks, 1).glyphs.get(0).x);
        assertEquals(24, blocks.get(1).top);
    }

    @Test
    public void lineSpacingFollowsEveryLineIncludingEmptyOnes() {
        PageLayout<String> page = page();
        page.setFont("FONT_12_24", "FONT_16_16");
        page.setSpacing(0, 10);
        page.addText("A\n\nB\n");
        List<PageLayout.Block> blocks = page.finish();
        assertEquals(3, blocks.size());
        assertTrue(line(blocks, 1).glyphs.isEmpty());
        assertEquals(34, blocks.get(1).top);
        assertEquals(68, blocks.get(2).top);
        assertEquals(102, page.height());
    }

    @Test
    public void wordSpaceFollowsEveryCharacter() {
        PageLayout<String> page = page();
        page.setSpacing(4, 0);
        page.addText("AB");
        PageLayout.TextLine line = line(page.finish(), 0);
        assertEquals(0, line.glyphs.get(0).x);
        assertEquals(12, line.glyphs.get(1).x);
    }

    @Test
    public void textWithoutNewlineStaysOnTheSameLineUntilStep() {
        PageLayout<String> page = page();
        page.addText("AB");
        page.addText("CD");
        page.step(50);
        List<PageLayout.Block> blocks = page.finish();
        assertEquals(2, blocks.size());
        assertEquals(4, line(blocks, 0).glyphs.size());
        assertEquals(16, blocks.get(1).top);
        assertEquals(50, blocks.get(1).height);
    }

    @Test
    public void stepBetweenLinesOnlyFeeds() {
        PageLayout<String> page = page();
        page.addText("A\n");
        page.step(150);
        List<PageLayout.Block> blocks = page.finish();
        assertEquals(2, blocks.size());
        assertTrue(blocks.get(1) instanceof PageLayout.Feed);
        assertEquals(16 + 150, page.height());
    }

    @Test
    public void latin1CharactersUseTheAsciiFontAndOthersTheExtendedFont() {
        PageLayout<String> page = page();
        page.setFont("FONT_12_24", "FONT_24_24");
        // A, e-acute, a no-break space (en_ZA currency grouping), then a CJK character.
        page.addText("Aé 中");
        PageLayout.TextLine line = line(page.finish(), 0);
        assertEquals(12, line.glyphs.get(0).width);
        assertEquals(12, line.glyphs.get(1).width);
        assertEquals(12, line.glyphs.get(2).width);
        assertEquals(24, line.glyphs.get(3).width);
        assertEquals(36, line.glyphs.get(3).x);
    }

    @Test
    public void doubleWidthAndHeightApplyToAsciiAndExtendedSeparately() {
        PageLayout<String> page = page();
        page.setFont("FONT_12_24", "FONT_16_16");
        page.setDoubleWidth(true, false);
        page.setDoubleHeight(true, false);
        page.addText("A中\n");
        PageLayout.TextLine line = line(page.finish(), 0);
        assertEquals(24, line.glyphs.get(0).width);
        assertEquals(48, line.glyphs.get(0).height);
        assertEquals(16, line.glyphs.get(1).width);
        assertEquals(24, line.glyphs.get(1).x);
        assertEquals(48, line.textHeight);
    }

    @Test
    public void indentStartsEveryLineAndImage() {
        PageLayout<String> page = page();
        page.setIndent(10);
        page.addText("A\nB\n");
        page.addImage("logo", 100, 40);
        List<PageLayout.Block> blocks = page.finish();
        assertEquals(10, line(blocks, 0).glyphs.get(0).x);
        assertEquals(10, line(blocks, 1).glyphs.get(0).x);
        assertEquals(10, ((PageLayout.Image<?>) blocks.get(2)).x);
    }

    @Test
    public void wideImagesAreScaledDownToThePaper() {
        PageLayout<String> page = page();
        page.addImage("banner", 768, 200);
        PageLayout.Image<?> image = (PageLayout.Image<?>) page.finish().get(0);
        assertEquals(384, image.width);
        assertEquals(100, image.height);
    }

    @Test
    public void narrowImagesKeepTheirSize() {
        PageLayout<String> page = page();
        page.addText("x");
        page.addImage("sig", 200, 80);
        List<PageLayout.Block> blocks = page.finish();
        PageLayout.Image<?> image = (PageLayout.Image<?>) blocks.get(1);
        assertEquals(200, image.width);
        assertEquals(80, image.height);
        assertEquals(16, image.top);
    }

    @Test
    public void invertIsRecordedPerCharacter() {
        PageLayout<String> page = page();
        page.addText("A");
        page.setInvert(true);
        page.addText("B");
        PageLayout.TextLine line = line(page.finish(), 0);
        assertEquals(false, line.glyphs.get(0).invert);
        assertEquals(true, line.glyphs.get(1).invert);
    }

    @Test
    public void resetStyleRestoresDefaults() {
        PageLayout<String> page = page();
        page.setFont("FONT_24_48", "FONT_48_48");
        page.setSpacing(5, 5);
        page.setIndent(20);
        page.resetStyle();
        page.addText("AB\n");
        PageLayout.TextLine line = line(page.finish(), 0);
        assertEquals(0, line.glyphs.get(0).x);
        assertEquals(8, line.glyphs.get(1).x);
        assertEquals(16, line.height);
    }

    @Test
    public void spacingOutsideTheSdkRangeIsRejected() {
        PageLayout<String> page = page();
        assertThrows(IllegalArgumentException.class, () -> page.setSpacing(256, 0));
        assertThrows(IllegalArgumentException.class, () -> page.setSpacing(0, -1));
        page.setSpacing(255, 255);
    }
}
