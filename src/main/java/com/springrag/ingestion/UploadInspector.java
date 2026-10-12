package com.springrag.ingestion;

import com.springrag.common.ApiException;
import com.springrag.common.RagProperties;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Decides the content type of an upload from its bytes, never from the client-declared type or the extension alone.
 * Supported: PDF, DOCX, HTML, UTF-8/UTF-16 text and Markdown, and (only when OCR is enabled) PNG and JPEG images.
 */
@Component
public class UploadInspector {

    public static final String PDF = "application/pdf";
    public static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    public static final String HTML = "text/html";
    public static final String TEXT = "text/plain";
    public static final String MARKDOWN = "text/markdown";
    public static final String PNG = "image/png";
    public static final String JPEG = "image/jpeg";

    private static final int PDF_HEADER_WINDOW = 1024;

    private final boolean ocrEnabled;

    @Autowired
    public UploadInspector(RagProperties props) {
        this(props.ocr().enabled());
    }

    public UploadInspector(boolean ocrEnabled) {
        this.ocrEnabled = ocrEnabled;
    }

    /** Returns the detected content type or throws 415 when the file is not an accepted document. */
    public String detect(String sanitizedFilename, byte[] data) {
        String ext = extension(sanitizedFilename);

        if (hasPdfHeader(data)) {
            return PDF;
        }
        if (ext.equals("pdf")) {
            throw ApiException.unsupportedMediaType("The file has a .pdf extension but its content is not a PDF.");
        }

        if (ext.equals("docx")) {
            if (isZip(data) && DocxExtractor.looksLikeDocx(data)) {
                return DOCX;
            }
            throw ApiException.unsupportedMediaType("The file has a .docx extension but is not a valid Word document.");
        }

        if (ext.equals("png") || ext.equals("jpg") || ext.equals("jpeg")) {
            String type = ext.equals("png") ? (isPng(data) ? PNG : null) : (isJpeg(data) ? JPEG : null);
            if (type == null) {
                throw ApiException.unsupportedMediaType("The file extension does not match its image content.");
            }
            if (!ocrEnabled) {
                throw ApiException.unsupportedMediaType("Images can only be read with OCR, which is not enabled on this server.");
            }
            return type;
        }

        String textType = switch (ext) {
            case "txt" -> TEXT;
            case "md", "markdown" -> MARKDOWN;
            case "html", "htm" -> HTML;
            default -> null;
        };
        if (textType == null) {
            throw ApiException.unsupportedMediaType("Unsupported file type. Upload a PDF, Word (.docx), HTML, .txt or .md file"
                    + (ocrEnabled ? ", or a PNG/JPEG image." : "."));
        }
        if (TextDecoder.decode(data).isEmpty()) {
            throw ApiException.unsupportedMediaType("The file is not valid text (UTF-8, or UTF-16 with a byte-order mark).");
        }
        return textType;
    }

    static boolean hasPdfHeader(byte[] data) {
        byte[] magic = "%PDF-".getBytes(StandardCharsets.US_ASCII);
        int limit = Math.min(data.length, PDF_HEADER_WINDOW) - magic.length;
        outer:
        for (int i = 0; i <= limit; i++) {
            for (int j = 0; j < magic.length; j++) {
                if (data[i + j] != magic[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static boolean isZip(byte[] d) {
        return d.length > 4 && d[0] == 'P' && d[1] == 'K' && d[2] == 3 && d[3] == 4;
    }

    private static boolean isPng(byte[] d) {
        return d.length > 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G';
    }

    private static boolean isJpeg(byte[] d) {
        return d.length > 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF;
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
