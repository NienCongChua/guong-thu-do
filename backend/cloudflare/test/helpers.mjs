import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';

export const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/a9sAAAAASUVORK5CYII=', 'base64');
export class TestD1 {
  constructor() {
    this.sqlite = new DatabaseSync(':memory:');
    this.sqlite.exec(readFileSync(new URL('../migrations/0001_private_beta.sql', import.meta.url), 'utf8'));
    this.sqlite.exec(readFileSync(new URL('../migrations/0002_cleanup_tracking.sql', import.meta.url), 'utf8'));
  }
  prepare(sql) {
    const db = this;
    return {
      values: [],
      bind(...values) { this.values = values; return this; },
      async first() { return db.sqlite.prepare(sql).all(...this.values)[0] || null; },
      async all() { return { success: true, results: db.sqlite.prepare(sql).all(...this.values) }; },
      async run() {
        const result = db.sqlite.prepare(sql).run(...this.values);
        return { success: true, meta: { changes: Number(result.changes) } };
      }
    };
  }
  async batch(statements) {
    this.sqlite.exec('BEGIN');
    try {
      const results = [];
      for (const statement of statements) results.push(await statement.run());
      this.sqlite.exec('COMMIT'); return results;
    } catch (error) { this.sqlite.exec('ROLLBACK'); throw error; }
  }
}
export class TestBucket {
  data = new Map();
  async put(key, value, options = {}) {
    const bytes = value instanceof Blob ? new Uint8Array(await value.arrayBuffer()) : new Uint8Array(value);
    this.data.set(key, { bytes, ...options });
  }
  async get(key) {
    const value = this.data.get(key);
    if (!value) return null;
    return {
      ...value, size: value.bytes.length,
      body: new ReadableStream({ start(controller) { controller.enqueue(value.bytes.slice()); controller.close(); } }),
      arrayBuffer: async () => value.bytes.slice().buffer
    };
  }
  async head(key) { return this.get(key); }
  async delete(keys) { for (const key of Array.isArray(keys) ? keys : [keys]) this.data.delete(key); }
}
export function environment() {
  const DB = new TestD1();
  for (const [email, uid] of [['a@example.com', 'user-a'], ['b@example.com', 'user-b']]) {
    DB.sqlite.prepare('INSERT INTO invites(email,uid) VALUES (?,?)').run(email, uid);
  }
  const sent = [];
  const env = {
    DB, IMAGES: new TestBucket(), IMAGE_QUEUE: { send: async value => sent.push(value) },
    FIREBASE_PROJECT_ID: 'test-project', OPENAI_API_KEY: 'fake-test-only',
    PUBLIC_API_ORIGIN: 'https://try-on.example', ASSET_SIGNING_KEY: 'test-only-signing-key-at-least-32-characters',
    RESULT_RETENTION_DAYS: '7'
  };
  return { env, sent, close: () => DB.sqlite.close() };
}
export async function verifyToken(token) {
  const users = {
    'token-a': { uid: 'user-a', email: 'a@example.com', authTime: Math.floor(Date.now() / 1000) },
    'token-b': { uid: 'user-b', email: 'b@example.com', authTime: Math.floor(Date.now() / 1000) },
    'uninvited': { uid: 'user-c', email: 'c@example.com', authTime: Math.floor(Date.now() / 1000) }
  };
  if (!users[token]) throw new Error('TEST_UNAUTHORIZED');
  return users[token];
}
export function upload({ quality = 'fast', fallback = false } = {}) {
  const form = new FormData();
  form.append('person', new Blob([png], { type: 'image/png' }), 'person.png');
  form.append('garment', new Blob([png], { type: 'image/png' }), 'garment.png');
  form.append('kind', 'top'); form.append('quality', quality); form.append('allowFallback', String(fallback));
  return form;
}
export function request(path, { token = 'token-a', key, method = 'GET', body } = {}) {
  const headers = { Authorization: 'Bearer ' + token };
  if (key) headers['Idempotency-Key'] = key;
  return new Request('https://try-on.example' + path, { method, headers, body });
}
export function message(jobId) {
  return {
    body: { jobId }, acknowledged: false, retried: false,
    ack() { this.acknowledged = true; }, retry() { this.retried = true; }
  };
}
