package com.fileintake.submission;

import com.fileintake.common.exception.ConflictException;
import com.fileintake.common.exception.NotFoundException;
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
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SubmissionService {

    private final SubmissionRepository submissionRepository;
    private final SubmissionFileRepository submissionFileRepository;
    private final UploadTransport uploadTransport;
    private final Clock clock;
    private final TransactionTemplate readOnlyTx;
    private final TransactionTemplate writeTx;
    private final Duration submissionTtl;
    private final Duration credentialTtl;

    public SubmissionService(
            SubmissionRepository submissionRepository,
            SubmissionFileRepository submissionFileRepository,
            UploadTransport uploadTransport,
            Clock clock,
            PlatformTransactionManager transactionManager,
            @Value("${fileintake.submission.ttl-hours:24}") long submissionTtlHours,
            @Value("${fileintake.upload.credential-ttl-minutes:15}") long credentialTtlMinutes) {
        this.submissionRepository = submissionRepository;
        this.submissionFileRepository = submissionFileRepository;
        this.uploadTransport = uploadTransport;
        this.clock = clock;
        this.readOnlyTx = new TransactionTemplate(transactionManager);
        this.readOnlyTx.setReadOnly(true);
        this.writeTx = new TransactionTemplate(transactionManager);
        this.submissionTtl = Duration.ofHours(submissionTtlHours);
        this.credentialTtl = Duration.ofMinutes(credentialTtlMinutes);
    }

    // The "exactly 10 files" invariant is enforced by @Size(min=10,max=10) Bean Validation on
    // CreateSubmissionRequest before this method is ever invoked.
    @Transactional
    public CreateSubmissionResponse createSubmission(CreateSubmissionRequest request) {
        Instant now = clock.instant();
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
        file.setUpdatedAt(clock.instant());
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
}
