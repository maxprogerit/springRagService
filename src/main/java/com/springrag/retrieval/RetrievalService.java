package com.springrag.retrieval;

import com.springrag.embedding.EmbeddingClient;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class RetrievalService {

    public enum Mode { HYBRID, VECTOR, KEYWORD }

    /**
     * @param vectorHits  candidates from the vector retriever (up to the candidate pool size)
     * @param keywordHits candidates from the full-text retriever
     * @param fused       final ranking, at most topK entries
     */
    public record Result(List<ScoredChunk> vectorHits, List<ScoredChunk> keywordHits, List<FusedHit> fused) {
    }

    private final EmbeddingClient embeddings;
    private final ChunkSearchRepository repository;

    public RetrievalService(EmbeddingClient embeddings, ChunkSearchRepository repository) {
        this.embeddings = embeddings;
        this.repository = repository;
    }

    /** Each retriever fetches a wider pool than topK so fusion has something to reorder. */
    public static int candidatePool(int topK) {
        return Math.max(topK * 4, 20);
    }

    public int countChunks(long collectionId) {
        return repository.countChunks(collectionId);
    }

    public Result retrieve(long collectionId, String question, int topK, Mode mode) {
        int pool = candidatePool(topK);
        List<ScoredChunk> vector = List.of();
        List<ScoredChunk> keyword = List.of();
        if (mode != Mode.KEYWORD) {
            float[] query = embeddings.embed(List.of(question)).get(0);
            vector = repository.vectorSearch(collectionId, query, pool);
        }
        if (mode != Mode.VECTOR) {
            String tsQuery = KeywordQuery.build(question);
            if (tsQuery != null) {
                keyword = repository.keywordSearch(collectionId, tsQuery, pool);
            }
        }
        // For single-retriever modes the other list is empty, so RRF just preserves that retriever's order.
        List<FusedHit> fused = RrfFusion.fuse(vector, keyword, RrfFusion.DEFAULT_K);
        return new Result(vector, keyword, fused.subList(0, Math.min(topK, fused.size())));
    }
}
