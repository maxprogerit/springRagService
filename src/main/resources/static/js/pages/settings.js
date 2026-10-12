import { api } from '../api.js';
import { fact } from '../components.js';
import { errorMessage, fmt, h, icon, pageHeader, toast } from '../dom.js';
import { getSession, setSession, tokenExpiry } from '../state.js';

export async function mount(root) {
  const session = getSession();
  const expiry = tokenExpiry(session.token);
  const health = h('span', { class: 'badge badge-processing' }, 'checking');

  api.health().then(
    (body) => {
      health.textContent = body.status;
      health.className = `badge ${body.status === 'UP' ? 'badge-ready' : 'badge-failed'}`;
    },
    () => {
      health.textContent = 'unreachable';
      health.className = 'badge badge-failed';
    });

  // ----- change password -----
  const current = h('input', { class: 'input', id: 'current-password', type: 'password', required: true, maxlength: 72, autocomplete: 'current-password' });
  const next = h('input', { class: 'input', id: 'new-password', type: 'password', required: true, minlength: 8, maxlength: 72, autocomplete: 'new-password' });
  const formError = h('p', { class: 'notice notice-error', role: 'alert', hidden: true });
  const changeButton = h('button', { class: 'btn btn-primary', type: 'submit' }, 'Change password');

  async function onChangePassword(event) {
    event.preventDefault();
    formError.hidden = true;
    changeButton.disabled = true;
    try {
      const result = await api.changePassword(current.value, next.value);
      // Every older token was revoked on the server; keep this device signed in with the fresh one.
      setSession({ token: result.token, email: result.email });
      current.value = '';
      next.value = '';
      toast('Password changed. Other devices were signed out.');
    } catch (e) {
      formError.textContent = errorMessage(e);
      formError.hidden = false;
    } finally {
      changeButton.disabled = false;
    }
  }

  async function signOutEverywhere() {
    if (!window.confirm('Sign out on all devices, including this one?')) return;
    try {
      await api.logoutEverywhere();
    } catch (e) {
      toast(errorMessage(e), 'error');
      return;
    }
    setSession(null);
    window.dispatchEvent(new HashChangeEvent('hashchange'));
  }

  root.append(
    pageHeader('Account', 'Settings'),
    h('section', { class: 'card', 'aria-label': 'Account' },
      h('div', { class: 'card-head' }, h('h2', null, 'Account')),
      h('dl', { class: 'kv' },
        ...fact('Email', session.email),
        ...fact('Session expires', expiry ? fmt.date(new Date(expiry).toISOString()) : 'unknown'),
        ...fact('Token storage', 'In memory and sessionStorage (cleared when the tab closes)')),
      h('div', { class: 'card-gap' }),
      h('div', { class: 'card-actions' },
        h('button', { class: 'btn', type: 'button', onClick: () => { setSession(null); window.dispatchEvent(new HashChangeEvent('hashchange')); } }, icon('logout', 16), 'Sign out'),
        h('button', { class: 'btn btn-danger', type: 'button', onClick: signOutEverywhere }, 'Sign out everywhere'))),
    h('section', { class: 'card', 'aria-label': 'Change password' },
      h('div', { class: 'card-head' }, h('h2', null, 'Change password')),
      h('form', { class: 'inline-form', onSubmit: onChangePassword },
        h('div', { class: 'field' }, h('label', { class: 'label', for: 'current-password' }, 'Current password'), current),
        h('div', { class: 'field' }, h('label', { class: 'label', for: 'new-password' }, 'New password (8 to 72 characters)'), next),
        changeButton),
      formError),
    h('section', { class: 'card', 'aria-label': 'Server' },
      h('div', { class: 'card-head' }, h('h2', null, 'Server')),
      h('dl', { class: 'kv' },
        ...fact('Health', health),
        ...fact('API', `${location.origin}/api`),
        ...fact('Secrets', 'The OpenAI key and JWT secret never leave the server; this page cannot show them.'))));
}
