package com.springrag.collection;

import com.springrag.common.ApiException;
import com.springrag.common.PageResult;
import com.springrag.common.RagProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CollectionService {

    private final CollectionRepository collections;
    private final RagProperties props;

    public CollectionService(CollectionRepository collections, RagProperties props) {
        this.collections = collections;
        this.props = props;
    }

    @Transactional(readOnly = true)
    public PageResult<CollectionSummary> list(long userId, int page, int size) {
        var items = collections.findSummaries(userId, PageRequest.of(PageResult.clampPage(page), PageResult.clampSize(size)));
        return new PageResult<>(items, collections.countByOwnerId(userId));
    }

    public CollectionSummary create(long userId, String name) {
        String trimmed = name.trim();
        if (collections.existsByOwnerIdAndName(userId, trimmed)) {
            throw ApiException.conflict("A collection with this name already exists.");
        }
        if (collections.countByOwnerId(userId) >= props.quota().maxCollectionsPerUser()) {
            throw ApiException.quota("You already have " + props.quota().maxCollectionsPerUser()
                    + " collections (the limit). Delete one first.");
        }
        try {
            DocumentCollection saved = collections.saveAndFlush(new DocumentCollection(userId, trimmed));
            return new CollectionSummary(saved.getId(), saved.getName(), saved.getCreatedAt(), 0);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("A collection with this name already exists.");
        }
    }

    /**
     * The single ownership gate. Every collection-scoped endpoint calls this first.
     * A collection owned by someone else is reported as 404, not 403, so ids cannot be probed.
     */
    @Transactional(readOnly = true)
    public DocumentCollection requireOwned(long userId, long collectionId) {
        return collections.findByIdAndOwnerId(collectionId, userId)
                .orElseThrow(() -> ApiException.notFound("Collection not found."));
    }

    @Transactional
    public void delete(long userId, long collectionId) {
        collections.delete(requireOwned(userId, collectionId));
    }
}
