import { api } from '../api.js';
import { collectionPicker, noCollections, pickCollection } from '../components.js';
import { emptyState, errorMessage, fmt, h, icon, label, pageHeader } from '../dom.js';

function snippetOf(text) {
  const flat = text.replace(/\s+/g, ' ').trim();
  return flat.length > 90 ? `${flat.slice(0, 90)}…` : flat;
}

function hitCard(hit, { scoreText, extra }) {
  return h('li', { class: 'hit' },
    h('div', { class: 'hit-top' },
      h('span', { class: 'hit-title' }, `${hit.filename} · p.${hit.page}`),
      h('span', { class: 'hit-score', title: 'score' }, scoreText)),
    extra || null,
    h('details', null, h('summary', null, snippetOf(hit.text)), h('p', null, hit.text)));
}

function column(title, description, count, hits, render) {
  return h('section', { class: 'card', 'aria-label': title },
    h('div', { class: 'card-head' }, h('h2', null, title), h('span', { class: 'badge badge-ready' }, String(count))),
    h('p', { class: 'dim' }, description),
    h('div', { class: 'card-gap-sm' }),
    hits.length === 0
      ? emptyState('No data yet', 'Nothing matched.')
      : h('ol', { class: 'hit-list' }, hits.map(render)));
}

export async function mount(root, { collectionId, navigate }) {
  let list;
  try {
    list = (await api.listCollections({ size: 200 })).items;
  } catch (e) {
    root.append(pageHeader('Debug', 'Retrieval inspector'), h('p', { class: 'notice notice-error', role: 'alert' }, errorMessage(e)));
    return undefined;
  }
  const collection = pickCollection(list, collectionId);
  if (!collection) {
    root.append(pageHeader('Debug', 'Retrieval inspector'), noCollections());
    return undefined;
  }

  const results = h('div', null, emptyState('No data yet', 'Type a query to see what each retriever finds and how the fused ranking is built.'));
  const question = h('input', { class: 'input', id: 'inspect-query', type: 'text', maxlength: 2000, required: true, placeholder: 'e.g. refund window for annual plans', autocomplete: 'off' });
  const topK = h('input', { class: 'input', id: 'inspect-topk', type: 'number', min: 1, max: 20, placeholder: '5' });
  const run = h('button', { class: 'btn btn-primary', type: 'submit' }, icon('inspector', 16), 'Run search');

  function renderResult(r) {
    const fusedExtra = (hit) => h('div', { class: 'hit-ranks' },
      h('span', { class: 'chip chip-static', title: hit.vectorRank ? `cosine similarity ${fmt.fixed(hit.vectorSimilarity, 3)}` : 'not returned by the vector search' },
        `vector ${hit.vectorRank ? `#${hit.vectorRank}` : '–'}`),
      h('span', { class: 'chip chip-static', title: hit.keywordRank ? `ts_rank_cd ${fmt.fixed(hit.keywordScore, 3)}` : 'not returned by the keyword search' },
        `keyword ${hit.keywordRank ? `#${hit.keywordRank}` : '–'}`));

    results.replaceChildren(
      h('p', { class: 'summary-line mono' },
        h('span', null, `vector hits ${r.vectorHits.length}`),
        h('span', null, `keyword hits ${r.keywordHits.length}`),
        h('span', null, `fused top ${r.fused.length} (K=${r.topK})`),
        h('span', null, `${r.latencyMs} ms`)),
      h('div', { class: 'cols-3' },
        column('Vector hits', 'Cosine similarity of the query embedding to each chunk (pgvector, HNSW).', r.vectorHits.length, r.vectorHits,
          (hit, i) => hitCard(hit, { scoreText: fmt.fixed(hit.score, 3), extra: h('div', { class: 'hit-ranks' }, h('span', { class: 'chip chip-static' }, `#${i + 1}`)) })),
        column('Keyword hits', 'PostgreSQL full-text search, ranked by ts_rank_cd over query words joined with OR.', r.keywordHits.length, r.keywordHits,
          (hit, i) => hitCard(hit, { scoreText: fmt.fixed(hit.score, 3), extra: h('div', { class: 'hit-ranks' }, h('span', { class: 'chip chip-static' }, `#${i + 1}`)) })),
        column('Fused ranking', 'Reciprocal Rank Fusion: the sum of 1 / (60 + rank) over both lists.', r.fused.length, r.fused,
          (hit, i) => hitCard(hit, { scoreText: fmt.fixed(hit.score, 4), extra: h('div', null, h('span', { class: 'chip chip-static' }, `#${i + 1}`), fusedExtra(hit)) }))));
  }

  async function onSubmit(event) {
    event.preventDefault();
    run.disabled = true;
    results.replaceChildren(h('p', { class: 'dim' }, 'Searching…'));
    try {
      renderResult(await api.search(collection.id, question.value.trim(), topK.value ? Number(topK.value) : undefined));
    } catch (e) {
      results.replaceChildren(h('p', { class: 'notice notice-error', role: 'alert' }, errorMessage(e)));
    } finally {
      run.disabled = false;
    }
  }

  root.append(
    pageHeader('Debug', 'Retrieval inspector', collectionPicker(list, collection.id, (id) => navigate(`inspector/${id}`))),
    h('p', { class: 'muted' }, 'Retrieval only, no language model involved. Compare what the vector and keyword searches return with the fused ranking.'),
    h('form', { class: 'card inline-form', onSubmit },
      h('div', { class: 'field' }, h('label', { class: 'label', for: 'inspect-query' }, 'Query'), question),
      h('div', { class: 'field' }, h('label', { class: 'label', for: 'inspect-topk' }, 'Top K'), topK),
      run),
    h('div', { class: 'card-gap' }),
    results);
}
