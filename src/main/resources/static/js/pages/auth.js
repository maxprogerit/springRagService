import { api } from '../api.js';
import { errorMessage, h } from '../dom.js';

export function mount(root, { onAuthed }) {
  let mode = 'login';
  let config = { passwordResetAvailable: false, emailVerificationRequired: false };

  const error = h('p', { class: 'notice notice-error', role: 'alert', hidden: true });
  const info = h('p', { class: 'notice', role: 'status', hidden: true });
  const resend = h('button', { class: 'btn btn-small', type: 'button', hidden: true, onClick: onResend }, 'Send the confirmation e-mail again');
  const email = h('input', { class: 'input', id: 'auth-email', type: 'email', name: 'email', required: true, autocomplete: 'email', maxlength: 254 });
  const password = h('input', { class: 'input', id: 'auth-password', type: 'password', name: 'password', required: true, minlength: 8, maxlength: 72 });
  const submit = h('button', { class: 'btn btn-primary', type: 'submit' });
  const hint = h('p', { class: 'dim', id: 'auth-hint' });
  const forgot = h('a', { class: 'link', href: '#/forgot', hidden: true }, 'Forgot your password?');
  const loginTab = h('button', { type: 'button', onClick: () => setMode('login') }, 'Sign in');
  const registerTab = h('button', { type: 'button', onClick: () => setMode('register') }, 'Create account');

  function setMode(next) {
    mode = next;
    loginTab.setAttribute('aria-pressed', String(mode === 'login'));
    registerTab.setAttribute('aria-pressed', String(mode === 'register'));
    submit.textContent = mode === 'login' ? 'Sign in' : 'Create account';
    password.setAttribute('autocomplete', mode === 'login' ? 'current-password' : 'new-password');
    hint.textContent = mode === 'register' ? 'Password: 8 to 72 characters.' : '';
    forgot.hidden = !(mode === 'login' && config.passwordResetAvailable);
    error.hidden = true;
    info.hidden = true;
    resend.hidden = true;
  }

  function show(node, text) {
    node.textContent = text;
    node.hidden = false;
  }

  async function onResend() {
    try {
      await api.resendVerification(email.value);
      show(info, 'If the address is registered and not yet confirmed, a new e-mail is on its way.');
    } catch (e) {
      show(error, errorMessage(e));
    }
  }

  async function onSubmit(event) {
    event.preventDefault();
    error.hidden = true;
    info.hidden = true;
    resend.hidden = true;
    submit.disabled = true;
    try {
      const result = mode === 'login'
        ? await api.login(email.value, password.value)
        : await api.register(email.value, password.value);
      if (result.verificationRequired) {
        setMode('login');
        show(info, `We sent a confirmation link to ${result.email}. Open it, then sign in.`);
        resend.hidden = false;
      } else {
        onAuthed({ token: result.token, email: result.email });
      }
    } catch (e) {
      show(error, errorMessage(e));
      resend.hidden = e.code !== 'EMAIL_NOT_VERIFIED';
    } finally {
      submit.disabled = false;
    }
  }

  root.append(h('div', { class: 'auth-wrap' },
    h('main', { class: 'card auth-card', id: 'main' },
      h('div', { class: 'logo' }, 'Spring ', h('span', { class: 'accent' }, 'RAG'), ' Service'),
      h('p', { class: 'muted' }, 'Ask questions about your documents. Every answer cites its sources.'),
      h('div', { class: 'segmented', role: 'group', 'aria-label': 'Sign in or create an account' }, loginTab, registerTab),
      h('form', { onSubmit },
        h('div', { class: 'field' }, h('label', { class: 'label', for: 'auth-email' }, 'Email'), email),
        h('div', { class: 'field' }, h('label', { class: 'label', for: 'auth-password' }, 'Password'), password, hint),
        error, info, resend,
        submit,
        forgot))));
  setMode('login');
  email.focus();

  // Tells whether e-mail features exist on this server (they need MAIL_HOST).
  api.config().then((c) => {
    config = c;
    forgot.hidden = !(mode === 'login' && config.passwordResetAvailable);
  }, () => { /* the form works without it */ });
}
