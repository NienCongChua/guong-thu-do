import { authorize, verifyIdentity } from './auth.mjs';
import { ApiError, errorResponse, json, secure, sqlError, unix, uuidPattern } from './http.mjs';
import { capabilities, generateImage, mintLiveToken, verifyAssetUrl } from './providers.mjs';
import { enqueue, inputKey, inviteStillActive, ownedJob, publicJob, rateLimit, removeImages, resultKey } from './store.mjs';

const MAX_FILE = 6 * 1024 * 1024;
const MAX_UPLOAD = 13 * 1024 * 1024;

function retention(env) {
  const days = Number(env.RESULT_RETENTION_DAYS || 7);
  return (Number.isInteger(days) && days >= 1 && days <= 30 ? days : 7) * 86400;
}
function requestKey(request) {
  const key = request.headers.get('idempotency-key');
  if (!uuidPattern.test(key || '')) throw new ApiError(400, 'IDEMPOTENCY_REQUIRED', 'Yêu cầu thiếu mã thao tác hợp lệ.');
  return key;
}
async function readUpload(request) {
  const type = request.headers.get('content-type') || '';
  if (!type.startsWith('multipart/form-data;')) throw new ApiError(415, 'INVALID_UPLOAD', 'Cần gửi hai tệp ảnh.');
  if (Number(request.headers.get('content-length')) > MAX_UPLOAD)
    throw new ApiError(413, 'IMAGE_TOO_LARGE', 'Tối đa 6 MB mỗi ảnh.');
  let size = 0;
  const limited = request.body?.pipeThrough(new TransformStream({
    transform(chunk, controller) {
      size += chunk.length;
      if (size > MAX_UPLOAD) throw new ApiError(413, 'IMAGE_TOO_LARGE', 'Tối đa 6 MB mỗi ảnh.');
      controller.enqueue(chunk);
    }
  }));
  if (!limited) throw new ApiError(400, 'INVALID_UPLOAD', 'Chưa nhận được ảnh.');
  let form;
  try { form = await new Response(limited, { headers: { 'Content-Type': type } }).formData(); }
  catch (error) {
    if (error instanceof ApiError) throw error;
    throw new ApiError(400, 'INVALID_UPLOAD', 'Dữ liệu ảnh gửi lên chưa hợp lệ.');
  }
  const allowed = ['person', 'garment', 'kind', 'quality', 'allowFallback'];
  for (const key of form.keys()) {
    if (!allowed.includes(key) || form.getAll(key).length !== 1)
      throw new ApiError(400, 'INVALID_UPLOAD', 'Dữ liệu ảnh gửi lên chưa hợp lệ.');
  }
  const kind = form.get('kind');
  const quality = form.get('quality');
  const fallback = form.get('allowFallback');
  if (!['top', 'dress'].includes(kind) || !['fast', 'detail'].includes(quality) || !['true', 'false'].includes(fallback))
    throw new ApiError(400, 'INVALID_OPTIONS', 'Tùy chọn mặc thử không hợp lệ.');
  const images = {};
  for (const name of ['person', 'garment']) {
    const file = form.get(name);
    if (!file || typeof file.arrayBuffer !== 'function' || file.size < 12 ||
        file.size > MAX_FILE || !['image/png', 'image/jpeg', 'image/webp'].includes(file.type))
      throw new ApiError(400, 'INVALID_IMAGES', 'Cần hai ảnh JPEG/PNG/WebP, tối đa 6 MB mỗi ảnh.');
    const bytes = new Uint8Array(await file.arrayBuffer());
    const signature = file.type === 'image/jpeg' ? bytes[0] === 255 && bytes[1] === 216 :
      file.type === 'image/png' ? [137,80,78,71,13,10,26,10].every((n, i) => bytes[i] === n) :
      new TextDecoder().decode(bytes.subarray(0,4)) === 'RIFF' && new TextDecoder().decode(bytes.subarray(8,12)) === 'WEBP';
    if (!signature) throw new ApiError(400, 'INVALID_IMAGES', 'Nội dung ảnh không khớp định dạng.');
    images[name] = { bytes, type: file.type };
  }
  const metadata = new TextEncoder().encode(JSON.stringify([kind, quality, fallback, images.person.type, images.garment.type, images.person.bytes.length, images.garment.bytes.length]));
  const combined = new Uint8Array(metadata.length + images.person.bytes.length + images.garment.bytes.length);
  combined.set(metadata);
  combined.set(images.person.bytes, metadata.length);
  combined.set(images.garment.bytes, metadata.length + images.person.bytes.length);
  const hash = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', combined)), b => b.toString(16).padStart(2, '0')).join('');
  return { images, kind, quality, allowFallback: fallback === 'true', hash };
}
function repeatedJob(row, hash) {
  if (row.input_hash !== hash) throw new ApiError(409, 'IDEMPOTENCY_CONFLICT', 'Mã thao tác đã được dùng với ảnh hoặc tùy chọn khác.');
  return json({ job: publicJob(row) }, 202);
}
async function createJob(request, env, identity) {
  if (!capabilities(env).photo) throw new ApiError(503, 'PHOTO_UNAVAILABLE', 'Dịch vụ ảnh hiện chưa được bật.');
  await rateLimit(env.DB, identity.uid, 'upload', 6);
  const key = requestKey(request);
  const upload = await readUpload(request);
  const prior = await env.DB.prepare('SELECT * FROM jobs WHERE uid=? AND request_key=?').bind(identity.uid, key).first();
  if (prior) return repeatedJob(prior, upload.hash);
  const id = crypto.randomUUID();
  const now = unix();
  let job;
  try {
    job = await env.DB.prepare(
      "INSERT INTO jobs(id,uid,request_key,input_hash,status,kind,quality,allow_fallback,created_at,updated_at,expires_at) VALUES (?,?,?,?,'uploading',?,?,?,?,?,?) RETURNING *"
    ).bind(id, identity.uid, key, upload.hash, upload.kind, upload.quality, Number(upload.allowFallback),
      now, now, now + retention(env)).first();
  } catch (error) {
    const repeated = await env.DB.prepare('SELECT * FROM jobs WHERE uid=? AND request_key=?').bind(identity.uid, key).first();
    if (repeated) return repeatedJob(repeated, upload.hash);
    throw sqlError(error);
  }
  try {
    for (const name of ['person', 'garment']) {
      await env.IMAGES.put(inputKey(id, name), upload.images[name].bytes, {
        httpMetadata: { contentType: upload.images[name].type }
      });
    }
    job = await env.DB.prepare(
      "UPDATE jobs SET status='queued',updated_at=? WHERE id=? AND status='uploading' RETURNING *"
    ).bind(unix(), id).first();
    if (!job) {
      await removeImages(env, id, true);
      throw new ApiError(409, 'JOB_CANCELLED', 'Tác vụ đã được hủy.');
    }
  } catch (error) {
    await env.DB.prepare("UPDATE jobs SET status='failed',error_code='UPLOAD_FAILED',updated_at=? WHERE id=? AND status='uploading'")
      .bind(unix(), id).run();
    await removeImages(env, id).catch(() => {});
    throw error;
  }
  // D1 is also an outbox: cron re-enqueues queued rows when send or the response
  // is interrupted. The queue consumer atomically claims each row only once.
  try { await enqueue(env, job); } catch { /* Scheduled recovery owns retries. */ }
  return json({ job: publicJob(job) }, 202);
}
async function deleteAccount(env, identity) {
  const now = unix();
  await env.DB.batch([
    env.DB.prepare('UPDATE invites SET enabled=0,tokens_valid_after=? WHERE uid=?').bind(now, identity.uid),
    env.DB.prepare("UPDATE jobs SET status='deleted',updated_at=? WHERE uid=?").bind(now, identity.uid)
  ]);
  const { results } = await env.DB.prepare('SELECT id FROM jobs WHERE uid=?').bind(identity.uid).all();
  for (let i = 0; i < results.length; i += 100) {
    await env.IMAGES.delete(results.slice(i, i + 100).flatMap(row =>
      [inputKey(row.id, 'person'), inputKey(row.id, 'garment'), resultKey(row.id)]));
  }
  const prefix = identity.uid + ':';
  await env.DB.batch([
    env.DB.prepare('DELETE FROM jobs WHERE uid=?').bind(identity.uid),
    env.DB.prepare('DELETE FROM live_requests WHERE uid=?').bind(identity.uid),
    env.DB.prepare('DELETE FROM daily_usage WHERE scope=?').bind('user:' + identity.uid),
    env.DB.prepare('DELETE FROM rate_buckets WHERE substr(key,1,?)=?').bind(prefix.length, prefix),
    env.DB.prepare('DELETE FROM invites WHERE uid=?').bind(identity.uid)
  ]);
  return new Response(null, { status: 204 });
}

export function createWorker({ verifyToken, generate = generateImage, mint = mintLiveToken } = {}) {
  async function fetchHandler(request, env) {
    const requestId = crypto.randomUUID();
    try {
      const url = new URL(request.url);
      if (url.protocol !== 'https:' && !['localhost', '127.0.0.1', '[::1]'].includes(url.hostname))
        throw new ApiError(400, 'HTTPS_REQUIRED', 'Kết nối an toàn HTTPS là bắt buộc.');
      if (url.pathname === '/health' && request.method === 'GET') return secure(json({ ok: true, service: 'guong-thu-do' }), requestId);
      if (!env.DB || !env.IMAGES || !env.IMAGE_QUEUE) throw new ApiError(503, 'NOT_CONFIGURED', 'Dịch vụ chưa sẵn sàng.');
      const asset = /^\/provider-assets\/([0-9a-f-]{36})\/(person|garment)$/.exec(url.pathname);
      if (asset && request.method === 'GET') {
        const [, id, kind] = asset;
        if (!uuidPattern.test(id) || !await verifyAssetUrl(env, id, kind, url))
          throw new ApiError(404, 'NOT_FOUND', 'Không tìm thấy ảnh.');
        const job = await env.DB.prepare("SELECT uid FROM jobs WHERE id=? AND status='processing'").bind(id).first();
        if (!job || !await inviteStillActive(env, job.uid)) throw new ApiError(404, 'NOT_FOUND', 'Không tìm thấy ảnh.');
        const object = await env.IMAGES.get(inputKey(id, kind));
        if (!object) throw new ApiError(404, 'NOT_FOUND', 'Không tìm thấy ảnh.');
        return secure(new Response(object.body, { headers: { 'Content-Type': object.httpMetadata?.contentType || 'image/png' } }), requestId);
      }
      if (!url.pathname.startsWith('/api/')) throw new ApiError(404, 'NOT_FOUND', 'Không tìm thấy chức năng.');
      if (url.pathname === '/api/account' && request.method === 'DELETE') {
        // Even a revoked beta member can erase their own data.
        const identity = await verifyIdentity(request, env, verifyToken);
        await rateLimit(env.DB, identity.uid, 'delete-account', 3);
        return secure(await deleteAccount(env, identity), requestId);
      }
      const identity = await authorize(request, env, verifyToken);
      await rateLimit(env.DB, identity.uid, 'api', 90);
      if (request.method === 'GET' && url.pathname === '/api/capabilities') return secure(json(capabilities(env)), requestId);
      if (request.method === 'GET' && url.pathname === '/api/me') {
        const { results: usage } = await env.DB.prepare(
          "SELECT feature,count FROM daily_usage WHERE scope=? AND day=date(?,'unixepoch')"
        ).bind('user:' + identity.uid, unix()).all();
        const active = await env.DB.prepare(
          "SELECT * FROM jobs WHERE uid=? AND status IN ('uploading','queued','processing') ORDER BY created_at DESC LIMIT 1"
        ).bind(identity.uid).first();
        return secure(json({
          uid: identity.uid, email: identity.email, inviteOnly: true,
          quota: {
            imagesLimit: identity.invite.image_daily_limit, imagesUsed: usage.find(row => row.feature === 'image')?.count || 0,
            liveLimit: identity.invite.live_daily_limit, liveUsed: usage.find(row => row.feature === 'live')?.count || 0
          }, activeJob: active ? publicJob(active) : null
        }), requestId);
      }
      if (url.pathname === '/api/jobs') {
        if (request.method === 'POST') return secure(await createJob(request, env, identity), requestId);
        if (request.method === 'GET') {
          const { results } = await env.DB.prepare(
            "SELECT * FROM jobs WHERE uid=? AND status NOT IN ('deleted','expired') AND expires_at>? ORDER BY created_at DESC,id DESC LIMIT 20"
          ).bind(identity.uid, unix()).all();
          return secure(json({ jobs: results.map(publicJob) }), requestId);
        }
      }
      const byRequest = /^\/api\/jobs\/by-request\/([0-9a-f-]{36})$/.exec(url.pathname);
      if (byRequest && request.method === 'GET' && uuidPattern.test(byRequest[1])) {
        const row = await env.DB.prepare('SELECT * FROM jobs WHERE uid=? AND request_key=?')
          .bind(identity.uid, byRequest[1]).first();
        if (!row) throw new ApiError(404, 'JOB_NOT_FOUND', 'Chưa tìm thấy tác vụ đã gửi.');
        return secure(json({ job: publicJob(row) }), requestId);
      }
      const route = /^\/api\/jobs\/([0-9a-f-]{36})(?:\/(result|cancel))?$/.exec(url.pathname);
      if (route && uuidPattern.test(route[1])) {
        const [, id, action] = route;
        const job = await ownedJob(env, identity.uid, id);
        if (request.method === 'GET' && !action) return secure(json({ job: publicJob(job) }), requestId);
        if (request.method === 'GET' && action === 'result') {
          if (job.status !== 'completed' || job.expires_at <= unix()) throw new ApiError(410, 'RESULT_UNAVAILABLE', 'Kết quả chưa sẵn sàng hoặc đã hết hạn.');
          const object = await env.IMAGES.get(resultKey(id));
          if (!object) throw new ApiError(410, 'RESULT_UNAVAILABLE', 'Ảnh kết quả đã được xóa.');
          return secure(new Response(object.body, { headers: {
            'Content-Type': 'image/png', 'Content-Disposition': 'attachment; filename="try-on.png"',
            'X-Image-Provider': job.provider || '', 'X-Image-Fallback': String(!!job.fallback_used)
          } }), requestId);
        }
        if (request.method === 'POST' && action === 'cancel') {
          await env.DB.prepare(
            "UPDATE jobs SET status='cancelled',updated_at=? WHERE id=? AND status IN ('uploading','queued','processing')"
          ).bind(unix(), id).run();
          return secure(json({ job: publicJob(await ownedJob(env, identity.uid, id)) }), requestId);
        }
        if (request.method === 'DELETE' && !action) {
          await env.DB.prepare("UPDATE jobs SET status='deleted',updated_at=? WHERE id=?").bind(unix(), id).run();
          await removeImages(env, id, true);
          await env.DB.prepare('UPDATE jobs SET inputs_deleted_at=?,result_deleted_at=? WHERE id=?').bind(unix(), unix(), id).run();
          return secure(new Response(null, { status: 204 }), requestId);
        }
      }
      if (url.pathname === '/api/realtime-token' && request.method === 'POST') {
        if (!capabilities(env).decart) throw new ApiError(503, 'LIVE_UNAVAILABLE', 'Gương trực tiếp chưa được bật.');
        await rateLimit(env.DB, identity.uid, 'live', 3);
        const key = requestKey(request);
        const prior = await env.DB.prepare('SELECT id FROM live_requests WHERE uid=? AND request_key=?').bind(identity.uid, key).first();
        if (prior) throw new ApiError(409, 'LIVE_ALREADY_REQUESTED', 'Phiên đã được yêu cầu. Không tự cấp lại token để tránh mở trùng phiên.');
        const id = crypto.randomUUID();
        try {
          await env.DB.prepare('INSERT INTO live_requests(id,uid,request_key,created_at) VALUES (?,?,?,?)')
            .bind(id, identity.uid, key, unix()).run();
        } catch (error) { throw sqlError(error); }
        try {
          const token = await mint(env);
          await env.DB.prepare("UPDATE live_requests SET state='issued' WHERE id=?").bind(id).run();
          return secure(json(token), requestId);
        } catch (error) {
          await env.DB.prepare("UPDATE live_requests SET state='failed' WHERE id=?").bind(id).run();
          throw error;
        }
      }
      throw new ApiError(404, 'NOT_FOUND', 'Không tìm thấy chức năng.');
    } catch (error) { return errorResponse(error, requestId); }
  }

  async function queueHandler(batch, env) {
    for (const message of batch.messages) {
      const id = message.body?.jobId;
      if (!uuidPattern.test(id || '')) { message.ack(); continue; }
      try {
        const job = await env.DB.prepare(
          "UPDATE jobs SET status='processing',updated_at=?,attempt_started_at=? WHERE id=? AND status='queued' RETURNING *"
        ).bind(unix(), unix(), id).first();
        if (!job) {
          // At-least-once delivery is never permission to repeat a paid request.
          message.ack(); continue;
        }
        if (job.expires_at <= unix() || !await inviteStillActive(env, job.uid)) {
          await env.DB.prepare("UPDATE jobs SET status='cancelled',error_code='ACCESS_REVOKED',updated_at=? WHERE id=? AND status='processing'")
            .bind(unix(), id).run();
        } else {
          try {
            const result = await generate(job, env);
            const latest = await env.DB.prepare('SELECT status FROM jobs WHERE id=?').bind(id).first();
            if (latest?.status === 'processing' && await inviteStillActive(env, job.uid)) {
              await env.IMAGES.put(resultKey(id), result.image, {
                httpMetadata: { contentType: 'image/png' },
                customMetadata: { provider: result.provider, fallbackUsed: String(result.fallbackUsed), elapsedMs: String(result.elapsedMs) }
              });
              const completed = await env.DB.prepare(
                "UPDATE jobs SET status='completed',provider=?,fallback_used=?,elapsed_ms=?,updated_at=? WHERE id=? AND status='processing' RETURNING id"
              ).bind(result.provider, Number(result.fallbackUsed), result.elapsedMs, unix(), id).first();
              if (!completed) await env.IMAGES.delete(resultKey(id));
            }
          } catch (error) {
            // Never send the provider again if execution or result persistence failed.
            // A stored result can be recovered by cron after an isolate interruption.
            const stored = await env.IMAGES.head(resultKey(id));
            if (!stored) {
              await env.DB.prepare("UPDATE jobs SET status='failed',error_code=?,updated_at=? WHERE id=? AND status='processing'")
                .bind(error instanceof ApiError ? error.code : 'PROCESSING_INTERRUPTED', unix(), id).run();
            }
          }
        }
        const final = await env.DB.prepare('SELECT status FROM jobs WHERE id=?').bind(id).first();
        const eraseOutput = final && ['failed','cancelled','deleted','expired'].includes(final.status);
        await removeImages(env, id, !!eraseOutput);
        await env.DB.prepare(
          'UPDATE jobs SET inputs_deleted_at=?,result_deleted_at=CASE WHEN ?=1 THEN ? ELSE result_deleted_at END WHERE id=?'
        ).bind(unix(), Number(!!eraseOutput), unix(), id).run();
        message.ack();
      } catch {
        // The next delivery can claim only a still-queued job. A processing row
        // is deliberately not reset to queued; cron reconciles it without billing again.
        message.retry({ delaySeconds: 30 });
      }
    }
  }

  async function scheduledHandler(_event, env) {
    const now = unix();
    const { results: pending } = await env.DB.prepare(
      "SELECT * FROM jobs WHERE status='queued' AND (enqueued_at IS NULL OR enqueued_at<?) LIMIT 25"
    ).bind(now - 120).all();
    for (const job of pending) {
      if (job.created_at < now - 900) {
        await env.DB.prepare("UPDATE jobs SET status='failed',error_code='QUEUE_TIMEOUT',updated_at=? WHERE id=? AND status='queued'").bind(now, job.id).run();
      } else await enqueue(env, job);
    }
    const { results: interrupted } = await env.DB.prepare(
      "SELECT * FROM jobs WHERE status IN ('uploading','processing') AND updated_at<? LIMIT 25"
    ).bind(now - 600).all();
    for (const job of interrupted) {
      const stored = job.status === 'processing' ? await env.IMAGES.head(resultKey(job.id)) : null;
      if (stored && await inviteStillActive(env, job.uid)) {
        const meta = stored.customMetadata || {};
        await env.DB.prepare(
          "UPDATE jobs SET status='completed',provider=?,fallback_used=?,elapsed_ms=?,updated_at=? WHERE id=? AND status='processing'"
        ).bind(meta.provider || '', Number(meta.fallbackUsed === 'true'), Number(meta.elapsedMs) || 0, now, job.id).run();
      } else {
        await env.DB.prepare(
          "UPDATE jobs SET status='failed',error_code='PROCESSING_INTERRUPTED',updated_at=? WHERE id=? AND status IN ('uploading','processing')"
        ).bind(now, job.id).run();
        if (stored) await env.IMAGES.delete(resultKey(job.id));
      }
    }
    const { results: expired } = await env.DB.prepare(
      "SELECT id FROM jobs WHERE expires_at<=? AND status NOT IN ('expired','deleted') LIMIT 50"
    ).bind(now).all();
    for (const job of expired) {
      await env.DB.prepare("UPDATE jobs SET status='expired',updated_at=? WHERE id=?").bind(now, job.id).run();
      await removeImages(env, job.id, true);
      await env.DB.prepare('UPDATE jobs SET inputs_deleted_at=?,result_deleted_at=? WHERE id=?').bind(now, now, job.id).run();
    }
    const { results: clean } = await env.DB.prepare(
      "SELECT id,status FROM jobs WHERE status IN ('completed','failed','cancelled','deleted','expired') AND (inputs_deleted_at IS NULL OR (status!='completed' AND result_deleted_at IS NULL)) LIMIT 50"
    ).all();
    for (const job of clean) {
      await removeImages(env, job.id, job.status !== 'completed');
      await env.DB.prepare(
        'UPDATE jobs SET inputs_deleted_at=?,result_deleted_at=CASE WHEN ?=1 THEN ? ELSE result_deleted_at END WHERE id=?'
      ).bind(now, Number(job.status !== 'completed'), now, job.id).run();
    }
    await env.DB.batch([
      env.DB.prepare('DELETE FROM rate_buckets WHERE expires_at<?').bind(now),
      env.DB.prepare("DELETE FROM daily_usage WHERE day<date(?,'unixepoch','-90 days')").bind(now),
      env.DB.prepare("DELETE FROM live_requests WHERE created_at<?").bind(now - 90 * 86400),
      env.DB.prepare("DELETE FROM jobs WHERE status IN ('deleted','expired') AND inputs_deleted_at IS NOT NULL AND result_deleted_at IS NOT NULL AND expires_at<?")
        .bind(now - 30 * 86400)
    ]);
  }
  return { fetch: fetchHandler, queue: queueHandler, scheduled: scheduledHandler };
}

export default createWorker();
