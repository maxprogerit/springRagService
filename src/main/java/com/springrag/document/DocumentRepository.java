package com.springrag.document;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    List<Document> findByCollectionIdOrderByCreatedAtDescIdDesc(Long collectionId, Pageable pageable);

    long countByCollectionId(Long collectionId);

    Optional<Document> findByIdAndCollectionId(Long id, Long collectionId);

    Optional<Document> findByCollectionIdAndContentHash(Long collectionId, String contentHash);

    /** Bytes stored by all of a user's collections (the quota counts the original file sizes). */
    @Query("""
            select coalesce(sum(d.sizeBytes), 0) from Document d
            where d.collectionId in (select c.id from DocumentCollection c where c.ownerId = :ownerId)
            """)
    long storageUsedByOwner(@Param("ownerId") Long ownerId);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update Document d set d.status = 'READY', d.pageCount = :pages, d.errorMessage = null where d.id = :id")
    int markReady(@Param("id") Long id, @Param("pages") int pages);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update Document d set d.status = 'FAILED', d.errorMessage = :message where d.id = :id")
    int markFailed(@Param("id") Long id, @Param("message") String message);

    /** Startup: leftovers of this instance's previous run, plus anything older than the processing timeout. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
            update Document d set d.status = 'FAILED', d.errorMessage = :message
            where d.status = 'PROCESSING' and (d.worker = :worker or d.createdAt < :cutoff)
            """)
    int failOwnAndStale(@Param("worker") String worker, @Param("cutoff") Instant cutoff, @Param("message") String message);

    /** Periodic sweep: only documents past the processing timeout, so other live instances are never disturbed. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update Document d set d.status = 'FAILED', d.errorMessage = :message where d.status = 'PROCESSING' and d.createdAt < :cutoff")
    int failStale(@Param("cutoff") Instant cutoff, @Param("message") String message);
}
