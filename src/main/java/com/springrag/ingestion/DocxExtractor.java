package com.springrag.ingestion;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Reads the text of a .docx (a ZIP of XML files) without any office library.
 * Pages follow the page breaks stored in the file: explicit breaks and the "last rendered" breaks Word saves.
 * A file written by a tool that stores neither (for example python-docx) comes out as a single page.
 */
final class DocxExtractor {

    static final String DOCUMENT_XML = "word/document.xml";
    private static final String CONTENT_TYPES = "[Content_Types].xml";
    private static final int MAX_ENTRIES = 2000;
    /** Guards against zip bombs: the XML is read through a counter that stops at this many bytes. */
    private static final long MAX_XML_BYTES = 64L * 1024 * 1024;

    private DocxExtractor() {
    }

    /** True when the bytes are a ZIP that contains both [Content_Types].xml and word/document.xml. */
    static boolean looksLikeDocx(byte[] data) {
        boolean contentTypes = false;
        boolean document = false;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;
            for (int i = 0; i < MAX_ENTRIES && (entry = zip.getNextEntry()) != null; i++) {
                contentTypes |= CONTENT_TYPES.equals(entry.getName());
                document |= DOCUMENT_XML.equals(entry.getName());
                if (contentTypes && document) {
                    return true;
                }
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
        return false;
    }

    static List<PageText> extract(byte[] data) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;
            for (int i = 0; i < MAX_ENTRIES && (entry = zip.getNextEntry()) != null; i++) {
                if (DOCUMENT_XML.equals(entry.getName())) {
                    return parse(new LimitedInputStream(zip, MAX_XML_BYTES));
                }
            }
        } catch (IOException | XMLStreamException e) {
            throw new IngestionException("The Word document could not be read (damaged or too large).");
        }
        throw new IngestionException("The Word document has no text content.");
    }

    private static List<PageText> parse(InputStream xml) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        // No DTDs and no external entities: the XML comes from an untrusted upload (XXE protection).
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        XMLStreamReader reader = factory.createXMLStreamReader(xml);
        List<String> pages = new ArrayList<>();
        StringBuilder page = new StringBuilder();
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    switch (reader.getLocalName()) {
                        case "t" -> page.append(reader.getElementText());
                        case "tab" -> page.append('\t');
                        case "cr" -> page.append('\n');
                        case "br" -> {
                            if ("page".equals(reader.getAttributeValue(null, "type"))
                                    || "page".equals(attributeByLocalName(reader, "type"))) {
                                endPage(pages, page);
                            } else {
                                page.append('\n');
                            }
                        }
                        case "lastRenderedPageBreak" -> endPage(pages, page);
                        default -> { }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    switch (reader.getLocalName()) {
                        case "p", "tr" -> page.append('\n');
                        case "tc" -> page.append('\t');
                        default -> { }
                    }
                }
            }
        } finally {
            reader.close();
        }
        endPage(pages, page);
        if (pages.isEmpty()) {
            pages.add("");
        }
        List<PageText> result = new ArrayList<>(pages.size());
        for (int i = 0; i < pages.size(); i++) {
            result.add(new PageText(i + 1, DocumentParser.clean(pages.get(i))));
        }
        return result;
    }

    private static String attributeByLocalName(XMLStreamReader reader, String localName) {
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            if (localName.equals(reader.getAttributeLocalName(i))) {
                return reader.getAttributeValue(i);
            }
        }
        return null;
    }

    /** Closes the current page unless it is empty, so back-to-back break markers do not create blank pages. */
    private static void endPage(List<String> pages, StringBuilder page) {
        if (!page.toString().isBlank()) {
            pages.add(page.toString());
        }
        page.setLength(0);
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private long remaining;

        LimitedInputStream(InputStream in, long limit) {
            super(in);
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                throw new IOException("XML part is larger than the allowed size");
            }
            int b = super.read();
            if (b >= 0) {
                remaining--;
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int off, int len) throws IOException {
            if (remaining <= 0) {
                throw new IOException("XML part is larger than the allowed size");
            }
            int n = super.read(buffer, off, (int) Math.min(len, remaining));
            if (n > 0) {
                remaining -= n;
            }
            return n;
        }
    }
}
