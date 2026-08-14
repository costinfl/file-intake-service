package com.fileintake.submission;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fileintake.common.exception.ConflictException;
import com.fileintake.common.exception.NotFoundException;
import com.fileintake.outbox.OutboxEvent;
import com.fileintake.outbox.OutboxRepository;
import com.fileintake.submission.dto.CommitResponse;
import com.fileintake.submission.dto.CreateSubmissionRequest;
import com.fileintake.submission.dto.CreateSubmissionResponse;
import com.fileintake.submission.dto.DeclaredFile;
import com.fileintake.submission.dto.FileRef;
import com.fileintake.submission.dto.FileStatusView;
import com.fileintake.submission.dto.SubmissionStatusResponse;
import com.fileintake.submission.dto.UploadCredentialsResponse;
import com.fileintake.upload.InitUpload;
import com.fileintake.upload.InitUploadRequest;
import com.fileintake.upload.UploadTransport;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SubmissionService {

    private static final int EXPECTED_FILE_COUNT = 10;
    private static final String SUBMISSION_COMMITTED_EVENT_TYPE = "submission.committed";

    private final SubmissionRepository submissionRepository;
    private final SubmissionFileRepository submissionFileRepository;
    private final OutboxRepository outboxRepository;
    private final UploadTransport uploadTransport;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate readOnlyTx;
    private final TransactionTemplate writeTx;
    private final Duration submissionTtl;
    private final Duration credentialTtl;

    public SubmissionService(
            SubmissionRepository submissionRepository,
            SubmissionFileRepository submissionFileRepository,
            OutboxRepository outboxRepository,
            UploadTransport uploadTransport,
            ObjectMapper objectMapper,
            Clock clock,
            PlatformTransactionManager transactionManager,
            @Value("${fileintake.submission.ttl-hours:24}") long submissionTtlHours,
            @Value("${fileintake.upload.credential-ttl-minutes:15}") long credentialTtlMinutes) {
        this.submissionRepository = submissionRepository;
        this.submissionFileRepository = submissionFileRepository;
        this.outboxRepository = outboxRepository;
        this.uploadTransport = uploadTransport;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.readOnlyTx = new TransactionTemplate(transactionManager);
        this.readOnlyTx.setReadOnly(true);
        this.writeTx = new TransactionTemplate(transactionManager);
        this.submissionTtl = Duration.ofHours(submissionTtlHours);
        this.credentialTtl = Duration.ofMinutes(credentialTtlMinutes);
    }

    // Truncated to microseconds to match Postgres timestamptz precision: without this, a value
    // returned in an HTTP response right after being computed can carry sub-microsecond digits
    // that are silently dropped once the same row is re-read from the DB, making two responses
    // for what's actually the same instant compare as different.
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    // The "exactly 10 files" invariant is enforced by @Size(min=10,max=10) Bean Validation on
    // CreateSubmissionRequest before this method is ever invoked.
    @Transactional
    public CreateSubmissionResponse createSubmission(CreateSubmissionRequest request) {
        Instant now = now();
        UUID submissionId = UUID.randomUUID();
        Submission submission =
                new Submission(submissionId, request.ownerId(), SubmissionStatus.PENDING, now, now.plus(submissionTtl));

        List<DeclaredFile> declaredFiles = request.files();
        for (int ordinal = 0; ordinal < declaredFiles.size(); ordinal++) {
            DeclaredFile declared = declaredFiles.get(ordinal);
            UUID fileId = UUID.randomUUID();
            String objectPath = "staging/" + submissionId + "/" + fileId;
            submission.addFile(
                    new SubmissionFile(
                            fileId,
                            ordinal,
                            objectPath,
                            declared.declaredName(),
                            declared.declaredSize(),
                            SubmissionFileStatus.PENDING,
                            now));
        }

        submissionRepository.save(submission);

        List<FileRef> fileRefs =
                submission.getFiles().stream()
                        .map(f -> new FileRef(f.getId(), f.getOrdinal(), f.getDeclaredName()))
                        .toList();
        return new CreateSubmissionResponse(submissionId, submission.getExpiresAt(), fileRefs);
    }

    /**
     * Deliberately not {@code @Transactional} at the method level: the network call to {@link
     * UploadTransport#init} must not happen while holding a DB connection under a 200x10-submitter
     * burst, so validation and the UPLOADING write are each their own short transaction via
     * TransactionTemplate, with the transport call running between them, uncommitted-to-DB.
     */
    public UploadCredentialsResponse issueUploadCredentials(UUID submissionId, UUID fileId) {
        SubmissionFile validated = readOnlyTx.execute(status -> validateForCredentialIssuance(submissionId, fileId));

        InitUpload init =
                uploadTransport.init(
                        new InitUploadRequest(validated.getObjectPath(), validated.getDeclaredSize(), credentialTtl));

        writeTx.executeWithoutResult(status -> markUploading(fileId));

        return new UploadCredentialsResponse(
                init.uploadUrl(),
                init.requiredHeaders(),
                init.expiresAt(),
                validated.getDeclaredSize(),
                validated.getDeclaredSize());
    }

    private SubmissionFile validateForCredentialIssuance(UUID submissionId, UUID fileId) {
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
        if (file.getStatus() == SubmissionFileStatus.UPLOADED) {
            throw new ConflictException("file already uploaded: " + fileId);
        }
        return file;
    }

    private void markUploading(UUID fileId) {
        SubmissionFile file =
                submissionFileRepository.findById(fileId).orElseThrow(() -> new NotFoundException("file not found: " + fileId));
        file.setStatus(SubmissionFileStatus.UPLOADING);
        file.setUpdatedAt(now());
    }

    @Transactional(readOnly = true)
    public SubmissionStatusResponse getSubmission(UUID submissionId) {
        Submission submission =
                submissionRepository
                        .findByIdWithFiles(submissionId)
                        .orElseThrow(() -> new NotFoundException("submission not found: " + submissionId));

        List<FileStatusView> files =
                submission.getFiles().stream()
                        .map(
                                f ->
                                        new FileStatusView(
                                                f.getId(),
                                                f.getOrdinal(),
                                                f.getDeclaredName(),
                                                f.getStatus().name(),
                                                f.getSizeBytes(),
                                                f.getCrc32c()))
                        .toList();

        return new SubmissionStatusResponse(
                submission.getId(), submission.getStatus().name(), submission.getExpiresAt(), submission.getCommittedAt(), files);
    }

    /**
     * Idempotent and all-DB: no {@link UploadTransport} call, since the size/crc32c/generation
     * recorded by completeFile is treated as the source of truth. The {@code SELECT ... FOR
     * UPDATE} row lock from findByIdForUpdate is what makes concurrent double-commit safe — the
     * second caller blocks on the lock, then observes COMMITTED and short-circuits below without
     * writing anything.
     */
    @Transactional
    public CommitResponse commit(UUID submissionId) {
        Submission submission =
                submissionRepository
                        .findByIdForUpdate(submissionId)
                        .orElseThrow(() -> new NotFoundException("submission not found: " + submissionId));

        if (submission.getStatus() == SubmissionStatus.COMMITTED) {
            return new CommitResponse(submission.getId(), submission.getStatus().name(), submission.getCommittedAt());
        }
        if (submission.getStatus() != SubmissionStatus.PENDING) {
            throw new ConflictException("submission cannot be committed from status " + submission.getStatus() + ": " + submissionId);
        }

        List<SubmissionFile> files = submissionFileRepository.findBySubmissionId(submissionId);
        if (files.size() != EXPECTED_FILE_COUNT || files.stream().anyMatch(f -> !isReadyToCommit(f))) {
            throw new ConflictException("not all files are uploaded and verified for submission: " + submissionId);
        }

        Instant now = now();
        submission.setStatus(SubmissionStatus.COMMITTED);
        submission.setCommittedAt(now);

        outboxRepository.save(
                new OutboxEvent(submissionId, SUBMISSION_COMMITTED_EVENT_TYPE, toPayloadJson(submission, files), now));

        return new CommitResponse(submission.getId(), submission.getStatus().name(), submission.getCommittedAt());
    }

    private static boolean isReadyToCommit(SubmissionFile file) {
        return file.getStatus() == SubmissionFileStatus.UPLOADED
                && file.getCrc32c() != null
                && Objects.equals(file.getSizeBytes(), file.getDeclaredSize());
    }

    private String toPayloadJson(Submission submission, List<SubmissionFile> files) {
        List<CommittedFileRef> fileRefs =
                files.stream()
                        .map(
                                f ->
                                        new CommittedFileRef(
                                                f.getId(), f.getOrdinal(), f.getObjectPath(), f.getSizeBytes(), f.getCrc32c()))
                        .toList();
        CommittedPayload payload =
                new CommittedPayload(submission.getId(), submission.getOwnerId(), submission.getCommittedAt(), fileRefs);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize outbox payload for submission " + submission.getId(), e);
        }
    }

    private record CommittedFileRef(UUID fileId, int ordinal, String objectPath, Long sizeBytes, String crc32c) {}

    private record CommittedPayload(UUID submissionId, String ownerId, Instant committedAt, List<CommittedFileRef> files) {}
}
