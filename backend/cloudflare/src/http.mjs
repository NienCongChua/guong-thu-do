export class ApiError extends Error {
  constructor(status, code, message, fallbackSafe = false) {
    super(message); this.status = status; this.code = code; this.fallbackSafe = fallbackSafe;
  }
}
export const unix = () => Math.floor(Date.now() / 1000);
export const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export function json(value, status = 200) { return Response.json(value, { status }); }
export function secure(response, requestId) {
  const headers = new Headers(response.headers);
  headers.set('Cache-Control', 'no-store');
  headers.set('X-Content-Type-Options', 'nosniff');
  headers.set('Referrer-Policy', 'no-referrer');
  headers.set('X-Request-Id', requestId);
  return new Response(response.body, { status: response.status, headers });
}
export function errorResponse(error, requestId) {
  const known = error instanceof ApiError;
  // Do not log provider response bodies, URLs, tokens, email addresses or keys.
  if (!known) console.error(JSON.stringify({ event: 'request_failed', requestId, code: 'SERVER_ERROR' }));
  return secure(json({
    code: known ? error.code : 'SERVER_ERROR',
    error: known ? error.message : 'Dịch vụ tạm thời gặp lỗi. Hãy thử lại sau.',
    requestId
  }, known ? error.status : 500), requestId);
}
export async function boundedJson(response, maxBytes = 12 * 1024 * 1024) {
  if (Number(response.headers.get('content-length')) > maxBytes) {
    await response.body?.cancel();
    throw new ApiError(502, 'RESULT_TOO_LARGE', 'Phản hồi dịch vụ quá lớn.');
  }
  const chunks = [];
  let size = 0;
  const reader = response.body?.getReader();
  if (!reader) throw new ApiError(502, 'INVALID_RESULT', 'Dịch vụ trả về dữ liệu trống.');
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      size += value.length;
      if (size > maxBytes) throw new ApiError(502, 'RESULT_TOO_LARGE', 'Phản hồi dịch vụ quá lớn.');
      chunks.push(value);
    }
    const data = new Uint8Array(size);
    let offset = 0;
    for (const chunk of chunks) { data.set(chunk, offset); offset += chunk.length; }
    return JSON.parse(new TextDecoder().decode(data));
  } catch (error) {
    await reader.cancel().catch(() => {});
    if (error instanceof ApiError || error.name === 'AbortError') throw error;
    throw new ApiError(502, 'INVALID_RESULT', 'Dịch vụ trả về dữ liệu không hợp lệ.');
  } finally { reader.releaseLock(); }
}
export function sqlError(error) {
  const message = String(error?.message || '');
  for (const [code, status, text] of [
    ['INVITE_REQUIRED', 403, 'Tài khoản chưa được mời hoặc lời mời đã bị thu hồi.'],
    ['IMAGE_BUSY', 409, 'Bạn đang có một tác vụ ảnh chưa hoàn tất. Hãy mở tiến trình hiện tại.'],
    ['DAILY_LIMIT', 429, 'Bạn đã dùng hết hạn mức hôm nay. Hạn mức được đặt lại theo ngày UTC.'],
    ['GLOBAL_LIMIT', 429, 'Dịch vụ đã đạt hạn mức beta hôm nay. Hãy quay lại vào ngày mai.'],
    ['LIVE_COOLDOWN', 429, 'Phiên trực tiếp trước vừa được cấp. Hãy đợi tối đa 3 phút trước khi mở phiên mới.']
  ]) if (message.includes(code)) return new ApiError(status, code, text);
  return error;
}
