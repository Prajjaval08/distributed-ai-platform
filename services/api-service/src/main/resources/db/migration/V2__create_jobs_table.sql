CREATE TABLE jobs (
    id              UUID PRIMARY KEY,
    user_id         UUID NOT NULL,
    prompt          TEXT NOT NULL,
    model           VARCHAR(100) NOT NULL,
    status          VARCHAR(20) NOT NULL,
    priority        VARCHAR(10) NOT NULL,
    retry_count     INT NOT NULL DEFAULT 0,
    result_url      TEXT,
    error_code      VARCHAR(50),
    error_message   TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    started_at      TIMESTAMP,
    completed_at    TIMESTAMP,

    CONSTRAINT fk_jobs_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT chk_jobs_status CHECK (status IN ('CREATED','QUEUED','PROCESSING','COMPLETED','FAILED','CANCELLED')),
    CONSTRAINT chk_jobs_priority CHECK (priority IN ('HIGH','NORMAL','LOW')),
    CONSTRAINT chk_jobs_retry_count CHECK (retry_count >= 0)
);