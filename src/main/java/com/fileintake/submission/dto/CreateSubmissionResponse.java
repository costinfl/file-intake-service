package com.fileintake.submission.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CreateSubmissionResponse(UUID submissionId, Instant expiresAt, List<FileRef> files) {}
