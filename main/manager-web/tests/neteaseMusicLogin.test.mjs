import assert from 'node:assert/strict';
import test from 'node:test';

import {
  checkNeteaseQrLogin,
  createNeteaseQrLogin,
  getNeteaseLoginProfile,
  normalizeNeteaseApiBaseUrl,
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

test('creates a QR login using POST requests', async () => {
  const calls = [];
  const fakeFetch = async (url, options) => {
    calls.push({ url, options });
    if (url.endsWith('/login/qr/key')) {
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
