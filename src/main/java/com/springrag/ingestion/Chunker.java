package com.springrag.ingestion;

import com.springrag.common.RagProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Splits pages into overlapping windows of about {@code size} characters.
 * Chunks never cross a page boundary, so a citation can always name a single page.
 * Break preference inside a window: paragraph break, then sentence end, then whitespace, then a hard cut.
 */
@Component
public class Chunker {

    private static final Pattern HORIZONTAL_SPACE = Pattern.compile("[ \\t\\u00A0]+");
    private static final Pattern SPACE_BEFORE_NEWLINE = Pattern.compile(" +\\n");
    private static final Pattern BLANK_LINES = Pattern.compile("\\n{3,}");

    private final int size;
    private final int overlap;

    @Autowired
    public Chunker(RagProperties props) {
        this(props.chunk().sizeChars(), props.chunk().overlapChars());
    }

    public Chunker(int size, int overlap) {
        if (size < 1 || overlap < 0 || overlap >= size) {
            throw new IllegalArgumentException("Need size >= 1 and 0 <= overlap < size");
        }
        this.size = size;
        this.overlap = overlap;
    }

    public List<Chunk> chunk(List<PageText> pages) {
        List<Chunk> chunks = new ArrayList<>();
        int index = 0;
        for (PageText page : pages) {
            for (String piece : split(normalize(page.text()))) {
                chunks.add(new Chunk(page.pageNumber(), index++, piece));
            }
        }
        return chunks;
    }

    private List<String> split(String text) {
        List<String> pieces = new ArrayList<>();
        int length = text.length();
        int start = 0;
        while (start < length) {
            int end = Math.min(start + size, length);
            if (end < length) {
                end = breakPoint(text, start, end);
            }
            String piece = text.substring(start, end).strip();
            if (!piece.isEmpty()) {
                pieces.add(piece);
            }
            if (end >= length) {
                break;
            }
            // Step back by the overlap, but always move forward by at least half a chunk.
            int next = Math.max(end - overlap, start + Math.max(1, (end - start) / 2));
            start = alignToWordStart(text, next, end);
        }
        return pieces;
    }

    private int breakPoint(String text, int start, int end) {
        int min = start + size / 2;
        int paragraph = text.lastIndexOf("\n\n", end - 1);
        if (paragraph >= min) {
            return paragraph + 2;
        }
        for (int i = end - 2; i >= min; i--) {
            char c = text.charAt(i);
            if ((c == '.' || c == '!' || c == '?') && Character.isWhitespace(text.charAt(i + 1))) {
                return i + 1;
            }
        }
        for (int i = end - 1; i >= min; i--) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return end;
    }

    /** Moves a start offset forward to the beginning of the next word so chunks do not start mid-word. */
    private static int alignToWordStart(String text, int pos, int limit) {
        if (pos <= 0 || Character.isWhitespace(text.charAt(pos - 1)) || Character.isWhitespace(text.charAt(pos))) {
            return pos;
        }
        int p = pos;
        while (p < limit && !Character.isWhitespace(text.charAt(p))) {
            p++;
        }
        if (p >= limit) {
            return pos;
        }
        while (p < limit && Character.isWhitespace(text.charAt(p))) {
            p++;
        }
        return p;
    }

    private static String normalize(String text) {
        String t = text.replace("\r\n", "\n").replace('\r', '\n');
        t = HORIZONTAL_SPACE.matcher(t).replaceAll(" ");
        t = SPACE_BEFORE_NEWLINE.matcher(t).replaceAll("\n");
        t = BLANK_LINES.matcher(t).replaceAll("\n\n");
        return t.strip();
    }
}
