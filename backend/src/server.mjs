import express from 'express';
import multer from 'multer';
import { createHash, randomBytes, timingSafeEqual } from 'node:crypto';
import { DatabaseSync } from 'node:sqlite';
import { pathToFileURL } from 'node:url';

import { ApiError, createImageService } from './image-providers.mjs';
export { IMAGE_PROMPT } from './image-providers.mjs';
export const LIVE_MODEL = 'lucy-vton-3.6';
const hash = value => createHash('sha256').update(value).digest();
const int = (value, fallback, max) => {
  const raw = value ?? fallback;
  const n = Number(raw);
  if (n === 0) return 0; // 0 = bỏ hạn mức hằng ngày
  if (!Number.isInteger(n) || n < 1 || n > max) throw new Error('Biến giới hạn không hợp lệ');
  return n;
};

export function createApp(config = process.env, { fetchImpl = fetch, mintToken, ...imageOptions } = {}) {
  const password = config.DEMO_PASSWORD ?? '';
  if (password.length < 12) throw new Error('Hãy đặt DEMO_PASSWORD ít nhất 12 ký tự trên server.');
  const decartKey = config.DECART_API_KEY?.trim();
  const images = createImageService(config, { fetchImpl, ...imageOptions });
  const imageLimit = int(config.DAILY_IMAGE_LIMIT, 6, 1000);
  const liveLimit = int(config.DAILY_LIVE_TOKEN_LIMIT, 10, 1000);
  const maxSeconds = int(config.LIVE_MAX_SECONDS, 120, 600);
  const db = new DatabaseSync(config.USAGE_DB || './usage.sqlite');
  db.exec('CREATE TABLE IF NOT EXISTS usage (day TEXT, feature TEXT, count INTEGER, PRIMARY KEY(day, feature))');
  const sessions = new Map();
  const rates = new Map();
  let imageBusy = false;
  const app = express();
  app.disable('x-powered-by');
  // Keep trust proxy disabled; do not trust arbitrary X-Forwarded-For headers.
  app.use((_req, res, next) => { res.set('Cache-Control', 'no-store'); next(); });
  app.use(express.json({ limit: '4kb' }));
  const rate = (name, limit, windowMs) => (req, _res, next) => {
    const now = Date.now();
    for (const [key, item] of rates) if (item.until <= now) rates.delete(key);
    const key = name + ':' + req.ip;
    const item = rates.get(key) || { count: 0, until: now + windowMs };
    item.count++; rates.set(key, item);
    if (item.count > limit) {
      _res.set('Retry-After', String(Math.max(1, Math.ceil((item.until - now) / 1000))));
      return next(new ApiError(429, 'RATE_LIMIT', 'Quá nhiều yêu cầu. Hãy đợi một phút rồi thử lại.'));
    }
    next();
  };
  const requireAuth = (req, _res, next) => {
    const token = req.get('authorization')?.replace(/^Bearer /, '');
    const session = sessions.get(token);
    if (!session || session.expiresAt <= Date.now())
      return next(new ApiError(401, 'AUTH_EXPIRED', 'Phiên đăng nhập đã hết hạn. Hãy kết nối lại trong mục Kết nối.'));
    next();
  };
  const reserve = feature => {
    const day = new Date().toISOString().slice(0, 10);
    db.exec('BEGIN IMMEDIATE');
    try {
      const count = db.prepare('SELECT count FROM usage WHERE day=? AND feature=?').get(day, feature)?.count || 0;
      const limit = feature === 'image' ? imageLimit : liveLimit;
      if (limit !== 0 && count >= limit) throw new ApiError(429, 'DAILY_LIMIT', 'Đã hết hạn mức demo hôm nay cho chế độ này (ngày UTC).');
      db.prepare('INSERT INTO usage VALUES (?, ?, 1) ON CONFLICT(day, feature) DO UPDATE SET count=count+1').run(day, feature);
      db.exec('COMMIT');
    } catch (e) { db.exec('ROLLBACK'); throw e; }
  };
  app.get('/health', (_req, res) => res.json({ ok: true }));
  app.post('/api/login', rate('login', 5, 60000), (req, res, next) => {
    if (typeof req.body?.password !== 'string' || !timingSafeEqual(hash(req.body.password), hash(password)))
      return next(new ApiError(401, 'BAD_PASSWORD', 'Mật khẩu demo không đúng.'));
    for (const [key, session] of sessions) if (session.expiresAt <= Date.now()) sessions.delete(key);
    if (sessions.size >= 30) return next(new ApiError(429, 'LOGIN_LIMIT', 'Có quá nhiều phiên đăng nhập demo.'));
    const token = randomBytes(32).toString('base64url');
    const expiresAt = Date.now() + 8 * 60 * 60 * 1000;
    sessions.set(token, { expiresAt });
    res.json({ token, expiresAt });
  });
  app.use('/api', requireAuth, rate('api', 30, 60000));
  app.get('/api/capabilities', (_req, res) => res.json({
    decart: !!decartKey, liveModel: LIVE_MODEL,
    ...images.capabilities(), maxSessionSeconds: maxSeconds
  }));
  app.post('/api/realtime-token', rate('live', 3, 60000), async (_req, res) => {
    if (!decartKey) throw new ApiError(503, 'DECART_NOT_CONFIGURED', 'Server chưa cấu hình DECART_API_KEY. Bạn có thể dùng Thử bằng ảnh nếu đã cấu hình OpenAI.');
    reserve('live');
    const options = {
      expiresIn: 60,
      allowedModels: [LIVE_MODEL],
      constraints: { realtime: { maxSessionDuration: maxSeconds } }
    };
    try {
      const create = mintToken || (async opts => {
        const { createDecartClient } = await import('@decartai/sdk');
        return createDecartClient({ apiKey: decartKey }).tokens.create(opts);
      });
      const token = await Promise.race([
        create(options),
        new Promise((_, reject) => { const t = setTimeout(() => reject(new ApiError(504, 'DECART_TIMEOUT', 'Decart không phản hồi cấp token. Hãy thử lại.')), 20000); t.unref(); })
      ]);
      if (!token.apiKey || !token.expiresAt) throw new Error('Invalid token');
      res.json({ apiKey: token.apiKey, expiresAt: token.expiresAt, maxSessionSeconds: maxSeconds });
    } catch (e) {
      if (e instanceof ApiError) throw e;
      const status = e?.status || e?.data?.status;
      if (status === 401 || status === 403) throw new ApiError(502, 'DECART_ACCESS', 'Decart từ chối key hoặc quyền dùng model. Kiểm tra key và tài khoản trên server.');
      if (status === 429 || status === 402) throw new ApiError(429, 'DECART_QUOTA', 'Decart đã hết hạn mức, số dư hoặc phiên đồng thời.');
      throw new ApiError(502, 'DECART_UPSTREAM', 'Không cấp được token Decart. Kiểm tra mạng, key và quyền model trên server.');
    }
  });
  const upload = multer({
    storage: multer.memoryStorage(),
    limits: { fileSize: 12 * 1024 * 1024, files: 2, fields: 3, parts: 5, fieldSize: 30 },
    fileFilter: (_req, file, cb) => cb(null, ['image/jpeg', 'image/png', 'image/webp'].includes(file.mimetype))
  }).fields([{ name: 'person', maxCount: 1 }, { name: 'garment', maxCount: 1 }]);
  // Reserve the memory/concurrency slot BEFORE parsing multipart bodies.
  const imageSlot = (_req, res, next) => {
    if (!images.capabilities().photo) return next(new ApiError(503, 'OPENAI_NOT_CONFIGURED', 'Server chưa cấu hình OPENAI_API_KEY hoặc FASHN_API_KEY.'));
    if (imageBusy) return next(new ApiError(429, 'IMAGE_BUSY', 'Server đang tạo một ảnh khác. Hãy chờ rồi thử lại.'));
    imageBusy = true;
    let released = false;
    const release = () => { if (!released) { released = true; imageBusy = false; } };
    res.locals.releaseImageSlot = release;
    res.on('finish', release);
    res.on('close', () => { if (!res.locals.imageStarted) release(); });
    next();
  };
  app.post('/api/try-on-photo', rate('image', 2, 60000), imageSlot, upload, async (req, res) => {
    const person = req.files?.person?.[0];
    const garment = req.files?.garment?.[0];
    const kind = req.body?.kind;
    if (!person || !garment || !['top', 'dress'].includes(kind))
      throw new ApiError(400, 'INVALID_IMAGES', 'Cần ảnh người, ảnh trang phục JPEG/PNG/WebP và loại áo hoặc váy.');
    for (const file of [person, garment]) {
      const b = file.buffer;
      const valid = file.mimetype === 'image/jpeg' ? b[0] === 255 && b[1] === 216 :
        file.mimetype === 'image/png' ? b.subarray(0,8).equals(Buffer.from([137,80,78,71,13,10,26,10])) :
        b.subarray(0,4).toString() === 'RIFF' && b.subarray(8,12).toString() === 'WEBP';
      if (!valid) throw new ApiError(400, 'INVALID_IMAGE_DATA', 'Dữ liệu ảnh không khớp định dạng.');
    }
    const quality = req.body?.quality ?? 'detail'; // Old clients retain the original quality.
    const fallback = req.body?.allowFallback ?? 'false';
    if (!['fast', 'detail'].includes(quality) || !['true', 'false'].includes(fallback))
      throw new ApiError(400, 'INVALID_OPTIONS', 'Chế độ tạo ảnh hoặc tùy chọn dự phòng không hợp lệ.');
    reserve('image'); // One user action; at most one explicitly permitted fallback.
    res.locals.imageStarted = true;
    const abort = new AbortController();
    const onClose = () => { if (!res.writableEnded) abort.abort(); };
    res.on('close', onClose);
    try {
      const result = await images.edit({
        person, garment, kind, quality, allowFallback: fallback === 'true', signal: abort.signal
      });
      if (abort.signal.aborted || res.destroyed) return;
      res.set('X-Image-Provider', result.provider);
      res.set('X-Image-Fallback', String(result.fallbackUsed));
      res.set('Server-Timing', 'image;dur=' + result.elapsedMs);
      res.type('png').send(result.image);
    } finally {
      res.off('close', onClose);
      res.locals.releaseImageSlot();
    }
  });
  app.use((err, _req, res, _next) => {
    if (res.destroyed || res.headersSent) return;
    const status = err instanceof ApiError ? err.status : err instanceof multer.MulterError ? 413 : err.status === 400 ? 400 : 500;
    res.status(status).json({ code: err.code || 'SERVER_ERROR',
      error: err instanceof ApiError ? err.message : status === 413 ? 'Ảnh quá lớn hoặc số lượng ảnh không hợp lệ (tối đa 12 MB/ảnh).' : 'Yêu cầu không hợp lệ hoặc server gặp lỗi.' });
  });
  return { app, close: () => db.close() };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const { app, close } = createApp();
  const server = app.listen(Number(process.env.PORT || 8787), process.env.HOST || '127.0.0.1',
    () => console.log('Gương Thử Đồ backend sẵn sàng trên cổng ' + (process.env.PORT || 8787)));
  server.requestTimeout = 200000;
  for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => server.close(() => { close(); process.exit(0); }));
}
