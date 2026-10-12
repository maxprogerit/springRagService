package com.springrag.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FilenameSanitizerTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "report.pdf                          | report.pdf",
            "../../etc/passwd                    | passwd",
            "C:\\Users\\x\\secret.txt            | secret.txt",
            "<script>alert(1)</script>.md        | script_.md",
            "<b onload=x>.txt                    | _b onload_x_.txt",
            "..hidden                            | hidden",
            "my   spaced   file.txt              | my spaced file.txt",
            "Договор №5.pdf                      | Договор No5.pdf"
    })
    void sanitizes(String input, String expected) {
        // Normalizer NFKC turns the single character № into "No".
        assertThat(FilenameSanitizer.sanitize(input)).isEqualTo(expected);
    }

    @Test
    void nullOrEmptyBecomesDefaultName() {
        assertThat(FilenameSanitizer.sanitize(null)).isEqualTo("document");
        assertThat(FilenameSanitizer.sanitize("   ")).isEqualTo("document");
        assertThat(FilenameSanitizer.sanitize("...")).isEqualTo("document");
    }

    @Test
    void stripsControlCharacters() {
        assertThat(FilenameSanitizer.sanitize("a\u0000b\nc.txt")).isEqualTo("abc.txt");
    }

    @Test
    void longNamesKeepTheirExtension() {
        String result = FilenameSanitizer.sanitize("a".repeat(500) + ".pdf");

        assertThat(result).hasSize(200).endsWith(".pdf");
    }
}
