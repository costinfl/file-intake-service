package com.fileintake.outbox;

import com.google.api.core.ApiFuture;
import com.google.cloud.pubsub.v1.Publisher;
import com.google.protobuf.ByteString;
import com.google.pubsub.v1.PubsubMessage;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Publishes to the topic named by {@code fileintake.outbox.topic}. Dead-letter routing is a
 * subscription-side policy provisioned out-of-band (Terraform/gcloud) against that topic's
 * subscriptions — a publisher only ever writes to the topic itself, so there's nothing for this
 * class to configure for it.
 */
@Component
@ConditionalOnProperty(name = "fileintake.outbox.publisher", havingValue = "pubsub")
public class PubSubOutboxPublisher implements OutboxPublisher {

    private final Publisher publisher;

    public PubSubOutboxPublisher(Publisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public ApiFuture<String> publish(OutboxEvent event) {
        PubsubMessage message =
                PubsubMessage.newBuilder()
                        .setData(ByteString.copyFromUtf8(event.getPayload()))
                        .putAttributes("type", event.getType())
                        .putAttributes("aggregateId", event.getAggregateId().toString())
                        .build();
        return publisher.publish(message);
    }

    @PreDestroy
    void shutdown() throws InterruptedException {
        publisher.shutdown();
        publisher.awaitTermination(1, TimeUnit.MINUTES);
    }
}
