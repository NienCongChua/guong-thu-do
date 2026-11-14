import { setTimeout as sleep } from 'node:timers/promises';

export const IMAGE_PROMPT = "Edit the first photo so the person is wearing the garment from the second photo. Use the second photo only as the garment reference. Replace the original clothing in the relevant area. Match perspective, lighting, shadows, fabric folds and occlusion. Preserve the person’s identity, pose, hands and surroundings.";
const PNG = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
const MAX_JSON = 42 * 1024 * 1024;

export class ApiError extends Error {
  constructor(status, code, message, fallbackSafe = false) {
    super(message); this.status = status; this.code = code; this.fallbackSafe = fallbackSafe;
  }
}

// Bound the body while reading, including when upstream omits Content-Length.
async function readJson(response) {
  if (Number(response.headers.get('content-length')) > MAX_JSON) {
    await response.body?.cancel();
    throw new ApiError(502, 'RESULT_TOO_LARGE', 'Phản hồi dịch vụ ảnh quá lớn.');
  }
  const reader = response.body?.getReader();
  if (!reader) throw new ApiError(502, 'NO_IMAGE_RESULT', 'Dịch vụ không trả về dữ liệu.');
  let size = 0;
  const chunks = [];
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.length;
      if (size > MAX_JSON) throw new ApiError(502, 'RESULT_TOO_LARGE', 'Phản hồi dịch vụ ảnh quá lớn.');
      chunks.push(Buffer.from(value));
    }
    return JSON.parse(Buffer.concat(chunks).toString('utf8'));
  } catch (e) {
    await reader.cancel().catch(() => {});
    if (e instanceof ApiError || e.name === 'AbortError') throw e;
    throw new ApiError(502, 'INVALID_RESULT', 'Dịch vụ trả về dữ liệu không hợp lệ.');
  } finally { reader.releaseLock(); }
}

function decodePng(encoded) {
  if (typeof encoded !== 'string' || encoded.length === 0 || encoded.length > 40 * 1024 * 1024)
    throw new ApiError(502, 'NO_IMAGE_RESULT', 'Dịch vụ không trả về ảnh kết quả hợp lệ.');
  const image = Buffer.from(encoded, 'base64');
  if (!image.subarray(0, 8).equals(PNG))
    throw new ApiError(502, 'INVALID_RESULT', 'Ảnh kết quả không phải PNG hợp lệ.');
  return image;
}

async function checkResponse(response, provider) {
  if (response.ok) return;
  await response.body?.cancel(); // Never relay provider bodies, credentials or image data.
  const status = response.status;
  if (status === 401 || status === 403)
    throw new ApiError(502, provider + '_ACCESS', provider + ' từ chối key hoặc quyền model. Kiểm tra cấu hình server.', true);
  if (status === 402 || status === 429)
    throw new ApiError(429, provider + '_QUOTA', provider + ' đã hết số dư/hạn mức hoặc đang giới hạn tốc độ.', true);
  if (status === 404)
    throw new ApiError(502, provider + '_MODEL', provider + ' không tìm thấy model đã cấu hình.', true);
  if (status === 400 || status === 422)
    throw new ApiError(422, provider + '_REJECTED', provider + ' từ chối ảnh hoặc yêu cầu. Hãy kiểm tra ảnh và cấu hình model.');
  throw new ApiError(502, provider + '_UPSTREAM', provider + ' gặp lỗi dịch vụ.', status >= 500);
}

export function createImageService(config, {
  fetchImpl = fetch, pollDelayMs = 1000, imageTimeoutMs = 180000, now = Date.now
} = {}) {
  const openaiKey = config.OPENAI_API_KEY?.trim();
  const fashnKey = config.FASHN_API_KEY?.trim();
  const imageModel = config.OPENAI_IMAGE_MODEL || 'gpt-image-1.5';
  if (!['gpt-image-1.5', 'gpt-image-2', 'gpt-image-2.5-sunburst', 'gpt-image-2.5-flare'].includes(imageModel))
    throw new Error('OPENAI_IMAGE_MODEL phải là GPT Image hỗ trợ chỉnh sửa nhiều ảnh.');
  let primaryUnavailableUntil = 0;
  const capabilities = () => ({
    openai: !!openaiKey, fashn: !!fashnKey, photo: !!(openaiKey || fashnKey),
    imageModel, imageProvider: openaiKey ? 'OpenAI' : fashnKey ? 'FASHN' : '',
    fallbackAvailable: !!(openaiKey && fashnKey)
  });

  async function openai(person, garment, kind, quality, signal) {
    const form = new FormData();
    form.append('model', imageModel);
    for (const [name, file] of [['person', person], ['garment', garment]])
      form.append('image[]', new Blob([file.buffer], { type: file.mimetype }), name + '.' + file.mimetype.split('/')[1]);
    form.append('prompt', IMAGE_PROMPT + (kind === 'dress' ? ' The garment is a dress.' : ' The garment is a top.'));
    form.append('n', '1'); form.append('size', '1024x1536');
    form.append('quality', quality === 'fast' ? 'low' : 'medium');
    form.append('output_format', 'png');
    if (imageModel === 'gpt-image-1.5') form.append('input_fidelity', 'high');
    const response = await fetchImpl('https://api.openai.com/v1/images/edits', {
      method: 'POST', headers: { Authorization: 'Bearer ' + openaiKey },
      body: form, signal, redirect: 'error'
    });
    await checkResponse(response, 'OPENAI');
    return decodePng((await readJson(response))?.data?.[0]?.b64_json);
  }

  async function fashn(person, garment, kind, quality, signal) {
    const headers = { Authorization: 'Bearer ' + fashnKey, 'Content-Type': 'application/json' };
    const dataUri = file => 'data:' + file.mimetype + ';base64,' + file.buffer.toString('base64');
    const response = await fetchImpl('https://api.fashn.ai/v1/run', {
      method: 'POST', headers, signal, redirect: 'error',
      body: JSON.stringify({
        model_name: 'tryon-v1.6',
        inputs: {
          model_image: dataUri(person), garment_image: dataUri(garment),
          category: kind === 'dress' ? 'one-pieces' : 'tops',
          mode: quality === 'fast' ? 'performance' : 'quality',
          num_samples: 1, output_format: 'png', return_base64: true
        }
      })
    });
    await checkResponse(response, 'FASHN');
    const prediction = await readJson(response);
    if (prediction.error || typeof prediction.id !== 'string' || !/^[a-zA-Z0-9_-]{1,160}$/.test(prediction.id))
      throw new ApiError(502, 'FASHN_RESULT', 'FASHN không trả về mã xử lý hợp lệ.');
    // Poll an existing prediction only. Never create a second paid prediction.
    while (true) {
      await sleep(pollDelayMs, undefined, { signal });
      const status = await fetchImpl('https://api.fashn.ai/v1/status/' + prediction.id, {
        headers: { Authorization: headers.Authorization }, signal, redirect: 'error'
      });
      await checkResponse(status, 'FASHN');
      const result = await readJson(status);
      if (result.status === 'completed') {
        const output = result.output?.[0];
        if (typeof output !== 'string' || !output.startsWith('data:image/png;base64,'))
          throw new ApiError(502, 'FASHN_RESULT', 'FASHN không trả về ảnh PNG base64 như yêu cầu.');
        return decodePng(output.slice('data:image/png;base64,'.length));
      }
      if (result.status === 'failed' || result.error)
        throw new ApiError(422, 'FASHN_REJECTED', 'FASHN không xử lý được ảnh. Chọn ảnh rõ người và trang phục, rồi thử lại.');
      if (!['starting', 'in_queue', 'processing'].includes(result.status))
        throw new ApiError(502, 'FASHN_RESULT', 'Trạng thái xử lý FASHN không hợp lệ.');
    }
  }

  return {
    capabilities,
    async edit({ person, garment, kind, quality = 'detail', allowFallback = false, signal }) {
      if (!openaiKey && !fashnKey)
        throw new ApiError(503, 'OPENAI_NOT_CONFIGURED', 'Server chưa cấu hình OPENAI_API_KEY hoặc FASHN_API_KEY.');
      const deadline = new AbortController();
      const timer = setTimeout(() => deadline.abort(), imageTimeoutMs);
      timer.unref();
      const combined = signal ? AbortSignal.any([signal, deadline.signal]) : deadline.signal;
      const started = now();
      let provider = openaiKey ? 'OpenAI' : 'FASHN';
      let fallbackUsed = false;
      try {
        combined.throwIfAborted();
        let image;
        // Only skip an unhealthy primary if this request explicitly permits FASHN.
        if (openaiKey && fashnKey && allowFallback && now() < primaryUnavailableUntil) {
          provider = 'FASHN'; fallbackUsed = true;
          image = await fashn(person, garment, kind, quality, combined);
        } else if (openaiKey) {
          try {
            image = await openai(person, garment, kind, quality, combined);
            primaryUnavailableUntil = 0;
          } catch (e) {
            // No fallback on input/policy rejection, malformed success, transport
            // failure, cancellation or timeout: acceptance/billing may be ambiguous.
            if (combined.aborted || !e.fallbackSafe) throw e;
            primaryUnavailableUntil = now() + 30000;
            if (!allowFallback || !fashnKey) throw e;
            provider = 'FASHN'; fallbackUsed = true;
            image = await fashn(person, garment, kind, quality, combined);
          }
        } else image = await fashn(person, garment, kind, quality, combined);
        return { image, provider, fallbackUsed, elapsedMs: Math.max(0, now() - started) };
      } catch (e) {
        if (signal?.aborted) throw new ApiError(499, 'CLIENT_CANCELLED', 'Đã hủy chờ kết quả.');
        if (deadline.signal.aborted)
          throw new ApiError(504, 'IMAGE_TIMEOUT', 'Hết thời gian chờ dịch vụ ảnh. Yêu cầu có thể đã tính phí; kiểm tra trước khi tạo lại.');
        if (e instanceof ApiError) throw e;
        throw new ApiError(502, 'NETWORK', 'Server không kết nối được dịch vụ ảnh. Không tự gửi lại để tránh tính phí lặp.');
      } finally { clearTimeout(timer); }
    }
  };
}
