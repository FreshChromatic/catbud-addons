PRAGMA foreign_keys=ON;

CREATE TABLE contributors (
  id TEXT PRIMARY KEY,
  token_hash TEXT NOT NULL UNIQUE,
  enabled INTEGER NOT NULL DEFAULT 1 CHECK(enabled IN (0,1)),
  created_at INTEGER NOT NULL
);

CREATE TABLE warps (
  uuid TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  published INTEGER NOT NULL DEFAULT 0 CHECK(published IN (0,1)),
  pass_state TEXT NOT NULL DEFAULT 'unknown',
  availability TEXT NOT NULL DEFAULT 'unknown',
  checked_at INTEGER NOT NULL DEFAULT 0,
  next_check INTEGER NOT NULL DEFAULT 0,
  verification_lease INTEGER NOT NULL DEFAULT 0,
  snapshot_hash TEXT,
  previous_hash TEXT,
  previous_until INTEGER NOT NULL DEFAULT 0,
  preview_at INTEGER NOT NULL DEFAULT 0,
  display_name TEXT,
  description TEXT,
  tags TEXT NOT NULL DEFAULT '[]',
  revision INTEGER NOT NULL DEFAULT 0,
  last_success INTEGER NOT NULL DEFAULT 0,
  last_failure INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX warps_due ON warps(published,next_check,verification_lease);

CREATE TABLE previews (
  hash TEXT PRIMARY KEY,
  bytes BLOB NOT NULL,
  size INTEGER NOT NULL CHECK(size BETWEEN 1 AND 65536),
  created_at INTEGER NOT NULL
);

CREATE TABLE submissions (
  uuid TEXT PRIMARY KEY REFERENCES warps(uuid),
  contributor TEXT NOT NULL REFERENCES contributors(id),
  hash TEXT REFERENCES previews(hash),
  kind TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE INDEX submissions_expiry ON submissions(expires_at);

CREATE TABLE upload_tickets (
  id TEXT PRIMARY KEY,
  uuid TEXT NOT NULL REFERENCES warps(uuid),
  contributor TEXT NOT NULL REFERENCES contributors(id),
  hash TEXT NOT NULL,
  size INTEGER NOT NULL CHECK(size BETWEEN 1 AND 65536),
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  consumed INTEGER NOT NULL DEFAULT 0 CHECK(consumed IN (0,1)),
  result_hash TEXT,
  UNIQUE(uuid,hash,contributor)
);
CREATE INDEX tickets_expiry ON upload_tickets(expires_at);

CREATE TABLE budgets (
  key TEXT PRIMARY KEY,
  used INTEGER NOT NULL CHECK(used>=0),
  expires_at INTEGER NOT NULL
);

CREATE TABLE metadata (key TEXT PRIMARY KEY, value INTEGER NOT NULL);
INSERT INTO metadata VALUES ('revision',0),('stored_bytes',0),('reserved_bytes',0);

CREATE TABLE changes (
  revision INTEGER PRIMARY KEY AUTOINCREMENT,
  uuid TEXT NOT NULL,
  deleted INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL
);
CREATE INDEX changes_expiry ON changes(created_at);

CREATE TABLE audit (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  uuid TEXT,
  action TEXT NOT NULL,
  created_at INTEGER NOT NULL
);

CREATE TABLE availability_reports (
  uuid TEXT PRIMARY KEY REFERENCES warps(uuid),
  contributor TEXT NOT NULL REFERENCES contributors(id),
  reason TEXT NOT NULL,
  created_at INTEGER NOT NULL
);

CREATE TRIGGER previews_added AFTER INSERT ON previews BEGIN
  UPDATE metadata SET value=value+NEW.size WHERE key='stored_bytes';
END;
CREATE TRIGGER previews_removed AFTER DELETE ON previews BEGIN
  UPDATE metadata SET value=value-OLD.size WHERE key='stored_bytes';
END;
CREATE TRIGGER tickets_reserved AFTER INSERT ON upload_tickets WHEN NEW.consumed=0 BEGIN
  UPDATE metadata SET value=value+NEW.size WHERE key='reserved_bytes';
END;
CREATE TRIGGER tickets_consumed AFTER UPDATE OF consumed ON upload_tickets WHEN OLD.consumed=0 AND NEW.consumed=1 BEGIN
  UPDATE metadata SET value=value-OLD.size WHERE key='reserved_bytes';
END;
CREATE TRIGGER tickets_removed AFTER DELETE ON upload_tickets WHEN OLD.consumed=0 BEGIN
  UPDATE metadata SET value=value-OLD.size WHERE key='reserved_bytes';
END;

CREATE TRIGGER warps_changed AFTER UPDATE ON warps
WHEN NEW.published!=OLD.published OR (NEW.published=1 AND (
  NEW.name!=OLD.name OR NEW.pass_state!=OLD.pass_state OR NEW.availability!=OLD.availability
  OR NEW.snapshot_hash IS NOT OLD.snapshot_hash OR NEW.display_name IS NOT OLD.display_name
  OR NEW.description IS NOT OLD.description OR NEW.tags!=OLD.tags)) BEGIN
  INSERT INTO changes(uuid,deleted,created_at) VALUES (NEW.uuid,1-NEW.published,CAST(strftime('%s','now') AS INTEGER)*1000);
  UPDATE metadata SET value=value+1 WHERE key='revision';
  UPDATE warps SET revision=revision+1 WHERE uuid=NEW.uuid;
END;
