import { loadEnvFile } from 'node:process';
import { randomBytes } from 'node:crypto';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

if (!process.argv.includes('--from-legacy-env')) {
  console.error('Dùng node scripts/seed-secrets.mjs --from-legacy-env để chuyển key backend hiện tại vào Worker secrets.');
  process.exit(1);
}
// Operational import only. Never print the source .env, secret values or stdin.
loadEnvFile(fileURLToPath(new URL('../../.env', import.meta.url)));
const secrets = {};
for (const name of ['OPENAI_API_KEY', 'DECART_API_KEY', 'FASHN_API_KEY']) {
  if (process.env[name]?.trim()) secrets[name] = process.env[name].trim();
}
secrets.ASSET_SIGNING_KEY = randomBytes(32).toString('base64url');
const require = createRequire(import.meta.url);
const result = spawnSync(process.execPath, [join(dirname(require.resolve('wrangler/package.json')), 'bin', 'wrangler.js'), 'secret', 'bulk'], {
  cwd: fileURLToPath(new URL('../', import.meta.url)),
  input: JSON.stringify(secrets), encoding: 'utf8',
  stdio: ['pipe', 'pipe', 'pipe'], shell: false,
  env: { ...process.env, WRANGLER_SEND_METRICS: 'false' }
});
if (result.status !== 0) {
  console.error('Không chuyển được secrets. Kiểm tra wrangler login và Worker đích; không in giá trị key.');
  process.exit(1);
}
console.log('Đã lưu ' + Object.keys(secrets).join(', ') + ' vào Worker secrets; không ghi giá trị vào mã nguồn.');
