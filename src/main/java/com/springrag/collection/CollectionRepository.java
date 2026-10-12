package com.springrag.collection;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CollectionRepository extends JpaRepository<DocumentCollection, Long> {

    Optional<DocumentCollection> findByIdAndOwnerId(Long id, Long ownerId);

    boolean existsByOwnerIdAndName(Long ownerId, String name);

    Optional<DocumentCollection> findByOwnerIdAndName(Long ownerId, String name);

    long countByOwnerId(Long ownerId);

    @Query("""
            select new com.springrag.collection.CollectionSummary(c.id, c.name, c.createdAt, count(d.id))
            from DocumentCollection c left join com.springrag.document.Document d on d.collectionId = c.id
            where c.ownerId = :ownerId
            group by c.id, c.name, c.createdAt
            order by c.createdAt desc, c.id desc
            """)
    List<CollectionSummary> findSummaries(@Param("ownerId") Long ownerId, Pageable pageable);
}
