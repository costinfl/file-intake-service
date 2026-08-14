package com.fileintake.submission;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SubmissionRepository extends JpaRepository<Submission, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Submission s where s.id = :id")
    Optional<Submission> findByIdForUpdate(@Param("id") UUID id);

    @Query("select s from Submission s left join fetch s.files where s.id = :id")
    Optional<Submission> findByIdWithFiles(@Param("id") UUID id);

    List<Submission> findByStatusAndExpiresAtBefore(SubmissionStatus status, Instant expiresAt);
}
