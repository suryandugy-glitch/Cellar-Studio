import { test } from 'node:test';
import assert from 'node:assert/strict';
import { buildAccountHandler } from '../api/account.mjs';
import { accountConfig } from '../server/account-config.mjs';

const config = {
  url: 'https://project.supabase.co',
  origin: 'https://cellar-csv.vercel.app',
  key: 'sb_publishable_test',
  providers: { google: 'google' },
};
const profile = {
  email: 'test@example.net',
  user_metadata: { full_name: 'Test Account', avatar_url: 'https://lh3.googleusercontent.com/a/example=s96-c' },
  app_metadata: { provider: 'google', providers: ['google', 'google'] },
  last_sign_in_at: '2026-11-28T08:00:00.000Z',
};

function mockHandler({ settings = config, auth = {} } = {}) {
  const calls = [];
  let user = profile;
  const handler = buildAccountHandler({
    getConfig: () => settings,
    createClient(url, key, { cookies }) {
      const setSession = () => cookies.setAll([{ name: '__Host-cellar-session', value: 'test-session', options: {} }]);
      return {
        auth: {
          getUser: async () => ({ data: { user } }),
          updateUser: async ({ data }) => {
            calls.push(['update', data]);
            user = { ...user, user_metadata: { ...user.user_metadata, ...data } };
            return { data: { user } };
          },
          signInWithPassword: async details => { calls.push(['signin', details]); setSession(); return {}; },
          signUp: async details => { calls.push(['signup', details]); return { data: { session: null } }; },
          signOut: async details => { calls.push(['logout', details]); return {}; },
          signInWithOAuth: async ({ provider, options }) => {
            calls.push(['oauth', provider, options]);
            cookies.setAll([{ name: '__Host-cellar-session-code-verifier', value: 'verifier', options: {} }]);
            return { data: { url: url + '/auth/v1/authorize?provider=' + provider } };
          },
          exchangeCodeForSession: async code => { calls.push(['callback', code]); setSession(); return {}; },
          verifyOtp: async options => { calls.push(['verify', options]); setSession(); return {}; },
          ...auth,
        },
      };
    },
  });
  return { handler, calls };
}

async function call(handler, action, options = {}) {
  const req = {
    method: options.method || (options.body === undefined ? 'GET' : 'POST'),
    url: '/api/account?action=' + action,
    headers: { 'content-type': 'application/json', origin: config.origin, ...options.headers },
    body: options.body,
  };
  const headers = new Map();
  const response = {
    statusCode: 200,
    setHeader: (key, value) => headers.set(key, value),
    end(value) { this.body = value ? JSON.parse(value) : null; },
  };
  await handler(req, response);
  return { status: response.statusCode, headers, data: response.body };
}

test('account configuration requires a publishable key and HTTPS', () => {
  assert.equal(accountConfig({}), null);
  assert.throws(() => accountConfig({ CELLAR_SUPABASE_URL: config.url, CELLAR_SUPABASE_PUBLISHABLE_KEY: 'secret' }), /publishable key/);
  assert.deepEqual(accountConfig({ CELLAR_SUPABASE_URL: config.url, CELLAR_SUPABASE_PUBLISHABLE_KEY: config.key, CELLAR_AUTH_MICROSOFT: '1' }).providers, {});
});

test('unconfigured accounts leave guest browsing available and reject writes', async () => {
  const { handler } = mockHandler({ settings: null });
  assert.deepEqual((await call(handler, 'session')).data, { available: false, providers: [], user: null });
  assert.equal((await call(handler, 'signin', { body: {} })).status, 503);
});

test('email registration requires confirmation and rejects a cross-site POST', async () => {
  const { handler, calls } = mockHandler();
  const signup = { name: 'Test Account', email: 'test@example.net', password: 'a long test password' };
  assert.equal((await call(handler, 'signup', { body: signup, headers: { origin: 'https://other.example' } })).status, 403);
  assert.equal((await call(handler, 'signup', { body: { ...signup, password: 'short' } })).status, 400);
  assert.equal(calls.length, 0);
  const result = await call(handler, 'signup', { body: signup });
  assert.deepEqual(result.data, { confirmation: true });
  assert.equal(result.headers.get('Set-Cookie'), undefined);
  assert.equal(calls[0][1].options.data.full_name, 'Test Account');
  const confirmed = await call(handler, 'confirm&type=email&token_hash=once');
  assert.equal(confirmed.headers.get('Location'), '/signin.html?notice=verified');
  assert.equal(confirmed.headers.get('Set-Cookie'), undefined);
});

test('Google returns only the configured authorize origin with session-bound callbacks', async () => {
  const { handler, calls } = mockHandler();
  for (const [provider, name] of Object.entries(config.providers)) {
    const result = await call(handler, 'oauth', { body: { provider, remember: true } });
    assert.equal(result.data.url, config.url + '/auth/v1/authorize?provider=' + name);
    assert.ok(result.headers.get('Set-Cookie').every(value => /Max-Age=900; Path=\/; HttpOnly; Secure; SameSite=Lax/.test(value)));
  }
  assert.equal(calls[0][2].redirectTo, config.origin + '/api/account?action=callback');
  const unbound = await call(handler, 'callback&code=once');
  assert.equal(unbound.headers.get('Location'), '/signin.html?notice=provider');
  const bound = await call(handler, 'callback&code=once', { headers: { cookie: '__Host-cellar-signin=keep' } });
  assert.equal(bound.headers.get('Location'), '/account.html');
  assert.ok(bound.headers.get('Set-Cookie').some(value => /^__Host-cellar-session=test-session; Max-Age=2592000/.test(value)));
});

test('the real Supabase client generates a PKCE verifier and authorization challenge', async () => {
  const handler = buildAccountHandler({ getConfig: () => config });
  const result = await call(handler, 'oauth', { body: { provider: 'google' } });
  assert.equal(result.status, 200);
  const url = new URL(result.data.url);
  assert.equal(url.searchParams.get('provider'), 'google');
  assert.equal(url.searchParams.get('code_challenge_method'), 's256');
  assert.ok(url.searchParams.get('code_challenge').length > 30);
  assert.ok(result.headers.get('Set-Cookie').some(value => value.startsWith('__Host-cellar-session-code-verifier=')));
});

test('remember is opt-in and signed-in account data is private and revocable', async () => {
  const { handler } = mockHandler();
  const input = { email: 'test@example.net', password: 'a long test password' };
  const current = await call(handler, 'signin', { body: input });
  assert.ok(current.headers.get('Set-Cookie').some(value => /^__Host-cellar-session=test-session; Path=\/; HttpOnly; Secure/.test(value)));
  assert.equal((await call(handler, 'session')).data.user, null);
  const signedIn = { cookie: '__Host-cellar-session.0=test-session; __Host-cellar-remember=1' };
  const session = await call(handler, 'session', { headers: signedIn });
  assert.equal(session.headers.get('Cache-Control'), 'private, no-store');
  assert.deepEqual(session.data.user, {
    email: profile.email, name: 'Test Account', avatarUrl: profile.user_metadata.avatar_url,
  });
  const logout = await call(handler, 'logout', { body: {}, headers: signedIn });
  assert.ok(logout.headers.get('Set-Cookie').some(value => value.startsWith('__Host-cellar-session.0=; Max-Age=0')));
});

test('profile uses the provider picture and ignores untrusted photo URLs', async () => {
  const headers = { cookie: '__Host-cellar-session=test-session' };
  const metadata = {
    full_name: ' ',
    name: 'Test Name',
    avatar_url: 'https://untrusted.example/picture.png',
    picture: 'https://lh6.googleusercontent.com/a/test',
    unrelated: 'private metadata',
  };
  const { handler } = mockHandler({ auth: {
    getUser: async () => ({ data: { user: { ...profile, user_metadata: metadata } } }),
  } });
  const session = await call(handler, 'session', { headers });
  assert.deepEqual(session.data.user, { email: profile.email, name: 'Test Name', avatarUrl: metadata.picture });
  metadata.picture = 'http://lh3.googleusercontent.com/a/test';
  assert.equal((await call(handler, 'session', { headers })).data.user.avatarUrl, null);
});

test('only the signed-in owner can save and view optional profile details', async () => {
  const { handler, calls } = mockHandler();
  const headers = { cookie: '__Host-cellar-session=test-session' };
  const body = {
    name: ' New Name ', organization: 'Example Studio', jobTitle: 'Engineer',
    website: 'https://example.net', role: 'admin', email: 'another@example.net',
  };
  assert.equal((await call(handler, 'profile', { body })).status, 401);
  assert.equal((await call(handler, 'profile', { body, headers: { ...headers, origin: 'https://other.example' } })).status, 403);
  for (const website of ['javascript:alert(1)', 'https://secret@example.net/', 'not a URL']) {
    assert.equal((await call(handler, 'profile', { headers, body: { ...body, website } })).status, 400);
  }
  assert.equal((await call(handler, 'profile', { headers, body: { ...body, organization: 'x'.repeat(101) } })).status, 400);
  assert.equal(calls.length, 0);

  const saved = await call(handler, 'profile', { headers, body });
  const details = { name: 'New Name', organization: 'Example Studio', jobTitle: 'Engineer', website: 'https://example.net/' };
  assert.deepEqual(calls, [['update', { cellar_profile: details }]]);
  assert.deepEqual(saved.data.user, {
    ...details, email: profile.email, avatarUrl: profile.user_metadata.avatar_url,
    signInMethods: ['Google'], lastSignIn: '2026-11-28T08:00:00.000Z',
  });
  const reloaded = await call(handler, 'profile', { headers });
  assert.equal(reloaded.headers.get('Cache-Control'), 'private, no-store');
  assert.deepEqual(reloaded.data.user, saved.data.user);
  assert.equal((await call(handler, 'profile')).data.user, null);
  const headerSession = await call(handler, 'session', { headers });
  assert.deepEqual(headerSession.data.user, {
    name: 'New Name', email: profile.email, avatarUrl: profile.user_metadata.avatar_url,
  });
  const cleared = await call(handler, 'profile', { headers, body: { name: '', organization: '', jobTitle: '', website: '' } });
  assert.equal(cleared.data.user.name, 'Test Account');
  assert.equal(cleared.data.user.website, '');
});

test('sign out on all devices requires an authenticated account and requests global revocation', async () => {
  const { handler, calls } = mockHandler();
  const body = { allDevices: true };
  assert.equal((await call(handler, 'logout', { body })).status, 401);
  assert.equal(calls.length, 0);
  const headers = { cookie: '__Host-cellar-session=test-session' };
  const result = await call(handler, 'logout', { body, headers });
  assert.deepEqual(calls, [['logout', { scope: 'global' }]]);
  assert.equal(result.status, 200);
  assert.ok(result.headers.get('Set-Cookie').some(value => value.startsWith('__Host-cellar-session=; Max-Age=0')));
});
