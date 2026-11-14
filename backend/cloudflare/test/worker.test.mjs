import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createWorker } from '../src/worker.mjs';
import { assetUrl } from '../src/providers.mjs';
import { inputKey, resultKey } from '../src/store.mjs';
import { environment, verifyToken, request, upload, message, png } from './helpers.mjs';

const output = () => ({ image: png, provider: 'OpenAI', fallbackUsed: false, elapsedMs: 1250 });
const make = options => createWorker({ verifyToken, generate: async () => output(), ...options });
async function submit(worker, env, options = {}) {
  const key = options.key || crypto.randomUUID();
  const response = await worker.fetch(request('/api/jobs', { method: 'POST', body: upload(options), key, token: options.token }), env);
  return { response, value: await response.json(), key };
}

test('health is public; application data requires identity and a current invitation', async () => {
  const { env, close } = environment();
  try {
    const worker = make();
    assert.equal((await worker.fetch(new Request('https://try-on.example/health'), env)).status, 200);
    assert.equal((await worker.fetch(new Request('https://try-on.example/api/me'), env)).status, 401);
    assert.equal((await worker.fetch(request('/api/me', { token: 'uninvited' }), env)).status, 403);
    const me = await (await worker.fetch(request('/api/me'), env)).json();
    assert.equal(me.email, 'a@example.com'); assert.equal(me.quota.imagesUsed, 0);
    env.DB.sqlite.prepare('UPDATE invites SET enabled=0 WHERE uid=?').run('user-a');
    assert.equal((await worker.fetch(request('/api/me'), env)).status, 403);
  } finally { close(); }
});

test('duplicate upload and duplicate queue delivery produce exactly one paid generation', async () => {
  const { env, sent, close } = environment();
  let calls = 0;
  try {
    const worker = make({ generate: async () => { calls++; return output(); } });
    const first = await submit(worker, env);
    const second = await submit(worker, env, { key: first.key });
    assert.equal(first.response.status, 202); assert.equal(second.response.status, 202);
    assert.equal(first.value.job.id, second.value.job.id);
    assert.equal(sent.length, 1);
    await worker.queue({ messages: [message(first.value.job.id), message(first.value.job.id)] }, env);
    assert.equal(calls, 1);
    assert.equal(env.IMAGES.data.has(inputKey(first.value.job.id, 'person')), false);
    const response = await worker.fetch(request('/api/jobs/' + first.value.job.id + '/result'), env);
    assert.equal(response.status, 200);
    assert.equal(response.headers.get('x-image-provider'), 'OpenAI');
    assert.equal(response.headers.get('cache-control'), 'no-store');
    assert.deepEqual(Buffer.from(await response.arrayBuffer()), png);
    const me = await (await worker.fetch(request('/api/me'), env)).json();
    assert.equal(me.quota.imagesUsed, 1);
  } finally { close(); }
});

test('idempotency key cannot silently refer to different inputs or options', async () => {
  const { env, close } = environment();
  try {
    const worker = make();
    const first = await submit(worker, env);
    const conflict = await submit(worker, env, { key: first.key, quality: 'detail' });
    assert.equal(conflict.response.status, 409);
    assert.equal(conflict.value.code, 'IDEMPOTENCY_CONFLICT');
  } finally { close(); }
});

test('concurrent different jobs cannot exceed one active job per user', async () => {
  const { env, close } = environment();
  try {
    const worker = make();
    const attempts = await Promise.all([submit(worker, env), submit(worker, env)]);
    assert.deepEqual(attempts.map(x => x.response.status).sort(), [202, 409]);
    const used = env.DB.sqlite.prepare("SELECT count FROM daily_usage WHERE scope='user:user-a' AND feature='image'").get();
    assert.equal(used.count, 1);
  } finally { close(); }
});

test('quota zero disables generation, global quota also applies across identities', async () => {
  const { env, close } = environment();
  try {
    const worker = make();
    env.DB.sqlite.prepare('UPDATE invites SET image_daily_limit=0 WHERE uid=?').run('user-a');
    assert.equal((await submit(worker, env)).value.code, 'DAILY_LIMIT');
    env.DB.sqlite.prepare("UPDATE settings SET value=1 WHERE key='global_image_daily_limit'").run();
    assert.equal((await submit(worker, env, { token: 'token-b' })).response.status, 202);
    env.DB.sqlite.prepare('UPDATE invites SET image_daily_limit=6 WHERE uid=?').run('user-a');
    assert.equal((await submit(worker, env)).value.code, 'GLOBAL_LIMIT');
  } finally { close(); }
});

test('another customer cannot read, cancel, delete or download an owned job', async () => {
  const { env, close } = environment();
  try {
    const worker = make();
    const { value } = await submit(worker, env);
    for (const [method, suffix] of [['GET', ''], ['GET', '/result'], ['POST', '/cancel'], ['DELETE', '']]) {
      const response = await worker.fetch(request('/api/jobs/' + value.job.id + suffix, { method, token: 'token-b' }), env);
      assert.equal(response.status, 404);
    }
    const list = await (await worker.fetch(request('/api/jobs', { token: 'token-b' }), env)).json();
    assert.equal(list.jobs.length, 0);
  } finally { close(); }
});

test('cancellation before consumption never invokes the provider', async () => {
  const { env, close } = environment();
  let calls = 0;
  try {
    const worker = make({ generate: async () => { calls++; return output(); } });
    const { value } = await submit(worker, env);
    await worker.fetch(request('/api/jobs/' + value.job.id + '/cancel', { method: 'POST' }), env);
    await worker.queue({ messages: [message(value.job.id)] }, env);
    await worker.scheduled({}, env);
    assert.equal(calls, 0); assert.equal(env.IMAGES.data.size, 0);
  } finally { close(); }
});

test('cancellation during processing discards the late result without rerunning provider', async () => {
  const { env, close } = environment();
  let finish;
  let entered;
  const started = new Promise(resolve => { entered = resolve; });
  try {
    const worker = make({ generate: async () => { entered(); return new Promise(resolve => { finish = resolve; }); } });
    const { value } = await submit(worker, env);
    const running = worker.queue({ messages: [message(value.job.id)] }, env);
    await started;
    await worker.fetch(request('/api/jobs/' + value.job.id + '/cancel', { method: 'POST' }), env);
    finish(output());
    await running;
    assert.equal(env.IMAGES.data.has(resultKey(value.job.id)), false);
    assert.equal((await worker.fetch(request('/api/jobs/' + value.job.id + '/result'), env)).status, 410);
  } finally { close(); }
});

test('queue send failure is recovered from the D1 outbox', async () => {
  const { env, close } = environment();
  try {
    const worker = make();
    env.IMAGE_QUEUE.send = async () => { throw new Error('temporary queue failure'); };
    const { value, response } = await submit(worker, env);
    assert.equal(response.status, 202);
    const recovered = [];
    env.IMAGE_QUEUE.send = async body => recovered.push(body);
    await worker.scheduled({}, env);
    assert.equal(recovered[0].jobId, value.job.id);
  } finally { close(); }
});

test('an interrupted paid attempt is never reset to queued or automatically paid again', async () => {
  const { env, close } = environment();
  let calls = 0;
  try {
    const worker = make({ generate: async () => { calls++; return output(); } });
    const { value } = await submit(worker, env);
    env.DB.sqlite.prepare("UPDATE jobs SET status='processing',attempt_started_at=?,updated_at=? WHERE id=?")
      .run(Math.floor(Date.now()/1000)-1000, Math.floor(Date.now()/1000)-1000, value.job.id);
    await worker.queue({ messages: [message(value.job.id)] }, env);
    await worker.scheduled({}, env);
    const job = await (await worker.fetch(request('/api/jobs/' + value.job.id), env)).json();
    assert.equal(job.job.status, 'failed');
    assert.equal(job.job.errorCode, 'PROCESSING_INTERRUPTED'); assert.equal(calls, 0);
  } finally { close(); }
});

test('stored output is reconciled after an interrupted completion write', async () => {
  const { env, close } = environment();
  try {
    const worker = make();
    const { value } = await submit(worker, env);
    env.DB.sqlite.prepare("UPDATE jobs SET status='processing',updated_at=? WHERE id=?")
      .run(Math.floor(Date.now()/1000)-1000, value.job.id);
    await env.IMAGES.put(resultKey(value.job.id), png, { customMetadata: { provider: 'OpenAI', elapsedMs: '25', fallbackUsed: 'false' } });
    await worker.scheduled({}, env);
    const job = await (await worker.fetch(request('/api/jobs/' + value.job.id), env)).json();
    assert.equal(job.job.status, 'completed'); assert.equal(job.job.provider, 'OpenAI');
  } finally { close(); }
});

test('short-lived provider links require signature, processing state and active invite', async () => {
  const { env, close } = environment();
  try {
    const worker = make();
    const { value } = await submit(worker, env);
    env.DB.sqlite.prepare("UPDATE jobs SET status='processing' WHERE id=?").run(value.job.id);
    const signed = await assetUrl(env, value.job.id, 'person');
    const response = await worker.fetch(new Request(signed), env);
    assert.equal(response.status, 200);
    assert.equal((await worker.fetch(new Request(signed.replace('sig=', 'sig=x')), env)).status, 404);
    env.DB.sqlite.prepare('UPDATE invites SET enabled=0 WHERE uid=?').run('user-a');
    assert.equal((await worker.fetch(new Request(signed), env)).status, 404);
  } finally { close(); }
});

test('deleting a result removes R2 objects but retains charged attempt count', async () => {
  const { env, close } = environment();
  try {
    const worker = make();
    const { value } = await submit(worker, env);
    await worker.queue({ messages: [message(value.job.id)] }, env);
    assert.equal((await worker.fetch(request('/api/jobs/' + value.job.id, { method: 'DELETE' }), env)).status, 204);
    assert.equal(env.IMAGES.data.size, 0);
    assert.equal((await (await worker.fetch(request('/api/me'), env)).json()).quota.imagesUsed, 1);
  } finally { close(); }
});

test('account erasure works for revoked members, removes their data and leaves other accounts intact', async () => {
  const { env, close } = environment();
  try {
    const worker = make();
    await submit(worker, env);
    env.DB.sqlite.prepare('UPDATE invites SET enabled=0 WHERE uid=?').run('user-a');
    assert.equal((await worker.fetch(request('/api/account', { method: 'DELETE' }), env)).status, 204);
    assert.equal(env.DB.sqlite.prepare('SELECT * FROM invites WHERE uid=?').get('user-a'), undefined);
    assert.equal(env.DB.sqlite.prepare('SELECT count(*) AS total FROM jobs').get().total, 0);
    assert.equal(env.IMAGES.data.size, 0);
    assert.equal((await worker.fetch(request('/api/me', { token: 'token-b' }), env)).status, 200);
  } finally { close(); }
});

test('live token requests are bounded per identity and never replayed on the same key', async () => {
  const { env, close } = environment();
  let minted = 0;
  env.DECART_API_KEY = 'fake';
  try {
    const worker = make({ mint: async () => { minted++; return { apiKey: 'ek_test_only', maxSessionSeconds: 120 }; } });
    const key = crypto.randomUUID();
    const first = await worker.fetch(request('/api/realtime-token', { method: 'POST', key }), env);
    assert.equal(first.status, 200);
    const duplicate = await worker.fetch(request('/api/realtime-token', { method: 'POST', key }), env);
    assert.equal(duplicate.status, 409);
    const again = await worker.fetch(request('/api/realtime-token', { method: 'POST', key: crypto.randomUUID() }), env);
    assert.equal(again.status, 429); assert.equal(minted, 1);
  } finally { close(); }
});
