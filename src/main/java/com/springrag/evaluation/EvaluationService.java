package com.springrag.evaluation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springrag.auth.User;
import com.springrag.auth.UserRepository;
import com.springrag.collection.CollectionRepository;
import com.springrag.collection.DocumentCollection;
import com.springrag.common.RagProperties;
import com.springrag.document.Document;
import com.springrag.document.DocumentRepository;
import com.springrag.document.DocumentStatus;
import com.springrag.generation.RelevanceGate;
import com.springrag.ingestion.FilenameSanitizer;
import com.springrag.ingestion.IngestionService;
import com.springrag.ingestion.UploadInspector;
import com.springrag.retrieval.FusedHit;
import com.springrag.retrieval.KeywordQuery;
import com.springrag.retrieval.RetrievalService;
import com.springrag.retrieval.ScoredChunk;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Measures retrieval quality on a small golden set: for each question the expected (document, page) must appear
 * in the top k results. Runs vector-only, keyword-only and hybrid (RRF) over the same chunks.
 * It also helps choosing MIN_SIMILARITY: questions the documents cannot answer show how similar the best wrong
 * match gets, next to how similar the right page is for answerable questions.
 * The corpus and questions are fictional demo data shipped in src/main/resources/eval.
 */
@Service
public class EvaluationService {

    public static final String DEMO_COLLECTION = "demo-eval";
    public static final String TEMP_USER = "eval-demo@example.invalid";
    private static final int TOP_K = 5;

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GoldenSet(List<Question> questions, List<String> unanswerable) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Question(String type, String question, String file, int page) {
    }

    /** Number of questions whose expected page was found within rank 1, 3 and 5. */
    public record Scores(int total, int hit1, int hit3, int hit5) {
        Scores add(int rank) {
            return new Scores(total + 1, hit1 + (rank == 1 ? 1 : 0), hit3 + (rank >= 1 && rank <= 3 ? 1 : 0),
                    hit5 + (rank >= 1 && rank <= 5 ? 1 : 0));
        }
    }

    public record Spread(double min, double median, double max) {
        static Spread of(List<Double> values) {
            if (values.isEmpty()) {
                return null;
            }
            List<Double> sorted = values.stream().sorted().toList();
            return new Spread(sorted.get(0), sorted.get(sorted.size() / 2), sorted.get(sorted.size() - 1));
        }
    }

    /**
     * @param correctPageSimilarity cosine similarity of the expected page, over the answerable questions
     * @param wrongBestSimilarity   highest cosine similarity found for each unanswerable question
     * @param suggestedMinSimilarity a threshold between the two groups, or null when they overlap
     */
    public record Calibration(double minSimilarity, Spread correctPageSimilarity, Spread wrongBestSimilarity,
                              int answerableThrough, int answerableTotal, int unanswerableThrough, int unanswerableTotal,
                              Double suggestedMinSimilarity) {
    }

    public record Report(int questions, int documents, int chunks, String embeddingModel, int chunkSize, int chunkOverlap,
                         Map<RetrievalService.Mode, Scores> overall,
                         Map<String, Map<RetrievalService.Mode, Scores>> byType, Calibration calibration) {

        public String toMarkdown() {
            StringBuilder sb = new StringBuilder();
            sb.append("Golden set: ").append(questions).append(" questions over ").append(documents)
                    .append(" demo documents (").append(chunks).append(" chunks, size ").append(chunkSize)
                    .append(" / overlap ").append(chunkOverlap).append("), embedding model: ").append(embeddingModel)
                    .append("\n\n");
            table(sb, "All questions", overall);
            byType.forEach((type, scores) -> table(sb, "Questions of type \"" + type + "\"", scores));
            calibration(sb);
            return sb.toString();
        }

        private void calibration(StringBuilder sb) {
            Calibration c = calibration;
            if (c == null || c.answerableTotal() == 0) {
                return;
            }
            sb.append(String.format(Locale.ROOT, "Similarity gate at MIN_SIMILARITY=%.2f%n%n", c.minSimilarity()));
            sb.append(String.format(Locale.ROOT, "- answerable questions whose right page reaches the model: %d/%d%n",
                    c.answerableThrough(), c.answerableTotal()));
            sb.append(String.format(Locale.ROOT, "- unanswerable questions that still reach the model (the model must refuse): %d/%d%n",
                    c.unanswerableThrough(), c.unanswerableTotal()));
            sb.append("- cosine similarity of the right page: ").append(spread(c.correctPageSimilarity())).append('\n');
            sb.append("- best cosine similarity for unanswerable questions: ").append(spread(c.wrongBestSimilarity())).append('\n');
            sb.append(c.suggestedMinSimilarity() != null
                    ? String.format(Locale.ROOT, "- a threshold that separates both groups on this data: %.2f%n", c.suggestedMinSimilarity())
                    : "- the two groups overlap: no similarity threshold separates them, so the language model has to refuse\n");
            sb.append('\n');
        }

        private static String spread(Spread s) {
            return s == null ? "n/a" : String.format(Locale.ROOT, "min %.3f / median %.3f / max %.3f", s.min(), s.median(), s.max());
        }

        private static void table(StringBuilder sb, String title, Map<RetrievalService.Mode, Scores> scores) {
            sb.append(title).append("\n\n| Retrieval | hit@1 | hit@3 | hit@5 |\n|---|---|---|---|\n");
            for (RetrievalService.Mode mode : new RetrievalService.Mode[] {RetrievalService.Mode.VECTOR,
                    RetrievalService.Mode.KEYWORD, RetrievalService.Mode.HYBRID}) {
                Scores s = scores.get(mode);
                sb.append("| ").append(label(mode)).append(" | ").append(cell(s.hit1(), s.total()))
                        .append(" | ").append(cell(s.hit3(), s.total()))
                        .append(" | ").append(cell(s.hit5(), s.total())).append(" |\n");
            }
            sb.append('\n');
        }

        private static String label(RetrievalService.Mode mode) {
            return switch (mode) {
                case VECTOR -> "vector only";
                case KEYWORD -> "keyword only";
                case HYBRID -> "hybrid (RRF)";
            };
        }

        private static String cell(int hits, int total) {
            return String.format(Locale.ROOT, "%.1f%% (%d/%d)", total == 0 ? 0.0 : 100.0 * hits / total, hits, total);
        }
    }

    private final ObjectMapper mapper;
    private final UserRepository users;
    private final CollectionRepository collections;
    private final DocumentRepository documents;
    private final IngestionService ingestion;
    private final RetrievalService retrieval;
    private final PasswordEncoder encoder;
    private final RagProperties props;
    private final UploadInspector inspector;

    public EvaluationService(ObjectMapper mapper, UserRepository users, CollectionRepository collections,
                             DocumentRepository documents, IngestionService ingestion, RetrievalService retrieval,
                             PasswordEncoder encoder, RagProperties props, UploadInspector inspector) {
        this.mapper = mapper;
        this.users = users;
        this.collections = collections;
        this.documents = documents;
        this.ingestion = ingestion;
        this.retrieval = retrieval;
        this.encoder = encoder;
        this.props = props;
        this.inspector = inspector;
    }

    /**
     * @param ownerEmail an existing user who will own (and keep) the demo collection so it shows up in the UI;
     *                   blank = use a throwaway user and delete everything after the run
     */
    public Report run(String ownerEmail) {
        boolean keep = ownerEmail != null && !ownerEmail.isBlank();
        User owner = keep
                ? users.findByEmail(ownerEmail.trim().toLowerCase(Locale.ROOT))
                        .orElseThrow(() -> new IllegalArgumentException("No user with email " + ownerEmail))
                : users.findByEmail(TEMP_USER).orElseGet(() -> users.save(new User(TEMP_USER, encoder.encode(UUID.randomUUID().toString()))));

        collections.findByOwnerIdAndName(owner.getId(), DEMO_COLLECTION).ifPresent(collections::delete);
        DocumentCollection collection = collections.saveAndFlush(new DocumentCollection(owner.getId(), DEMO_COLLECTION));
        try {
            int docs = ingestCorpus(collection.getId());
            int chunks = retrieval.countChunks(collection.getId());
            GoldenSet golden = loadGoldenSet();
            return score(collection.getId(), golden, docs, chunks);
        } finally {
            if (!keep) {
                collections.delete(collection);
            }
        }
    }

    private int ingestCorpus(long collectionId) {
        List<Resource> files;
        try {
            files = new ArrayList<>(Arrays.asList(new PathMatchingResourcePatternResolver().getResources("classpath:eval/corpus/*.txt")));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot list the evaluation corpus", e);
        }
        files.sort(Comparator.comparing(Resource::getFilename));
        for (Resource file : files) {
            byte[] data;
            try (InputStream in = file.getInputStream()) {
                data = in.readAllBytes();
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read " + file.getFilename(), e);
            }
            String name = FilenameSanitizer.sanitize(file.getFilename());
            String contentType = inspector.detect(name, data);
            Document doc = documents.saveAndFlush(new Document(collectionId, name, contentType, data.length, null,
                    props.ingestion().instanceId()));
            ingestion.ingest(doc.getId(), collectionId, data, contentType);
            Document done = documents.findById(doc.getId()).orElseThrow();
            if (done.getStatus() != DocumentStatus.READY) {
                throw new IllegalStateException("Corpus document " + name + " failed to ingest: " + done.getErrorMessage());
            }
        }
        return files.size();
    }

    private GoldenSet loadGoldenSet() {
        try (InputStream in = getClass().getResourceAsStream("/eval/golden-set.json")) {
            return mapper.readValue(in, GoldenSet.class);
        } catch (IOException | NullPointerException e) {
            throw new IllegalStateException("Cannot read eval/golden-set.json", e);
        }
    }

    private Report score(long collectionId, GoldenSet golden, int docs, int chunks) {
        Map<RetrievalService.Mode, Scores> overall = emptyScores();
        Map<String, Map<RetrievalService.Mode, Scores>> byType = new TreeMap<>();
        List<Double> correctPageSimilarity = new ArrayList<>();
        int answerableThrough = 0;

        for (Question q : golden.questions()) {
            Map<RetrievalService.Mode, Scores> typeScores = byType.computeIfAbsent(q.type(), t -> emptyScores());
            for (RetrievalService.Mode mode : RetrievalService.Mode.values()) {
                RetrievalService.Result result = retrieval.retrieve(collectionId, q.question(), TOP_K, mode);
                int rank = rankOfExpected(result.fused(), q);
                overall.compute(mode, (m, s) -> s.add(rank));
                typeScores.compute(mode, (m, s) -> s.add(rank));

                if (mode == RetrievalService.Mode.HYBRID) {
                    List<String> terms = KeywordQuery.terms(q.question());
                    result.vectorHits().stream()
                            .filter(h -> h.filename().equals(q.file()) && h.page() == q.page())
                            .findFirst().ifPresent(h -> correctPageSimilarity.add(h.score()));
                    boolean through = result.fused().stream()
                            .anyMatch(h -> h.filename().equals(q.file()) && h.page() == q.page()
                                    && RelevanceGate.passes(h, terms, props.minSimilarity()));
                    answerableThrough += through ? 1 : 0;
                }
            }
        }

        List<Double> wrongBest = new ArrayList<>();
        int unanswerableThrough = 0;
        List<String> unanswerable = golden.unanswerable() == null ? List.of() : golden.unanswerable();
        for (String question : unanswerable) {
            RetrievalService.Result result = retrieval.retrieve(collectionId, question, TOP_K, RetrievalService.Mode.HYBRID);
            List<String> terms = KeywordQuery.terms(question);
            wrongBest.add(result.vectorHits().stream().mapToDouble(ScoredChunk::score).max().orElse(0));
            if (result.fused().stream().anyMatch(h -> RelevanceGate.passes(h, terms, props.minSimilarity()))) {
                unanswerableThrough++;
            }
        }

        Spread correct = Spread.of(correctPageSimilarity);
        Spread wrong = Spread.of(wrongBest);
        Double suggested = correct != null && wrong != null && wrong.max() < correct.min()
                ? Math.round((wrong.max() + correct.min()) / 2 * 100) / 100.0 : null;
        Calibration calibration = new Calibration(props.minSimilarity(), correct, wrong, answerableThrough,
                golden.questions().size(), unanswerableThrough, unanswerable.size(), suggested);

        return new Report(golden.questions().size(), docs, chunks, props.openai().embeddingModel(),
                props.chunk().sizeChars(), props.chunk().overlapChars(), overall, byType, calibration);
    }

    /** 1-based rank of the first result on the expected page of the expected document, or 0 when absent. */
    static int rankOfExpected(List<FusedHit> results, Question q) {
        for (int i = 0; i < results.size(); i++) {
            FusedHit hit = results.get(i);
            if (hit.filename().equals(q.file()) && hit.page() == q.page()) {
                return i + 1;
            }
        }
        return 0;
    }

    private static Map<RetrievalService.Mode, Scores> emptyScores() {
        Map<RetrievalService.Mode, Scores> map = new EnumMap<>(RetrievalService.Mode.class);
        for (RetrievalService.Mode mode : RetrievalService.Mode.values()) {
            map.put(mode, new Scores(0, 0, 0, 0));
        }
        return map;
    }
}
