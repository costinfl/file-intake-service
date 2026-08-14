package com.fileintake.submission;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubmissionFileRepository extends JpaRepository<SubmissionFile, UUID> {

    List<SubmissionFile> findBySubmissionId(UUID submissionId);

    Optional<SubmissionFile> findByIdAndSubmissionId(UUID id, UUID submissionId);
}
