export function normalizeNeteaseApiBaseUrl(value) {
  const baseUrl = String(value || '').trim().replace(/\/+$/, '');
  if (!/^https?:\/\/[^/]+/i.test(baseUrl)) {
    throw new Error('网易云音乐 API 地址必须是有效的 HTTP 或 HTTPS 地址');
  }
  return baseUrl;
}

export function resolveNeteaseLoginApiBaseUrl(apiBaseUrl, managerApiBaseUrl) {
  const normalizedApiBaseUrl = normalizeNeteaseApiBaseUrl(apiBaseUrl);
  const hostname = new URL(normalizedApiBaseUrl).hostname.toLowerCase();
  if (!['127.0.0.1', 'localhost', '[::1]'].includes(hostname)) {
    return normalizedApiBaseUrl;
  }
  const normalizedManagerApiBaseUrl = String(managerApiBaseUrl || '').trim().replace(/\/+$/, '');
  return `${normalizedManagerApiBaseUrl}/models/provider/plugin/netease-login`;
}

function normalizeNeteaseRequestBaseUrl(value) {
  const baseUrl = String(value || '').trim().replace(/\/+$/, '');
  if (baseUrl.startsWith('/') && !baseUrl.startsWith('//')) {
    return baseUrl;
  }
  return normalizeNeteaseApiBaseUrl(baseUrl);
}

let lastRequestTimestamp = 0;

function nextRequestTimestamp() {
  lastRequestTimestamp = Math.max(Date.now(), lastRequestTimestamp + 1);
  return String(lastRequestTimestamp);
}

export async function requestNeteaseApi(
  baseUrl,
  path,
  params = {},
  fetchImpl = fetch,
  signal = undefined,
) {
  const normalizedBaseUrl = normalizeNeteaseRequestBaseUrl(baseUrl);
  const timestamp = nextRequestTimestamp();
  const body = new URLSearchParams({
    ...params,
    timestamp,
  });
  const separator = path.includes('?') ? '&' : '?';
  const requestUrl = `${normalizedBaseUrl}${path}${separator}timestamp=${timestamp}`;
  const controller = new AbortController();
  const abortRequest = () => controller.abort();
  signal?.addEventListener('abort', abortRequest, { once: true });
  const timeout = setTimeout(abortRequest, 10000);
  let response;
  try {
    response = await fetchImpl(requestUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
      body,
      signal: controller.signal,
    });
  } catch (error) {
    if (controller.signal.aborted) {
      throw new Error('网易云音乐 API 请求已取消或超时');
    }
    throw error;
  } finally {
    clearTimeout(timeout);
    signal?.removeEventListener('abort', abortRequest);
  }
  if (!response.ok) {
    throw new Error(`网易云音乐 API 请求失败（HTTP ${response.status}）`);
  }
  const payload = await response.json();
  if (!payload || typeof payload !== 'object') {
    throw new Error('网易云音乐 API 返回了无效数据');
  }
  return payload;
}

export async function createNeteaseQrLogin(baseUrl, fetchImpl = fetch, signal = undefined) {
  const keyPayload = await requestNeteaseApi(baseUrl, '/login/qr/key', {}, fetchImpl, signal);
  const key = keyPayload?.data?.unikey;
  if (keyPayload.code !== 200 || !key) {
    throw new Error(keyPayload.message || '生成网易云登录二维码密钥失败');
  }

  const qrPayload = await requestNeteaseApi(
    baseUrl,
    '/login/qr/create',
    { key, qrimg: 'true' },
    fetchImpl,
    signal,
  );
  const qrImage = qrPayload?.data?.qrimg;
  if (qrPayload.code !== 200 || !qrImage) {
    throw new Error(qrPayload.message || '生成网易云登录二维码失败');
  }
  return { key, qrImage };
}

export function checkNeteaseQrLogin(baseUrl, key, fetchImpl = fetch, signal = undefined) {
  return requestNeteaseApi(
    baseUrl,
    '/login/qr/check',
    { key, noCookie: 'true' },
    fetchImpl,
    signal,
  );
}

export async function getNeteaseLoginProfile(
  baseUrl,
  cookie,
  fetchImpl = fetch,
  signal = undefined,
) {
  if (!cookie) {
    return null;
  }
  const payload = await requestNeteaseApi(
    baseUrl,
    '/user/account',
    { cookie },
    fetchImpl,
    signal,
  );
  if (payload.code !== 200 || !payload.profile?.userId) {
    return null;
  }
  return payload.profile;
}
