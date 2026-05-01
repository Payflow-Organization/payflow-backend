package com.payflow.domain.model.event;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

@Table(name = "processed_events")
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessedEvent {

    @Getter
    @EmbeddedId
    private ProcessedEventId id;

    @Column(nullable = false, updatable = false)
    private Instant processedAt;

    @Embeddable
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProcessedEventId implements Serializable {
        @Column(name = "event_id", updatable = false)
        private UUID eventId;

        @Column(name = "consumer_group", updatable = false)
        private String consumerGroup;
    }
}
