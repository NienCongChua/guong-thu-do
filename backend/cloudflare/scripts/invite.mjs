import { parseArgs } from 'node:util';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const { values } = parseArgs({
  options: {
    email: { type: 'string' }, images: { type: 'string', default: '6' },
    live: { type: 'string', default: '3' }, days: { type: 'string', default: '30' },
    revoke: { type: 'boolean', default: false }, remote: { type: 'boolean', default: false }
  }
});
const email = values.email?.trim().toLowerCase();
if (!email || email.length > 254 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
  console.error('Dùng: npm run invite -- --email customer@example.com --images 6 --live 3 --days 30 --remote');
  process.exit(1);
}
for (const name of ['images', 'live', 'days']) {
  const n = Number(values[name]);
  const max = name === 'days' ? 365 : 100;
  if (!Number.isInteger(n) || n < (name === 'days' ? 1 : 0) || n > max) {
    console.error('Hạn mức hoặc thời hạn không hợp lệ.'); process.exit(1);
  }
}
const quoted = "'" + email.replaceAll("'", "''") + "'";
const sql = values.revoke ?
  'UPDATE invites SET enabled=0,tokens_valid_after=unixepoch() WHERE email=' + quoted + ';' :
  'INSERT INTO invites(email,image_daily_limit,live_daily_limit,expires_at) VALUES (' + quoted + ',' +
    Number(values.images) + ',' + Number(values.live) + ',unixepoch()+' + Number(values.days) * 86400 +
    ') ON CONFLICT(email) DO UPDATE SET enabled=1,image_daily_limit=excluded.image_daily_limit,' +
    'live_daily_limit=excluded.live_daily_limit,expires_at=excluded.expires_at;';
const require = createRequire(import.meta.url);
const binary = join(dirname(require.resolve('wrangler/package.json')), 'bin', 'wrangler.js');
const result = spawnSync(process.execPath, [binary, 'd1', 'execute', 'DB',
  values.remote ? '--remote' : '--local', '--command', sql], {
  cwd: fileURLToPath(new URL('../', import.meta.url)), stdio: 'inherit', shell: false
});
process.exit(result.status ?? 1);
