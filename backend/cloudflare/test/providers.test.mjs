import { test } from 'node:test';
import assert from 'node:assert/strict';
import { generateImage, mintLiveToken } from '../src/providers.mjs';
import { environment, png } from './helpers.mjs';
import { inputKey } from '../src/store.mjs';

const job = {
  id: '12345678-1234-1234-1234-123456789abc', kind: 'top',
  quality: 'fast', allow_fallback: 1
};
const result = () => Response.json({ status: 'completed', output: ['data:image/png;base64,' + png.toString('base64')] });
async function inputs(env) {
  for (const name of ['person', 'garment']) await env.IMAGES.put(inputKey(job.id, name), png, { httpMetadata: { contentType: 'image/png' } });
}

test('OpenAI receives ordered inputs and fast quality; result bytes come from the real response body', async () => {
  const { env, close } = environment();
  try {
    await inputs(env);
    const value = await generateImage(job, env, { fetchImpl: async (url, options) => {
      assert.equal(url, 'https://api.openai.com/v1/images/edits');
      assert.equal(options.body.get('quality'), 'low');
      assert.deepEqual(options.body.getAll('image[]').map(file => file.name), ['person.png', 'garment.png']);
      return Response.json({ data: [{ b64_json: png.toString('base64') }] });
    } });
    assert.deepEqual(value.image, png); assert.equal(value.provider, 'OpenAI'); assert.equal(value.fallbackUsed, false);
  } finally { close(); }
});

test('FASHN fallback submits one prediction with temporary signed input links', async () => {
  const { env, close } = environment();
  env.FASHN_API_KEY = 'fake';
  let submissions = 0;
  let calls = 0;
  try {
    await inputs(env);
    const value = await generateImage(job, env, { pollMs: 1, fetchImpl: async (url, options) => {
      calls++;
      if (url.includes('openai.com')) return new Response(null, { status: 503 });
      if (url.endsWith('/run')) {
        submissions++;
        const body = JSON.parse(options.body);
        assert.equal(body.model_name, 'tryon-v1.6'); assert.equal(body.inputs.num_samples, 1);
        assert.equal(body.inputs.mode, 'performance');
        assert.equal(body.inputs.model_image.startsWith('https://try-on.example/provider-assets/'), true);
        assert.equal(new URL(body.inputs.model_image).searchParams.has('sig'), true);
        return Response.json({ id: 'prediction-id' });
      }
      assert.equal(url, 'https://api.fashn.ai/v1/status/prediction-id');
      return result();
    } });
    assert.equal(value.provider, 'FASHN'); assert.equal(value.fallbackUsed, true);
    assert.equal(submissions, 1); assert.equal(calls, 3);
  } finally { close(); }
});

test('rejected content, network ambiguity, malformed output and opted-out fallback never submit to another provider', async () => {
  for (const [fetchResponse, allowed] of [
    [() => new Response(null, { status: 400 }), true],
    [() => { throw new Error('private transport body'); }, true],
    [() => Response.json({ data: [] }), true],
    [() => new Response(null, { status: 429 }), false]
  ]) {
    const { env, close } = environment();
    env.FASHN_API_KEY = 'fake';
    let calls = 0;
    try {
      await inputs(env);
      await assert.rejects(generateImage({ ...job, allow_fallback: Number(allowed) }, env, {
        fetchImpl: async () => { calls++; return fetchResponse(); }
      }), error => !error.message.includes('private'));
      assert.equal(calls, 1);
    } finally { close(); }
  }
});

test('provider timeout never triggers a second paid request', async () => {
  const { env, close } = environment();
  env.FASHN_API_KEY = 'fake';
  let calls = 0;
  const keepAlive = setInterval(() => {}, 100);
  try {
    await inputs(env);
    await assert.rejects(generateImage(job, env, { timeoutMs: 15, fetchImpl: async (_url, { signal }) => {
      calls++;
      return new Promise((_resolve, reject) => signal.addEventListener('abort', () => reject(signal.reason), { once: true }));
    } }), { code: 'IMAGE_TIMEOUT' });
    assert.equal(calls, 1);
  } finally { clearInterval(keepAlive); close(); }
});

test('live token is scoped to the exact model and bounded lifetime; master key is not returned', async () => {
  const { env, close } = environment();
  env.DECART_API_KEY = 'test-master-key'; env.LIVE_MAX_SECONDS = '90';
  try {
    const result = await mintLiveToken(env, async (url, options) => {
      assert.equal(url, 'https://api.decart.ai/v1/client/tokens');
      assert.equal(options.headers['X-API-KEY'], 'test-master-key');
      const body = JSON.parse(options.body);
      assert.deepEqual(body.allowedModels, ['lucy-vton-3.6']);
      assert.equal(body.expiresIn, 60); assert.equal(body.constraints.realtime.maxSessionDuration, 90);
      return Response.json({ apiKey: 'ek_test', expiresAt: '2026-11-14T00:01:00Z' });
    });
    assert.equal(result.maxSessionSeconds, 90);
    assert.equal(JSON.stringify(result).includes('test-master-key'), false);
  } finally { close(); }
});
