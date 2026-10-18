CREATE TABLE addon_state (
    id INTEGER PRIMARY KEY CHECK (id=1),
    revision INTEGER NOT NULL DEFAULT 0,
    checked_at INTEGER NOT NULL DEFAULT 0,
    next_check INTEGER NOT NULL DEFAULT 0,
    lease_until INTEGER NOT NULL DEFAULT 0,
    sync_error TEXT NOT NULL DEFAULT ''
);
INSERT INTO addon_state(id) VALUES(1);
CREATE TABLE addon_releases (
    id TEXT PRIMARY KEY,
    version TEXT NOT NULL,
    envelope TEXT NOT NULL,
    source_snapshot TEXT NOT NULL,
    imported_at INTEGER NOT NULL,
    verified_at INTEGER NOT NULL,
    validation_error TEXT NOT NULL DEFAULT ''
);
CREATE TABLE addon_release_channels (
    minecraft TEXT NOT NULL,
    channel TEXT NOT NULL CHECK(channel='stable'),
    release_id TEXT NOT NULL REFERENCES addon_releases(id),
    PRIMARY KEY(minecraft,channel)
);
CREATE TABLE addon_notices (
    id TEXT PRIMARY KEY,
    revision INTEGER NOT NULL,
    state TEXT NOT NULL CHECK(state IN ('draft','published','withdrawn')),
    content TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    starts_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL
);
CREATE INDEX addon_notices_active ON addon_notices(state,starts_at,expires_at);
CREATE TABLE addon_operations (
    request_id TEXT PRIMARY KEY,
    actor TEXT NOT NULL,
    request_hash TEXT NOT NULL,
    action TEXT NOT NULL,
    result TEXT NOT NULL,
    created_at INTEGER NOT NULL
);
CREATE TABLE addon_guards (
    id TEXT PRIMARY KEY,
    valid INTEGER NOT NULL CHECK(valid=1)
);
