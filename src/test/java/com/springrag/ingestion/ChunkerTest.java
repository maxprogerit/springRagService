package com.springrag.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ChunkerTest {

    private static String words(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append("word").append(i).append(i % 12 == 11 ? ". " : " ");
        }
        return sb.toString().strip();
    }

    @Test
    void shortPageBecomesOneChunk() {
        List<Chunk> chunks = new Chunker(1000, 150).chunk(List.of(new PageText(1, "A short page.")));

        assertThat(chunks).containsExactly(new Chunk(1, 0, "A short page."));
    }

    @Test
    void chunksStayWithinSizeAndCoverTheWholeText() {
        String text = words(600);
        List<Chunk> chunks = new Chunker(300, 50).chunk(List.of(new PageText(1, text)));

        assertThat(chunks).hasSizeGreaterThan(5);
        assertThat(chunks).allSatisfy(c -> assertThat(c.text().length()).isLessThanOrEqualTo(300));
        assertThat(chunks.get(0).text()).startsWith("word0 ");
        assertThat(chunks.get(chunks.size() - 1).text()).endsWith("word599.");
        // Every word appears in at least one chunk: nothing is lost between windows.
        for (int i = 0; i < 600; i++) {
            String w = "word" + i;
            assertThat(chunks).anySatisfy(c -> assertThat(c.text()).containsPattern("\\b" + w + "\\b"));
        }
    }

    @Test
    void consecutiveChunksOverlap() {
        List<Chunk> chunks = new Chunker(300, 80).chunk(List.of(new PageText(1, words(300))));

        for (int i = 1; i < chunks.size(); i++) {
            String previous = chunks.get(i - 1).text();
            String firstWord = chunks.get(i).text().split(" ")[0];
            assertThat(previous).as("chunk %d should start inside the tail of chunk %d", i, i - 1).contains(firstWord);
        }
    }

    @Test
    void chunksNeverStartOrEndInTheMiddleOfAWord() {
        List<Chunk> chunks = new Chunker(200, 40).chunk(List.of(new PageText(1, words(300))));

        assertThat(chunks).allSatisfy(c -> {
            assertThat(c.text().split(" ")[0]).matches("word\\d+\\.?");
            String[] parts = c.text().split(" ");
            assertThat(parts[parts.length - 1]).matches("word\\d+\\.?");
        });
    }

    @Test
    void chunksNeverCrossPagesAndKeepPageNumbers() {
        List<Chunk> chunks = new Chunker(200, 30).chunk(List.of(
                new PageText(1, words(80)), new PageText(2, "   "), new PageText(3, words(80))));

        assertThat(chunks).extracting(Chunk::pageNumber).containsOnly(1, 3);
        assertThat(chunks.stream().filter(c -> c.pageNumber() == 1)).isNotEmpty()
                .allSatisfy(c -> assertThat(c.text()).doesNotContain("word79 word0"));
        assertThat(chunks).extracting(Chunk::chunkIndex)
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, chunks.size()).boxed().toList());
    }

    @Test
    void prefersParagraphBreaksOverMidParagraphCuts() {
        String para1 = "First paragraph sentence one. First paragraph sentence two with more words.";
        String para2 = "Second paragraph is here and it is also reasonably long to force a split somewhere.";
        List<Chunk> chunks = new Chunker(100, 10).chunk(List.of(new PageText(1, para1 + "\n\n" + para2)));

        assertThat(chunks.get(0).text()).isEqualTo(para1);
    }

    @Test
    void textWithoutWhitespaceIsCutHardAndTerminates() {
        List<Chunk> chunks = new Chunker(100, 20).chunk(List.of(new PageText(1, "x".repeat(1000))));

        assertThat(chunks).hasSizeGreaterThan(9);
        assertThat(chunks).allSatisfy(c -> assertThat(c.text().length()).isLessThanOrEqualTo(100));
    }

    @Test
    void hugeOverlapStillMakesProgress() {
        List<Chunk> chunks = new Chunker(100, 99).chunk(List.of(new PageText(1, words(200))));

        assertThat(chunks.size()).isLessThan(1000);
        assertThat(chunks.get(chunks.size() - 1).text()).endsWith("word199");
    }

    @Test
    void blankInputGivesNoChunks() {
        assertThat(new Chunker(100, 10).chunk(List.of(new PageText(1, " \n\t ")))).isEmpty();
        assertThat(new Chunker(100, 10).chunk(List.of())).isEmpty();
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new Chunker(100, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Chunker(100, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
