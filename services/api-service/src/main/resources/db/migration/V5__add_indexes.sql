-- Job history pagination: "give me this user's jobs, newest first"
CREATE INDEX idx_jobs_user_created ON jobs (user_id, created_at DESC);

-- Worker/operator queries by state: "give me all QUEUED jobs, oldest first"
CREATE INDEX idx_jobs_status_created ON jobs (status, created_at);

-- Priority scheduling queries: "give me HIGH priority QUEUED jobs"
CREATE INDEX idx_jobs_priority_status ON jobs (priority, status);

-- Timeline reconstruction for one job's full event history
CREATE INDEX idx_job_events_job_created ON job_events (job_id, created_at);