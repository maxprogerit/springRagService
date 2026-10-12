import { api } from '../api.js';
import { collectionPicker, fact, noCollections, pickCollection } from '../components.js';
import { emptyState, errorMessage, fmt, h, icon, pageHeader } from '../dom.js';

const NOT_FOUND = 'I could not find this in the uploaded documents.';
const HISTORY_TURNS = 6;

export async function mount(root, { collectionId, navigate }) {
  let list;
  try {
    list = (await api.listCollections({ size: 200 })).items;
  } catch (e) {
    root.append(pageHeader('Chat', 'Ask'), h('p', { class: 'notice notice-error', role: 'alert' }, errorMessage(e)));
    return undefined;
  }
  const collection = pickCollection(list, collectionId);
  if (!collection) {
    root.append(pageHeader('Chat', 'Ask'), noCollections());
    return undefined;
  }

  const log = h('div', { class: 'chat-log', role: 'log', 'aria-live': 'polite', 'aria-label': 'Conversation' },
    emptyState('No data yet', 'Ask a question about the documents in this collection. Follow-up questions are understood in context.'));
  const question = h('textarea', { class: 'input', id: 'question', rows: 2, maxlength: 2000, required: true, placeholder: 'What does the contract say about termination?' });
  const topK = h('input', { class: 'input', id: 'top-k', type: 'number', min: 1, max: 20, placeholder: '5' });
  const send = h('button', { class: 'btn btn-primary', type: 'submit' }, icon('send', 16), 'Ask');
  const newChat = h('button', { class: 'btn', type: 'button', onClick: resetChat }, 'New conversation');
  let started = false;
  let opener = null;
  /** Earlier turns, sent with the next question so a follow-up like "and for the Team plan?" can be understood. */
  let history = [];

  // ----- source panel -----
  const panelTitle = h('h2', { id: 'panel-title' }, 'Source');
  const panelBody = h('div');
  const closeButton = h('button', { class: 'btn btn-small btn-icon', type: 'button', 'aria-label': 'Close source panel', onClick: closePanel }, icon('close', 16));
  const panel = h('aside', { class: 'side-panel', role: 'dialog', 'aria-labelledby': 'panel-title', hidden: true },
    h('header', null, panelTitle, closeButton), panelBody);

  function openPanel(citation, trigger) {
    opener = trigger || null;
    panelTitle.textContent = `Source [${citation.index}]`;
    panelBody.replaceChildren(
      h('dl', { class: 'facts' },
        ...fact('Document', citation.filename),
        ...fact('Page', String(citation.page)),
        ...fact('Fused score (RRF)', fmt.fixed(citation.score, 4)),
        ...fact('Vector similarity', fmt.fixed(citation.vectorSimilarity, 3))),
      h('span', { class: 'label' }, 'Exact snippet'),
      h('div', { class: 'snippet' }, citation.snippet));
    panel.hidden = false;
    for (const chip of root.querySelectorAll('[data-source]')) {
      chip.setAttribute('aria-expanded', String(Number(chip.dataset.source) === citation.index && chip.closest('.msg') === trigger?.closest('.msg')));
    }
    closeButton.focus();
  }

  function closePanel() {
    panel.hidden = true;
    for (const chip of root.querySelectorAll('[data-source]')) chip.setAttribute('aria-expanded', 'false');
    if (opener && opener.isConnected) opener.focus();
    opener = null;
  }

  function onKeydown(event) {
    if (event.key === 'Escape' && !panel.hidden) closePanel();
  }
  document.addEventListener('keydown', onKeydown);

  function resetChat() {
    history = [];
    started = false;
    closePanel();
    log.replaceChildren(emptyState('No data yet', 'Ask a question about the documents in this collection. Follow-up questions are understood in context.'));
    newChat.hidden = true;
  }
  newChat.hidden = true;

  // ----- messages -----
  function answerNode(answer, citations) {
    const byIndex = new Map(citations.map((c) => [c.index, c]));
    const node = h('p', { class: 'msg-text' });
    const pattern = /\[(\d+(?:\s*,\s*\d+)*)]/g;
    let last = 0;
    let match;
    while ((match = pattern.exec(answer)) !== null) {
      node.append(answer.slice(last, match.index));
      const numbers = match[1].split(',').map((n) => Number(n.trim()));
      for (const n of numbers) {
        const citation = byIndex.get(n);
        if (citation) {
          const button = h('button', { class: 'cite-inline', type: 'button', 'data-source': n, 'aria-expanded': 'false', 'aria-label': `Open source ${n}` }, `[${n}]`);
          button.addEventListener('click', () => openPanel(citation, button));
          node.append(button);
        } else {
          node.append(`[${n}]`);
        }
      }
      last = match.index + match[0].length;
    }
    node.append(answer.slice(last));
    return node;
  }

  function chipRow(citations) {
    if (citations.length === 0) return null;
    return h('div', { class: 'msg-citations', role: 'group', 'aria-label': 'Sources' }, citations.map((c) => {
      const chip = h('button', { class: 'chip', type: 'button', 'data-source': c.index, 'aria-expanded': 'false' }, `[${c.index}] ${c.filename} · p.${c.page}`);
      chip.addEventListener('click', () => openPanel(c, chip));
      return chip;
    }));
  }

  function assistantMessage(result) {
    const notFound = result.citations.length === 0;
    return h('div', { class: `msg msg-assistant${notFound ? ' is-notfound' : ''}` },
      result.standaloneQuestion ? h('p', { class: 'dim mono msg-rewrite' }, `Understood as: ${result.standaloneQuestion}`) : null,
      notFound ? h('p', { class: 'msg-text' }, result.answer || NOT_FOUND) : answerNode(result.answer, result.citations),
      chipRow(result.citations),
      h('div', { class: 'msg-meta' },
        h('span', null, `vector ${result.retrieval.vectorHits}`),
        h('span', null, `keyword ${result.retrieval.keywordHits}`),
        h('span', null, `fused ${result.retrieval.fused}`),
        h('span', null, `${result.latencyMs} ms`)));
  }

  async function onSubmit(event) {
    event.preventDefault();
    const text = question.value.trim();
    if (!text) return;
    if (!started) {
      log.replaceChildren();
      started = true;
      newChat.hidden = false;
    }
    log.append(h('div', { class: 'msg msg-user' }, h('p', { class: 'msg-text' }, text)));
    // Streamed text is provisional: the server verifies the citations and then replaces it with the final answer.
    const live = h('p', { class: 'msg-text' }, h('span', { class: 'dim' }, 'Searching the documents…'));
    const pending = h('div', { class: 'msg msg-assistant', 'aria-busy': 'true' }, live);
    log.append(pending);
    question.value = '';
    send.disabled = true;
    let streamed = false;
    try {
      const requested = topK.value ? Number(topK.value) : undefined;
      const result = await api.askStream(collection.id, text, requested, history.slice(-HISTORY_TURNS), {
        onToken: (piece) => {
          if (!streamed) {
            live.textContent = '';
            streamed = true;
          }
          live.append(piece);
        },
      });
      pending.replaceWith(assistantMessage(result));
      history.push({ role: 'user', content: text.slice(0, 4000) });
      history.push({ role: 'assistant', content: String(result.answer).slice(0, 4000) });
      history = history.slice(-HISTORY_TURNS);
    } catch (e) {
      pending.replaceWith(h('div', { class: 'msg msg-assistant msg-error', role: 'alert' }, h('p', null, errorMessage(e))));
    } finally {
      send.disabled = false;
      question.focus();
      pending.scrollIntoView?.({ block: 'nearest' });
    }
  }

  question.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      if (!send.disabled) send.click();
    }
  });

  root.append(
    pageHeader('Chat', 'Ask', [collectionPicker(list, collection.id, (id) => navigate(`ask/${id}`)), newChat]),
    h('div', { class: 'chat-layout' },
      log,
      h('form', { class: 'ask-form', onSubmit },
        h('div', { class: 'field field-question' }, h('label', { class: 'label', for: 'question' }, 'Question'), question),
        h('div', { class: 'field field-topk' }, h('label', { class: 'label', for: 'top-k' }, 'Top K'), topK),
        send)),
    panel);

  return () => document.removeEventListener('keydown', onKeydown);
}
