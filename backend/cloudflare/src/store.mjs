import { ApiError, unix } from './http.mjs';

export const inputKey = (id, kind) => 'inputs/' + id + '/' + kind;
export const resultKey = id => 'results/' + id + '.png';
export async function removeImages(env, id, includeResult = false) {
  await env.IMAGES.delete([inputKey(id, 'person'), inputKey(id, 'garment'), ...(includeResult ? [resultKey(id)] : [])]);
}
export async function rateLimit(db, uid, action, limit, seconds = 60) {
  const now = unix();
  const bucket = Math.floor(now / seconds);
  const row = await db.prepare(
    'INSERT INTO rate_buckets(key,count,expires_at) VALUES (?,1,?) ON CONFLICT(key) DO UPDATE SET count=count+1 WHERE count<? RETURNING count'
  ).bind(uid + ':' + action + ':' + bucket, (bucket + 2) * seconds, limit).first();
  if (!row) throw new ApiError(429, 'RATE_LIMIT', 'Bạn thao tác quá nhanh. Hãy chờ một phút rồi thử lại.');
}
export function publicJob(row) {
  return {
    id: row.id, status: row.status, createdAt: row.created_at, expiresAt: row.expires_at,
    provider: row.provider, fallbackUsed: !!row.fallback_used, elapsedMs: row.elapsed_ms,
    errorCode: row.error_code,
    resultPath: row.status === 'completed' ? '/api/jobs/' + row.id + '/result' : null
  };
}
export async function ownedJob(env, uid, id) {
  const row = await env.DB.prepare('SELECT * FROM jobs WHERE id=? AND uid=?').bind(id, uid).first();
  if (!row) throw new ApiError(404, 'JOB_NOT_FOUND', 'Không tìm thấy lần thử này.');
  return row;
}
export async function enqueue(env, job) {
  await env.IMAGE_QUEUE.send({ jobId: job.id });
  await env.DB.prepare("UPDATE jobs SET enqueued_at=? WHERE id=? AND status='queued'").bind(unix(), job.id).run();
}
export async function inviteStillActive(env, uid) {
  return env.DB.prepare(
    'SELECT uid FROM invites WHERE uid=? AND enabled=1 AND (expires_at IS NULL OR expires_at>?)'
  ).bind(uid, unix()).first();
}
