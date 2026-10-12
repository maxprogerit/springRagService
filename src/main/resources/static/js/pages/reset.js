import { api } from '../api.js';
import { errorMessage, h } from '../dom.js';

export function mount(root, { param }) {
  const error = h('p', { class: 'notice notice-error', role: 'alert', hidden: true });
  const done = h('p', { class: 'notice', role: 'status', hidden: true });
  const password = h('input', { class: 'input', id: 'reset-password', type: 'password', required: true, minlength: 8, maxlength: 72, autocomplete: 'new-password' });
  const submit = h('button', { class: 'btn btn-primary', type: 'submit' }, 'Set new password');
  const form = h('form', { onSubmit },
    h('div', { class: 'field' }, h('label', { class: 'label', for: 'reset-password' }, 'New password'), password,
      h('p', { class: 'dim' }, '8 to 72 characters.')),
    error, submit);

  async function onSubmit(event) {
    event.preventDefault();
    error.hidden = true;
    submit.disabled = true;
    try {
      await api.resetPassword(param, password.value);
      form.hidden = true;
      done.textContent = 'Your password was changed and all devices were signed out. You can sign in now.';
      done.hidden = false;
    } catch (e) {
      error.textContent = errorMessage(e);
      error.hidden = false;
    } finally {
      submit.disabled = false;
    }
  }

  root.append(h('div', { class: 'auth-wrap' },
    h('main', { class: 'card auth-card', id: 'main' },
      h('div', { class: 'logo' }, 'Spring ', h('span', { class: 'accent' }, 'RAG'), ' Service'),
      h('h1', { class: 'page-title-small' }, 'Choose a new password'),
      param ? form : h('p', { class: 'notice notice-error', role: 'alert' }, 'This link is incomplete. Request a new one.'),
      done,
      h('a', { class: 'link', href: '#/' }, 'Go to sign in'))));
  if (param) password.focus();
}
