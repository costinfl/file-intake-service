package com.fileintake.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fileintake.support.AbstractIntegrationTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("worker")
class OutboxPollerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private OutboxPoller outboxPoller;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private FakeOutboxPublisher fakeOutboxPublisher;

    @BeforeEach
    void resetFakeOutboxPublisher() {
        fakeOutboxPublisher.reset();
    }

    private OutboxEvent unpublishedEvent() {
        OutboxEvent event =
                new OutboxEvent(UUID.randomUUID(), "submission.committed", "{\"k\":\"v\"}", Instant.now());
        return outboxRepository.saveAndFlush(event);
    }

    @Test
    void pollAndPublishPublishesAndStampsUnpublishedEvents() {
        OutboxEvent a = unpublishedEvent();
        OutboxEvent b = unpublishedEvent();

        outboxPoller.pollAndPublish();

        assertThat(fakeOutboxPublisher.published()).extracting(OutboxEvent::getId).contains(a.getId(), b.getId());
        assertThat(outboxRepository.findById(a.getId()).orElseThrow().getPublishedAt()).isNotNull();
        assertThat(outboxRepository.findById(b.getId()).orElseThrow().getPublishedAt()).isNotNull();
    }

    @Test
    void pollAndPublishIgnoresAlreadyPublishedEvents() {
        OutboxEvent alreadyPublished = unpublishedEvent();
        alreadyPublished.setPublishedAt(Instant.now());
        outboxRepository.saveAndFlush(alreadyPublished);

        outboxPoller.pollAndPublish();

        assertThat(fakeOutboxPublisher.published()).extracting(OutboxEvent::getId).doesNotContain(alreadyPublished.getId());
    }

    @Test
    void pollAndPublishLeavesFailedEventUnpublishedForRetry() {
        OutboxEvent event = unpublishedEvent();
        fakeOutboxPublisher.simulateFailureFor(event.getId());

        outboxPoller.pollAndPublish();

        assertThat(outboxRepository.findById(event.getId()).orElseThrow().getPublishedAt()).isNull();
    }
}
