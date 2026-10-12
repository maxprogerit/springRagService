// Session lives in memory and is mirrored to sessionStorage (cleared when the tab closes).
// Storage can be unavailable (private mode, blocked site data), so every access is wrapped.

const SESSION_KEY = 'rag.session';
const COLLECTION_KEY = 'rag.collection';

let session = null;
let currentCollectionId = null;

function readStorage(key) {
  try {
    return sessionStorage.getItem(key);
  } catch {
    return null;
  }
}

function writeStorage(key, value) {
  try {
    if (value === null || value === undefined) sessionStorage.removeItem(key);
    else sessionStorage.setItem(key, value);
  } catch {
    // Not persisted; the in-memory copy still works for this page load.
  }
}

/** Reads the exp claim (seconds) of a JWT without verifying it; the server is the authority. */
export function tokenExpiry(token) {
  try {
    const payload = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
    const json = JSON.parse(atob(payload.padEnd(Math.ceil(payload.length / 4) * 4, '=')));
    return typeof json.exp === 'number' ? json.exp * 1000 : null;
  } catch {
    return null;
  }
}

function restore() {
  const raw = readStorage(SESSION_KEY);
  if (raw) {
    try {
      const parsed = JSON.parse(raw);
      const expiry = tokenExpiry(parsed.token);
      if (parsed && typeof parsed.token === 'string' && (expiry === null || expiry > Date.now())) session = parsed;
    } catch {
      session = null;
    }
  }
  const stored = Number(readStorage(COLLECTION_KEY));
  currentCollectionId = Number.isInteger(stored) && stored > 0 ? stored : null;
}
restore();

export function getSession() {
  return session;
}

export function setSession(next) {
  session = next;
  writeStorage(SESSION_KEY, next ? JSON.stringify(next) : null);
  if (!next) setCurrentCollection(null);
}

export function getCurrentCollection() {
  return currentCollectionId;
}

export function setCurrentCollection(id) {
  currentCollectionId = id || null;
  writeStorage(COLLECTION_KEY, currentCollectionId ? String(currentCollectionId) : null);
}
