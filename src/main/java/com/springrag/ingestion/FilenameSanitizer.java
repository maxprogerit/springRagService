package com.springrag.ingestion;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * Turns a client-supplied filename into a harmless display name. The file itself is never written to disk
 * under this name; the sanitizing exists so the name is safe to store, log and show.
 */
public final class FilenameSanitizer {

    private static final int MAX_LENGTH = 200;
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");
    private static final Pattern UNSAFE = Pattern.compile("[^\\p{L}\\p{N}._ -]");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern LEADING_DOTS = Pattern.compile("^\\.+");

    private FilenameSanitizer() {
    }

    public static String sanitize(String original) {
        if (original == null) {
            return "document";
        }
        String name = original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        name = Normalizer.normalize(name, Normalizer.Form.NFKC);
        name = CONTROL.matcher(name).replaceAll("");
        name = UNSAFE.matcher(name).replaceAll("_");
        name = SPACES.matcher(name).replaceAll(" ").trim();
        name = LEADING_DOTS.matcher(name).replaceAll("");
        if (name.length() > MAX_LENGTH) {
            int dot = name.lastIndexOf('.');
            String ext = dot > 0 && name.length() - dot <= 10 ? name.substring(dot) : "";
            name = name.substring(0, MAX_LENGTH - ext.length()) + ext;
        }
        return name.isBlank() ? "document" : name;
    }
}
