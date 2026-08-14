package com.fileintake.submission.dto;

import java.time.Instant;
import java.util.Map;

public record UploadCredentialsResponse(
        String uploadUrl,
        Map<String, String> headers,
        Instant expiresAt,
        long contentLengthMin,
        long contentLengthMax) {}
