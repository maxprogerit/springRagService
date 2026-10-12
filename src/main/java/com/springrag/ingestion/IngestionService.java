package com.springrag.ingestion;

import com.springrag.common.ApiException;
import com.springrag.common.RagProperties;
import com.springrag.document.DocumentRepository;
import com.springrag.embedding.EmbeddingClient;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/** parse -> chunk -> embed (in batches) -> store. Any failure ends in status FAILED with a readable reason. */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);
    private static final int MAX_ERROR_LENGTH = 500;

    private final DocumentParser parser;
    private final Chunker chunker;
    private final EmbeddingClient embeddings;
    private final ChunkStore store;
    private final DocumentRepository documents;
    private final boolean ocrEnabled;

    public IngestionService(DocumentParser parser, Chunker chunker, EmbeddingClient embeddings, ChunkStore store,
                            DocumentRepository documents, RagProperties props) {
        this.parser = parser;
        this.chunker = chunker;
        this.embeddings = embeddings;
        this.store = store;
        this.documents = documents;
        this.ocrEnabled = props.ocr().enabled();
    }

    @Async("ingestionExecutor")
    public void ingestAsync(long documentId, long collectionId, byte[] data, String contentType) {
        ingest(documentId, collectionId, data, contentType);
    }

    /** Synchronous variant (also used by the evaluation command). Never throws: failures are recorded on the document. */
    public void ingest(long documentId, long collectionId, byte[] data, String contentType) {
        try {
            List<PageText> pages = parser.parse(contentType, data);
            List<Chunk> chunks = chunker.chunk(pages);
            if (chunks.isEmpty()) {
                throw new IngestionException(ocrEnabled
                        ? "No extractable text found, even with OCR."
                        : "No extractable text found. Scanned documents need OCR: start the server with OCR_ENABLED=true.");
            }
            List<float[]> vectors = embeddings.embed(chunks.stream().map(Chunk::text).toList());
            store.saveAndComplete(documentId, collectionId, pages.size(), chunks, vectors);
            log.info("Document {} ready: {} pages, {} chunks", documentId, pages.size(), chunks.size());
        } catch (IngestionException | ApiException e) {
            fail(documentId, e.getMessage());
        } catch (Exception e) {
            log.error("Ingestion of document {} failed unexpectedly", documentId, e);
            fail(documentId, "Unexpected error while processing the document.");
        }
    }

    private void fail(long documentId, String message) {
        String trimmed = message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message;
        documents.markFailed(documentId, trimmed);
        log.warn("Document {} failed: {}", documentId, trimmed);
    }
}
