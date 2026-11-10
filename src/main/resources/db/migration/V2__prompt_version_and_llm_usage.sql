ALTER TABLE reviews ADD COLUMN prompt_version TEXT;

-- Model requests per day, so the daily cap survives restarts (Render and Neon both sleep).
CREATE TABLE llm_usage (
    day      DATE    PRIMARY KEY,
    requests INTEGER NOT NULL
);
