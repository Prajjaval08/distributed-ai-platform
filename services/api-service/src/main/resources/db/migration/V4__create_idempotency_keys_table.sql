CREATE TABLE idempotency_keys (
    key             VARCHAR(255) NOT NULL,
    user_id         UUID NOT NULL,
    job_id          UUID NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    expires_at      TIMESTAMP NOT NULL,

    -- Same key string may be reused by different users; uniqueness is per user
    CONSTRAINT pk_idempotency_keys PRIMARY KEY (user_id, key),
    CONSTRAINT fk_idempotency_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_idempotency_job FOREIGN KEY (job_id) REFERENCES jobs (id)
);