package com.springrag.ingestion;

import com.springrag.common.RagProperties;
import java.awt.image.BufferedImage;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

@Configuration
public class OcrConfig {

    /** With OCR_ENABLED=false (default) nothing from Tess4J or the native library is ever loaded. */
    @Bean
    OcrEngine ocrEngine(RagProperties props) {
        if (!props.ocr().enabled()) {
            return new OcrEngine() {
                @Override
                public boolean available() {
                    return false;
                }

                @Override
                public String recognize(BufferedImage image) {
                    throw new IllegalStateException("OCR is disabled");
                }
            };
        }
        return new TesseractOcrEngine(props.ocr());
    }

    /** Refuse to start with OCR enabled but unusable, instead of failing every scanned upload later. */
    @Bean
    @Order(0)
    ApplicationRunner ocrStartupCheck(OcrEngine engine, RagProperties props) {
        return args -> {
            if (props.ocr().enabled()) {
                engine.verify();
            }
        };
    }
}
