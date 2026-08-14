package com.fileintake.reaper;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Storage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "fileintake.upload.transport", havingValue = "xml-multipart")
public class GcsStagingObjectStore implements StagingObjectStore {

    private final Storage storage;
    private final String bucket;

    public GcsStagingObjectStore(Storage storage, @Value("${fileintake.upload.bucket}") String bucket) {
        this.storage = storage;
        this.bucket = bucket;
    }

    @Override
    public void delete(String objectPath) {
        // Storage.delete returns false for a missing object rather than throwing, so deleting an
        // object that was never actually uploaded (or was already reaped) is a safe no-op.
        storage.delete(BlobId.of(bucket, objectPath));
    }
}
