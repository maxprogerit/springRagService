import { api } from '../api.js';
import { collectionPicker, isDemo, demoBadge, noCollections, pickCollection, statusBadge } from '../components.js';
import { clear, emptyState, errorMessage, fmt, h, icon, label, pageHeader, toast } from '../dom.js';

const POLL_MS = 2000;
const PAGE_SIZE = 50;
const BASE_TYPES = ['.pdf', '.docx', '.html', '.htm', '.txt', '.md', '.markdown'];

export async function mount(root, { collectionId, navigate }) {
  let list;
  let config = { ocrEnabled: false, maxUploadMb: 20 };
  try {
    [list, config] = await Promise.all([api.listCollections({ size: 200 }), api.config().catch(() => config)]);
  } catch (e) {
    root.append(pageHeader('Library', 'Documents'), h('p', { class: 'notice notice-error', role: 'alert' }, errorMessage(e)));
    return undefined;
  }
  const collection = pickCollection(list.items, collectionId);
  if (!collection) {
    root.append(pageHeader('Library', 'Documents'), noCollections());
    return undefined;
  }

  let disposed = false;
  let timer = null;
  let shown = PAGE_SIZE; // how many of the newest documents are on screen
  const statsBox = h('div', { class: 'stats-strip' });
  const tableBox = h('div', { class: 'table-wrap' });
  const counter = h('p', { class: 'dim mono', role: 'status' });
  const more = h('button', { class: 'btn', type: 'button', hidden: true, onClick: () => { shown += PAGE_SIZE; refresh(); } }, 'Show more');

  const types = config.ocrEnabled ? [...BASE_TYPES, '.png', '.jpg', '.jpeg'] : BASE_TYPES;
  const fileInput = h('input', { class: 'visually-hidden', id: 'file-input', type: 'file', multiple: true, accept: types.join(','), onChange: () => upload(fileInput.files) });
  const zone = h('label', { class: 'dropzone', for: 'file-input' },
    icon('upload', 28),
    h('strong', null, 'Drop files here or click to choose'),
    h('span', { class: 'dim' }, `${types.map((t) => t.slice(1).toUpperCase()).join(', ')} · up to ${config.maxUploadMb} MB each · the type is checked by content on the server`),
    fileInput);
  for (const type of ['dragenter', 'dragover']) {
    zone.addEventListener(type, (e) => { e.preventDefault(); zone.classList.add('is-over'); });
  }
  for (const type of ['dragleave', 'drop']) {
    zone.addEventListener(type, (e) => { e.preventDefault(); zone.classList.remove('is-over'); });
  }
  zone.addEventListener('drop', (e) => upload(e.dataTransfer?.files));

  async function upload(files) {
    const selected = Array.from(files || []);
    fileInput.value = '';
    for (const file of selected) {
      try {
        await api.uploadDocument(collection.id, file);
        toast(`${file.name}: accepted, processing started.`);
      } catch (e) {
        toast(`${file.name}: ${errorMessage(e)}`, 'error');
      }
    }
    await refresh();
  }

  async function remove(doc) {
    if (!window.confirm(`Delete "${doc.filename}" and its search data?`)) return;
    try {
      await api.deleteDocument(collection.id, doc.id);
      await refresh();
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  }

  function row(doc) {
    return h('tr', null,
      h('td', { class: 'cell-name' }, doc.filename,
        doc.errorMessage ? h('div', { class: 'cell-error' }, doc.errorMessage) : null),
      h('td', { class: 'mono' }, doc.contentType),
      h('td', { class: 'num mono' }, doc.pageCount ?? '–'),
      h('td', { class: 'num mono' }, fmt.bytes(doc.sizeBytes)),
      h('td', null, statusBadge(doc.status)),
      h('td', { class: 'dim' }, fmt.date(doc.createdAt)),
      h('td', null, h('button', { class: 'btn btn-small btn-danger btn-icon', type: 'button', 'aria-label': `Delete ${doc.filename}`, onClick: () => remove(doc) }, icon('trash', 16))));
  }

  function renderDocuments(docs, total) {
    clear(tableBox);
    if (docs.length === 0) {
      tableBox.append(emptyState('No data yet', 'Upload a document to start asking questions.'));
    } else {
      tableBox.append(h('table', null,
        h('thead', null, h('tr', null,
          ['Document', 'Type', 'Pages', 'Size', 'Status', 'Uploaded', ''].map((text, i) =>
            h('th', { class: `label${i === 2 || i === 3 ? ' num' : ''}`, scope: 'col' }, text)))),
        h('tbody', null, docs.map(row))));
    }
    more.hidden = docs.length >= total;
    counter.textContent = total > 0 ? `Showing ${docs.length} of ${total}` : '';
  }

  function renderStats(stats) {
    clear(statsBox);
    const items = [
      ['Documents', stats.documents.total],
      ['Ready', stats.documents.ready],
      ['Chunks', stats.chunks],
      ['Pages', stats.pages],
      ['Questions', stats.queries.total],
      ['Avg latency', stats.queries.avgLatencyMs === null || stats.queries.avgLatencyMs === undefined ? '–' : `${stats.queries.avgLatencyMs} ms`],
    ];
    for (const [name, value] of items) {
      statsBox.append(h('div', { class: 'stat' }, label(name), h('strong', null, String(value))));
    }
  }

  async function refresh() {
    clearTimeout(timer);
    try {
      // Re-read every page that is on screen, so status changes (PROCESSING -> READY) show up everywhere.
      const pages = Math.max(1, Math.ceil(shown / PAGE_SIZE));
      let docs = [];
      let total = 0;
      for (let p = 0; p < pages; p++) {
        const page = await api.listDocuments(collection.id, { page: p, size: PAGE_SIZE });
        total = page.total;
        docs = docs.concat(page.items);
        if (docs.length >= total) break;
      }
      // Statistics are read after the list, so they are never older than what the table shows.
      const stats = await api.stats(collection.id);
      if (disposed) return;
      renderDocuments(docs, total);
      renderStats(stats);
      if (docs.some((d) => d.status === 'PROCESSING')) timer = setTimeout(refresh, POLL_MS);
    } catch (e) {
      if (disposed) return;
      clear(tableBox).append(h('p', { class: 'notice notice-error', role: 'alert' }, errorMessage(e)));
    }
  }

  root.append(
    pageHeader('Library', 'Documents', collectionPicker(list.items, collection.id, (id) => navigate(`documents/${id}`))),
    isDemo(collection.name) ? h('p', { class: 'notice' }, demoBadge(), ' This collection holds fictional demo documents created by the evaluation command.') : null,
    h('section', { class: 'card', 'aria-label': 'Statistics' }, statsBox),
    h('section', { class: 'card', 'aria-label': 'Upload' }, zone),
    h('section', { class: 'card', 'aria-label': 'Documents' }, tableBox, h('div', { class: 'more-row' }, counter, more)));
  await refresh();

  return () => {
    disposed = true;
    clearTimeout(timer);
  };
}
