package com.fileintake.upload;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stands in for real object storage in tests and local dev. Bytes never reach this process even
 * here, so {@link #verify} reports whatever a test explicitly configured via {@link
 * #simulateUploadSuccess} rather than anything actually written to a URL.
 */
@Component
@ConditionalOnProperty(name = "fileintake.upload.transport", havingValue = "fake", matchIfMissing = true)
public class FakeUploadTransport implements UploadTransport {

    private final Map<String, UploadedObject> uploaded = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> generations = new ConcurrentHashMap<>();

    @Override
    public InitUpload init(InitUploadRequest request) {
        String uploadUrl = "fake://upload/" + request.objectPath() + "?token=" + UUID.randomUUID();
        Instant expiresAt = Instant.now().plus(request.credentialTtl());
        return new InitUpload(uploadUrl, Map.of(), expiresAt);
    }

    @Override
    public UploadedObject verify(String objectPath) {
        return uploaded.getOrDefault(objectPath, UploadedObject.notFound());
    }

    public void simulateUploadSuccess(String objectPath, long sizeBytes, String crc32c) {
        long generation = generations.computeIfAbsent(objectPath, k -> new AtomicLong(0)).incrementAndGet();
        uploaded.put(objectPath, new UploadedObject(true, sizeBytes, crc32c, generation));
    }

    public void simulateUploadFailure(String objectPath) {
        uploaded.remove(objectPath);
    }

    public void reset() {
        uploaded.clear();
        generations.clear();
    }
}
