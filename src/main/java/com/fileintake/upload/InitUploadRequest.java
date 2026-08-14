package com.fileintake.upload;

import java.time.Duration;

public record InitUploadRequest(String objectPath, long declaredSize, Duration credentialTtl) {}
