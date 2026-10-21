CREATE TABLE maintenance_state (
  key TEXT PRIMARY KEY,
  next_run INTEGER NOT NULL DEFAULT 0,
  cursor TEXT NOT NULL DEFAULT ''
);
INSERT INTO maintenance_state(key) VALUES ('server_cleanup');

CREATE INDEX server_warps_current_hash ON server_warps(snapshot_hash);
CREATE INDEX server_warps_previous_hash ON server_warps(previous_hash);
CREATE INDEX server_tickets_submission ON server_upload_tickets(submission_id);
CREATE INDEX server_submissions_retention ON server_submissions(expires_at);
CREATE INDEX budgets_expiry ON budgets(expires_at);
