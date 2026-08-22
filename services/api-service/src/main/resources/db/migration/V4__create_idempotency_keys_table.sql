CREATE TABLE idempotency_keys (
    key             VARCHAR(255) PRIMARY KEY,
    user_id         UUID NOT NULL,
    job_id          UUID NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    expires_at      TIMESTAMP NOT NULL,

    CONSTRAINT fk_idempotency_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_idempotency_job FOREIGN KEY (job_id) REFERENCES jobs (id)
);