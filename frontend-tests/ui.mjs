// End-to-end check of the web UI: runs the real front-end modules in jsdom against a running server.
// It is a development check only (the app itself has no Node build). See README.md in this folder.
import { JSDOM } from 'jsdom';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const BASE = process.env.BASE_URL || 'http://localhost:8080';
const STATIC = process.env.STATIC_DIR
  || path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../src/main/resources/static');

const dom = new JSDOM(fs.readFileSync(`${STATIC}/index.html`, 'utf8'), { url: `${BASE}/`, pretendToBeVisual: true });
const w = dom.window;
Object.assign(globalThis, { window: w, document: w.document, Node: w.Node, HashChangeEvent: w.HashChangeEvent, sessionStorage: w.sessionStorage });
Object.defineProperty(globalThis, 'location', { value: w.location, configurable: true });
globalThis.confirm = w.confirm = () => true;
w.HTMLElement.prototype.scrollIntoView = () => {};
const realFetch = globalThis.fetch;
globalThis.fetch = (url, opts) => realFetch(new URL(url, BASE), opts);

let failures = 0;
const ok = (name, cond, extra = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${cond ? '' : `  ${extra}`}`);
  if (!cond) failures++;
};
const q = (sel) => document.querySelector(sel);
const qa = (sel) => [...document.querySelectorAll(sel)];
async function waitFor(fn, label, ms = 20000) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    try { const v = fn(); if (v) return v; } catch { /* keep waiting */ }
    await new Promise((r) => setTimeout(r, 50));
  }
  throw new Error(`timeout waiting for: ${label}`);
}
const submit = (form) => form.dispatchEvent(new w.Event('submit', { bubbles: true, cancelable: true }));
const click = (el) => el.dispatchEvent(new w.MouseEvent('click', { bubbles: true, cancelable: true }));
const go = (hash) => { w.location.hash = hash; };
const authHeader = () => ({ Authorization: `Bearer ${JSON.parse(w.sessionStorage.getItem('rag.session')).token}`, 'Content-Type': 'application/json' });

try {
  await import(pathToFileURL(`${STATIC}/js/main.js`).href);

  // ----- sign in / register -----
  await waitFor(() => q('.auth-card'), 'auth card');
  ok('logged-out users see the sign-in card, no sidebar', q('.sidebar') === null && /Spring\s*RAG\s*Service/.test(q('.auth-card .logo').textContent));
  await waitFor(() => q('a[href="#/forgot"]') !== null, 'forgot link element');
  ok('"Forgot password" is hidden when the server has no e-mail configured', q('a[href="#/forgot"]').hidden === true);
  const email = `ui-${Date.now()}@example.com`;
  click(qa('.segmented button')[1]);
  q('#auth-email').value = email; q('#auth-password').value = 'correct-horse-battery';
  submit(q('.auth-card form'));
  await waitFor(() => q('.shell'), 'shell after register');
  ok('register logs in and shows the shell', q('.sidebar .email').textContent === email);
  ok('sidebar has the 5 nav items', qa('.nav a').map((a) => a.textContent.trim()).join('|') === 'Collections|Documents|Ask|Retrieval inspector|Settings');
  ok('session kept in sessionStorage', !!w.sessionStorage.getItem('rag.session'));

  // ----- collections (+ pagination) -----
  await waitFor(() => q('.empty'), 'empty collections');
  ok('empty state says "No data yet"', /No data yet/.test(q('.empty').textContent));
  q('#new-collection').value = 'ui-test'; submit(q('form.inline-form'));
  const card = await waitFor(() => q('.grid-cards .card'), 'collection card');
  ok('collection card shows name and document count 0', /ui-test/.test(card.textContent) && card.querySelector('.stat strong').textContent === '0');
  const colId = Number(/documents\/(\d+)/.exec(card.querySelector('a').getAttribute('href'))[1]);
  q('#new-collection').value = 'demo-sample'; submit(q('form.inline-form'));
  await waitFor(() => qa('.grid-cards .card').length === 2, 'second card');
  ok('collections named demo-* carry a visible demo badge', qa('.badge-demo').length === 1);
  for (let i = 0; i < 25; i++) {
    const r = await fetch('/api/collections', { method: 'POST', headers: authHeader(), body: JSON.stringify({ name: `bulk ${i}` }) });
    if (r.status !== 201) throw new Error(`could not create bulk collection ${i}: ${r.status}`);
  }
  go('#/settings'); await waitFor(() => q('.kv'), 'settings'); go('#/collections');
  await waitFor(() => qa('.grid-cards .card').length === 24, '24 cards on the first page');
  ok('the collections list is paged (24 of 27)', /Showing 24 of 27/.test(q('.more-row').textContent) && q('.more-row button').hidden === false);
  click(q('.more-row button'));
  await waitFor(() => qa('.grid-cards .card').length === 27, 'all 27 cards');
  ok('"Show more" loads the rest and hides itself', q('.more-row button').hidden === true && /Showing 27 of 27/.test(q('.more-row').textContent));

  // ----- documents -----
  go(`#/documents/${colId}`);
  await waitFor(() => q('.dropzone'), 'dropzone');
  ok('documents page shows upload zone and empty table state', /No data yet/.test(q('.table-wrap').textContent));
  ok('upload zone lists the supported types', /DOCX/.test(q('.dropzone').textContent) && /HTML/.test(q('.dropzone').textContent));
  const evil = 'Refunds are possible within 14 days. <b>bold</b> <img src=x onerror=alert(1)> end of policy.';
  const html = '<html><head><title>Plans</title><script>steal()</script></head><body><p>The Team plan costs 49 dollars per user.</p></body></html>';
  const drop = new w.Event('drop', { bubbles: true, cancelable: true });
  drop.dataTransfer = { files: [new File([evil], 'refund-policy.txt', { type: 'text/plain' }), new File(['MZ\u0000\u0003'], 'bad.txt'), new File([html], 'plans.html')] };
  q('.dropzone').dispatchEvent(drop);
  await waitFor(() => qa('.badge-ready').length === 2, 'two READY badges');
  ok('text and HTML documents reach READY', /refund-policy\.txt/.test(q('tbody').textContent) && /plans\.html/.test(q('tbody').textContent));
  ok('rejected file shows an error toast', qa('.toast-error').some((t) => /bad\.txt/.test(t.textContent)));
  const again = new w.Event('drop', { bubbles: true, cancelable: true });
  again.dataTransfer = { files: [new File([evil], 'copy-of-policy.txt')] };
  q('.dropzone').dispatchEvent(again);
  await waitFor(() => qa('.toast-error').some((t) => /already uploaded/.test(t.textContent)), 'duplicate toast');
  ok('uploading the same content again is refused with a clear message', true);
  ok('stats strip shows 2 chunks', /Chunks\s*2/.test(q('.stats-strip').textContent.replace(/\s+/g, ' ')), q('.stats-strip').textContent);

  // ----- ask: streaming, citations, follow-up -----
  go(`#/ask/${colId}`);
  await waitFor(() => q('#question'), 'ask form');
  q('#question').value = 'How many days for refunds?'; submit(q('.ask-form'));
  const chip = await waitFor(() => q('.msg-assistant .chip'), 'citation chip');
  ok('answer has a citation chip like "[1] file · p.N"', /^\[\d\] refund-policy\.txt · p\.1$/.test(chip.textContent), chip.textContent);
  ok('answer text is not interpreted as HTML', q('.msg-assistant b') === null && q('.msg-assistant img') === null);
  ok('answer shows retrieval counts and latency', /vector \d+/.test(q('.msg-meta').textContent) && /\d+ ms/.test(q('.msg-meta').textContent));
  click(chip);
  const panel = q('.side-panel');
  ok('clicking the chip opens the source panel with exact snippet and score', !panel.hidden && panel.textContent.includes('<b>bold</b>') && /Fused score/.test(panel.textContent) && /0\.\d{4}/.test(panel.textContent));
  ok('snippet is shown as text (no injected elements)', panel.querySelector('b') === null && panel.querySelector('img') === null);
  ok('focus moved into the panel', document.activeElement?.getAttribute('aria-label') === 'Close source panel');
  document.dispatchEvent(new w.KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
  ok('Escape closes the panel and returns focus to the chip', q('.side-panel').hidden && document.activeElement === chip);
  q('#question').value = 'and what does the Team plan cost per user?'; submit(q('.ask-form'));
  await waitFor(() => qa('.msg-assistant').length === 2 && qa('.msg-assistant')[1].getAttribute('aria-busy') !== 'true' && qa('.msg-assistant')[1].querySelector('.chip'), 'follow-up answer');
  ok('a follow-up sends the earlier turns and shows how it was understood', qa('.msg-assistant')[1].querySelector('.msg-rewrite') !== null);
  ok('the follow-up cites the HTML document', /plans\.html/.test(qa('.msg-assistant')[1].querySelector('.chip').textContent));
  q('#question').value = 'quantum chromodynamics lagrangian'; submit(q('.ask-form'));
  await waitFor(() => qa('.msg-assistant').length === 3 && qa('.msg-assistant')[2].getAttribute('aria-busy') !== 'true', 'third answer');
  const nf = qa('.msg-assistant')[2];
  ok('insufficient context shows the exact not-found sentence and no chips', nf.classList.contains('is-notfound') && nf.querySelector('.msg-text').textContent === 'I could not find this in the uploaded documents.' && nf.querySelector('.chip') === null);
  click(qa('.page-tools button').find((b) => /New conversation/.test(b.textContent)));
  ok('"New conversation" clears the chat', qa('.msg').length === 0 && /No data yet/.test(q('.chat-log').textContent));

  // ----- inspector -----
  go(`#/inspector/${colId}`);
  await waitFor(() => q('#inspect-query'), 'inspector form');
  q('#inspect-query').value = 'refund policy days'; submit(q('form.inline-form'));
  await waitFor(() => q('.cols-3'), 'three columns');
  ok('inspector shows Vector hits / Keyword hits / Fused ranking', qa('.cols-3 h2').map((e) => e.textContent).join('|') === 'Vector hits|Keyword hits|Fused ranking');
  ok('every column lists hits with scores', qa('.cols-3 section').every((s) => s.querySelectorAll('.hit').length >= 1 && /\d\.\d{3}/.test(s.querySelector('.hit-score').textContent)));
  ok('fused hits show per-retriever ranks', /vector #1/.test(qa('.cols-3 section')[2].textContent) && /keyword #1/.test(qa('.cols-3 section')[2].textContent));

  // ----- public pages from e-mail links -----
  go('#/settings');
  await waitFor(() => /UP/.test(qa('.kv')[2]?.textContent || ''), 'health UP');
  ok('settings shows the account and server health UP', qa('.kv')[0].textContent.includes(email));

  // change password keeps this device signed in
  const before = JSON.parse(w.sessionStorage.getItem('rag.session')).token;
  q('#current-password').value = 'correct-horse-battery'; q('#new-password').value = 'a-brand-new-password';
  submit(q('form.inline-form'));
  await waitFor(() => JSON.parse(w.sessionStorage.getItem('rag.session')).token !== before, 'fresh token stored');
  ok('changing the password stores a fresh token for this device', true);
  const stale = await fetch('/api/collections', { headers: { Authorization: `Bearer ${before}` } });
  ok('the old token stopped working immediately', stale.status === 401);
  q('#current-password').value = 'wrong-password-1'; q('#new-password').value = 'another-new-password';
  submit(q('form.inline-form'));
  await waitFor(() => !q('form.inline-form + [role=alert]').hidden, 'wrong current password message');
  ok('a wrong current password is reported inline', /current password is wrong/.test(q('form.inline-form + [role=alert]').textContent));

  // ----- delete + sign out everywhere -----
  go('#/collections');
  await waitFor(() => qa('.grid-cards .card').length >= 2, 'cards again');
  const before27 = qa('.grid-cards .card').length;
  click(qa('.grid-cards .card button.btn-danger')[0]);
  await waitFor(() => qa('.grid-cards .card').length < before27 || /Showing 26/.test(q('.more-row').textContent), 'card removed');
  ok('deleting a collection removes its card', true);
  go('#/settings');
  await waitFor(() => q('.card-actions button.btn-danger'), 'sign out everywhere button');
  click(q('.card-actions button.btn-danger'));
  await waitFor(() => q('.auth-card'), 'back to auth');
  ok('sign out everywhere returns to the sign-in card and clears sessionStorage', w.sessionStorage.getItem('rag.session') === null);
  const relog = await fetch('/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ email, password: 'a-brand-new-password' }) });
  ok('the new password works for signing in', relog.status === 200);

  // ----- wrong password + public pages -----
  q('#auth-email').value = email; q('#auth-password').value = 'wrong-password-1'; submit(q('.auth-card form'));
  await waitFor(() => !q('.auth-card [role=alert]').hidden, 'auth error');
  ok('wrong password shows the server error inline', /Invalid email or password/.test(q('.auth-card [role=alert]').textContent));
  go('#/reset/not-a-real-token');
  await waitFor(() => q('#reset-password'), 'reset form');
  q('#reset-password').value = 'some-new-password'; submit(q('.auth-card form'));
  await waitFor(() => !q('.auth-card [role=alert]').hidden, 'reset error');
  ok('an invalid reset link is rejected with a clear message', /invalid or has expired/.test(q('.auth-card [role=alert]').textContent));
  go('#/verify/not-a-real-token');
  await waitFor(() => /invalid or has expired/.test(q('.auth-card [role=status], .auth-card .notice')?.textContent || ''), 'verify error');
  ok('an invalid verification link is rejected with a clear message', true);
} catch (e) {
  console.log('ERROR', e.message);
  failures++;
}
console.log(failures === 0 ? '\nALL UI CHECKS PASSED' : `\n${failures} UI CHECK(S) FAILED`);
process.exit(failures === 0 ? 0 : 1);
