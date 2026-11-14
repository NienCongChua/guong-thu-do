ALTER TABLE jobs ADD COLUMN result_deleted_at INTEGER;
CREATE INDEX jobs_input_cleanup ON jobs(inputs_deleted_at,status) WHERE inputs_deleted_at IS NULL;
CREATE INDEX jobs_result_cleanup ON jobs(result_deleted_at,status) WHERE result_deleted_at IS NULL;
