-- Intelligent Login Monitoring (ILM) - PostgreSQL schema
-- Run once as the database owner:  psql -U ilm -d ilm -f db/schema.sql
-- Timestamps are stored as local time (WIB), the same as the source logs.

CREATE TABLE IF NOT EXISTS app_user (
    user_id       varchar(100) PRIMARY KEY,
    display_name  varchar(200),
    email         varchar(200),
    role          varchar(20)  NOT NULL CHECK (role IN ('ANALYST','ADMIN','MANAGER','AUDITOR')),
    auth_source   varchar(10)  NOT NULL DEFAULT 'AD' CHECK (auth_source IN ('AD','LOCAL')),
    password_hash varchar(300),
    active        boolean      NOT NULL DEFAULT true,
    created_at    timestamp    NOT NULL DEFAULT now(),
    created_by    varchar(100),
    last_login_at timestamp
);

CREATE TABLE IF NOT EXISTS upload_batch (
    id          bigserial PRIMARY KEY,
    origin      varchar(10)  NOT NULL CHECK (origin IN ('UPLOAD','REALTIME')),
    source_kind varchar(20)  NOT NULL,
    app_code    varchar(50),
    file_name   varchar(255),
    file_sha256 char(64),
    uploaded_by varchar(100) NOT NULL,
    uploaded_at timestamp    NOT NULL DEFAULT now(),
    accepted    int NOT NULL DEFAULT 0,
    duplicates  int NOT NULL DEFAULT 0,
    rejected    int NOT NULL DEFAULT 0,
    skipped     int NOT NULL DEFAULT 0
);

-- Standardized login events (FR-06..FR-10). raw_text keeps the original record (FR-05).
CREATE TABLE IF NOT EXISTS login_event (
    id            bigserial PRIMARY KEY,
    event_hash    char(64)     NOT NULL UNIQUE,
    batch_id      bigint       REFERENCES upload_batch(id),
    source_type   varchar(20)  NOT NULL,
    app_code      varchar(50)  NOT NULL,
    user_id       varchar(100) NOT NULL,
    display_name  varchar(200),
    event_time    timestamp    NOT NULL,
    event_type    varchar(30)  NOT NULL,
    auth_result   varchar(10)  NOT NULL,
    ip_address    varchar(64),
    ip_class      varchar(10),
    pc_name       varchar(100),
    endpoint      varchar(200),
    thread_name   varchar(100),
    code_location varchar(150),
    source_ref    varchar(40),
    raw_text      text,
    status        varchar(12)  NOT NULL DEFAULT 'NORMALIZED' CHECK (status IN ('NORMALIZED','SCORED','ARCHIVED')),
    created_at    timestamp    NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_event_time      ON login_event (event_time, id);
CREATE INDEX IF NOT EXISTS ix_event_user_time ON login_event (user_id, event_time);
CREATE INDEX IF NOT EXISTS ix_event_pending   ON login_event (event_time, id) WHERE status = 'NORMALIZED';

-- Records that could not be processed (FR-04, BR-05).
CREATE TABLE IF NOT EXISTS event_error (
    id         bigserial PRIMARY KEY,
    batch_id   bigint REFERENCES upload_batch(id),
    line_no    int,
    raw_text   text,
    message    varchar(300),
    created_at timestamp NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS user_profile (
    user_id       varchar(100) PRIMARY KEY,
    first_seen    timestamp,
    last_seen     timestamp,
    total_logins  int NOT NULL DEFAULT 0,
    failed_logins int NOT NULL DEFAULT 0,
    active_days   int NOT NULL DEFAULT 0,
    hour_hist     varchar(300),
    dow_hist      varchar(100),
    apps          varchar(1000),
    updated_at    timestamp NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS ml_model (
    version       serial PRIMARY KEY,
    model_type    varchar(40) NOT NULL,
    trained_at    timestamp   NOT NULL DEFAULT now(),
    trained_by    varchar(100),
    sample_count  int,
    tree_count    int,
    sample_size   int,
    feature_names text,
    model_data    text        NOT NULL,
    active        boolean     NOT NULL DEFAULT false
);

CREATE TABLE IF NOT EXISTS risk_assessment (
    id            bigserial PRIMARY KEY,
    event_id      bigint NOT NULL UNIQUE REFERENCES login_event(id),
    anomaly_score double precision,
    ml_points     int NOT NULL DEFAULT 0,
    rule_points   int NOT NULL DEFAULT 0,
    risk_score    int NOT NULL CHECK (risk_score BETWEEN 0 AND 100),
    risk_level    varchar(10) NOT NULL CHECK (risk_level IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    factors       text,
    model_version int,
    assessed_at   timestamp NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_risk_level ON risk_assessment (risk_level);

-- One alert groups risky events of the same user and application that happen close together.
CREATE TABLE IF NOT EXISTS alert (
    id               bigserial PRIMARY KEY,
    user_id          varchar(100) NOT NULL,
    app_code         varchar(50)  NOT NULL,
    severity         varchar(10)  NOT NULL CHECK (severity IN ('MEDIUM','HIGH','CRITICAL','LOW')),
    status           varchar(12)  NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','IN_PROGRESS','CLOSED')),
    first_event_time timestamp    NOT NULL,
    last_event_time  timestamp    NOT NULL,
    event_count      int          NOT NULL DEFAULT 1,
    max_risk_score   int          NOT NULL,
    top_factor       varchar(500),
    created_at       timestamp    NOT NULL DEFAULT now(),
    closed_at        timestamp,
    closed_by        varchar(100),
    resolution       varchar(20) CHECK (resolution IN ('FALSE_POSITIVE','CONFIRMED_RISK','DUPLICATE','NOT_RELEVANT')),
    email_sent       boolean      NOT NULL DEFAULT false
);
CREATE INDEX IF NOT EXISTS ix_alert_status ON alert (status, max_risk_score DESC);
CREATE INDEX IF NOT EXISTS ix_alert_user   ON alert (user_id, app_code, last_event_time);

CREATE TABLE IF NOT EXISTS alert_event (
    alert_id bigint NOT NULL REFERENCES alert(id),
    event_id bigint NOT NULL UNIQUE REFERENCES login_event(id),
    PRIMARY KEY (alert_id, event_id)
);

CREATE TABLE IF NOT EXISTS investigation (
    id         bigserial PRIMARY KEY,
    alert_id   bigint NOT NULL UNIQUE REFERENCES alert(id),
    analyst    varchar(100) NOT NULL,
    status     varchar(24)  NOT NULL DEFAULT 'UNDER_INVESTIGATION'
               CHECK (status IN ('UNDER_INVESTIGATION','ESCALATED','CLOSED')),
    result     varchar(20) CHECK (result IN ('FALSE_POSITIVE','CONFIRMED_RISK')),
    created_at timestamp NOT NULL DEFAULT now(),
    closed_at  timestamp
);

CREATE TABLE IF NOT EXISTS investigation_note (
    id         bigserial PRIMARY KEY,
    alert_id   bigint NOT NULL REFERENCES alert(id),
    author     varchar(100) NOT NULL,
    note       text NOT NULL,
    created_at timestamp NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS app_setting (
    key        varchar(80) PRIMARY KEY,
    value      varchar(1000) NOT NULL,
    updated_by varchar(100),
    updated_at timestamp NOT NULL DEFAULT now()
);

-- Who did what in this application (NFR-S03, BR-08). Rows are never updated or deleted by the app.
CREATE TABLE IF NOT EXISTS audit_trail (
    id      bigserial PRIMARY KEY,
    at      timestamp NOT NULL DEFAULT now(),
    actor   varchar(100) NOT NULL,
    action  varchar(40)  NOT NULL,
    target  varchar(200),
    detail  text,
    ip      varchar(64)
);
CREATE INDEX IF NOT EXISTS ix_audit_at ON audit_trail (at DESC);

CREATE TABLE IF NOT EXISTS ingest_checkpoint (
    source_key varchar(300) PRIMARY KEY,
    position   bigint NOT NULL,
    updated_at timestamp NOT NULL DEFAULT now()
);
