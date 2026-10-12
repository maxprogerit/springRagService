package com.springrag.ingestion;

import com.springrag.common.RagProperties;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Extracts text per page. Output is stripped of control characters (PostgreSQL text cannot hold NUL). */
@Component
public class DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(DocumentParser.class);
    /** A rendered page or an uploaded image may not exceed this many pixels (guards memory). */
    private static final long MAX_PIXELS = 40_000_000L;

    private final int maxPages;
    private final OcrEngine ocr;
    private final RagProperties.Ocr ocrConfig;

    @Autowired
    public DocumentParser(RagProperties props, OcrEngine ocr) {
        this.maxPages = props.ingestion().maxPages();
        this.ocr = ocr;
        this.ocrConfig = props.ocr();
    }

    public List<PageText> parse(String contentType, byte[] data) {
        return switch (contentType) {
            case UploadInspector.PDF -> parsePdf(data);
            case UploadInspector.DOCX -> DocxExtractor.extract(data);
            case UploadInspector.HTML -> List.of(new PageText(1, clean(HtmlExtractor.extract(decodeText(data)))));
            case UploadInspector.PNG, UploadInspector.JPEG -> parseImage(data);
            default -> parseText(data);
        };
    }

    private List<PageText> parsePdf(byte[] data) {
        try (PDDocument doc = Loader.loadPDF(data)) {
            int pageCount = doc.getNumberOfPages();
            if (pageCount > maxPages) {
                throw new IngestionException("The PDF has " + pageCount + " pages; the limit is " + maxPages + ".");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            PDFRenderer renderer = ocr.available() ? new PDFRenderer(doc) : null;
            int ocrPages = 0;
            List<PageText> pages = new ArrayList<>(pageCount);
            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = clean(stripper.getText(doc));
                // A page without a text layer is a scan: read it with OCR (limited, because OCR is slow).
                if (text.isBlank() && renderer != null && ocrPages < ocrConfig.maxPages()) {
                    text = clean(ocr.recognize(render(renderer, doc.getPage(page - 1), page - 1)));
                    ocrPages++;
                }
                pages.add(new PageText(page, text));
            }
            if (ocrPages > 0) {
                log.info("OCR was used for {} page(s)", ocrPages);
            }
            return pages;
        } catch (IOException e) {
            throw new IngestionException("The PDF could not be read (damaged or password-protected).");
        }
    }

    private BufferedImage render(PDFRenderer renderer, PDPage page, int index) throws IOException {
        float widthIn = page.getMediaBox().getWidth() / 72f;
        float heightIn = page.getMediaBox().getHeight() / 72f;
        float dpi = ocrConfig.dpi();
        double pixels = widthIn * dpi * heightIn * dpi;
        if (pixels > MAX_PIXELS) {
            dpi = (float) Math.max(72, dpi * Math.sqrt(MAX_PIXELS / pixels));
        }
        return renderer.renderImageWithDPI(index, dpi, ImageType.GRAY);
    }

    private List<PageText> parseImage(byte[] data) {
        if (!ocr.available()) {
            throw new IngestionException("Images need OCR, which is not enabled on this server.");
        }
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new IngestionException("The image could not be read.");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                if ((long) reader.getWidth(0) * reader.getHeight(0) > MAX_PIXELS) {
                    throw new IngestionException("The image is too large to process.");
                }
                BufferedImage image = reader.read(0);
                return List.of(new PageText(1, clean(ocr.recognize(image))));
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw new IngestionException("The image could not be read.");
        }
    }

    private static String decodeText(byte[] data) {
        return TextDecoder.decode(data).orElseThrow(() -> new IngestionException("The file is not valid text."));
    }

    static List<PageText> parseText(byte[] data) {
        String text = decodeText(data);
        String[] raw = text.split("\f", -1);
        int last = raw.length;
        while (last > 1 && raw[last - 1].isBlank()) {
            last--;
        }
        List<PageText> pages = new ArrayList<>(last);
        for (int i = 0; i < last; i++) {
            pages.add(new PageText(i + 1, clean(raw[i])));
        }
        return pages;
    }

    static String clean(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    continue;
                }
                sb.append('\n');
            } else if (c == '\n' || c == '\t' || (c >= 0x20 && c != 0x7f)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
