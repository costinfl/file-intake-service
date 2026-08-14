package com.fileintake.outbox;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    // SKIP LOCKED has no portable JPQL equivalent, hence native SQL. Consumed by the
    // outbox poller (Step 6); the query is defined here alongside the table/entity.
    @Query(
            value =
                    """
                    SELECT * FROM outbox
                    WHERE published_at IS NULL
                    ORDER BY id
                    FOR UPDATE SKIP LOCKED
                    LIMIT :batchSize
                    """,
            nativeQuery = true)
    List<OutboxEvent> lockBatchForPublish(@Param("batchSize") int batchSize);
}
