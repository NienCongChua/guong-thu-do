import { importX509, jwtVerify } from 'jose';
import { ApiError, boundedJson, unix } from './http.mjs';

const CERTS = 'https://www.googleapis.com/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com';

export function createFirebaseVerifier({ fetchImpl = fetch, now = unix, resolveKey } = {}) {
  let certificates = {};
  let keys = new Map();
  let expiresAt = 0;
  let lastFetchAt = -Infinity;
  let pending;
  async function keyFor({ kid, alg }) {
    if (alg !== 'RS256' || typeof kid !== 'string' || kid.length > 256) throw new Error('JWT_HEADER');
    if (now() >= expiresAt || !certificates[kid]) {
      if (!pending && now() - lastFetchAt >= 30) {
        lastFetchAt = now();
        pending = (async () => {
          const response = await fetchImpl(CERTS, { signal: AbortSignal.timeout(5000), redirect: 'error' });
          if (!response.ok) throw new Error('JWT_CERTIFICATE_UNAVAILABLE');
          const lifetime = Number(response.headers.get('cache-control')?.match(/max-age=(\d+)/)?.[1] || 300);
          certificates = await boundedJson(response, 64 * 1024);
          keys = new Map();
          expiresAt = now() + Math.min(Math.max(lifetime, 30), 21600);
        })().finally(() => { pending = undefined; });
      }
      if (pending) await pending;
    }
    const pem = Object.hasOwn(certificates, kid) ? certificates[kid] : undefined;
    if (now() >= expiresAt || typeof pem !== 'string') throw new Error('JWT_UNKNOWN_KEY');
    if (!keys.has(kid)) keys.set(kid, await importX509(pem, 'RS256'));
    return keys.get(kid);
  }
  return async (token, projectId) => {
    if (!/^[a-z][a-z0-9-]{4,28}[a-z0-9]$/.test(projectId || ''))
      throw new ApiError(503, 'AUTH_NOT_CONFIGURED', 'Dịch vụ chưa hoàn tất cấu hình đăng nhập.');
    if (typeof token !== 'string' || token.length > 8192)
      throw new ApiError(401, 'AUTH_REQUIRED', 'Hãy đăng nhập bằng Google để tiếp tục.');
    try {
      const { payload } = await jwtVerify(token, resolveKey || keyFor, {
        algorithms: ['RS256'], audience: projectId, issuer: 'https://securetoken.google.com/' + projectId,
        requiredClaims: ['sub', 'exp', 'iat', 'auth_time'], currentDate: new Date(now() * 1000)
      });
      return validateClaims(payload, now());
    } catch (error) {
      if (error instanceof ApiError) throw error;
      throw new ApiError(401, 'AUTH_EXPIRED', 'Phiên đăng nhập không hợp lệ hoặc đã hết hạn. Hãy đăng nhập lại.');
    }
  };
}

export function validateClaims(payload, timestamp = unix()) {
  if (typeof payload.sub !== 'string' || !payload.sub.length || payload.sub.length > 128 ||
      typeof payload.iat !== 'number' || payload.iat > timestamp ||
      typeof payload.auth_time !== 'number' || payload.auth_time > timestamp ||
      payload.auth_time > payload.iat || payload.email_verified !== true ||
      payload.firebase?.sign_in_provider !== 'google.com' ||
      typeof payload.email !== 'string' || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(payload.email) ||
      payload.email.length > 254)
    throw new ApiError(401, 'GOOGLE_REQUIRED', 'Hãy dùng tài khoản Google có email đã xác minh.');
  return { uid: payload.sub, email: payload.email.toLowerCase(), authTime: payload.auth_time };
}

const verify = createFirebaseVerifier();
export async function verifyIdentity(request, env, verifier = verify) {
  const header = request.headers.get('authorization') || '';
  const match = /^Bearer ([A-Za-z0-9._-]+)$/.exec(header);
  if (!match) throw new ApiError(401, 'AUTH_REQUIRED', 'Hãy đăng nhập bằng Google để tiếp tục.');
  return verifier(match[1], env.FIREBASE_PROJECT_ID);
}
export async function authorize(request, env, verifier = verify) {
  const identity = await verifyIdentity(request, env, verifier);
  const timestamp = unix();
  let invite = await env.DB.prepare('SELECT * FROM invites WHERE email=?').bind(identity.email).first();
  if (!invite || !invite.enabled || (invite.expires_at !== null && invite.expires_at <= timestamp) ||
      invite.tokens_valid_after > identity.authTime || (invite.uid && invite.uid !== identity.uid))
    throw new ApiError(403, 'INVITE_REQUIRED', 'Email Google này chưa được mời hoặc quyền truy cập đã hết hạn.');
  if (!invite.uid) {
    invite = await env.DB.prepare(
      'UPDATE invites SET uid=? WHERE email=? AND uid IS NULL AND enabled=1 AND (expires_at IS NULL OR expires_at>?) AND tokens_valid_after<=? RETURNING *'
    ).bind(identity.uid, identity.email, timestamp, identity.authTime).first();
    if (!invite) throw new ApiError(403, 'INVITE_REQUIRED', 'Lời mời không còn hợp lệ.');
  }
  return { ...identity, invite };
}
