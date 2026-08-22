package com.distributedai.worker.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "job_events")
@Getter
@Setter
@NoArgsConstructor
public class JobEvent {

    @Id
    private UUID id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @org.hibernate.annotations.Type(io.hypersistence.utils.hibernate.type.json.JsonType.class)
    @Column(columnDefinition = "jsonb")
    private java.util.Map<String, Object> metadata;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        createdAt = LocalDateTime.now();
    }
}
