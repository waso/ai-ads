-- Google Ads Autopilot: system of record for proposals, changes and audit history.
-- Google's own change history is only a cross-check (30 days, may omit changes).
--
-- Flyway runs as the schema owner. The application connects as ${app_role}, which must already exist
-- (created by ops). It gets full access to working tables but only SELECT and INSERT on the
-- append-only tables, so a bug or a compromised run cannot rewrite history.

-- Owner-set caps and allowlists, versioned. A new row is written whenever server configuration changes.
CREATE TABLE config_limits (
    version     bigserial   PRIMARY KEY,
    created_at  timestamptz NOT NULL DEFAULT now(),
    limits      jsonb       NOT NULL
);

-- What Claude optimizes for, versioned like the limits.
CREATE TABLE goals (
    version     bigserial   PRIMARY KEY,
    created_at  timestamptz NOT NULL DEFAULT now(),
    goals       jsonb       NOT NULL
);

-- Kill switch, circuit breaker and read pause. Exactly one row.
CREATE TABLE control_state (
    id               int         PRIMARY KEY CHECK (id = 1),
    kill_switch      boolean     NOT NULL DEFAULT false,
    kill_reason      text,
    breaker_open     boolean     NOT NULL DEFAULT false,
    breaker_since    timestamptz,
    breaker_paused   jsonb       NOT NULL DEFAULT '[]'::jsonb,
    reads_paused     boolean     NOT NULL DEFAULT false,
    updated_at       timestamptz NOT NULL DEFAULT now(),
    updated_by       text
);
INSERT INTO control_state (id) VALUES (1);

-- One row per scheduled or chat run. slot_key makes a scheduled loop's window one-shot.
CREATE TABLE agent_runs (
    id              uuid        PRIMARY KEY,
    loop            text        NOT NULL,
    prompt_version  text        NOT NULL,
    slot_key        text,
    started_at      timestamptz NOT NULL,
    expires_at      timestamptz NOT NULL,
    ended_at        timestamptz,
    checked         text,
    did             text,
    next_focus      text
);
CREATE UNIQUE INDEX agent_runs_slot ON agent_runs (slot_key) WHERE slot_key IS NOT NULL;
CREATE INDEX agent_runs_started ON agent_runs (started_at);

-- Every requested change that needs the owner (or was filed instead of applied).
CREATE TABLE proposals (
    id               uuid        PRIMARY KEY,
    run_id           uuid        NOT NULL,
    tool             text        NOT NULL,
    tier             text        NOT NULL,
    status           text        NOT NULL,   -- PENDING, APPLIED, REJECTED, EXPIRED, STALE, FAILED
    request          jsonb       NOT NULL,
    meta             jsonb       NOT NULL,
    diff             jsonb       NOT NULL,
    state_hash       text        NOT NULL,
    why_proposed     text        NOT NULL,
    is_rollback      boolean     NOT NULL DEFAULT false,
    fingerprint      text        NOT NULL,
    created_at       timestamptz NOT NULL,
    expires_at       timestamptz NOT NULL,
    decided_at       timestamptz,
    decided_by       text,
    decision_note    text,
    change_id        uuid,
    -- single-use approval link: the nonce is consumed when the owner decides
    nonce            text        NOT NULL,
    nonce_used_at    timestamptz
);
CREATE INDEX proposals_status ON proposals (status, created_at);
CREATE INDEX proposals_fingerprint ON proposals (fingerprint) WHERE status = 'PENDING';

-- Every change sent to Google. Append-only.
CREATE TABLE changes (
    id              uuid        PRIMARY KEY,
    proposal_id     uuid,
    run_id          uuid,
    tool            text        NOT NULL,
    tier            text        NOT NULL,
    entity_key      text,
    fingerprint     text        NOT NULL DEFAULT '',
    units           int         NOT NULL DEFAULT 1,
    actor           text        NOT NULL,      -- claude | watchdog | owner-approved
    approver        text,
    request         jsonb       NOT NULL,
    old_values      jsonb       NOT NULL,
    new_values      jsonb       NOT NULL,
    api_request_id  text,
    resources       jsonb       NOT NULL DEFAULT '[]'::jsonb,
    result          text        NOT NULL,      -- APPLIED, FAILED
    error           text,
    created_at      timestamptz NOT NULL
);
CREATE INDEX changes_entity ON changes (entity_key, created_at);
CREATE INDEX changes_created ON changes (created_at);
CREATE INDEX changes_fingerprint ON changes (fingerprint, created_at);

-- Every attempt, including refused ones. Append-only.
CREATE TABLE audit_events (
    id              bigserial   PRIMARY KEY,
    at              timestamptz NOT NULL,
    event           text        NOT NULL,      -- ATTEMPT, REFUSED, PROPOSED, APPROVED, REJECTED, EXPIRED, APPLIED, FAILED, VERIFY_MISMATCH, BREAKER, ...
    run_id          uuid,
    loop            text,
    tool            text,
    tier            text,
    entity_key      text,
    rule            text,
    message         text,
    reason          text,
    expected_effect text,
    old_values      jsonb,
    new_values      jsonb,
    approver        text,
    api_request_id  text,
    resources       jsonb,
    proposal_id     uuid,
    change_id       uuid,
    idempotency_key text
);
CREATE INDEX audit_events_at ON audit_events (at);
CREATE INDEX audit_events_entity ON audit_events (entity_key, at);

-- Effect of each change after its review window.
CREATE TABLE outcomes (
    id                    uuid        PRIMARY KEY,
    change_id             uuid        NOT NULL,
    review_at             timestamptz NOT NULL,
    baseline              jsonb,
    result                jsonb,
    verdict               text,                 -- EXPECTED, NEUTRAL, WORSE, UNKNOWN
    rollback_proposal_id  uuid,
    reviewed_at           timestamptz
);
CREATE INDEX outcomes_due ON outcomes (review_at) WHERE reviewed_at IS NULL;

-- Watchdog findings. One open alert per (type, entity).
CREATE TABLE alerts (
    id               bigserial   PRIMARY KEY,
    type             text        NOT NULL,
    severity         text        NOT NULL,    -- INFO, WARNING, CRITICAL
    entity_key       text        NOT NULL DEFAULT '',
    message          text        NOT NULL,
    details          jsonb,
    opened_at        timestamptz NOT NULL,
    last_seen_at     timestamptz NOT NULL,
    acknowledged_at  timestamptz,
    resolved_at      timestamptz
);
CREATE UNIQUE INDEX alerts_open ON alerts (type, entity_key) WHERE resolved_at IS NULL;

-- Daily and intraday metrics for baselines and alerts.
CREATE TABLE metric_snapshots (
    date              date             NOT NULL,
    entity_key        text             NOT NULL,
    kind              text             NOT NULL DEFAULT 'DAILY',
    cost_micros       bigint           NOT NULL,
    clicks            bigint           NOT NULL DEFAULT 0,
    impressions       bigint           NOT NULL DEFAULT 0,
    conversions       double precision NOT NULL DEFAULT 0,
    conversion_value  double precision NOT NULL DEFAULT 0,
    taken_at          timestamptz      NOT NULL,
    PRIMARY KEY (date, entity_key, kind)
);

-- Short notes carried between runs. Written by Claude, so read back as untrusted.
CREATE TABLE agent_notes (
    id          bigserial   PRIMARY KEY,
    topic       text        NOT NULL,
    text        text        NOT NULL,
    run_id      uuid,
    created_at  timestamptz NOT NULL,
    expires_at  timestamptz NOT NULL
);

-- Replays of a change call with the same key return the earlier result.
CREATE TABLE idempotency (
    key           text        PRIMARY KEY,
    request_hash  text        NOT NULL,
    outcome       jsonb       NOT NULL,
    created_at    timestamptz NOT NULL
);

-- Google Ads operations used, bucketed by hour, for the sliding 24-hour quota window.
CREATE TABLE quota_usage (
    hour_bucket  timestamptz PRIMARY KEY,
    ops          bigint      NOT NULL DEFAULT 0
);

-- Last heartbeat per source (the watchdog).
CREATE TABLE heartbeats (
    source   text        PRIMARY KEY,
    beat_at  timestamptz NOT NULL,
    details  jsonb
);

-- Changes made outside this system (for example by the owner in the Google Ads interface).
-- They start the cooldown and are never reverted; Claude reads them as the new baseline.
CREATE TABLE external_changes (
    id             bigserial   PRIMARY KEY,
    resource_name  text        NOT NULL,
    entity_key     text,
    at             timestamptz NOT NULL,
    actor          text,
    client_type    text,
    operation      text,
    UNIQUE (resource_name, at, operation)
);
CREATE INDEX external_changes_entity ON external_changes (entity_key, at);

-- Cursor for reconciliation against Google's change history.
CREATE TABLE change_cursor (
    id       int         PRIMARY KEY CHECK (id = 1),
    last_at  timestamptz
);
INSERT INTO change_cursor (id) VALUES (1);

-- Grants. Working tables: full access. Append-only tables: SELECT and INSERT only.
GRANT USAGE ON SCHEMA public TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO ${app_role};
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO ${app_role};
REVOKE UPDATE, DELETE, TRUNCATE ON changes, audit_events FROM ${app_role};
-- Versioned config is also history: new versions are inserted, old ones are never edited.
REVOKE UPDATE, DELETE, TRUNCATE ON config_limits, goals FROM ${app_role};
