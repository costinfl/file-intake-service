package com.fileintake.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs only under the {@code worker} profile — the outbox poller belongs to the worker
 * Deployment, not the API pods that serve submission traffic (see the build plan's Step 6 seam
 * note). Holds the {@code FOR UPDATE SKIP LOCKED} row lock for the duration of each event's
 * publish call: that lock, not application logic, is what stops two poller replicas from
 * publishing the same event, so it can't be dropped before publishing the way the upload-credential
 * endpoints drop their DB transaction before the network call.
 */
@Component
@Profile("worker")
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final OutboxRepository outboxRepository;
    private final OutboxPublisher outboxPublisher;
    private final Clock clock;
    private final int batchSize;
    private final Duration publishTimeout;

    public OutboxPoller(
            OutboxRepository outboxRepository,
            OutboxPublisher outboxPublisher,
            Clock clock,
            @Value("${fileintake.outbox.batch-size:100}") int batchSize,
            @Value("${fileintake.outbox.publish-timeout-ms:10000}") long publishTimeoutMs) {
        this.outboxRepository = outboxRepository;
        this.outboxPublisher = outboxPublisher;
        this.clock = clock;
        this.batchSize = batchSize;
        this.publishTimeout = Duration.ofMillis(publishTimeoutMs);
    }

    @Scheduled(fixedDelayString = "${fileintake.outbox.poll-interval-ms:5000}")
    @Transactional
    public void pollAndPublish() {
        List<OutboxEvent> batch = outboxRepository.lockBatchForPublish(batchSize);
        if (batch.isEmpty()) {
            return;
        }

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        for (OutboxEvent event : batch) {
            if (publish(event)) {
                event.setPublishedAt(now);
            }
        }
        outboxRepository.saveAll(batch);
    }

    private boolean publish(OutboxEvent event) {
        try {
            outboxPublisher.publish(event).get(publishTimeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (Exception e) {
            log.warn("failed to publish outbox event {}, will retry next poll", event.getId(), e);
            return false;
        }
    }
}
