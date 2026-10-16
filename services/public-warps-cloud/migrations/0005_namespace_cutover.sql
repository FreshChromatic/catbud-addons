DROP TRIGGER tickets_reserved;
DROP TRIGGER tickets_consumed;
DROP TRIGGER tickets_removed;
DROP TRIGGER warps_changed;

DELETE FROM server_upload_tickets WHERE consumed=1 AND submission_id IS NULL;
UPDATE metadata SET value=COALESCE(
  (SELECT SUM(size) FROM server_upload_tickets WHERE consumed=0),0)
  WHERE key='reserved_bytes';
UPDATE metadata SET value=COALESCE((SELECT SUM(size) FROM previews),0)
  WHERE key='stored_bytes';

CREATE INDEX server_warps_previous_expiry ON server_warps(previous_until)
  WHERE previous_hash IS NOT NULL;
CREATE INDEX server_submissions_expiry ON server_submissions(state,expires_at);
CREATE INDEX server_tickets_expiry ON server_upload_tickets(expires_at);
CREATE INDEX server_audit_expiry ON server_audit(created_at);
