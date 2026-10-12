package com.springrag.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.springrag.retrieval.FusedHit;
import com.springrag.retrieval.RetrievalService.Mode;
import com.springrag.support.IntegrationTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class EvaluationServiceTest extends IntegrationTestBase {

    @Autowired
    EvaluationService service;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void rankOfExpectedFindsTheFirstMatchingPage() {
        EvaluationService.Question q = new EvaluationService.Question("exact", "?", "a.txt", 2);
        List<FusedHit> hits = List.of(hit("a.txt", 1), hit("b.txt", 2), hit("a.txt", 2), hit("a.txt", 2));

        assertThat(EvaluationService.rankOfExpected(hits, q)).isEqualTo(3);
        assertThat(EvaluationService.rankOfExpected(List.of(hit("a.txt", 3)), q)).isZero();
        assertThat(EvaluationService.rankOfExpected(List.of(), q)).isZero();
    }

    private static FusedHit hit(String file, int page) {
        return new FusedHit(1, 1, file, page, "t", 0.1, null, null, null, null);
    }

    @Test
    void scoresAccumulateCumulativelyAcrossK() {
        EvaluationService.Scores s = new EvaluationService.Scores(0, 0, 0, 0).add(1).add(3).add(5).add(0);

        assertThat(s).isEqualTo(new EvaluationService.Scores(4, 1, 2, 3));
    }

    /**
     * Runs the whole pipeline (ingest the demo corpus, run the golden set) with the offline hashing embedder.
     * The printed vector/hybrid numbers are a regression baseline for this stand-in, NOT a quality claim for
     * a real embedding model.
     */
    @Test
    void runsTheGoldenSetOverTheDemoCorpusAndCleansUp() {
        EvaluationService.Report report = service.run(null);

        System.out.println("\n===== EVALUATION (offline hashing embedder, not a semantic model) =====\n" + report.toMarkdown());

        assertThat(report.questions()).isEqualTo(24);
        assertThat(report.documents()).isEqualTo(4);
        assertThat(report.chunks()).isEqualTo(12);
        for (Mode mode : Mode.values()) {
            EvaluationService.Scores s = report.overall().get(mode);
            assertThat(s.total()).isEqualTo(24);
            assertThat(s.hit1()).isLessThanOrEqualTo(s.hit3());
            assertThat(s.hit3()).isLessThanOrEqualTo(s.hit5());
        }
        assertThat(report.byType().keySet()).containsExactly("exact", "paraphrase");
        // Exact-term questions are the keyword retriever's home turf: it must find nearly all of them.
        assertThat(report.byType().get("exact").get(Mode.KEYWORD).hit5()).isGreaterThanOrEqualTo(10);
        assertThat(report.toMarkdown()).contains("| hybrid (RRF) |").contains("Similarity gate at MIN_SIMILARITY=0.20");
        EvaluationService.Calibration c = report.calibration();
        assertThat(c.answerableTotal()).isEqualTo(24);
        assertThat(c.unanswerableTotal()).isEqualTo(8);
        assertThat(c.correctPageSimilarity().min()).isLessThanOrEqualTo(c.correctPageSimilarity().median());
        assertThat(c.wrongBestSimilarity().median()).isLessThan(c.correctPageSimilarity().median());
        assertThat(c.answerableThrough()).isBetween(1, 24);
        assertThat(c.unanswerableThrough()).isBetween(0, 8);
        // The temporary user's data is removed afterwards.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM collections c JOIN users u ON u.id = c.owner_id
                WHERE u.email = ? AND c.name = ?""", Integer.class, EvaluationService.TEMP_USER,
                EvaluationService.DEMO_COLLECTION)).isZero();
    }

    @Test
    void canKeepTheDemoCollectionForAnExistingUser() throws Exception {
        String email = uniqueEmail();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/register")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"correct-horse-battery\"}"));

        service.run(email);
        service.run(email); // running twice must replace, not duplicate

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM collections c JOIN users u ON u.id = c.owner_id
                WHERE u.email = ? AND c.name = ?""", Integer.class, email, EvaluationService.DEMO_COLLECTION)).isEqualTo(1);
    }
}
