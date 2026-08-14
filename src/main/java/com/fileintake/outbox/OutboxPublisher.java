package com.fileintake.outbox;

import com.google.api.core.ApiFuture;

/** Publishes a single outbox event. Consumers are expected to be idempotent (at-least-once). */
public interface OutboxPublisher {

    ApiFuture<String> publish(OutboxEvent event);
}
