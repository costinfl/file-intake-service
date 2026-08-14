package com.fileintake.submission.dto;

import java.util.UUID;

public record FileRef(UUID fileId, int ordinal, String declaredName) {}
