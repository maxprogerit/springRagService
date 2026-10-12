package com.springrag.retrieval;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Turns a natural-language question into a tsquery string for the 'simple' text search configuration:
 * distinctive words joined with OR (|), ranked later by how many of them a chunk contains.
 * <p>
 * websearch_to_tsquery would AND every word, so a question such as "What is the refund window?" would match nothing
 * (no chunk contains "what" and "is" and "the"). Stop words are dropped here because the 'simple' configuration has none.
 * Only letters and digits survive, so the result can never contain tsquery operators from user input.
 */
public final class KeywordQuery {

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]{2,}");
    private static final int MAX_TERMS = 24;

    // Short lists of very common function words; ranking by ts_rank_cd does the rest.
    private static final String[] EN = {"the", "is", "are", "was", "were", "be", "been", "am", "an", "of", "and", "or", "to",
            "in", "on", "at", "for", "by", "with", "from", "as", "it", "its", "this", "that", "these", "those", "what",
            "which", "who", "whom", "whose", "when", "where", "why", "how", "do", "does", "did", "can", "could", "should",
            "would", "will", "shall", "may", "might", "must", "has", "have", "had", "there", "their", "they", "them",
            "you", "your", "our", "we", "my", "me", "if", "about", "into", "than", "then", "so", "not", "no", "any", "all"};
    private static final String[] RU = {"и", "в", "во", "не", "что", "он", "на", "я", "с", "со", "как", "а", "то", "все",
            "она", "так", "его", "но", "да", "ты", "к", "у", "же", "вы", "за", "бы", "по", "только", "ее", "мне", "было",
            "вот", "от", "меня", "еще", "нет", "о", "из", "ему", "ли", "если", "или", "ни", "быть", "был", "для", "это",
            "какой", "какая", "какие", "каковы", "сколько", "где", "когда", "почему", "есть"};
    private static final String[] DE = {"der", "die", "das", "den", "dem", "des", "ein", "eine", "einen", "einem", "und",
            "oder", "ist", "sind", "war", "wird", "werden", "wie", "was", "wer", "wo", "wann", "warum", "wieviel", "nicht",
            "mit", "von", "zu", "im", "in", "auf", "für", "bei", "nach", "aus", "es", "sie", "ich", "wir", "man", "kann"};
    private static final String[] FR = {"le", "la", "les", "un", "une", "des", "du", "de", "et", "ou", "est", "sont",
            "était", "que", "qui", "quoi", "quel", "quelle", "quels", "comment", "où", "quand", "pourquoi", "combien",
            "pas", "dans", "sur", "pour", "avec", "par", "au", "aux", "ce", "cette", "il", "elle", "nous", "vous", "ils"};
    private static final String[] ES = {"el", "la", "los", "las", "un", "una", "unos", "unas", "de", "del", "y", "o", "es",
            "son", "era", "que", "quién", "qué", "cuál", "cómo", "dónde", "cuándo", "por", "cuánto", "no", "en", "con",
            "para", "al", "se", "su", "sus", "lo", "esta", "este", "hay"};
    private static final String[] IT = {"il", "lo", "la", "gli", "le", "un", "una", "di", "del", "della", "e", "o", "è",
            "sono", "che", "chi", "cosa", "quale", "come", "dove", "quando", "perché", "quanto", "non", "in", "con", "per",
            "su", "al", "si", "suo", "sua", "questo", "questa"};
    private static final String[] PT = {"o", "os", "as", "um", "uma", "uns", "umas", "de", "do", "da", "dos", "das", "e",
            "ou", "é", "são", "era", "que", "quem", "qual", "quais", "como", "onde", "quando", "porque", "quanto", "não",
            "em", "com", "para", "por", "ao", "se", "seu", "sua", "isto", "isso"};

    private static final Set<String> STOP_WORDS = Stream.of(EN, RU, DE, FR, ES, IT, PT)
            .flatMap(Stream::of).collect(Collectors.toUnmodifiableSet());

    private KeywordQuery() {
    }

    public static boolean isStopWord(String lowerCaseWord) {
        return STOP_WORDS.contains(lowerCaseWord);
    }

    /** Distinctive lower-case words of the question, in order, without duplicates. */
    public static List<String> terms(String question) {
        Set<String> terms = new LinkedHashSet<>();
        Matcher m = WORD.matcher(question.toLowerCase(Locale.ROOT));
        while (m.find() && terms.size() < MAX_TERMS) {
            String word = m.group();
            if (!STOP_WORDS.contains(word)) {
                terms.add(word);
            }
        }
        return new ArrayList<>(terms);
    }

    /** Returns a string for {@code to_tsquery('simple', ?)}, or null when the question has no usable words. */
    public static String build(String question) {
        List<String> terms = terms(question);
        return terms.isEmpty() ? null : String.join(" | ", terms);
    }

    /** How many of the given terms occur as whole words in the text. */
    public static int countMatches(String text, List<String> terms) {
        Set<String> words = new LinkedHashSet<>();
        Matcher m = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            words.add(m.group());
        }
        int count = 0;
        for (String term : terms) {
            if (words.contains(term)) {
                count++;
            }
        }
        return count;
    }
}
