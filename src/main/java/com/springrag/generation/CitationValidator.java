package com.springrag.generation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Checks the source numbers the model cited, such as [1] or [2, 3], against the sources it was given. */
public final class CitationValidator {

    private static final Pattern GROUP = Pattern.compile("\\[(\\d+(?:\\s*,\\s*\\d+)*)]");

    /** @param text  the answer with invalid citation numbers removed
     *  @param cited valid source numbers (1-based) in order of first appearance */
    public record Result(String text, List<Integer> cited) {
    }

    private CitationValidator() {
    }

    public static Result validate(String answer, int sourceCount) {
        Set<Integer> cited = new LinkedHashSet<>();
        Matcher m = GROUP.matcher(answer);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            List<Integer> valid = new ArrayList<>();
            for (String part : m.group(1).split("\\s*,\\s*")) {
                int n = parse(part);
                if (n >= 1 && n <= sourceCount) {
                    valid.add(n);
                    cited.add(n);
                }
            }
            String replacement = valid.isEmpty() ? "" : "[" + String.join(", ", valid.stream().map(String::valueOf).toList()) + "]";
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        // Removing a bad "[9]" can leave a doubled space or a space before punctuation.
        String text = out.toString().replaceAll("[ \\t]{2,}", " ").replaceAll(" +([.,;:!?])", "$1").strip();
        return new Result(text, List.copyOf(cited));
    }

    private static int parse(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
