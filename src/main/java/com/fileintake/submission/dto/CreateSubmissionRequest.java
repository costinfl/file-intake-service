package com.fileintake.submission.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateSubmissionRequest(
        @NotBlank String ownerId,
        @Size(min = 10, max = 10, message = "exactly 10 files must be declared") @Valid List<DeclaredFile> files) {}
