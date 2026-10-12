package com.springrag.ingestion;

import java.awt.image.BufferedImage;

/** Turns a picture of text into text. The real implementation uses Tesseract; tests use a fake. */
public interface OcrEngine {

    boolean available();

    /** @return the recognised text (possibly empty) */
    String recognize(BufferedImage image);

    /** Fails with a clear message if the engine cannot work (called once at startup when OCR is enabled). */
    default void verify() {
    }
}
