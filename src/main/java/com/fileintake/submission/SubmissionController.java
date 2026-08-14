package com.fileintake.submission;

import com.fileintake.submission.dto.CommitResponse;
import com.fileintake.submission.dto.CompleteFileResponse;
import com.fileintake.submission.dto.CreateSubmissionRequest;
import com.fileintake.submission.dto.CreateSubmissionResponse;
import com.fileintake.submission.dto.SubmissionStatusResponse;
import com.fileintake.submission.dto.UploadCredentialsResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/submissions")
public class SubmissionController {

    private final SubmissionService submissionService;
    private final SubmissionFileService submissionFileService;

    public SubmissionController(SubmissionService submissionService, SubmissionFileService submissionFileService) {
        this.submissionService = submissionService;
        this.submissionFileService = submissionFileService;
    }

    @PostMapping
    public ResponseEntity<CreateSubmissionResponse> createSubmission(@Valid @RequestBody CreateSubmissionRequest request) {
        CreateSubmissionResponse response = submissionService.createSubmission(request);
        return ResponseEntity.created(URI.create("/submissions/" + response.submissionId())).body(response);
    }

    @PostMapping("/{submissionId}/files/{fileId}/upload-credentials")
    public UploadCredentialsResponse issueUploadCredentials(
            @PathVariable UUID submissionId, @PathVariable UUID fileId) {
        return submissionService.issueUploadCredentials(submissionId, fileId);
    }

    @PostMapping("/{submissionId}/files/{fileId}/complete")
    public CompleteFileResponse completeFile(@PathVariable UUID submissionId, @PathVariable UUID fileId) {
        return submissionFileService.completeFile(submissionId, fileId);
    }

    @GetMapping("/{submissionId}")
    public SubmissionStatusResponse getSubmission(@PathVariable UUID submissionId) {
        return submissionService.getSubmission(submissionId);
    }

    @PostMapping("/{submissionId}/commit")
    public CommitResponse commit(@PathVariable UUID submissionId) {
        return submissionService.commit(submissionId);
    }
}
