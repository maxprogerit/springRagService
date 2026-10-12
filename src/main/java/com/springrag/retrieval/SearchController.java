package com.springrag.retrieval;

import static com.springrag.auth.CurrentUser.id;

import com.springrag.collection.CollectionService;
import com.springrag.common.RagProperties;
import com.springrag.common.RateLimiter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Retrieval only, no LLM: shows what each retriever found and how fusion ranked it. */
@RestController
@RequestMapping("/api/collections/{collectionId}/search")
public class SearchController {

    public record SearchRequest(@NotBlank @Size(max = 2000) String question, @Min(1) @Max(20) Integer topK) {
    }

    public record SearchResponse(List<ScoredChunk> vectorHits, List<ScoredChunk> keywordHits, List<FusedHit> fused,
                                 int topK, long latencyMs) {
    }

    private final CollectionService collections;
    private final RetrievalService retrieval;
    private final RateLimiter limiter;
    private final RagProperties props;

    public SearchController(CollectionService collections, RetrievalService retrieval, RateLimiter limiter,
                            RagProperties props) {
        this.collections = collections;
        this.retrieval = retrieval;
        this.limiter = limiter;
        this.props = props;
    }

    @PostMapping
    public SearchResponse search(@AuthenticationPrincipal Jwt jwt, @PathVariable long collectionId,
                                 @Valid @RequestBody SearchRequest body) {
        long userId = id(jwt);
        collections.requireOwned(userId, collectionId);
        // Every search embeds the query (a paid call), so it shares the per-user budget of /ask.
        limiter.check("ask:" + userId, props.rateLimit().askPerMinute());
        int topK = body.topK() != null ? body.topK() : props.topK();
        long start = System.nanoTime();
        RetrievalService.Result r = retrieval.retrieve(collectionId, body.question(), topK, RetrievalService.Mode.HYBRID);
        return new SearchResponse(r.vectorHits(), r.keywordHits(), r.fused(), topK,
                (System.nanoTime() - start) / 1_000_000);
    }
}
