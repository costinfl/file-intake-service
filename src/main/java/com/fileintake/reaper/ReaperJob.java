package com.fileintake.reaper;

import com.fileintake.submission.Submission;
import com.fileintake.submission.SubmissionFile;
import com.fileintake.submission.SubmissionFileRepository;
import com.fileintake.submission.SubmissionRepository;
import com.fileintake.submission.SubmissionStatus;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Hourly cleanup for submissions abandoned before their 10 files were ever fully uploaded and
 * committed. The bucket's 7-day lifecycle rule on {@code staging/} is a backstop for objects this
 * job fails to delete, not the primary mechanism — this job is expected to run and catch
 * essentially everything within the 24h expiry window.
 */
@Component
public class ReaperJob {

    private static final Logger log = LoggerFactory.getLogger(ReaperJob.class);

    private final SubmissionRepository submissionRepository;
    private final SubmissionFileRepository submissionFileRepository;
    private final StagingObjectStore stagingObjectStore;
    private final Clock clock;
    private final TransactionTemplate readOnlyTx;
    private final TransactionTemplate writeTx;

    public ReaperJob(
            SubmissionRepository submissionRepository,
            SubmissionFileRepository submissionFileRepository,
            StagingObjectStore stagingObjectStore,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.submissionRepository = submissionRepository;
        this.submissionFileRepository = submissionFileRepository;
        this.stagingObjectStore = stagingObjectStore;
        this.clock = clock;
        this.readOnlyTx = new TransactionTemplate(transactionManager);
        this.readOnlyTx.setReadOnly(true);
        this.writeTx = new TransactionTemplate(transactionManager);
    }

    public void reap() {
        List<UUID> expiredSubmissionIds =
                readOnlyTx.execute(
                        status ->
                                submissionRepository.findByStatusAndExpiresAtBefore(SubmissionStatus.PENDING, clock.instant())
                                        .stream()
                                        .map(Submission::getId)
                                        .toList());

        log.info("reaper found {} expired PENDING submissions", expiredSubmissionIds.size());
        for (UUID submissionId : expiredSubmissionIds) {
            reapOne(submissionId);
        }
    }

    private void reapOne(UUID submissionId) {
        List<String> objectPaths =
                readOnlyTx.execute(
                        status ->
                                submissionFileRepository.findBySubmissionId(submissionId).stream()
                                        .map(SubmissionFile::getObjectPath)
                                        .toList());

        for (String objectPath : objectPaths) {
            try {
                stagingObjectStore.delete(objectPath);
            } catch (Exception e) {
                // Best-effort: an object left behind here is caught by the 7d bucket lifecycle
                // rule backstop. Still mark the submission EXPIRED below so it stops appearing in
                // future reaper scans and stays invisible to readers either way.
                log.warn("failed to delete staged object {} for submission {}", objectPath, submissionId, e);
            }
        }

        writeTx.executeWithoutResult(status -> markExpired(submissionId));
    }

    private void markExpired(UUID submissionId) {
        // findByIdForUpdate serializes against a concurrent commit() on the same submission -
        // without it, a client whose commit lands between reap()'s read and this write could have
        // its just-committed submission overwritten back to EXPIRED.
        Submission submission = submissionRepository.findByIdForUpdate(submissionId).orElse(null);
        if (submission == null || submission.getStatus() != SubmissionStatus.PENDING) {
            return;
        }
        submission.setStatus(SubmissionStatus.EXPIRED);
    }
}
