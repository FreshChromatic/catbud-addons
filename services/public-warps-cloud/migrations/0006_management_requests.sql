ALTER TABLE server_audit ADD COLUMN request_id TEXT;
ALTER TABLE server_audit ADD COLUMN source_role TEXT;
CREATE UNIQUE INDEX server_audit_request ON server_audit(actor,request_id)
  WHERE request_id IS NOT NULL;
