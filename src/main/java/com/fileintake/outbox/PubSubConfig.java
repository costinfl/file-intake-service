package com.fileintake.outbox;

import com.google.cloud.pubsub.v1.Publisher;
import com.google.pubsub.v1.TopicName;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Credentials resolve via Application Default Credentials (Workload Identity on GKE), same as
 * {@link com.fileintake.upload.xml.StorageConfig} — no SA JSON key. Only active when the real
 * publisher is selected, so local dev/tests using the fake publisher never need GCP credentials.
 */
@Configuration
@ConditionalOnProperty(name = "fileintake.outbox.publisher", havingValue = "pubsub")
public class PubSubConfig {

    @Bean(destroyMethod = "")
    public Publisher pubSubPublisher(
            @Value("${fileintake.outbox.gcp-project-id}") String projectId,
            @Value("${fileintake.outbox.topic}") String topic)
            throws Exception {
        return Publisher.newBuilder(TopicName.of(projectId, topic)).build();
    }
}
