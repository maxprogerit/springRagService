package com.springrag.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.springrag.common.ApiException;
import com.springrag.support.Docs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class UploadInspectorTest {

    private final UploadInspector inspector = new UploadInspector(false);
    private final UploadInspector withOcr = new UploadInspector(true);

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static void assertRejected(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE));
    }

    @Test
    void detectsPdfByContentEvenWithWrongExtension() {
        assertThat(inspector.detect("scan.txt", bytes("%PDF-1.7\n..."))).isEqualTo(UploadInspector.PDF);
        assertThat(inspector.detect("scan.pdf", bytes("%PDF-1.4"))).isEqualTo(UploadInspector.PDF);
    }

    @Test
    void rejectsPdfExtensionWithoutPdfContent() {
        assertRejected(() -> inspector.detect("fake.pdf", bytes("<html>not a pdf</html>")));
    }

    @Test
    void acceptsTextMarkdownAndHtmlByExtensionAndContent() {
        assertThat(inspector.detect("notes.txt", bytes("hello, мир"))).isEqualTo(UploadInspector.TEXT);
        assertThat(inspector.detect("README.MD", bytes("# title"))).isEqualTo(UploadInspector.MARKDOWN);
        assertThat(inspector.detect("a.markdown", bytes("x"))).isEqualTo(UploadInspector.MARKDOWN);
        assertThat(inspector.detect("page.html", bytes("<html><body>hi</body></html>"))).isEqualTo(UploadInspector.HTML);
        assertThat(inspector.detect("page.HTM", bytes("<p>hi</p>"))).isEqualTo(UploadInspector.HTML);
    }

    @Test
    void acceptsUtf16TextWithByteOrderMark() {
        byte[] le = "hello world".getBytes(StandardCharsets.UTF_16LE);
        byte[] withBom = new byte[le.length + 2];
        withBom[0] = (byte) 0xFF;
        withBom[1] = (byte) 0xFE;
        System.arraycopy(le, 0, withBom, 2, le.length);

        assertThat(inspector.detect("windows-notepad.txt", withBom)).isEqualTo(UploadInspector.TEXT);
    }

    @Test
    void rejectsUnsupportedExtensions() {
        assertRejected(() -> inspector.detect("tool.exe", bytes("text")));
        assertRejected(() -> inspector.detect("noextension", bytes("text")));
        assertRejected(() -> inspector.detect("old.doc", bytes("text")));
    }

    @Test
    void rejectsBinaryDisguisedAsText() {
        assertRejected(() -> inspector.detect("x.txt", new byte[] {'M', 'Z', 0, 3, 0}));
        assertRejected(() -> inspector.detect("x.txt", new byte[] {(byte) 0xff, (byte) 0xfe, 'a', 0, 0}));
        assertRejected(() -> inspector.detect("x.html", new byte[] {0, 1, 2, 3}));
    }

    @Test
    void allowsFormFeedAsPageSeparator() {
        assertThat(inspector.detect("pages.txt", bytes("page one\fpage two"))).isEqualTo(UploadInspector.TEXT);
    }

    @Test
    void docxIsRecognisedByItsStructure() throws IOException {
        assertThat(inspector.detect("report.docx", Docs.docx("one", "two"))).isEqualTo(UploadInspector.DOCX);
    }

    @Test
    void docxExtensionWithOtherContentIsRejected() throws IOException {
        assertRejected(() -> inspector.detect("fake.docx", bytes("just text")));
        assertRejected(() -> { try { inspector.detect("zip.docx", Docs.zip("a.txt", "x")); } catch (IOException e) { throw new IllegalStateException(e); } });
    }

    @Test
    void imagesNeedOcrAndMatchingContent() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0};

        assertRejected(() -> inspector.detect("scan.png", png));
        assertThat(withOcr.detect("scan.png", png)).isEqualTo(UploadInspector.PNG);
        assertThat(withOcr.detect("photo.jpg", jpeg)).isEqualTo(UploadInspector.JPEG);
        assertRejected(() -> withOcr.detect("fake.png", bytes("not an image")));
        assertRejected(() -> withOcr.detect("swapped.jpg", png));
    }
}
