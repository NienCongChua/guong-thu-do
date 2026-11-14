import { Buffer } from 'node:buffer';
import { ApiError, boundedJson, unix } from './http.mjs';
import { inputKey } from './store.mjs';

const PROMPT = "Edit the first photo so the person is wearing the garment from the second photo. Use the second photo only as the garment reference. Replace the original clothing in the relevant area. Match perspective, lighting, shadows, fabric folds and occlusion. Preserve the person’s identity, pose, hands and surroundings.";
const PNG = [137, 80, 78, 71, 13, 10, 26, 10];
export const MAX_RESULT_BYTES = 8 * 1024 * 1024;
const encoder = new TextEncoder();

export function capabilities(env) {
  const openai = !!env.OPENAI_API_KEY;
  const fashn = !!env.FASHN_API_KEY && (env.ASSET_SIGNING_KEY?.length >= 32) && /^https:\/\//.test(env.PUBLIC_API_ORIGIN || '');
  const seconds = Number(env.LIVE_MAX_SECONDS || 120);
  return {
    decart: !!env.DECART_API_KEY, openai, fashn, photo: openai || fashn,
    fallbackAvailable: openai && fashn, imageProvider: openai ? 'OpenAI' : fashn ? 'FASHN' : '',
    maxSessionSeconds: Number.isInteger(seconds) && seconds >= 30 && seconds <= 600 ? seconds : 120,
    asyncPhotos: true, authMode: 'firebase-google', inviteOnly: true
  };
}
async function hmacKey(secret) {
  if (!secret || secret.length < 32) throw new ApiError(503, 'ASSET_NOT_CONFIGURED', 'Dịch vụ ảnh chưa được cấu hình đầy đủ.');
  return crypto.subtle.importKey('raw', encoder.encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign', 'verify']);
}
export async function assetUrl(env, id, kind, expires = unix() + 300) {
  const origin = new URL(env.PUBLIC_API_ORIGIN);
  if (origin.protocol !== 'https:' || origin.username || origin.password || origin.pathname !== '/' || origin.search || origin.hash)
    throw new ApiError(503, 'ASSET_NOT_CONFIGURED', 'Địa chỉ dịch vụ ảnh chưa hợp lệ.');
  const signature = await crypto.subtle.sign('HMAC', await hmacKey(env.ASSET_SIGNING_KEY), encoder.encode(id + ':' + kind + ':' + expires));
  return origin.origin + '/provider-assets/' + id + '/' + kind + '?expires=' + expires + '&sig=' + Buffer.from(signature).toString('base64url');
}
export async function verifyAssetUrl(env, id, kind, url) {
  const expiry = url.searchParams.get('expires');
  const signature = url.searchParams.get('sig');
  if (!/^\d{10}$/.test(expiry || '') || Number(expiry) <= unix() || Number(expiry) > unix() + 360 ||
      typeof signature !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(signature)) return false;
  return crypto.subtle.verify('HMAC', await hmacKey(env.ASSET_SIGNING_KEY),
    Buffer.from(signature, 'base64url'), encoder.encode(id + ':' + kind + ':' + expiry));
}
async function check(response, provider) {
  if (response.ok) return;
  await response.body?.cancel();
  const status = response.status;
  if ([401, 403].includes(status))
    throw new ApiError(502, provider + '_ACCESS', 'Dịch vụ ảnh chưa sẵn sàng. Vui lòng liên hệ người quản lý.', true);
  if ([402, 429].includes(status))
    throw new ApiError(429, provider + '_QUOTA', 'Dịch vụ ảnh đang giới hạn lượt sử dụng.', true);
  if (status === 404)
    throw new ApiError(502, provider + '_MODEL', 'Dịch vụ ảnh tạm thời không khả dụng.', true);
  if ([400, 422].includes(status))
    throw new ApiError(422, provider + '_REJECTED', 'Dịch vụ không chấp nhận ảnh hoặc yêu cầu này.');
  throw new ApiError(502, provider + '_UPSTREAM', 'Dịch vụ ảnh tạm thời gặp lỗi.', status >= 500);
}
export function decodePng(encoded) {
  if (typeof encoded !== 'string' || !encoded.length || encoded.length > Math.ceil(MAX_RESULT_BYTES / 3) * 4 ||
      !/^[A-Za-z0-9+/]+={0,2}$/.test(encoded))
    throw new ApiError(502, 'INVALID_RESULT', 'Không nhận được ảnh kết quả hợp lệ.');
  const bytes = Buffer.from(encoded, 'base64');
  if (bytes.length > MAX_RESULT_BYTES || !PNG.every((value, i) => bytes[i] === value))
    throw new ApiError(502, 'INVALID_RESULT', 'Không nhận được ảnh PNG hợp lệ.');
  return bytes;
}
export async function generateImage(job, env, { fetchImpl = fetch, pollMs = 1500, timeoutMs = 180000 } = {}) {
  const caps = capabilities(env);
  const signal = AbortSignal.timeout(timeoutMs);
  const started = Date.now();
  async function openai() {
    const model = env.OPENAI_IMAGE_MODEL || 'gpt-image-1.5';
    if (!['gpt-image-1.5', 'gpt-image-2', 'gpt-image-2.5-sunburst', 'gpt-image-2.5-flare'].includes(model))
      throw new ApiError(503, 'MODEL_NOT_CONFIGURED', 'Dịch vụ ảnh chưa được cấu hình đúng.');
    const form = new FormData();
    form.set('model', model);
    for (const kind of ['person', 'garment']) {
      const asset = await env.IMAGES.get(inputKey(job.id, kind));
      if (!asset || asset.size > 6 * 1024 * 1024) throw new ApiError(410, 'INPUT_EXPIRED', 'Ảnh đầu vào đã hết hạn.');
      form.append('image[]', new Blob([await asset.arrayBuffer()], { type: asset.httpMetadata?.contentType || 'image/png' }), kind + '.png');
    }
    form.set('prompt', PROMPT + (job.kind === 'dress' ? ' The garment is a dress.' : ' The garment is a top.'));
    form.set('n', '1'); form.set('size', '1024x1536'); form.set('output_format', 'png');
    form.set('quality', job.quality === 'fast' ? 'low' : 'medium');
    if (model === 'gpt-image-1.5') form.set('input_fidelity', 'high');
    const response = await fetchImpl('https://api.openai.com/v1/images/edits', {
      method: 'POST', headers: { Authorization: 'Bearer ' + env.OPENAI_API_KEY },
      body: form, signal, redirect: 'error'
    });
    await check(response, 'OPENAI');
    return decodePng((await boundedJson(response))?.data?.[0]?.b64_json);
  }
  async function fashn() {
    const headers = { Authorization: 'Bearer ' + env.FASHN_API_KEY, 'Content-Type': 'application/json' };
    const response = await fetchImpl('https://api.fashn.ai/v1/run', {
      method: 'POST', headers, signal, redirect: 'error',
      body: JSON.stringify({
        model_name: 'tryon-v1.6',
        inputs: {
          model_image: await assetUrl(env, job.id, 'person'),
          garment_image: await assetUrl(env, job.id, 'garment'),
          category: job.kind === 'dress' ? 'one-pieces' : 'tops',
          mode: job.quality === 'fast' ? 'performance' : 'quality',
          num_samples: 1, output_format: 'png', return_base64: true
        }
      })
    });
    await check(response, 'FASHN');
    const prediction = await boundedJson(response, 16 * 1024);
    if (prediction.error || typeof prediction.id !== 'string' || !/^[A-Za-z0-9_-]{1,160}$/.test(prediction.id))
      throw new ApiError(502, 'INVALID_RESULT', 'Dịch vụ ảnh không trả về mã xử lý hợp lệ.');
    while (true) {
      await new Promise((resolve, reject) => {
        const timer = setTimeout(done, pollMs);
        function done() { signal.removeEventListener('abort', abort); resolve(); }
        function abort() { clearTimeout(timer); signal.removeEventListener('abort', abort); reject(signal.reason); }
        if (signal.aborted) abort(); else signal.addEventListener('abort', abort, { once: true });
      });
      const status = await fetchImpl('https://api.fashn.ai/v1/status/' + prediction.id, {
        headers: { Authorization: headers.Authorization }, signal, redirect: 'error'
      });
      await check(status, 'FASHN');
      const result = await boundedJson(status);
      if (result.status === 'completed') {
        const value = result.output?.[0];
        if (typeof value !== 'string' || !value.startsWith('data:image/png;base64,'))
          throw new ApiError(502, 'INVALID_RESULT', 'Không nhận được ảnh kết quả hợp lệ.');
        return decodePng(value.slice(22));
      }
      if (result.status === 'failed' || result.error)
        throw new ApiError(422, 'FASHN_REJECTED', 'Dịch vụ không xử lý được ảnh. Hãy chọn ảnh rõ người và trang phục.');
      if (!['starting', 'in_queue', 'processing'].includes(result.status))
        throw new ApiError(502, 'INVALID_RESULT', 'Trạng thái xử lý không hợp lệ.');
    }
  }
  try {
    let provider = caps.openai ? 'OpenAI' : 'FASHN';
    let fallbackUsed = false;
    let image;
    if (caps.openai) {
      try { image = await openai(); }
      catch (error) {
        if (signal.aborted || !error.fallbackSafe || !job.allow_fallback || !caps.fashn) throw error;
        fallbackUsed = true; provider = 'FASHN';
        image = await fashn();
      }
    } else if (caps.fashn) image = await fashn();
    else throw new ApiError(503, 'PROVIDER_NOT_CONFIGURED', 'Dịch vụ ảnh chưa được bật.');
    return { image, provider, fallbackUsed, elapsedMs: Date.now() - started };
  } catch (error) {
    if (signal.aborted) throw new ApiError(504, 'IMAGE_TIMEOUT', 'Dịch vụ ảnh xử lý quá lâu. Không tự tạo lại để tránh tính phí lặp.');
    if (error instanceof ApiError) throw error;
    throw new ApiError(502, 'PROVIDER_NETWORK', 'Mất kết nối tới dịch vụ ảnh. Yêu cầu không được tự gửi lại.');
  }
}
export async function mintLiveToken(env, fetchImpl = fetch) {
  const caps = capabilities(env);
  if (!caps.decart) throw new ApiError(503, 'LIVE_UNAVAILABLE', 'Gương trực tiếp chưa được bật.');
  try {
    const response = await fetchImpl('https://api.decart.ai/v1/client/tokens', {
      method: 'POST', headers: { 'X-API-KEY': env.DECART_API_KEY, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        expiresIn: 60, allowedModels: ['lucy-vton-3.6'],
        constraints: { realtime: { maxSessionDuration: caps.maxSessionSeconds } }
      }), signal: AbortSignal.timeout(20000), redirect: 'error'
    });
    await check(response, 'DECART');
    const result = await boundedJson(response, 32 * 1024);
    if (typeof result.apiKey !== 'string' || !result.expiresAt)
      throw new ApiError(502, 'LIVE_UNAVAILABLE', 'Không nhận được thông tin phiên trực tiếp.');
    return { apiKey: result.apiKey, expiresAt: result.expiresAt, maxSessionSeconds: caps.maxSessionSeconds };
  } catch (error) {
    if (error instanceof ApiError) throw error;
    throw new ApiError(502, 'LIVE_UNAVAILABLE', 'Không thể cấp phiên trực tiếp. Hãy thử lại sau.');
  }
}
