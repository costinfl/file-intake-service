package com.fileintake.upload.xml;

import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@link StorageOptions#getDefaultInstance()} resolves credentials via Application Default
 * Credentials, which on GKE means the pod's Workload Identity-bound service account — no SA JSON
 * key is read from disk or config. Only active when the real transport is selected, so local dev
 * and tests running with the fake transport never need GCP credentials at all.
 */
@Configuration
@ConditionalOnProperty(name = "fileintake.upload.transport", havingValue = "xml-multipart")
public class StorageConfig {

    @Bean
    public Storage storage() {
        return StorageOptions.getDefaultInstance().getService();
    }
}
