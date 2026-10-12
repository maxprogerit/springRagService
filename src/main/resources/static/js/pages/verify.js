import { api } from '../api.js';
import { errorMessage, h } from '../dom.js';

/** Opened from the link in the confirmation e-mail: confirms the address right away. */
export function mount(root, { param }) {
  const status = h('p', { class: 'notice', role: 'status' }, 'Confirming your e-mail address…');

  root.append(h('div', { class: 'auth-wrap' },
    h('main', { class: 'card auth-card', id: 'main' },
      h('div', { class: 'logo' }, 'Spring ', h('span', { class: 'accent' }, 'RAG'), ' Service'),
      h('h1', { class: 'page-title-small' }, 'E-mail confirmation'),
      status,
      h('a', { class: 'link', href: '#/' }, 'Go to sign in'))));

  if (!param) {
    status.textContent = 'This link is incomplete. Use the link from the e-mail.';
    status.className = 'notice notice-error';
    return;
  }
  api.verifyEmail(param).then(
    () => { status.textContent = 'Your e-mail address is confirmed. You can sign in now.'; },
    (e) => {
      status.textContent = errorMessage(e);
      status.className = 'notice notice-error';
    });
}
