package com.fileintake.submission.dto;

import java.util.UUID;

public record FileStatusView(
        UUID fileId, int ordinal, String declaredName, String status, Long sizeBytes, String crc32c) {}
