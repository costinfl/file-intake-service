package com.fileintake.submission.dto;

import java.time.Instant;
import java.util.UUID;

public record CommitResponse(UUID submissionId, String status, Instant committedAt) {}
