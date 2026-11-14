import { test } from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPair, SignJWT } from 'jose';
import { createFirebaseVerifier, authorize } from '../src/auth.mjs';
import { environment, request } from './helpers.mjs';

const { publicKey, privateKey } = await generateKeyPair('RS256');
const timestamp = Math.floor(Date.now() / 1000);
const claims = {
  sub: 'google-user-1', aud: 'test-project', iss: 'https://securetoken.google.com/test-project',
  iat: timestamp, exp: timestamp + 3600, auth_time: timestamp - 10,
  email: 'Invited@Example.com', email_verified: true, firebase: { sign_in_provider: 'google.com' }
};
const verifier = createFirebaseVerifier({ now: () => timestamp, resolveKey: async () => publicKey });
const sign = value => new SignJWT(value).setProtectedHeader({ alg: 'RS256', kid: 'test' }).sign(privateKey);

test('valid RS256 Firebase Google identity is verified and email normalized', async () => {
  const identity = await verifier(await sign(claims), 'test-project');
  assert.equal(identity.uid, claims.sub); assert.equal(identity.email, 'invited@example.com');
});

test('invalid signature, audience, issuer and expiration fail closed', async () => {
  const token = await sign(claims);
  const parts = token.split('.');
  parts[1] = Buffer.from(JSON.stringify({ ...claims, sub: 'attacker' })).toString('base64url');
  await assert.rejects(verifier(parts.join('.'), 'test-project'), { code: 'AUTH_EXPIRED' });
  for (const change of [
    { aud: 'another-project' }, { iss: 'https://untrusted.example' },
    { exp: timestamp - 1 }, { sub: '' }, { iat: timestamp + 60 }, { auth_time: timestamp + 60 }
  ]) await assert.rejects(verifier(await sign({ ...claims, ...change }), 'test-project'));
});

test('unverified email and non-Google providers cannot redeem private beta invitations', async () => {
  for (const change of [
    { email_verified: false }, { firebase: { sign_in_provider: 'password' } },
    { firebase: { sign_in_provider: 'anonymous' } }, { email: '' }, { auth_time: undefined }
  ]) await assert.rejects(verifier(await sign({ ...claims, ...change }), 'test-project'));
});

test('no project configuration is never treated as demo authentication', async () => {
  await assert.rejects(verifier(await sign(claims), ''), { code: 'AUTH_NOT_CONFIGURED' });
});

test('invitation can bind only once to a verified Firebase subject', async () => {
  const { env, close } = environment();
  try {
    env.DB.sqlite.prepare("INSERT INTO invites(email) VALUES ('invited@example.com')").run();
    const first = await authorize(request('/api/me'), env, async () => ({ uid: 'first-uid', email: 'invited@example.com', authTime: timestamp }));
    assert.equal(first.invite.uid, 'first-uid');
    await assert.rejects(authorize(request('/api/me'), env, async () => ({
      uid: 'second-uid', email: 'invited@example.com', authTime: timestamp
    })), { code: 'INVITE_REQUIRED' });
  } finally { close(); }
});

test('expired invites and sessions before the operator revocation timestamp are denied', async () => {
  const { env, close } = environment();
  try {
    const profile = async () => ({ uid: 'user-a', email: 'a@example.com', authTime: timestamp - 20 });
    env.DB.sqlite.prepare('UPDATE invites SET tokens_valid_after=? WHERE uid=?').run(timestamp, 'user-a');
    await assert.rejects(authorize(request('/api/me'), env, profile), { code: 'INVITE_REQUIRED' });
    env.DB.sqlite.prepare('UPDATE invites SET tokens_valid_after=0,expires_at=? WHERE uid=?').run(timestamp - 1, 'user-a');
    await assert.rejects(authorize(request('/api/me'), env, profile), { code: 'INVITE_REQUIRED' });
  } finally { close(); }
});
