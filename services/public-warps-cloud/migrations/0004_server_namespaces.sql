ALTER TABLE contributors ADD COLUMN role TEXT NOT NULL DEFAULT 'contributor'
  CHECK(role IN ('contributor','trusted'));

CREATE TABLE server_namespaces (
  id TEXT PRIMARY KEY CHECK(id IN ('catbud','catrealm')),
  name TEXT NOT NULL,
  revision INTEGER NOT NULL DEFAULT 0,
  changes_floor INTEGER NOT NULL DEFAULT 0
);
INSERT INTO server_namespaces VALUES
  ('catbud','貓芽',(SELECT value FROM metadata WHERE key='revision'),
   (SELECT value FROM metadata WHERE key='changes_floor')),
  ('catrealm','貓域',0,0);

CREATE TABLE contributor_servers (
  contributor TEXT NOT NULL REFERENCES contributors(id),
  server_id TEXT NOT NULL REFERENCES server_namespaces(id),
  PRIMARY KEY(contributor,server_id)
);
INSERT INTO contributor_servers SELECT id,'catbud' FROM contributors;

CREATE TABLE server_warps (
  server_id TEXT NOT NULL REFERENCES server_namespaces(id),
  uuid TEXT NOT NULL,
  name TEXT NOT NULL,
  published INTEGER NOT NULL DEFAULT 0 CHECK(published IN (0,1)),
  first_valid_at INTEGER NOT NULL DEFAULT 0,
  first_valid_source TEXT,
  pass_state TEXT NOT NULL DEFAULT 'unknown',
  availability TEXT NOT NULL DEFAULT 'unknown',
  checked_at INTEGER NOT NULL DEFAULT 0,
  next_check INTEGER NOT NULL DEFAULT 0,
  verification_lease INTEGER NOT NULL DEFAULT 0,
  snapshot_hash TEXT REFERENCES previews(hash),
  previous_hash TEXT REFERENCES previews(hash),
  previous_until INTEGER NOT NULL DEFAULT 0,
  preview_at INTEGER NOT NULL DEFAULT 0,
  display_name TEXT,
  description TEXT,
  tags TEXT NOT NULL DEFAULT '[]',
  revision INTEGER NOT NULL DEFAULT 0,
  last_success INTEGER NOT NULL DEFAULT 0,
  last_failure INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY(server_id,uuid)
);
INSERT INTO server_warps
SELECT 'catbud',uuid,name,published,
  CASE WHEN published=1 THEN MAX(1,checked_at,last_success) ELSE last_success END,
  CASE WHEN published=1 THEN 'legacy_admin' ELSE NULL END,
  pass_state,availability,checked_at,next_check,verification_lease,
  snapshot_hash,previous_hash,previous_until,preview_at,display_name,description,
  tags,revision,last_success,last_failure FROM warps;
CREATE INDEX server_warps_due ON server_warps(next_check,verification_lease);
CREATE INDEX server_warps_snapshot ON server_warps(server_id,snapshot_hash,published);

CREATE TABLE server_submissions (
  id TEXT PRIMARY KEY,
  server_id TEXT NOT NULL,
  uuid TEXT NOT NULL,
  contributor TEXT NOT NULL REFERENCES contributors(id),
  hash TEXT REFERENCES previews(hash),
  kind TEXT NOT NULL CHECK(kind IN ('discovery','preview','launch','arrival','failure','metadata')),
  payload TEXT NOT NULL DEFAULT '{}',
  base_revision INTEGER NOT NULL DEFAULT 0,
  state TEXT NOT NULL DEFAULT 'pending' CHECK(state IN ('pending','published','rejected','expired')),
  reason TEXT,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  FOREIGN KEY(server_id,uuid) REFERENCES server_warps(server_id,uuid)
);
CREATE UNIQUE INDEX server_submission_actor ON server_submissions(server_id,uuid,contributor)
  WHERE state='pending';
CREATE INDEX server_submission_queue ON server_submissions(server_id,state,created_at,id);
CREATE INDEX server_submission_hash ON server_submissions(hash);
INSERT INTO server_submissions
SELECT lower(hex(randomblob(16))),'catbud',s.uuid,s.contributor,s.hash,s.kind,'{}',
  w.revision,'pending',NULL,s.created_at,s.expires_at
FROM submissions s JOIN warps w ON w.uuid=s.uuid;

CREATE TABLE server_upload_tickets (
  id TEXT PRIMARY KEY,
  server_id TEXT NOT NULL,
  uuid TEXT NOT NULL,
  contributor TEXT NOT NULL REFERENCES contributors(id),
  hash TEXT NOT NULL,
  size INTEGER NOT NULL CHECK(size BETWEEN 1 AND 65536),
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  consumed INTEGER NOT NULL DEFAULT 0 CHECK(consumed IN (0,1)),
  result_hash TEXT,
  submission_id TEXT,
  FOREIGN KEY(server_id,uuid) REFERENCES server_warps(server_id,uuid),
  UNIQUE(server_id,uuid,hash,contributor)
);
CREATE INDEX server_ticket_pending ON server_upload_tickets(server_id,uuid,consumed,expires_at);
INSERT INTO server_upload_tickets
SELECT id,'catbud',uuid,contributor,hash,size,created_at,expires_at,consumed,result_hash,NULL
FROM upload_tickets;

CREATE TABLE server_changes (
  server_id TEXT NOT NULL REFERENCES server_namespaces(id),
  revision INTEGER NOT NULL,
  uuid TEXT NOT NULL,
  deleted INTEGER NOT NULL CHECK(deleted IN (0,1)),
  created_at INTEGER NOT NULL,
  PRIMARY KEY(server_id,revision)
);
INSERT INTO server_changes SELECT 'catbud',revision,uuid,deleted,created_at FROM changes;
CREATE INDEX server_changes_expiry ON server_changes(created_at);

CREATE TABLE server_audit (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  server_id TEXT,
  uuid TEXT,
  submission_id TEXT,
  actor TEXT NOT NULL,
  action TEXT NOT NULL,
  before_revision INTEGER,
  after_revision INTEGER,
  reason TEXT,
  created_at INTEGER NOT NULL
);
INSERT INTO server_audit(server_id,uuid,actor,action,created_at)
SELECT 'catbud',uuid,'legacy_admin',action,created_at FROM audit;

CREATE TRIGGER server_warps_changed AFTER UPDATE ON server_warps
WHEN NEW.published!=OLD.published OR (NEW.published=1 AND (
  NEW.name!=OLD.name OR NEW.pass_state!=OLD.pass_state OR NEW.availability!=OLD.availability
  OR NEW.snapshot_hash IS NOT OLD.snapshot_hash OR NEW.display_name IS NOT OLD.display_name
  OR NEW.description IS NOT OLD.description OR NEW.tags!=OLD.tags
  OR NEW.last_success!=OLD.last_success OR NEW.last_failure!=OLD.last_failure)) BEGIN
  UPDATE server_namespaces SET revision=revision+1 WHERE id=NEW.server_id;
  INSERT INTO server_changes SELECT NEW.server_id,revision,NEW.uuid,1-NEW.published,
    CAST(strftime('%s','now') AS INTEGER)*1000
    FROM server_namespaces WHERE id=NEW.server_id;
  UPDATE server_warps SET revision=revision+1
    WHERE server_id=NEW.server_id AND uuid=NEW.uuid;
END;

CREATE TRIGGER server_tickets_reserved AFTER INSERT ON server_upload_tickets
WHEN NEW.consumed=0 BEGIN
  UPDATE metadata SET value=value+NEW.size WHERE key='reserved_bytes';
END;
CREATE TRIGGER server_tickets_consumed AFTER UPDATE OF consumed ON server_upload_tickets
WHEN OLD.consumed=0 AND NEW.consumed=1 BEGIN
  UPDATE metadata SET value=value-OLD.size WHERE key='reserved_bytes';
END;
CREATE TRIGGER server_tickets_removed AFTER DELETE ON server_upload_tickets
WHEN OLD.consumed=0 BEGIN
  UPDATE metadata SET value=value-OLD.size WHERE key='reserved_bytes';
END;
