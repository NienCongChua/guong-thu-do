import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createApp, IMAGE_PROMPT, LIVE_MODEL } from '../src/server.mjs';

const PASSWORD = 'test-password-only';
const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/a9sAAAAASUVORK5CYII=', 'base64');
async function harness(config, dependencies, action) {
  const { app, close } = createApp({ DEMO_PASSWORD: PASSWORD, USAGE_DB: ':memory:', ...config }, dependencies);
  const server = app.listen(0, '127.0.0.1');
  await new Promise(resolve => server.once('listening', resolve));
  const url = 'http://127.0.0.1:' + server.address().port;
  const login = await fetch(url + '/api/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ password: PASSWORD }) });
  const { token } = await login.json();
  const headers = { Authorization: 'Bearer ' + token };
  try { await action({ url, headers }); }
  finally { await new Promise(resolve => server.close(resolve)); close(); }
}
function form() {
  const body = new FormData();
  body.append('person', new Blob([png], { type: 'image/png' }), 'me.png');
  body.append('garment', new Blob([png], { type: 'image/png' }), 'item.png');
  body.append('kind', 'top');
  return body;
}
test('no provider keys: authenticated capabilities and specific configuration errors', async () => {
  await harness({}, {}, async ({ url, headers }) => {
    assert.equal((await fetch(url + '/api/capabilities')).status, 401);
    const caps = await (await fetch(url + '/api/capabilities', { headers })).json();
    assert.equal(caps.decart, false); assert.equal(caps.openai, false);
    const live = await fetch(url + '/api/realtime-token', { method: 'POST', headers });
    assert.equal((await live.json()).code, 'DECART_NOT_CONFIGURED');
    const image = await fetch(url + '/api/try-on-photo', { method: 'POST', headers, body: form() });
    assert.equal((await image.json()).code, 'OPENAI_NOT_CONFIGURED');
  });
});
test('OpenAI only: two images in correct order, exact prompt and real response bytes', async () => {
  let calls = 0;
  await harness({ OPENAI_API_KEY: 'fake-key-for-test', DAILY_IMAGE_LIMIT: '1' }, {
    fetchImpl: async (url, options) => {
      calls++;
      assert.equal(url, 'https://api.openai.com/v1/images/edits');
      assert.equal(options.method, 'POST');
      assert.equal(options.headers.Authorization, 'Bearer fake-key-for-test');
      assert.equal(options.body.get('model'), 'gpt-image-1.5');
      const images = options.body.getAll('image[]');
      assert.equal(images[0].name, 'person.png'); assert.equal(images[1].name, 'garment.png');
      assert.deepEqual(Buffer.from(await images[0].arrayBuffer()), png);
      assert.deepEqual(Buffer.from(await images[1].arrayBuffer()), png);
      assert.ok(options.body.get('prompt').startsWith(IMAGE_PROMPT));
      return Response.json({ data: [{ b64_json: png.toString('base64') }] });
    }
  }, async ({ url, headers }) => {
    const caps = await (await fetch(url + '/api/capabilities', { headers })).json();
    assert.equal(caps.decart, false); assert.equal(caps.openai, true);
    assert.equal(calls, 0); // Capability checks never trigger a paid edit.
    const result = await fetch(url + '/api/try-on-photo', { method: 'POST', headers, body: form() });
    assert.equal(result.status, 200); assert.deepEqual(Buffer.from(await result.arrayBuffer()), png);
    const again = await fetch(url + '/api/try-on-photo', { method: 'POST', headers, body: form() });
    assert.equal((await again.json()).code, 'DAILY_LIMIT');
    assert.equal(calls, 1);
  });
});
test('Decart only: model scope, 60s token expiry, enforced server session constraint', async () => {
  await harness({ DECART_API_KEY: 'fake-key-for-test', LIVE_MAX_SECONDS: '120' }, {
    mintToken: async options => {
      assert.deepEqual(options.allowedModels, [LIVE_MODEL]);
      assert.equal(options.expiresIn, 60);
      assert.equal(options.constraints.realtime.maxSessionDuration, 120);
      return { apiKey: 'ek_fake', expiresAt: new Date(Date.now() + 60000).toISOString() };
    }
  }, async ({ url, headers }) => {
    const response = await fetch(url + '/api/realtime-token', { method: 'POST', headers });
    assert.equal(response.status, 200);
    const result = await response.json();
    assert.equal(result.apiKey, 'ek_fake'); assert.equal(result.maxSessionSeconds, 120);
    assert.equal(JSON.stringify(result).includes('fake-key-for-test'), false);
  });
});
test('OpenAI quota/access/empty output map to explicit errors, never a fallback image', async () => {
  for (const [status, code] of [[429, 'OPENAI_QUOTA'], [403, 'OPENAI_ACCESS'], [200, 'NO_IMAGE_RESULT']]) {
    await harness({ OPENAI_API_KEY: 'fake-key-for-test' }, {
      fetchImpl: async () => Response.json(status === 200 ? { data: [] } : { error: 'private upstream error' }, { status })
    }, async ({ url, headers }) => {
      const response = await fetch(url + '/api/try-on-photo', { method: 'POST', headers, body: form() });
      const result = await response.json();
      assert.equal(result.code, code);
      assert.equal(JSON.stringify(result).includes('private upstream error'), false);
    });
  }
});
test('authentication and burst limit protect both paid endpoints', async () => {
  let minted = 0;
  await harness({ DECART_API_KEY: 'fake-key-for-test' }, {
    mintToken: async () => { minted++; return { apiKey: 'ek_fake', expiresAt: new Date().toISOString() }; }
  }, async ({ url, headers }) => {
    assert.equal((await fetch(url + '/api/realtime-token', { method: 'POST' })).status, 401);
    assert.equal((await fetch(url + '/api/try-on-photo', { method: 'POST', body: form() })).status, 401);
    for (let i = 0; i < 3; i++) assert.equal((await fetch(url + '/api/realtime-token', { method: 'POST', headers })).status, 200);
    const limited = await fetch(url + '/api/realtime-token', { method: 'POST', headers });
    assert.equal(limited.status, 429); assert.equal(minted, 3);
  });
});
test('HTTP contract exposes FASHN capabilities, chosen provider and explicit fallback', async () => {
  let submissions = 0;
  await harness({ OPENAI_API_KEY: 'fake', FASHN_API_KEY: 'fake' }, {
    pollDelayMs: 1,
    fetchImpl: async url => {
      if (url.includes('openai.com')) return new Response(null, { status: 429 });
      if (url.endsWith('/run')) { submissions++; return Response.json({ id: 'test' }); }
      return Response.json({ status: 'completed', output: ['data:image/png;base64,' + png.toString('base64')] });
    }
  }, async ({ url, headers }) => {
    const caps = await (await fetch(url + '/api/capabilities', { headers })).json();
    assert.equal(caps.fashn, true); assert.equal(caps.photo, true);
    assert.equal(caps.fallbackAvailable, true); assert.equal(caps.imageProvider, 'OpenAI');
    assert.equal(submissions, 0);
    const body = form(); body.append('quality', 'fast'); body.append('allowFallback', 'true');
    const response = await fetch(url + '/api/try-on-photo', { method: 'POST', headers, body });
    assert.equal(response.status, 200);
    assert.equal(response.headers.get('x-image-provider'), 'FASHN');
    assert.equal(response.headers.get('x-image-fallback'), 'true');
    assert.deepEqual(Buffer.from(await response.arrayBuffer()), png);
    assert.equal(submissions, 1);
  });
});
test('FASHN-only backend works without an OpenAI key', async () => {
  await harness({ FASHN_API_KEY: 'fake' }, {
    pollDelayMs: 1,
    fetchImpl: async url => url.endsWith('/run') ? Response.json({ id: 'test' }) :
      Response.json({ status: 'completed', output: ['data:image/png;base64,' + png.toString('base64')] })
  }, async ({ url, headers }) => {
    const caps = await (await fetch(url + '/api/capabilities', { headers })).json();
    assert.equal(caps.openai, false); assert.equal(caps.photo, true); assert.equal(caps.imageProvider, 'FASHN');
    const response = await fetch(url + '/api/try-on-photo', { method: 'POST', headers, body: form() });
    assert.equal(response.status, 200); assert.equal(response.headers.get('x-image-fallback'), 'false');
    assert.deepEqual(Buffer.from(await response.arrayBuffer()), png);
  });
});
test('invalid generation options fail before a paid request', async () => {
  await harness({ OPENAI_API_KEY: 'fake' }, { fetchImpl: () => assert.fail('must not generate') },
    async ({ url, headers }) => {
      const body = form(); body.append('allowFallback', 'yes');
      const response = await fetch(url + '/api/try-on-photo', { method: 'POST', headers, body });
      assert.equal(response.status, 400);
      assert.equal((await response.json()).code, 'INVALID_OPTIONS');
    });
});
test('missing person or garment is rejected before OpenAI', async () => {
  await harness({ OPENAI_API_KEY: 'fake-key-for-test' }, { fetchImpl: () => assert.fail('must not call OpenAI') },
    async ({ url, headers }) => {
      const body = new FormData(); body.append('kind', 'top');
      const response = await fetch(url + '/api/try-on-photo', { method: 'POST', headers, body });
      assert.equal(response.status, 400);
    });
});
