CREATE TABLE review_guards (
  id TEXT PRIMARY KEY,
  valid INTEGER NOT NULL CHECK(valid=1)
);
CREATE INDEX warps_snapshot ON warps(snapshot_hash,published);
CREATE INDEX warps_previous ON warps(previous_hash);
CREATE INDEX submissions_hash ON submissions(hash);
