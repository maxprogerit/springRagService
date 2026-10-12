package com.springrag.support;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;

/** Builds real PDF, DOCX and image files for tests. */
public final class Docs {

    private Docs() {
    }

    /** A PDF with a text layer: one line of text per page (an empty string gives a blank page). */
    public static byte[] pdf(String... pageTexts) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String text : pageTexts) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(50, 700);
                    if (!text.isEmpty()) {
                        cs.showText(text);
                    }
                    cs.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** A "scanned" PDF: each page is only a picture of the text, with no text layer. */
    public static byte[] scannedPdf(String... pageTexts) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String text : pageTexts) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                BufferedImage image = pictureOfText(text, 1240, 400);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.drawImage(LosslessFactory.createFromImage(doc, image), 20, 500, 555, 180);
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    public static byte[] png(String text) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(pictureOfText(text, 1240, 400), "png", out);
            return out.toByteArray();
        }
    }

    /** Large black text on white, which Tesseract reads reliably. */
    public static BufferedImage pictureOfText(String text, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.BLACK);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 72));
        g.drawString(text, 40, height / 2 + 30);
        g.dispose();
        return image;
    }

    /** A minimal valid .docx. Each argument is one page; pages are separated by explicit page breaks. */
    public static byte[] docx(String... pages) throws IOException {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < pages.length; i++) {
            if (i > 0) {
                body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>");
            }
            for (String paragraph : pages[i].split("\n")) {
                body.append("<w:p><w:r><w:t xml:space=\"preserve\">").append(escape(paragraph)).append("</w:t></w:r></w:p>");
            }
        }
        String document = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
                + body + "</w:body></w:document>";
        return zip("[Content_Types].xml", "<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>",
                "word/document.xml", document);
    }

    public static byte[] zip(String... namesAndContents) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (int i = 0; i < namesAndContents.length; i += 2) {
                zip.putNextEntry(new ZipEntry(namesAndContents[i]));
                zip.write(namesAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.finish();
            return out.toByteArray();
        }
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
