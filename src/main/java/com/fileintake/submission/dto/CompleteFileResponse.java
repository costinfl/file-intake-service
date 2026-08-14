package com.fileintake.submission.dto;

import java.util.UUID;

public record CompleteFileResponse(UUID fileId, String status, Long sizeBytes, String crc32c) {}
