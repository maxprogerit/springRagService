import { h, label, emptyState } from './dom.js';
import { getCurrentCollection, setCurrentCollection } from './state.js';

/** The evaluation command creates a collection named demo-eval; the UI marks such collections as demo data. */
export function isDemo(name) {
  return typeof name === 'string' && name.toLowerCase().startsWith('demo-');
}

export function demoBadge() {
  return h('span', { class: 'badge badge-demo', title: 'Demo data created by the evaluation command' }, 'demo');
}

export function statusBadge(status) {
  return h('span', { class: `badge badge-${String(status).toLowerCase()}` }, status);
}

/** Labelled <select> of collections; changing it calls onChange(id). */
export function collectionPicker(collections, selectedId, onChange) {
  const id = 'collection-select';
  const select = h('select', { class: 'input', id, onChange: (e) => onChange(Number(e.target.value)) },
    collections.map((c) => h('option', { value: c.id, selected: c.id === selectedId }, isDemo(c.name) ? `${c.name} (demo)` : c.name)));
  return h('div', { class: 'field' }, h('label', { for: id, class: 'label' }, 'Collection'), select);
}

/**
 * Picks the collection a page should work on: the one in the URL, else the last used, else the first.
 * Returns null when the user has no collections yet.
 */
export function pickCollection(collections, requestedId) {
  const wanted = [requestedId, getCurrentCollection()].find((id) => id && collections.some((c) => c.id === id));
  const chosen = collections.find((c) => c.id === wanted) || collections[0] || null;
  setCurrentCollection(chosen ? chosen.id : null);
  return chosen;
}

export function noCollections() {
  return emptyState('No data yet', 'Create a collection first, then add documents to it.',
    h('a', { class: 'btn btn-primary', href: '#/collections' }, 'Go to collections'));
}

export function fact(term, value) {
  return [h('dt', { class: 'label' }, term), h('dd', null, value)];
}

export { label };
