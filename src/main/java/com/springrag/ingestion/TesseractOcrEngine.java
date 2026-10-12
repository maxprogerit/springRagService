package com.springrag.ingestion;

import com.springrag.common.RagProperties;
import java.awt.image.BufferedImage;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;

/** OCR through the Tesseract native library (Tess4J). One engine instance per thread, since Tesseract is not thread-safe. */
public class TesseractOcrEngine implements OcrEngine {

    private final String languages;
    private final String dataPath;
    private final ThreadLocal<Tesseract> engine = ThreadLocal.withInitial(this::create);

    public TesseractOcrEngine(RagProperties.Ocr config) {
        this.languages = config.languages();
        this.dataPath = config.dataPath();
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String recognize(BufferedImage image) {
        try {
            return engine.get().doOCR(image);
        } catch (TesseractException | UnsatisfiedLinkError | NoClassDefFoundError e) {
            throw new IngestionException("OCR failed: " + shortReason(e));
        }
    }

    @Override
    public void verify() {
        try {
            engine.get().doOCR(new BufferedImage(40, 40, BufferedImage.TYPE_BYTE_GRAY));
        } catch (TesseractException | UnsatisfiedLinkError | NoClassDefFoundError e) {
            throw new IllegalStateException("OCR_ENABLED=true but Tesseract cannot be used (" + shortReason(e)
                    + "). Install Tesseract and its language data for OCR_LANGUAGES=" + languages
                    + " (set OCR_DATA_PATH if the data is not in the default location), or set OCR_ENABLED=false.", e);
        }
    }

    private Tesseract create() {
        Tesseract tesseract = new Tesseract();
        tesseract.setLanguage(languages);
        if (dataPath != null && !dataPath.isBlank()) {
            tesseract.setDatapath(dataPath);
        }
        return tesseract;
    }

    private static String shortReason(Throwable e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null ? "" : ": " + message.lines().findFirst().orElse(""));
    }
}
