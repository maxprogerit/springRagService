package com.springrag.ingestion;

import com.springrag.common.RagProperties;
import com.springrag.document.DocumentRepository;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fails documents whose worker is gone, so they do not stay PROCESSING forever.
 * Safe with several app instances: an instance only fails its own leftovers at startup, and the periodic sweep
 * only touches documents older than the processing timeout.
 */
@Component
public class DocumentSweeper {

    static final String MESSAGE = "Processing was interrupted (the server restarted or took too long). Upload the file again.";
    private static final Logger log = LoggerFactory.getLogger(DocumentSweeper.class);

    private final DocumentRepository documents;
    private final RagProperties props;

    public DocumentSweeper(DocumentRepository documents, RagProperties props) {
        this.documents = documents;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        report(documents.failOwnAndStale(props.ingestion().instanceId(), cutoff(), MESSAGE));
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    public void sweepStale() {
        report(documents.failStale(cutoff(), MESSAGE));
    }

    private Instant cutoff() {
        return Instant.now().minus(Duration.ofMinutes(props.ingestion().processingTimeoutMinutes()));
    }

    private static void report(int count) {
        if (count > 0) {
            log.warn("Marked {} stuck document(s) as FAILED", count);
        }
    }
}
