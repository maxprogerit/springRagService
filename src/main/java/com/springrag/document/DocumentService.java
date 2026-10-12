package com.springrag.document;

import com.springrag.collection.CollectionService;
import com.springrag.common.ApiException;
import com.springrag.common.PageResult;
import com.springrag.common.RagProperties;
import com.springrag.ingestion.FilenameSanitizer;
import com.springrag.ingestion.IngestionService;
import com.springrag.ingestion.UploadInspector;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);
    private static final long MB = 1024L * 1024L;

    private final CollectionService collections;
    private final DocumentRepository documents;
    private final IngestionService ingestion;
    private final UploadInspector inspector;
    private final RagProperties props;

    public DocumentService(CollectionService collections, DocumentRepository documents, IngestionService ingestion,
                           UploadInspector inspector, RagProperties props) {
        this.collections = collections;
        this.documents = documents;
        this.ingestion = ingestion;
        this.inspector = inspector;
        this.props = props;
    }

    /** Validates, enforces quotas and duplicates, stores the row, then hands the bytes to the async pipeline. */
    public DocumentView upload(long userId, long collectionId, MultipartFile file) {
        collections.requireOwned(userId, collectionId);
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("The uploaded file is empty.");
        }
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("Could not read the uploaded file.");
        }
        String filename = FilenameSanitizer.sanitize(file.getOriginalFilename());
        String contentType = inspector.detect(filename, data);
        String hash = sha256(data);

        rejectDuplicate(collectionId, hash);
        enforceQuotas(userId, collectionId, data.length);

        Document saved;
        try {
            saved = documents.saveAndFlush(new Document(collectionId, filename, contentType, data.length, hash,
                    props.ingestion().instanceId()));
        } catch (DataIntegrityViolationException e) {
            // Two identical uploads raced past the check above; the unique index decided.
            throw duplicate(filename);
        }
        try {
            ingestion.ingestAsync(saved.getId(), collectionId, data, contentType);
        } catch (TaskRejectedException e) {
            log.warn("Ingestion queue is full, document {} rejected", saved.getId());
            documents.markFailed(saved.getId(), "Server is busy processing other documents. Please upload again later.");
        }
        return documents.findById(saved.getId()).map(DocumentView::of).orElseThrow();
    }

    @Transactional(readOnly = true)
    public PageResult<DocumentView> list(long userId, long collectionId, int page, int size) {
        collections.requireOwned(userId, collectionId);
        var items = documents.findByCollectionIdOrderByCreatedAtDescIdDesc(collectionId,
                PageRequest.of(PageResult.clampPage(page), PageResult.clampSize(size))).stream().map(DocumentView::of).toList();
        return new PageResult<>(items, documents.countByCollectionId(collectionId));
    }

    @Transactional
    public void delete(long userId, long collectionId, long documentId) {
        collections.requireOwned(userId, collectionId);
        Document doc = documents.findByIdAndCollectionId(documentId, collectionId)
                .orElseThrow(() -> ApiException.notFound("Document not found."));
        documents.delete(doc);
    }

    /** The same bytes twice in one collection are refused; a FAILED earlier attempt is simply replaced. */
    private void rejectDuplicate(long collectionId, String hash) {
        Optional<Document> same = documents.findByCollectionIdAndContentHash(collectionId, hash);
        if (same.isEmpty()) {
            return;
        }
        if (same.get().getStatus() == DocumentStatus.FAILED) {
            documents.delete(same.get());
            documents.flush();
        } else {
            throw duplicate(same.get().getFilename());
        }
    }

    private void enforceQuotas(long userId, long collectionId, long incomingBytes) {
        RagProperties.Quota quota = props.quota();
        if (documents.countByCollectionId(collectionId) >= quota.maxDocumentsPerCollection()) {
            throw ApiException.quota("This collection already holds " + quota.maxDocumentsPerCollection()
                    + " documents (the limit). Delete some or use another collection.");
        }
        if (documents.storageUsedByOwner(userId) + incomingBytes > quota.maxStorageMbPerUser() * MB) {
            throw ApiException.quota("Your storage limit of " + quota.maxStorageMbPerUser() + " MB would be exceeded.");
        }
    }

    private static ApiException duplicate(String existingName) {
        return new ApiException(HttpStatus.CONFLICT, "DUPLICATE_DOCUMENT",
                "This file was already uploaded to the collection as \"" + existingName + "\".");
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }
}
