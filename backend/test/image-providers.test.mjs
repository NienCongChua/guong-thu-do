import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createImageService } from '../src/image-providers.mjs';

const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/a9sAAAAASUVORK5CYII=', 'base64');
const file = { buffer: png, mimetype: 'image/png' };
const input = { person: file, garment: file, kind: 'top', quality: 'fast', allowFallback: true };
const keys = { OPENAI_API_KEY: 'fake-openai', FASHN_API_KEY: 'fake-fashn' };
const result = () => Response.json({ status: 'completed', output: ['data:image/png;base64,' + png.toString('base64')] });

test('explicit fallback: one OpenAI request, one FASHN prediction, polls only existing prediction', async () => {
  const calls = [];
  let polls = 0;
  const service = createImageService(keys, {
    pollDelayMs: 1,
    fetchImpl: async (url, options) => {
      calls.push(url);
      if (url.includes('openai.com')) {
        assert.equal(options.body.get('quality'), 'low');
        assert.equal(options.body.get('input_fidelity'), 'high');
        return new Response(null, { status: 503 });
      }
      assert.equal(options.headers.Authorization, 'Bearer fake-fashn');
      if (url.endsWith('/run')) {
        const body = JSON.parse(options.body);
        assert.equal(body.model_name, 'tryon-v1.6');
        assert.equal(body.inputs.model_image, 'data:image/png;base64,' + png.toString('base64'));
        assert.equal(body.inputs.garment_image, body.inputs.model_image);
        assert.equal(body.inputs.category, 'tops');
        assert.equal(body.inputs.mode, 'performance');
        assert.equal(body.inputs.num_samples, 1);
        assert.equal(body.inputs.return_base64, true);
        return Response.json({ id: 'test-id', error: null });
      }
      assert.equal(url, 'https://api.fashn.ai/v1/status/test-id');
      return ++polls === 1 ? Response.json({ status: 'processing' }) : result();
    }
  });
  assert.equal(service.capabilities().fallbackAvailable, true);
  const output = await service.edit(input);
  assert.equal(output.provider, 'FASHN');
  assert.equal(output.fallbackUsed, true);
  assert.deepEqual(output.image, png);
  assert.equal(calls.filter(url => url.endsWith('/run')).length, 1);
  assert.equal(polls, 2);
});

test('fallback is opt-in even with both provider keys present', async () => {
  let calls = 0;
  const service = createImageService(keys, {
    fetchImpl: async () => { calls++; return new Response(null, { status: 429 }); }
  });
  await assert.rejects(service.edit({ ...input, allowFallback: false }), { code: 'OPENAI_QUOTA' });
  assert.equal(calls, 1);
});

test('no fallback for rejected input/policy, ambiguous network error or invalid success payload', async () => {
  for (const [response, code] of [
    [() => new Response(null, { status: 400 }), 'OPENAI_REJECTED'],
    [() => new Response(null, { status: 422 }), 'OPENAI_REJECTED'],
    [() => { throw new TypeError('private transport details'); }, 'NETWORK'],
    [() => Response.json({ data: [] }), 'NO_IMAGE_RESULT'],
    [() => Response.json({ data: [{ b64_json: 'garbage' }] }), 'INVALID_RESULT']
  ]) {
    let calls = 0;
    const service = createImageService(keys, { fetchImpl: async () => { calls++; return response(); } });
    await assert.rejects(service.edit(input), error => error.code === code && !error.message.includes('private'));
    assert.equal(calls, 1);
  }
});

test('short cooldown skips unhealthy primary only when current request allows fallback', async () => {
  const calls = [];
  let clock = 1000;
  const service = createImageService(keys, {
    pollDelayMs: 1, now: () => clock,
    fetchImpl: async url => {
      calls.push(url);
      if (url.includes('openai.com')) return new Response(null, { status: 403 });
      return url.endsWith('/run') ? Response.json({ id: 'test-id' }) : result();
    }
  });
  await service.edit(input);
  clock += 500;
  await service.edit(input);
  assert.equal(calls.filter(url => url.includes('openai.com')).length, 1);
  await assert.rejects(service.edit({ ...input, allowFallback: false }), { code: 'OPENAI_ACCESS' });
  assert.equal(calls.filter(url => url.includes('openai.com')).length, 2);
  clock += 31000;
  await service.edit(input);
  assert.equal(calls.filter(url => url.includes('openai.com')).length, 3);
});

test('FASHN alone uses correct dress category and quality without pretending to be a fallback', async () => {
  const service = createImageService({ FASHN_API_KEY: 'fake' }, {
    pollDelayMs: 1,
    fetchImpl: async (url, options) => {
      if (url.endsWith('/run')) {
        const body = JSON.parse(options.body);
        assert.equal(body.inputs.category, 'one-pieces');
        assert.equal(body.inputs.mode, 'quality');
        return Response.json({ id: 'dress' });
      }
      return result();
    }
  });
  assert.equal(service.capabilities().imageProvider, 'FASHN');
  const output = await service.edit({ ...input, kind: 'dress', quality: 'detail' });
  assert.equal(output.fallbackUsed, false);
});

test('timeout and caller cancellation abort upstream without any fallback', async () => {
  for (const cancelByCaller of [true, false]) {
    const caller = new AbortController();
    let calls = 0;
    const service = createImageService(keys, {
      imageTimeoutMs: cancelByCaller ? 10000 : 15,
      fetchImpl: async (_url, { signal }) => {
        calls++;
        return new Promise((_resolve, reject) => {
          signal.addEventListener('abort', () => reject(signal.reason), { once: true });
          if (cancelByCaller) caller.abort();
        });
      }
    });
    // Keep the test event loop alive while the unref'ed deadline is under test.
    const keepAlive = setInterval(() => {}, 100);
    try {
      await assert.rejects(service.edit({ ...input, signal: caller.signal }),
        { code: cancelByCaller ? 'CLIENT_CANCELLED' : 'IMAGE_TIMEOUT' });
    } finally { clearInterval(keepAlive); }
    assert.equal(calls, 1);
  }
});

test('failed FASHN prediction and unexpected CDN output never become a displayed result', async () => {
  for (const payload of [
    { status: 'failed', error: { name: 'ContentModerationError', message: 'private details' } },
    { status: 'completed', output: ['https://untrusted.invalid/result.png'] }
  ]) {
    let predictions = 0;
    const service = createImageService({ FASHN_API_KEY: 'fake' }, {
      pollDelayMs: 1,
      fetchImpl: async url => {
        if (url.endsWith('/run')) { predictions++; return Response.json({ id: 'test' }); }
        return Response.json(payload);
      }
    });
    await assert.rejects(service.edit(input), error => !error.message.includes('private'));
    assert.equal(predictions, 1);
  }
});
