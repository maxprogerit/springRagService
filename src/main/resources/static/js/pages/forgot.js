import { api } from '../api.js';
import { errorMessage, h } from '../dom.js';

export function mount(root) {
  const message = h('p', { class: 'notice', role: 'status', hidden: true });
  const error = h('p', { class: 'notice notice-error', role: 'alert', hidden: true });
  const email = h('input', { class: 'input', id: 'forgot-email', type: 'email', required: true, autocomplete: 'email', maxlength: 254 });
  const submit = h('button', { class: 'btn btn-primary', type: 'submit' }, 'Send reset link');

  async function onSubmit(event) {
    event.preventDefault();
    error.hidden = true;
    message.hidden = true;
    submit.disabled = true;
    try {
      await api.forgotPassword(email.value);
      message.textContent = 'If this address has an account, a reset link is on its way. It works for 30 minutes.';
      message.hidden = false;
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
      h('h1', { class: 'page-title-small' }, 'Forgot your password?'),
      h('p', { class: 'muted' }, 'Enter your e-mail address and we will send you a link to choose a new one.'),
      h('form', { onSubmit },
        h('div', { class: 'field' }, h('label', { class: 'label', for: 'forgot-email' }, 'Email'), email),
        error, message, submit,
        h('a', { class: 'link', href: '#/' }, 'Back to sign in')))));
  email.focus();
}
