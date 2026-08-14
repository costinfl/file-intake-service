package com.fileintake.submission;

import com.fileintake.common.exception.ConflictException;
import com.fileintake.common.exception.NotFoundException;
import com.fileintake.common.exception.UnprocessableEntityException;
import com.fileintake.submission.dto.CompleteFileResponse;
import com.fileintake.upload.UploadTransport;
import com.fileintake.upload.UploadedObject;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SubmissionFileService {

    private final SubmissionRepository submissionRepository;
    private final SubmissionFileRepository submissionFileRepository;
    private final UploadTransport uploadTransport;
    private final Clock clock;
    private final TransactionTemplate readOnlyTx;
    private final TransactionTemplate writeTx;

    public SubmissionFileService(
            SubmissionRepository submissionRepository,
            SubmissionFileRepository submissionFileRepository,
            UploadTransport uploadTransport,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.submissionRepository = submissionRepository;
        this.submissionFileRepository = submissionFileRepository;
        this.uploadTransport = uploadTransport;
        this.clock = clock;
        this.readOnlyTx = new TransactionTemplate(transactionManager);
        this.readOnlyTx.setReadOnly(true);
        this.writeTx = new TransactionTemplate(transactionManager);
    }

    /**
     * Not {@code @Transactional} at the method level for the same reason as {@link
     * SubmissionService#issueUploadCredentials}: the {@link UploadTransport#verify} network call
     * must not run while holding a DB connection.
     */
    public CompleteFileResponse completeFile(UUID submissionId, UUID fileId) {
        CompletionPrecheck precheck = readOnlyTx.execute(status -> precheck(submissionId, fileId));

        if (precheck.alreadyUploaded()) {
            SubmissionFile file = precheck.file();
            return new CompleteFileResponse(
                    file.getId(), file.getStatus().name(), file.getSizeBytes(), file.getCrc32c());
        }

        UploadedObject result = uploadTransport.verify(precheck.file().getObjectPath());

        VerificationOutcome outcome = writeTx.execute(status -> applyVerificationResult(fileId, precheck.file(), result));

        if (!outcome.accepted()) {
            // Thrown after the transaction committed the FAILED status: a rollback here would
            // silently discard the state transition the client needs to see via GET /submissions.
            throw new UnprocessableEntityException(outcome.rejectionReason());
        }
        return outcome.response();
    }

    private CompletionPrecheck precheck(UUID submissionId, UUID fileId) {
        Submission submission =
                submissionRepository
                        .findById(submissionId)
                        .orElseThrow(() -> new NotFoundException("submission not found: " + submissionId));
        if (submission.getStatus() != SubmissionStatus.PENDING) {
            throw new ConflictException("submission is not PENDING: " + submissionId);
        }
        SubmissionFile file =
                submissionFileRepository
                        .findByIdAndSubmissionId(fileId, submissionId)
                        .orElseThrow(() -> new NotFoundException("file not found: " + fileId));
        if (file.getStatus() == SubmissionFileStatus.FAILED) {
            throw new ConflictException("file previously failed, re-issue upload credentials first: " + fileId);
        }
        return new CompletionPrecheck(file, file.getStatus() == SubmissionFileStatus.UPLOADED);
    }

    private VerificationOutcome applyVerificationResult(UUID fileId, SubmissionFile precheckedFile, UploadedObject result) {
        SubmissionFile file =
                submissionFileRepository.findById(fileId).orElseThrow(() -> new NotFoundException("file not found: " + fileId));

        if (!result.exists()) {
            file.setStatus(SubmissionFileStatus.FAILED);
            file.setUpdatedAt(clock.instant());
            return VerificationOutcome.rejected("uploaded object not found for file: " + fileId);
        }
        if (result.sizeBytes() != precheckedFile.getDeclaredSize()) {
            file.setStatus(SubmissionFileStatus.FAILED);
            file.setUpdatedAt(clock.instant());
            return VerificationOutcome.rejected(
                    "size mismatch for file "
                            + fileId
                            + ": declared="
                            + precheckedFile.getDeclaredSize()
                            + " actual="
                            + result.sizeBytes());
        }

        file.setStatus(SubmissionFileStatus.UPLOADED);
        file.setGcsGeneration(result.generation());
        file.setCrc32c(result.crc32c());
        file.setSizeBytes(result.sizeBytes());
        file.setUpdatedAt(clock.instant());

        return VerificationOutcome.accepted(
                new CompleteFileResponse(file.getId(), file.getStatus().name(), file.getSizeBytes(), file.getCrc32c()));
    }

    private record CompletionPrecheck(SubmissionFile file, boolean alreadyUploaded) {}

    private record VerificationOutcome(boolean accepted, CompleteFileResponse response, String rejectionReason) {
        static VerificationOutcome accepted(CompleteFileResponse response) {
            return new VerificationOutcome(true, response, null);
        }

        static VerificationOutcome rejected(String reason) {
            return new VerificationOutcome(false, null, reason);
        }
    }
}
