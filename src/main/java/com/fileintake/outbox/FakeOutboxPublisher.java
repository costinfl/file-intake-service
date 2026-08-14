package com.fileintake.outbox;

import com.google.api.core.ApiFuture;
import com.google.api.core.ApiFutures;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Stands in for Pub/Sub in tests and local dev: records what was published, in memory. */
@Component
@ConditionalOnProperty(name = "fileintake.outbox.publisher", havingValue = "fake", matchIfMissing = true)
public class FakeOutboxPublisher implements OutboxPublisher {

    private final List<OutboxEvent> published = new CopyOnWriteArrayList<>();
    private final Set<Long> failingEventIds = new CopyOnWriteArraySet<>();

    @Override
    public ApiFuture<String> publish(OutboxEvent event) {
        if (failingEventIds.contains(event.getId())) {
            return ApiFutures.immediateFailedFuture(new RuntimeException("simulated publish failure for event " + event.getId()));
        }
        published.add(event);
        return ApiFutures.immediateFuture(UUID.randomUUID().toString());
    }

    public List<OutboxEvent> published() {
        return List.copyOf(published);
    }

    public void simulateFailureFor(Long eventId) {
        failingEventIds.add(eventId);
    }

    public void reset() {
        published.clear();
        failingEventIds.clear();
    }
}
