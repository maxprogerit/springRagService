package com.springrag.collection;

import static com.springrag.auth.CurrentUser.id;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/collections/{collectionId}/stats")
public class StatsController {

    public record Stats(Documents documents, long chunks, long pages, Queries queries) {
    }

    public record Documents(long total, long ready, long processing, long failed) {
    }

    /** avgLatencyMs is null until the first question has been asked. */
    public record Queries(long total, long answered, Double avgLatencyMs) {
    }

    private final CollectionService collections;
    private final JdbcTemplate jdbc;

    public StatsController(CollectionService collections, JdbcTemplate jdbc) {
        this.collections = collections;
        this.jdbc = jdbc;
    }

    @GetMapping
    public Stats stats(@AuthenticationPrincipal Jwt jwt, @PathVariable long collectionId) {
        collections.requireOwned(id(jwt), collectionId);
        Map<String, Object> d = jdbc.queryForMap("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE status = 'READY') AS ready,
                       count(*) FILTER (WHERE status = 'PROCESSING') AS processing,
                       count(*) FILTER (WHERE status = 'FAILED') AS failed,
                       coalesce(sum(page_count) FILTER (WHERE status = 'READY'), 0) AS pages
                FROM documents WHERE collection_id = ?
                """, collectionId);
        long chunks = jdbc.queryForObject("SELECT count(*) FROM chunks WHERE collection_id = ?", Long.class, collectionId);
        Map<String, Object> q = jdbc.queryForMap("""
                SELECT count(*) AS total, count(*) FILTER (WHERE answered) AS answered, avg(latency_ms) AS avg_latency
                FROM queries_log WHERE collection_id = ?
                """, collectionId);
        Number avg = (Number) q.get("avg_latency");
        return new Stats(
                new Documents(num(d, "total"), num(d, "ready"), num(d, "processing"), num(d, "failed")),
                chunks, num(d, "pages"),
                new Queries(num(q, "total"), num(q, "answered"), avg == null ? null : Math.round(avg.doubleValue() * 10) / 10.0));
    }

    private static long num(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }
}
