package com.fileintake.reaper;

import static org.assertj.core.api.Assertions.assertThat;

import com.fileintake.submission.Submission;
import com.fileintake.submission.SubmissionFile;
import com.fileintake.submission.SubmissionFileRepository;
import com.fileintake.submission.SubmissionFileStatus;
import com.fileintake.submission.SubmissionRepository;
import com.fileintake.submission.SubmissionStatus;
import com.fileintake.support.AbstractIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ReaperJobIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ReaperJob reaperJob;

    @Autowired
    private SubmissionRepository submissionRepository;

    @Autowired
    private SubmissionFileRepository submissionFileRepository;

    @Autowired
    private FakeStagingObjectStore fakeStagingObjectStore;

    @BeforeEach
    void resetFakeStagingObjectStore() {
        fakeStagingObjectStore.reset();
    }

    private Submission persistSubmission(SubmissionStatus status, Instant expiresAt) {
        Instant now = Instant.now();
        Submission submission = new Submission(UUID.randomUUID(), "owner-1", status, now, expiresAt);
        List<String> objectPaths = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            UUID fileId = UUID.randomUUID();
            String objectPath = "staging/" + submission.getId() + "/" + fileId;
            objectPaths.add(objectPath);
            submission.addFile(
                    new SubmissionFile(fileId, i, objectPath, "file-" + i + ".bin", 50_000_000L, SubmissionFileStatus.PENDING, now));
        }
        submissionRepository.saveAndFlush(submission);
        return submission;
    }

    @Test
    void reapDeletesStagedObjectsAndMarksExpiredSubmissionsPastTtl() {
        Submission expired = persistSubmission(SubmissionStatus.PENDING, Instant.now().minus(1, ChronoUnit.HOURS));
        List<String> objectPaths =
                submissionFileRepository.findBySubmissionId(expired.getId()).stream().map(SubmissionFile::getObjectPath).toList();

        reaperJob.reap();

        assertThat(fakeStagingObjectStore.deleted()).containsExactlyInAnyOrderElementsOf(objectPaths);
        assertThat(submissionRepository.findById(expired.getId()).orElseThrow().getStatus()).isEqualTo(SubmissionStatus.EXPIRED);
    }

    @Test
    void reapLeavesNotYetExpiredPendingSubmissionsUntouched() {
        Submission notExpired = persistSubmission(SubmissionStatus.PENDING, Instant.now().plus(1, ChronoUnit.HOURS));

        reaperJob.reap();

        assertThat(fakeStagingObjectStore.deleted()).isEmpty();
        assertThat(submissionRepository.findById(notExpired.getId()).orElseThrow().getStatus()).isEqualTo(SubmissionStatus.PENDING);
    }

    @Test
    void reapLeavesCommittedSubmissionsUntouchedEvenIfPastOriginalExpiry() {
        Submission committed = persistSubmission(SubmissionStatus.COMMITTED, Instant.now().minus(1, ChronoUnit.HOURS));

        reaperJob.reap();

        assertThat(fakeStagingObjectStore.deleted()).isEmpty();
        assertThat(submissionRepository.findById(committed.getId()).orElseThrow().getStatus()).isEqualTo(SubmissionStatus.COMMITTED);
    }

    @Test
    void reapIsIdempotentOnAlreadyExpiredSubmissions() {
        Submission alreadyExpired = persistSubmission(SubmissionStatus.EXPIRED, Instant.now().minus(1, ChronoUnit.HOURS));

        reaperJob.reap();

        assertThat(fakeStagingObjectStore.deleted()).isEmpty();
        assertThat(submissionRepository.findById(alreadyExpired.getId()).orElseThrow().getStatus()).isEqualTo(SubmissionStatus.EXPIRED);
    }
}
