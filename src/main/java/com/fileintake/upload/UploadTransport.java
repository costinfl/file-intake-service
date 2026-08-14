package com.fileintake.upload;

/**
 * Issues scoped upload credentials against object storage and verifies what actually landed
 * there. Implementations never see file bytes — they only sign/verify against a single
 * {@code objectPath}.
 */
public interface UploadTransport {

    InitUpload init(InitUploadRequest request);

    UploadedObject verify(String objectPath);
}
