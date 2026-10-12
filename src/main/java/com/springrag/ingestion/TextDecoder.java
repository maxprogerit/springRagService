package com.springrag.ingestion;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Decodes uploaded text strictly. Supported: UTF-8 (with or without BOM) and UTF-16 with a byte-order mark.
 * Anything else, or text containing control characters (a sign of binary data), is rejected rather than guessed.
 */
public final class TextDecoder {

    private TextDecoder() {
    }

    public static Optional<String> decode(byte[] data) {
        Charset charset = StandardCharsets.UTF_8;
        int offset = 0;
        if (startsWith(data, 0xEF, 0xBB, 0xBF)) {
            offset = 3;
        } else if (startsWith(data, 0xFF, 0xFE)) {
            charset = StandardCharsets.UTF_16LE;
            offset = 2;
        } else if (startsWith(data, 0xFE, 0xFF)) {
            charset = StandardCharsets.UTF_16BE;
            offset = 2;
        }
        String text;
        try {
            text = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data, offset, data.length - offset)).toString();
        } catch (CharacterCodingException e) {
            return Optional.empty();
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean allowedControl = c == '\n' || c == '\r' || c == '\t' || c == '\f';
            if ((c < 0x20 && !allowedControl) || c == 0x7f) {
                return Optional.empty();
            }
        }
        return Optional.of(text);
    }

    private static boolean startsWith(byte[] data, int... prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((data[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
