package com.fileintake.upload;

import java.time.Instant;
import java.util.Map;

public record InitUpload(String uploadUrl, Map<String, String> requiredHeaders, Instant expiresAt) {}
