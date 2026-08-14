package com.fileintake.outbox;

import com.google.api.gax.core.NoCredentialsProvider;
import com.google.api.gax.grpc.GrpcTransportChannel;
import com.google.api.gax.rpc.FixedTransportChannelProvider;
import com.google.api.gax.rpc.NotFoundException;
import com.google.api.gax.rpc.TransportChannelProvider;
import com.google.cloud.pubsub.v1.Publisher;
import com.google.cloud.pubsub.v1.TopicAdminClient;
import com.google.cloud.pubsub.v1.TopicAdminSettings;
import com.google.pubsub.v1.TopicName;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Real Pub/Sub (default): credentials resolve via Application Default Credentials (Workload
 * Identity on GKE), same as {@link com.fileintake.upload.xml.StorageConfig} — no SA JSON key.
 *
 * <p>Local emulator (fileintake.outbox.pubsub-emulator-host set, e.g. by docker-compose's
 * pubsub-emulator): a plaintext gRPC channel points at the emulator instead of
 * pubsub.googleapis.com, with {@link NoCredentialsProvider} since the emulator doesn't check
 * auth. The emulator starts with no topics, so the configured topic is created via {@link
 * TopicAdminClient} on the same channel if it doesn't already exist.
 *
 * <p>Only active when the real publisher is selected, so local dev/tests using the fake publisher
 * never need GCP credentials or a running emulator.
 */
@Configuration
@ConditionalOnProperty(name = "fileintake.outbox.publisher", havingValue = "pubsub")
public class PubSubConfig {

    private static final Logger log = LoggerFactory.getLogger(PubSubConfig.class);

    @Bean(destroyMethod = "")
    public Publisher pubSubPublisher(
            @Value("${fileintake.outbox.gcp-project-id}") String projectId,
            @Value("${fileintake.outbox.topic}") String topic,
            @Value("${fileintake.outbox.pubsub-emulator-host:}") String emulatorHost)
            throws Exception {
        TopicName topicName = TopicName.of(projectId, topic);

        if (emulatorHost.isBlank()) {
            return Publisher.newBuilder(topicName).build();
        }

        log.warn("Using Pub/Sub emulator at {} — not for production use", emulatorHost);
        ManagedChannel channel = ManagedChannelBuilder.forTarget(emulatorHost).usePlaintext().build();
        TransportChannelProvider channelProvider =
                FixedTransportChannelProvider.create(GrpcTransportChannel.create(channel));
        NoCredentialsProvider credentialsProvider = NoCredentialsProvider.create();

        try (TopicAdminClient topicAdminClient =
                TopicAdminClient.create(
                        TopicAdminSettings.newBuilder()
                                .setTransportChannelProvider(channelProvider)
                                .setCredentialsProvider(credentialsProvider)
                                .build())) {
            try {
                topicAdminClient.getTopic(topicName);
            } catch (NotFoundException e) {
                topicAdminClient.createTopic(topicName);
                log.info("Created topic {} on Pub/Sub emulator at {}", topicName, emulatorHost);
            }
        }

        return Publisher.newBuilder(topicName)
                .setChannelProvider(channelProvider)
                .setCredentialsProvider(credentialsProvider)
                .build();
    }
}
