import { getSession } from './state.js';

export class ApiError extends Error {
  constructor(status, code, message) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
  }
}

let unauthorizedHandler = () => {};

/** Called when the server rejects the token of a signed-in user (expired, revoked or invalid). */
export function onUnauthorized(handler) {
  unauthorizedHandler = handler;
}

function authHeaders(extra = {}) {
  const headers = { ...extra };
  const session = getSession();
  if (session) headers.Authorization = `Bearer ${session.token}`;
  return headers;
}

async function failure(response, hadSession) {
  let data = null;
  try {
    data = await response.json();
  } catch {
    // Non-JSON body (proxy error page); fall through to the generic message.
  }
  if (response.status === 401 && hadSession) unauthorizedHandler();
  return new ApiError(response.status, data?.code ?? 'ERROR', data?.message ?? `Request failed (${response.status}).`);
}

async function request(method, path, { body, form } = {}) {
  const headers = {};
  let payload;
  if (form) {
    payload = form;
  } else if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
    payload = JSON.stringify(body);
  }

  let response;
  try {
    response = await fetch(path, { method, headers: authHeaders(headers), body: payload });
  } catch {
    throw new ApiError(0, 'NETWORK', 'Cannot reach the server.');
  }
  if (!response.ok) throw await failure(response, Boolean(getSession()));
  if (response.status === 204 || response.status === 202 && response.headers.get('content-length') === '0') {
    return { data: null, response };
  }
  let data = null;
  try {
    data = await response.json();
  } catch {
    // An empty 202/200 body is fine.
  }
  return { data, response };
}

/** Lists come back as arrays; the total number of items is in the X-Total-Count header. */
async function page(path, { page: pageNumber = 0, size = 50 } = {}) {
  const { data, response } = await request('GET', `${path}?page=${pageNumber}&size=${size}`);
  const total = Number(response.headers.get('X-Total-Count'));
  return { items: data ?? [], total: Number.isFinite(total) && total > 0 ? total : (data ?? []).length };
}

const enc = encodeURIComponent;
const data = async (promise) => (await promise).data;

/**
 * Streams an answer over server-sent events. Calls handlers.onToken(text) for every piece, then resolves with the
 * validated final answer (which replaces the streamed text). Rejects with ApiError if the server sends an error.
 */
async function askStream(id, body, { onToken, signal } = {}) {
  let response;
  try {
    response = await fetch(`/api/collections/${enc(id)}/ask/stream`, {
      method: 'POST',
      headers: authHeaders({ 'Content-Type': 'application/json', Accept: 'text/event-stream' }),
      body: JSON.stringify(body),
      signal,
    });
  } catch (e) {
    if (e.name === 'AbortError') throw e;
    throw new ApiError(0, 'NETWORK', 'Cannot reach the server.');
  }
  if (!response.ok) throw await failure(response, Boolean(getSession()));

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let result = null;

  function handle(block) {
    let name = 'message';
    const dataLines = [];
    for (const line of block.split('\n')) {
      if (line.startsWith('event:')) name = line.slice(6).trim();
      else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim());
    }
    if (dataLines.length === 0) return;
    const payload = JSON.parse(dataLines.join('\n'));
    if (name === 'token') onToken?.(payload.text);
    else if (name === 'final') result = payload;
    else if (name === 'error') throw new ApiError(payload.status, payload.code, payload.message);
  }

  for (;;) {
    const { done, value } = await reader.read();
    buffer += decoder.decode(value ?? new Uint8Array(), { stream: !done });
    let boundary;
    while ((boundary = buffer.indexOf('\n\n')) >= 0) {
      handle(buffer.slice(0, boundary));
      buffer = buffer.slice(boundary + 2);
    }
    if (done) break;
  }
  if (buffer.trim()) handle(buffer);
  if (!result) throw new ApiError(0, 'STREAM_ENDED', 'The answer stream ended unexpectedly.');
  return result;
}

export const api = {
  config: () => data(request('GET', '/api/auth/config')),
  register: (email, password) => data(request('POST', '/api/auth/register', { body: { email, password } })),
  login: (email, password) => data(request('POST', '/api/auth/login', { body: { email, password } })),
  forgotPassword: (email) => request('POST', '/api/auth/forgot-password', { body: { email } }),
  resetPassword: (token, newPassword) => request('POST', '/api/auth/reset-password', { body: { token, newPassword } }),
  verifyEmail: (token) => request('POST', '/api/auth/verify-email', { body: { token } }),
  resendVerification: (email) => request('POST', '/api/auth/resend-verification', { body: { email } }),
  changePassword: (currentPassword, newPassword) =>
    data(request('POST', '/api/auth/change-password', { body: { currentPassword, newPassword } })),
  logoutEverywhere: () => request('POST', '/api/auth/logout-all', { body: {} }),

  listCollections: (options) => page('/api/collections', options),
  createCollection: (name) => data(request('POST', '/api/collections', { body: { name } })),
  deleteCollection: (id) => request('DELETE', `/api/collections/${enc(id)}`),

  listDocuments: (id, options) => page(`/api/collections/${enc(id)}/documents`, options),
  uploadDocument: (id, file) => {
    const form = new FormData();
    form.append('file', file);
    return data(request('POST', `/api/collections/${enc(id)}/documents`, { form }));
  },
  deleteDocument: (id, documentId) => request('DELETE', `/api/collections/${enc(id)}/documents/${enc(documentId)}`),

  stats: (id) => data(request('GET', `/api/collections/${enc(id)}/stats`)),
  search: (id, question, topK) => data(request('POST', `/api/collections/${enc(id)}/search`, { body: { question, topK } })),
  ask: (id, question, topK, history) =>
    data(request('POST', `/api/collections/${enc(id)}/ask`, { body: { question, topK, history } })),
  askStream: (id, question, topK, history, handlers) => askStream(id, { question, topK, history }, handlers),

  health: () => data(request('GET', '/actuator/health')),
};
