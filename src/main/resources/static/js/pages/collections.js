import { api } from '../api.js';
import { demoBadge, isDemo } from '../components.js';
import { clear, emptyState, errorMessage, fmt, h, icon, label, pageHeader, toast } from '../dom.js';
import { setCurrentCollection } from '../state.js';

const PAGE_SIZE = 24;

export async function mount(root) {
  const grid = h('div', { class: 'grid-cards' });
  const holder = h('div');
  const more = h('button', { class: 'btn', type: 'button', hidden: true, onClick: () => loadMore() }, 'Show more');
  const counter = h('p', { class: 'dim mono', role: 'status' });
  const nameInput = h('input', { class: 'input', id: 'new-collection', name: 'name', maxlength: 120, required: true, placeholder: 'e.g. Contracts 2025', autocomplete: 'off' });
  const createButton = h('button', { class: 'btn btn-primary', type: 'submit' }, icon('plus', 16), 'Create');
  let loaded = 0;
  let total = 0;

  async function onCreate(event) {
    event.preventDefault();
    createButton.disabled = true;
    try {
      const created = await api.createCollection(nameInput.value);
      setCurrentCollection(created.id);
      nameInput.value = '';
      toast(`Collection "${created.name}" created.`);
      await reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      createButton.disabled = false;
    }
  }

  async function onDelete(collection) {
    const ok = window.confirm(`Delete "${collection.name}" with all ${collection.documentCount} document(s) and their search data? This cannot be undone.`);
    if (!ok) return;
    try {
      await api.deleteCollection(collection.id);
      toast(`Collection "${collection.name}" deleted.`);
      await reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  }

  function card(collection) {
    const remember = () => setCurrentCollection(collection.id);
    return h('article', { class: 'card' },
      h('div', null,
        h('h2', { class: 'collection-name' }, collection.name, ' ', isDemo(collection.name) ? demoBadge() : null),
        h('span', { class: 'label' }, `Created ${fmt.date(collection.createdAt)}`)),
      h('div', { class: 'stat' }, label('Documents'), h('strong', null, String(collection.documentCount))),
      h('div', { class: 'card-actions' },
        h('a', { class: 'btn btn-small', href: `#/documents/${collection.id}`, onClick: remember }, 'Documents'),
        h('a', { class: 'btn btn-small', href: `#/ask/${collection.id}`, onClick: remember }, 'Ask'),
        h('a', { class: 'btn btn-small', href: `#/inspector/${collection.id}`, onClick: remember }, 'Inspect'),
        h('button', { class: 'btn btn-small btn-danger btn-icon', type: 'button', 'aria-label': `Delete collection ${collection.name}`, onClick: () => onDelete(collection) }, icon('trash', 16))));
  }

  function refreshChrome() {
    clear(holder);
    if (total === 0) {
      holder.append(emptyState('No data yet', 'Create your first collection above.'));
    } else {
      holder.append(grid);
    }
    more.hidden = loaded >= total;
    counter.textContent = total > 0 ? `Showing ${loaded} of ${total}` : '';
  }

  async function reload() {
    clear(grid);
    loaded = 0;
    total = 0;
    await loadMore();
  }

  async function loadMore() {
    try {
      const page = await api.listCollections({ page: Math.floor(loaded / PAGE_SIZE), size: PAGE_SIZE });
      total = page.total;
      grid.append(...page.items.map(card));
      loaded += page.items.length;
      refreshChrome();
    } catch (e) {
      clear(holder).append(h('p', { class: 'notice notice-error', role: 'alert' }, errorMessage(e)));
    }
  }

  root.append(
    pageHeader('Workspace', 'Collections'),
    h('form', { class: 'card inline-form', onSubmit: onCreate },
      h('div', { class: 'field' }, h('label', { class: 'label', for: 'new-collection' }, 'New collection'), nameInput),
      createButton),
    h('div', { class: 'card-gap' }),
    holder,
    h('div', { class: 'more-row' }, counter, more));
  await reload();
}
