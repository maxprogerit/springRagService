import { api, onUnauthorized } from './api.js';
import { clear, h, icon, toast } from './dom.js';
import { getCurrentCollection, getSession, setSession } from './state.js';
import * as landing from './pages/landing.js';
import * as auth from './pages/auth.js';
import * as forgot from './pages/forgot.js';
import * as reset from './pages/reset.js';
import * as verify from './pages/verify.js';
import * as collections from './pages/collections.js';
import * as documents from './pages/documents.js';
import * as ask from './pages/ask.js';
import * as inspector from './pages/inspector.js';
import * as settings from './pages/settings.js';

const PAGES = {
  collections: { title: 'Collections', icon: 'collections', page: collections },
  documents: { title: 'Documents', icon: 'documents', page: documents, scoped: true },
  ask: { title: 'Ask', icon: 'ask', page: ask, scoped: true },
  inspector: { title: 'Retrieval inspector', icon: 'inspector', page: inspector, scoped: true },
  settings: { title: 'Settings', icon: 'settings', page: settings },
};

/** Pages reached from e-mail links or the sign-in form: they work without a session. */
const PUBLIC_PAGES = {
  forgot: { title: 'Forgot password', page: forgot },
  reset: { title: 'Choose a new password', page: reset },
  verify: { title: 'Confirm e-mail', page: verify },
};

let disposeCurrent = null;
let renderToken = 0;

function parseHash() {
  const [route, param] = location.hash.replace(/^#\/?/, '').split('/');
  if (Object.hasOwn(PUBLIC_PAGES, route)) return { route, param: param || '', public: true };
  const known = Object.hasOwn(PAGES, route) ? route : 'collections';
  const numeric = Number(param);
  // An empty hash is the front door: visitors without a session get the landing page there, and the sign-in form at #/signin.
  return { route: known, collectionId: Number.isInteger(numeric) && numeric > 0 ? numeric : null, public: false, landing: route === '' };
}

export function navigate(path) {
  const target = `#/${path}`;
  if (location.hash === target) render();
  else location.hash = target;
}

function logout() {
  setSession(null);
  location.hash = '#/';
  render();
}

function sidebar(activeRoute, session) {
  const current = getCurrentCollection();
  const links = Object.entries(PAGES).map(([route, item]) => {
    const href = item.scoped && current ? `#/${route}/${current}` : `#/${route}`;
    return h('a', { href, 'aria-current': route === activeRoute ? 'page' : null }, icon(item.icon), item.title);
  });
  return h('aside', { class: 'sidebar' },
    h('div', { class: 'logo' }, 'Spring ', h('span', { class: 'accent' }, 'RAG'), ' Service'),
    h('nav', { class: 'nav', 'aria-label': 'Main' }, links),
    h('div', { class: 'sidebar-footer' },
      h('span', { class: 'label' }, 'Signed in'),
      h('span', { class: 'email mono' }, session.email),
      h('button', { class: 'btn btn-small', type: 'button', onClick: logout }, icon('logout', 16), 'Sign out')));
}

function mountPage(item, main, context, token) {
  Promise.resolve(item.page.mount(main, context)).then((dispose) => {
    if (typeof dispose !== 'function') return;
    if (token === renderToken) disposeCurrent = dispose;
    else dispose(); // the user navigated away before the page finished loading
  }).catch((error) => {
    main.append(h('p', { class: 'notice notice-error', role: 'alert' }, error?.message || 'Could not load this page.'));
  });
}

function render() {
  if (disposeCurrent) {
    disposeCurrent();
    disposeCurrent = null;
  }
  const token = ++renderToken;
  const app = clear(document.getElementById('app'));
  const session = getSession();
  const where = parseHash();

  if (where.public) {
    const item = PUBLIC_PAGES[where.route];
    document.title = `${item.title} · Spring RAG Service`;
    mountPage(item, app, { param: where.param, navigate }, token);
    return;
  }

  if (!session && where.landing) {
    document.title = 'Spring RAG Service';
    disposeCurrent = landing.mount(app);
    return;
  }

  if (!session) {
    document.title = 'Sign in · Spring RAG Service';
    auth.mount(app, {
      onAuthed: (next) => {
        setSession(next);
        navigate('collections');
      },
    });
    return;
  }

  const item = PAGES[where.route];
  document.title = `${item.title} · Spring RAG Service`;
  const main = h('main', { class: 'main', id: 'main', tabindex: '-1' });
  app.append(h('div', { class: 'shell' }, sidebar(where.route, session), main));
  mountPage(item, main, { collectionId: where.collectionId, navigate }, token);
}

onUnauthorized(() => {
  setSession(null);
  toast('Your session ended (it expired or was signed out elsewhere). Please sign in again.', 'error');
  render();
});

window.addEventListener('hashchange', render);
render();

// Exposed only so the page can be inspected from the console during development.
export { api };
