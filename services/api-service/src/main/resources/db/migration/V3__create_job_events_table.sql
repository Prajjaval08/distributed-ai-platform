CREATE TABLE job_events (
    id              UUID PRIMARY KEY,
    job_id          UUID NOT NULL,
    event_type      VARCHAR(50) NOT NULL,
    metadata        JSONB,
    created_at      TIMESTAMP NOT NULL DEFAULT now(),

    CONSTRAINT fk_job_events_job FOREIGN KEY (job_id) REFERENCES jobs (id)
);