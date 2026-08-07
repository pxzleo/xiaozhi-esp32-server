import assert from 'node:assert/strict';
import test from 'node:test';

import {
  checkNeteaseQrLogin,
  createNeteaseQrLogin,
  getNeteaseLoginProfile,
  normalizeNeteaseApiBaseUrl,
  requestNeteaseApi,
  resolveNeteaseLoginApiBaseUrl,
} from '../src/utils/neteaseMusicLogin.mjs';

function response(payload, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => payload,
  };
}

test('normalizes an HTTP API base URL and rejects non-HTTP values', () => {
  assert.equal(normalizeNeteaseApiBaseUrl(' http://127.0.0.1:3000/ '), 'http://127.0.0.1:3000');
  assert.throws(() => normalizeNeteaseApiBaseUrl('file:///tmp/api'));
});

test('routes loopback login APIs through the authenticated manager API proxy', () => {
  const proxyBaseUrl = '/xiaozhi/models/provider/plugin/netease-login';

  assert.equal(resolveNeteaseLoginApiBaseUrl('http://127.0.0.1:3000', '/xiaozhi'), proxyBaseUrl);
  assert.equal(resolveNeteaseLoginApiBaseUrl('http://localhost:3000/', '/xiaozhi/'), proxyBaseUrl);
  assert.equal(
    resolveNeteaseLoginApiBaseUrl('https://music-api.example.com', '/xiaozhi'),
    'https://music-api.example.com',
  );
});

test('creates a QR login through the same-origin manager API proxy', async () => {
  const calls = [];
  const fakeFetch = async (url) => {
    calls.push(url);
    if (new URL(url, 'http://manager.example.com').pathname.endsWith('/login/qr/key')) {
      return response({ code: 200, data: { unikey: 'proxy-key' } });
    }
    return response({ code: 200, data: { qrimg: 'data:image/png;base64,proxy' } });
  };
  const proxyBaseUrl = resolveNeteaseLoginApiBaseUrl('http://127.0.0.1:3000', '/xiaozhi');

  const result = await createNeteaseQrLogin(proxyBaseUrl, fakeFetch);

  assert.equal(result.key, 'proxy-key');
  assert.equal(calls.length, 2);
  assert.match(calls[0], /^\/xiaozhi\/models\/provider\/plugin\/netease-login\/login\/qr\/key\?/);
});

test('creates a QR login using POST requests', async () => {
  const calls = [];
  const fakeFetch = async (url, options) => {
    calls.push({ url, options });
    if (new URL(url).pathname.endsWith('/login/qr/key')) {
      return response({ code: 200, data: { unikey: 'qr-key' } });
    }
    return response({ code: 200, data: { qrimg: 'data:image/png;base64,abc' } });
  };

  const result = await createNeteaseQrLogin('https://music-api.example.com/', fakeFetch);

  assert.deepEqual(result, { key: 'qr-key', qrImage: 'data:image/png;base64,abc' });
  assert.equal(calls.length, 2);
  assert.equal(calls[0].options.method, 'POST');
  assert.match(calls[1].options.body.toString(), /key=qr-key/);
});

test('uses a unique URL timestamp to bypass path-only API caches', async () => {
  const urls = [];
  const fakeFetch = async (url) => {
    urls.push(new URL(url));
    return response({ code: 200 });
  };

  await requestNeteaseApi('https://music-api.example.com', '/login/qr/key', {}, fakeFetch);
  await requestNeteaseApi('https://music-api.example.com', '/login/qr/key', {}, fakeFetch);

  assert.ok(urls[0].searchParams.get('timestamp'));
  assert.ok(urls[1].searchParams.get('timestamp'));
  assert.notEqual(urls[0].href, urls[1].href);
});

test('returns QR status cookie without placing it in the URL', async () => {
  let request;
  const fakeFetch = async (url, options) => {
    request = { url, options };
    return response({ code: 803, cookie: 'MUSIC_U=secret' });
  };

  const result = await checkNeteaseQrLogin('https://music-api.example.com', 'qr-key', fakeFetch);

  assert.equal(result.cookie, 'MUSIC_U=secret');
  assert.doesNotMatch(request.url, /qr-key|MUSIC_U/);
  assert.match(request.options.body.toString(), /key=qr-key/);
});

test('recognizes valid and expired account cookies', async () => {
  const validFetch = async () => response({ code: 200, profile: { userId: 7, nickname: '测试用户' } });
  const expiredFetch = async () => response({ code: 200, profile: null });

  assert.equal((await getNeteaseLoginProfile('https://music-api.example.com', 'cookie', validFetch)).nickname, '测试用户');
  assert.equal(await getNeteaseLoginProfile('https://music-api.example.com', 'cookie', expiredFetch), null);
});

test('cancels an in-flight QR status request', async () => {
  const controller = new AbortController();
  const pendingFetch = (_url, options) => new Promise((_resolve, reject) => {
    options.signal.addEventListener('abort', () => reject(new Error('aborted')), { once: true });
  });

  const request = checkNeteaseQrLogin(
    'https://music-api.example.com',
    'qr-key',
    pendingFetch,
    controller.signal,
  );
  controller.abort();

  await assert.rejects(request, /取消或超时/);
});
