package com.fileintake.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fileintake.support.AbstractIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

class SubmissionRepositoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private SubmissionRepository submissionRepository;

    @Autowired
    private SubmissionFileRepository submissionFileRepository;

    private Submission newSubmission() {
        Instant now = Instant.now();
        Submission submission =
                new Submission(UUID.randomUUID(), "owner-1", SubmissionStatus.PENDING, now, now.plus(24, ChronoUnit.HOURS));
        for (int i = 0; i < 10; i++) {
            UUID fileId = UUID.randomUUID();
            SubmissionFile file =
                    new SubmissionFile(
                            fileId,
                            i,
                            "staging/" + submission.getId() + "/" + fileId,
                            "file-" + i + ".bin",
                            50_000_000L,
                            SubmissionFileStatus.PENDING,
                            now);
            submission.addFile(file);
        }
        return submission;
    }

    @Test
    void savesSubmissionWithTenFilesAndReadsThemBack() {
        Submission submission = submissionRepository.saveAndFlush(newSubmission());

        var found = submissionRepository.findByIdWithFiles(submission.getId()).orElseThrow();

        assertThat(found.getFiles()).hasSize(10);
        assertThat(found.getFiles().get(0).getOrdinal()).isZero();
        assertThat(found.getFiles().get(9).getOrdinal()).isEqualTo(9);
        assertThat(submissionFileRepository.findBySubmissionId(submission.getId())).hasSize(10);
    }

    @Test
    @Transactional
    void findByIdForUpdateLocksAndReturnsSubmission() {
        Submission saved = submissionRepository.saveAndFlush(newSubmission());

        Submission locked = submissionRepository.findByIdForUpdate(saved.getId()).orElseThrow();

        assertThat(locked.getId()).isEqualTo(saved.getId());
    }

    @Test
    void ordinalMustBeUniquePerSubmission() {
        Submission submission = newSubmission();
        // duplicate ordinal 0 alongside the existing ordinal-0 file
        UUID dupFileId = UUID.randomUUID();
        SubmissionFile dup =
                new SubmissionFile(
                        dupFileId,
                        0,
                        "staging/" + submission.getId() + "/" + dupFileId,
                        "dup.bin",
                        1L,
                        SubmissionFileStatus.PENDING,
                        Instant.now());
        submission.addFile(dup);

        assertThrows(
                DataIntegrityViolationException.class, () -> submissionRepository.saveAndFlush(submission));
    }
}
