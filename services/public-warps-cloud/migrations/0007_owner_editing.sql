CREATE TABLE owner_challenges (
  id TEXT PRIMARY KEY,
  server_id TEXT NOT NULL,
  uuid TEXT NOT NULL,
  name TEXT NOT NULL,
  server_hash TEXT NOT NULL,
  proof_hash TEXT NOT NULL,
  expires_at INTEGER NOT NULL,
  consumed INTEGER NOT NULL DEFAULT 0 CHECK(consumed IN (0,1)),
  FOREIGN KEY(server_id,uuid) REFERENCES server_warps(server_id,uuid) ON DELETE CASCADE
);
CREATE INDEX owner_challenges_expiry ON owner_challenges(expires_at);
CREATE TABLE owner_sessions (
  token_hash TEXT PRIMARY KEY,
  server_id TEXT NOT NULL,
  uuid TEXT NOT NULL,
  expires_at INTEGER NOT NULL,
  FOREIGN KEY(server_id,uuid) REFERENCES server_warps(server_id,uuid) ON DELETE CASCADE
);
CREATE INDEX owner_sessions_expiry ON owner_sessions(expires_at);
