package com.fileintake.submission.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SubmissionStatusResponse(
        UUID submissionId, String status, Instant expiresAt, Instant committedAt, List<FileStatusView> files) {}
