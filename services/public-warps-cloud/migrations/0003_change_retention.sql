INSERT INTO metadata(key,value) SELECT 'changes_floor',
  COALESCE((SELECT MIN(revision)-1 FROM changes),(SELECT value FROM metadata WHERE key='revision'));
