package com.fileintake.submission.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record DeclaredFile(@NotBlank String declaredName, @Positive long declaredSize) {}
