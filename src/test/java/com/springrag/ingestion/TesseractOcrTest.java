package com.springrag.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.springrag.support.Docs;
import com.springrag.support.TestProps;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

/**
 * Runs the real Tesseract engine. It is skipped automatically on machines without Tesseract (for example a plain
 * Windows laptop); in the Docker image, or anywhere Tesseract and its language data are installed, it really reads
 * pictures of text. Point OCR_DATA_PATH at the tessdata directory if it is not found by default.
 */
class TesseractOcrTest {

    private static TesseractOcrEngine engine;
    private static DocumentParser parser;

    @BeforeAll
    static void requireTesseract() {
        String dataPath = System.getenv().getOrDefault("OCR_DATA_PATH", "");
        engine = new TesseractOcrEngine(new com.springrag.common.RagProperties.Ocr(true, "eng", dataPath, 200, 10));
        try {
            engine.verify();
        } catch (RuntimeException | LinkageError e) {
            System.err.println("TesseractOcrTest skipped, Tesseract is not available here: " + e);
            Assumptions.abort("Tesseract is not available here: " + e.getMessage());
        }
        parser = new DocumentParser(TestProps.defaults(), engine);
    }

    private static String normalized(List<PageText> pages) {
        return pages.stream().map(PageText::text).reduce("", (a, b) -> a + " " + b).toLowerCase().replaceAll("\\s+", " ");
    }

    @Test
    void aScannedPdfIsReadPageByPage() throws IOException {
        List<PageText> pages = parser.parse(UploadInspector.PDF, Docs.scannedPdf("Invoice total 1250", "Payment net thirty"));

        assertThat(pages).hasSize(2);
        assertThat(pages.get(0).text().toLowerCase()).contains("invoice").contains("1250");
        assertThat(pages.get(1).text().toLowerCase()).contains("payment").contains("thirty");
    }

    @Test
    void aMixedPdfOnlyOcrsThePagesWithoutATextLayer() throws IOException {
        // Page 1 has real text, so its text must come from the PDF text layer, not from a picture.
        List<PageText> pages = parser.parse(UploadInspector.PDF, Docs.pdf("Genuine text layer here", ""));

        assertThat(pages.get(0).text()).contains("Genuine text layer here");
    }

    @Test
    void aPngImageIsRead() throws IOException {
        List<PageText> pages = parser.parse(UploadInspector.PNG, Docs.png("Hello OCR World"));

        assertThat(normalized(pages)).contains("hello").contains("world");
    }
}
