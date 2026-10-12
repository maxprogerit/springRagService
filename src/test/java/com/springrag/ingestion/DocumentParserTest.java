package com.springrag.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.springrag.support.Docs;
import com.springrag.support.TestProps;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentParserTest {

    /** Fake OCR: returns "scan-N" for the Nth image it is given, so tests can see which pages were read by OCR. */
    static final class CountingOcr implements OcrEngine {
        final List<BufferedImage> images = new ArrayList<>();
        boolean available = true;

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public String recognize(BufferedImage image) {
            images.add(image);
            return "scan-" + images.size();
        }
    }

    private static DocumentParser parser(int maxPages) {
        CountingOcr off = new CountingOcr();
        off.available = false;
        return new DocumentParser(TestProps.custom(8, "http://x", "e", 0.0, 3, maxPages), off);
    }

    private static DocumentParser parserWith(CountingOcr ocr) {
        return new DocumentParser(TestProps.custom(8, "http://x", "e", 0.0, 3, 100), ocr);
    }

    @Test
    void pdfPagesKeepTheirNumbers() throws IOException {
        List<PageText> pages = parser(100).parse(UploadInspector.PDF, Docs.pdf("Alpha on page one", "Bravo on page two", "Charlie three"));

        assertThat(pages).extracting(PageText::pageNumber).containsExactly(1, 2, 3);
        assertThat(pages.get(0).text()).contains("Alpha");
        assertThat(pages.get(1).text()).contains("Bravo").doesNotContain("Alpha");
        assertThat(pages.get(2).text()).contains("Charlie");
    }

    @Test
    void pdfWithTooManyPagesIsRejected() throws IOException {
        byte[] data = Docs.pdf("a", "b", "c");

        assertThatThrownBy(() -> parser(2).parse(UploadInspector.PDF, data))
                .isInstanceOf(IngestionException.class).hasMessageContaining("limit");
    }

    @Test
    void damagedPdfGivesReadableError() {
        assertThatThrownBy(() -> parser(10).parse(UploadInspector.PDF, "%PDF-1.4 garbage".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IngestionException.class).hasMessageContaining("could not be read");
    }

    @Test
    void textIsSplitIntoPagesOnFormFeed() {
        List<PageText> pages = DocumentParser.parseText("one\ftwo\f\fthree\f\f".getBytes(StandardCharsets.UTF_8));

        assertThat(pages).extracting(PageText::pageNumber).containsExactly(1, 2, 3, 4);
        assertThat(pages.get(3).text()).isEqualTo("three");
    }

    @Test
    void plainTextWithoutFormFeedIsOnePage() {
        assertThat(DocumentParser.parseText("just text".getBytes(StandardCharsets.UTF_8)))
                .containsExactly(new PageText(1, "just text"));
    }

    @Test
    void utf16TextIsDecoded() {
        byte[] be = "привет мир".getBytes(StandardCharsets.UTF_16BE);
        byte[] data = new byte[be.length + 2];
        data[0] = (byte) 0xFE;
        data[1] = (byte) 0xFF;
        System.arraycopy(be, 0, data, 2, be.length);

        assertThat(DocumentParser.parseText(data).get(0).text()).isEqualTo("привет мир");
    }

    @Test
    void cleanRemovesNulAndControlCharactersAndNormalizesLineEnds() {
        assertThat(DocumentParser.clean("a\u0000b\u0007c\r\nd\re\tf")).isEqualTo("abc\nd\ne\tf");
    }

    @Test
    void bomIsRemoved() {
        assertThat(DocumentParser.parseText("﻿hello".getBytes(StandardCharsets.UTF_8)).get(0).text())
                .isEqualTo("hello");
    }

    // ----- DOCX -----

    @Test
    void docxTextAndPageBreaksBecomePages() throws IOException {
        List<PageText> pages = parser(100).parse(UploadInspector.DOCX,
                Docs.docx("First page\nsecond paragraph", "Second page <b>&</b> more", "Third"));

        assertThat(pages).extracting(PageText::pageNumber).containsExactly(1, 2, 3);
        assertThat(pages.get(0).text()).isEqualTo("First page\nsecond paragraph\n");
        assertThat(pages.get(1).text()).contains("Second page <b>&</b> more");
        assertThat(pages.get(2).text().strip()).isEqualTo("Third");
    }

    @Test
    void docxWithoutBreaksIsOnePage() throws IOException {
        assertThat(parser(100).parse(UploadInspector.DOCX, Docs.docx("only page"))).hasSize(1);
    }

    @Test
    void docxXmlExternalEntitiesAreNotResolved() throws IOException {
        String xml = "<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY secret SYSTEM \"file:///etc/hostname\">]>"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
                + "<w:p><w:r><w:t>&secret;</w:t></w:r></w:p></w:body></w:document>";
        byte[] data = Docs.zip("[Content_Types].xml", "<Types/>", "word/document.xml", xml);

        // Either the parser refuses the DTD or it yields no entity content; it must never read the file.
        try {
            String text = parser(100).parse(UploadInspector.DOCX, data).get(0).text();
            assertThat(text).doesNotContain("/").doesNotContainPattern("[a-z0-9-]{4,}");
        } catch (IngestionException expected) {
            assertThat(expected.getMessage()).contains("could not be read");
        }
    }

    @Test
    void brokenDocxGivesReadableError() throws IOException {
        byte[] data = Docs.zip("[Content_Types].xml", "<Types/>", "word/document.xml", "<w:document><unclosed>");

        assertThatThrownBy(() -> parser(100).parse(UploadInspector.DOCX, data))
                .isInstanceOf(IngestionException.class).hasMessageContaining("could not be read");
    }

    // ----- HTML -----

    @Test
    void htmlKeepsReadableTextAndDropsScriptsAndStyles() {
        String html = "<html><head><title>My Page</title><style>p{color:red}</style></head><body>"
                + "<h1>Heading</h1><p>First <b>bold</b> paragraph.</p><script>alert('x')</script>"
                + "<ul><li>one</li><li>two</li></ul><noscript>no js</noscript></body></html>";

        String text = parser(100).parse(UploadInspector.HTML, html.getBytes(StandardCharsets.UTF_8)).get(0).text();

        assertThat(text).contains("My Page", "Heading", "First bold paragraph.", "one", "two")
                .doesNotContain("alert", "color:red", "no js", "<", ">");
        assertThat(text.lines().filter(l -> !l.isBlank()).toList()).contains("Heading", "one", "two");
    }

    @Test
    void htmlEntitiesAreDecodedAndMarkupInTextStaysText() {
        String html = "<p>Tom &amp; Jerry &lt;b&gt;not bold&lt;/b&gt;</p>";

        assertThat(parser(100).parse(UploadInspector.HTML, html.getBytes(StandardCharsets.UTF_8)).get(0).text())
                .contains("Tom & Jerry <b>not bold</b>");
    }

    // ----- OCR -----

    @Test
    void scannedPdfPagesAreReadWithOcrButTextPagesAreNot() throws IOException {
        CountingOcr ocr = new CountingOcr();
        // page 1 has a text layer, page 2 is blank (as a scan would be for the text extractor)
        List<PageText> pages = parserWith(ocr).parse(UploadInspector.PDF, Docs.pdf("Real text layer", ""));

        assertThat(pages.get(0).text()).contains("Real text layer");
        assertThat(pages.get(1).text()).isEqualTo("scan-1");
        assertThat(ocr.images).hasSize(1);
    }

    @Test
    void ocrIsNotCalledWhenDisabledAndBlankPagesStayBlank() throws IOException {
        List<PageText> pages = parser(100).parse(UploadInspector.PDF, Docs.pdf("", ""));

        assertThat(pages).extracting(PageText::text).allMatch(String::isBlank);
    }

    @Test
    void imageGoesThroughOcrAsOnePage() throws IOException {
        CountingOcr ocr = new CountingOcr();

        List<PageText> pages = parserWith(ocr).parse(UploadInspector.PNG, Docs.png("Hello"));

        assertThat(pages).containsExactly(new PageText(1, "scan-1"));
        assertThat(ocr.images.get(0).getWidth()).isEqualTo(1240);
    }

    @Test
    void imagesWithoutOcrAreRefused() throws IOException {
        assertThatThrownBy(() -> parser(100).parse(UploadInspector.PNG, Docs.png("Hello")))
                .isInstanceOf(IngestionException.class).hasMessageContaining("OCR");
    }
}
