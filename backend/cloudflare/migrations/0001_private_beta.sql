PRAGMA foreign_keys = ON;

CREATE TABLE invites (
  email TEXT PRIMARY KEY COLLATE NOCASE,
  uid TEXT UNIQUE,
  enabled INTEGER NOT NULL DEFAULT 1 CHECK(enabled IN (0,1)),
  image_daily_limit INTEGER NOT NULL DEFAULT 6 CHECK(image_daily_limit BETWEEN 0 AND 100),
  live_daily_limit INTEGER NOT NULL DEFAULT 3 CHECK(live_daily_limit BETWEEN 0 AND 100),
  expires_at INTEGER,
  tokens_valid_after INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL DEFAULT (unixepoch())
);
CREATE TABLE settings (key TEXT PRIMARY KEY, value INTEGER NOT NULL);
INSERT INTO settings VALUES ('global_image_daily_limit', 50), ('global_live_daily_limit', 30);

CREATE TABLE daily_usage (
  scope TEXT NOT NULL, day TEXT NOT NULL, feature TEXT NOT NULL,
  count INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY(scope, day, feature)
);

CREATE TABLE jobs (
  id TEXT PRIMARY KEY,
  uid TEXT NOT NULL,
  request_key TEXT NOT NULL,
  input_hash TEXT NOT NULL,
  status TEXT NOT NULL CHECK(status IN ('uploading','queued','processing','completed','failed','cancelled','deleted','expired')),
  kind TEXT NOT NULL CHECK(kind IN ('top','dress')),
  quality TEXT NOT NULL CHECK(quality IN ('fast','detail')),
  allow_fallback INTEGER NOT NULL CHECK(allow_fallback IN (0,1)),
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  enqueued_at INTEGER,
  attempt_started_at INTEGER,
  provider TEXT,
  fallback_used INTEGER NOT NULL DEFAULT 0,
  elapsed_ms INTEGER,
  error_code TEXT,
  inputs_deleted_at INTEGER,
  UNIQUE(uid, request_key)
);
CREATE INDEX jobs_owner_created ON jobs(uid, created_at DESC);
CREATE INDEX jobs_status_updated ON jobs(status, updated_at);
CREATE INDEX jobs_expiry ON jobs(expires_at);

CREATE TRIGGER reserve_image BEFORE INSERT ON jobs BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM invites WHERE uid=NEW.uid AND enabled=1 AND (expires_at IS NULL OR expires_at>NEW.created_at)
  ) THEN RAISE(ABORT, 'INVITE_REQUIRED') END;
  SELECT CASE WHEN EXISTS (
    SELECT 1 FROM jobs WHERE uid=NEW.uid AND status IN ('uploading','queued','processing')
  ) THEN RAISE(ABORT, 'IMAGE_BUSY') END;
  SELECT CASE WHEN COALESCE((
    SELECT count FROM daily_usage WHERE scope='user:'||NEW.uid AND feature='image'
      AND day=date(NEW.created_at, 'unixepoch')
  ),0) >= (SELECT image_daily_limit FROM invites WHERE uid=NEW.uid)
    THEN RAISE(ABORT, 'DAILY_LIMIT') END;
  SELECT CASE WHEN COALESCE((
    SELECT count FROM daily_usage WHERE scope='global' AND feature='image'
      AND day=date(NEW.created_at, 'unixepoch')
  ),0) >= COALESCE((SELECT value FROM settings WHERE key='global_image_daily_limit'),0)
    THEN RAISE(ABORT, 'GLOBAL_LIMIT') END;
END;

CREATE TRIGGER count_image AFTER INSERT ON jobs BEGIN
  INSERT INTO daily_usage VALUES ('user:'||NEW.uid, date(NEW.created_at,'unixepoch'), 'image', 1)
    ON CONFLICT(scope,day,feature) DO UPDATE SET count=count+1;
  INSERT INTO daily_usage VALUES ('global', date(NEW.created_at,'unixepoch'), 'image', 1)
    ON CONFLICT(scope,day,feature) DO UPDATE SET count=count+1;
END;

CREATE TABLE live_requests (
  id TEXT PRIMARY KEY, uid TEXT NOT NULL, request_key TEXT NOT NULL,
  state TEXT NOT NULL DEFAULT 'reserved', created_at INTEGER NOT NULL,
  UNIQUE(uid, request_key)
);
CREATE INDEX live_owner_created ON live_requests(uid, created_at DESC);
CREATE TRIGGER reserve_live BEFORE INSERT ON live_requests BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM invites WHERE uid=NEW.uid AND enabled=1 AND (expires_at IS NULL OR expires_at>NEW.created_at)
  ) THEN RAISE(ABORT, 'INVITE_REQUIRED') END;
  SELECT CASE WHEN EXISTS (
    SELECT 1 FROM live_requests WHERE uid=NEW.uid AND created_at>NEW.created_at-180
  ) THEN RAISE(ABORT, 'LIVE_COOLDOWN') END;
  SELECT CASE WHEN COALESCE((
    SELECT count FROM daily_usage WHERE scope='user:'||NEW.uid AND feature='live'
      AND day=date(NEW.created_at,'unixepoch')
  ),0) >= (SELECT live_daily_limit FROM invites WHERE uid=NEW.uid)
    THEN RAISE(ABORT, 'DAILY_LIMIT') END;
  SELECT CASE WHEN COALESCE((
    SELECT count FROM daily_usage WHERE scope='global' AND feature='live'
      AND day=date(NEW.created_at,'unixepoch')
  ),0) >= COALESCE((SELECT value FROM settings WHERE key='global_live_daily_limit'),0)
    THEN RAISE(ABORT, 'GLOBAL_LIMIT') END;
END;
CREATE TRIGGER count_live AFTER INSERT ON live_requests BEGIN
  INSERT INTO daily_usage VALUES ('user:'||NEW.uid, date(NEW.created_at,'unixepoch'), 'live', 1)
    ON CONFLICT(scope,day,feature) DO UPDATE SET count=count+1;
  INSERT INTO daily_usage VALUES ('global', date(NEW.created_at,'unixepoch'), 'live', 1)
    ON CONFLICT(scope,day,feature) DO UPDATE SET count=count+1;
END;

CREATE TABLE rate_buckets (
  key TEXT PRIMARY KEY, count INTEGER NOT NULL, expires_at INTEGER NOT NULL
);
