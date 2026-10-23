import { h, label } from '../dom.js';

// Public entry page. The numbers on it are sample values, but the shapes are the real ones: the not-found sentence,
// the inspector's rank chips, the 1 / (60 + rank) fusion arithmetic and the document statuses all come from the app.

const REPO = 'https://github.com/maxprogerit/springRagService';

const SECTIONS = [
  { id: 'lp-hero', name: 'Overview' },
  { id: 'lp-refusal', name: 'Grounded answers' },
  { id: 'lp-fusion', name: 'Retrieval inspector' },
  { id: 'lp-ingest', name: 'Collections' },
  { id: 'lp-start', name: 'Run it' },
];

const glow = (...names) => names.map((name) => h('div', { class: `lp-glow lp-glow--${name}`, 'aria-hidden': 'true' }));
const eyebrow = (text) => h('p', { class: 'lp-eyebrow' }, text);
const fact = (term, text) => h('div', null, h('dt', null, term), h('dd', null, text));
const spans = (items) => items.map((text) => h('span', null, text));

// ---------- 1. Hero: the monitor ----------

function hitCard(title, rows, fused = false) {
  return h('div', { class: `lp-hit-card${fused ? ' lp-hit-card--fused' : ''}` },
    h('p', { class: 'lp-hit-card__label' }, title),
    rows.map((row, i) => h('p', { class: `lp-hit-row${i === 0 ? ' is-lead' : ''}` }, row)));
}

function screenContent() {
  return h('div', { class: 'lp-screen__inner' },
    h('p', { class: 'lp-screen__label' }, 'Hybrid search'),
    h('div', { class: 'lp-hits' },
      hitCard('Vector', ['p.4 · 0.82', 'p.9 · 0.74']),
      hitCard('Keyword', ['p.4 · 0.91', 'p.2 · 0.60']),
      hitCard('Fused (RRF)', [['1 · p.4 · ', h('b', null, '0.0328')], ['2 · p.9 · ', h('b', null, '0.0161')]], true)),
    h('p', { class: 'lp-answer' }, 'The notice period is 30 days. ', h('span', { class: 'lp-cite' }, '[1]')),
    h('p', { class: 'lp-screen__meta' }, spans(['vector 20', 'keyword 7', 'fused 2', '38 ms'])));
}

const STAGE_ALT = 'A desktop monitor showing the retrieval inspector for the question "notice period". '
  + 'The vector search ranks page 4 first with similarity 0.82, then page 9 with 0.74. '
  + 'The keyword search ranks page 4 first with 0.91, then page 2 with 0.60. '
  + 'Fused by rank, page 4 comes first and page 9 second. '
  + 'The answer below reads: The notice period is 30 days, citing source 1. '
  + 'Two labels float over the monitor frame: a citation for contract.pdf page 4 with score 0.82, and an "answer verified" status.';

function hero() {
  return h('section', { class: 'lp-section lp-hero', id: 'lp-hero', 'aria-labelledby': 'lp-h1' },
    glow('a', 'b', 'c'),
    h('div', { class: 'lp-hero__copy lp-reveal' },
      eyebrow('Watch the two searches agree'),
      h('h1', { class: 'lp-display lp-h1', id: 'lp-h1' },
        h('span', { class: 'lp-line' }, 'Vector and'), ' ',
        h('span', { class: 'lp-line' }, 'keyword,'), ' ',
        h('span', { class: 'lp-line' }, 'fused in the ', h('em', null, 'open.'))),
      h('p', { class: 'lp-lede' }, 'The retrieval inspector shows both rankings and the fused result before any answer gets generated, so nothing about the match is hidden.'),
      h('div', { class: 'lp-actions' },
        h('a', { class: 'btn btn-primary lp-cta', href: '#/signin' }, 'Ask a question'),
        h('span', { class: 'lp-caption' }, 'upload → chunks → hybrid → cited answer'))),
    h('figure', { class: 'lp-stage lp-reveal lp-d2', role: 'img', 'aria-label': STAGE_ALT },
      h('div', { class: 'lp-monitor' },
        h('div', { class: 'lp-bezel' }, h('div', { class: 'lp-screen' }, screenContent())),
        h('div', { class: 'lp-neck' }),
        h('div', { class: 'lp-base' })),
      h('div', { class: 'lp-chip lp-chip--cite' },
        h('span', { class: 'lp-chip__main' }, '[1] contract.pdf · p.4'),
        h('span', { class: 'lp-chip__sub' }, 'score 0.82')),
      h('div', { class: 'lp-chip lp-chip--status' },
        h('span', { class: 'lp-dot' }), 'answer verified · ', h('span', { class: 'accent' }, '[1]'))));
}

// ---------- 2. Grounded refusal ----------

function gateRow(question, value, widthClass, note, passed) {
  return h('div', { class: `lp-gate__row${passed ? '' : ' is-dropped'}` },
    h('span', { class: 'lp-gate__q' }, question),
    h('span', { class: 'lp-gate__v' }, value),
    h('div', { class: 'lp-track', 'aria-hidden': 'true' },
      h('div', { class: `lp-track__fill ${widthClass}` }),
      h('div', { class: 'lp-track__tick' })),
    h('p', { class: 'lp-gate__note' }, note));
}

function refusal() {
  return h('section', { class: 'lp-section lp-refusal', id: 'lp-refusal', 'aria-labelledby': 'lp-h-refusal' },
    glow('a', 'b'),
    h('div', { class: 'lp-split lp-split--flip' },
      h('div', { class: 'lp-copy lp-reveal' },
        eyebrow('When the files do not say'),
        h('h2', { class: 'lp-display lp-h2', id: 'lp-h-refusal' }, 'A weak match never ', h('em', null, 'reaches the model.')),
        h('p', { class: 'lp-lede' }, 'Every chunk gets a cosine similarity before generation. Anything under MIN_SIMILARITY (0.20 by default) is dropped, unless the keyword search found it and it really contains the words of the question.'),
        h('p', { class: 'lp-lede' }, 'If nothing passes, the model is not called. You get one fixed sentence, with a gold border so it cannot be mistaken for a real answer.'),
        h('dl', { class: 'lp-facts' },
          fact('Before the model', 'A chunk needs the similarity, or a real keyword match, to be sent at all.'),
          fact('After the model', 'Every [n] is checked against the sources that were sent. A number that points at nothing is removed.'))),
      h('div', { class: 'lp-visual lp-visual--l lp-reveal lp-d1' },
        h('div', { class: 'lp-panel', role: 'group', 'aria-label': 'Example: a question the documents cannot answer' },
          h('div', { class: 'chat-log' },
            h('div', { class: 'msg msg-user' }, h('p', { class: 'msg-text' }, 'What is the parental leave policy?')),
            h('div', { class: 'msg msg-assistant is-notfound' },
              h('p', { class: 'msg-text' }, 'I could not find this in the uploaded documents.'),
              h('p', { class: 'dim msg-hint' }, 'Ask about what the documents contain, for example: contract.pdf, nda-template.docx.'),
              h('div', { class: 'msg-meta' }, spans(['vector 20', 'keyword 0', 'fused 5', '612 ms'])))),
          h('div', { class: 'lp-gate' },
            h('div', { class: 'lp-panel__bar' },
              label('Relevance gate'),
              h('span', { class: 'mono dim' }, 'tick = MIN_SIMILARITY 0.20')),
            gateRow('What is the notice period?', '0.82', 'lp-w-82', 'Above the gate, so the chunk goes to the model.', true),
            gateRow('What is the parental leave policy?', '0.14', 'lp-w-14', 'Below the gate and no keyword matched, so the model is not called.', false))),
        h('div', { class: 'lp-chip lp-chip--inline lp-chip--refusal', 'aria-hidden': 'true' },
          h('span', { class: 'lp-dot lp-dot--warn' }), 'model not called'))));
}

// ---------- 3. Retrieval inspector numbers ----------

const FUSED = [
  { doc: 'terms.pdf · p.6', vector: '#1 · 0.78', keyword: '#2 · 0.41', sum: '1/61 + 1/62', score: '0.0325', agree: true },
  { doc: 'terms.pdf · p.7', vector: '#3 · 0.55', keyword: '#1 · 0.64', sum: '1/63 + 1/61', score: '0.0323', agree: true },
  { doc: 'pricing.pdf · p.2', vector: '#2 · 0.71', keyword: null, sum: '1/62', score: '0.0161', agree: false },
  { doc: 'faq.md · p.1', vector: null, keyword: '#3 · 0.18', sum: '1/63', score: '0.0159', agree: false },
];

function fusedRow(row, index) {
  return h('li', { class: `hit lp-fuse-row${row.agree ? ' is-agree' : ''}` },
    h('span', { class: 'lp-fuse-row__rank' }, String(index + 1)),
    h('span', { class: 'hit-title' }, row.doc),
    h('div', { class: 'hit-ranks' },
      h('span', { class: 'chip chip-static' }, `vector ${row.vector ?? '–'}`),
      h('span', { class: 'chip chip-static' }, `keyword ${row.keyword ?? '–'}`)),
    h('span', { class: 'lp-fuse-row__sum' }, h('span', { class: 'visually-hidden' }, 'Sum: '), row.sum),
    h('span', { class: 'hit-score' }, h('span', { class: 'visually-hidden' }, 'Fused score '), row.score));
}

function fusion() {
  return h('section', { class: 'lp-section lp-fusion', id: 'lp-fusion', 'aria-labelledby': 'lp-h-fusion' },
    glow('a', 'b'),
    h('div', { class: 'lp-split' },
      h('div', { class: 'lp-copy lp-reveal' },
        eyebrow('Before anything is generated'),
        h('h2', { class: 'lp-display lp-h2', id: 'lp-h-fusion' }, 'Every rank, ', h('em', null, 'in plain numbers.')),
        h('p', { class: 'lp-lede' }, 'The retrieval inspector runs your query with no language model involved and prints what each search found, with its real score.'),
        h('p', { class: 'lp-lede' }, 'The fused score is the sum of 1 / (60 + rank) over both lists. Only ranks count, so cosine similarity and ts_rank_cd never have to share a scale.')),
      h('div', { class: 'lp-visual lp-visual--r lp-reveal lp-d1' },
        h('div', { class: 'lp-panel', role: 'group', 'aria-label': 'Example: the fused ranking for "refund window for annual plans"' },
          h('div', { class: 'lp-panel__bar' },
            label('Fused ranking'),
            h('span', { class: 'lp-query' }, 'refund window for annual plans')),
          h('p', { class: 'summary-line mono dim' }, spans(['vector hits 20', 'keyword hits 7', 'fused top 4 (K=4)', '31 ms'])),
          h('div', { class: 'lp-fuse-head', 'aria-hidden': 'true' },
            ['#', 'Chunk', 'Rank in each search', '1/(60+rank)', 'Fused'].map((text) => label(text))),
          h('ol', { class: 'hit-list' }, FUSED.map(fusedRow)),
          h('p', { class: 'lp-note' },
            h('b', null, 'terms.pdf p.7'), ' came third by meaning and first by words. Both searches found it, so it outranks pricing.pdf p.2, which only the vector search found.')),
        h('div', { class: 'lp-chip lp-chip--inline lp-chip--k', 'aria-hidden': 'true' }, 'RRF ', h('b', null, 'k = 60')))));
}

// ---------- 4. Collections and ingestion ----------

const DOCS = [
  { name: 'contract.pdf', pages: '14', status: 'READY' },
  { name: 'nda-template.docx', pages: '6', status: 'PROCESSING' },
  { name: 'handbook-2019.pdf', pages: '–', status: 'FAILED', error: 'The PDF could not be read (damaged or password-protected).' },
];

function docRow(doc) {
  return h('tr', null,
    h('td', { class: 'cell-name' }, doc.name, doc.error ? h('div', { class: 'cell-error' }, doc.error) : null),
    h('td', { class: 'num mono' }, doc.pages),
    h('td', null, h('span', { class: `badge badge-${doc.status.toLowerCase()}` }, doc.status)));
}

function ingest() {
  return h('section', { class: 'lp-section lp-ingest', id: 'lp-ingest', 'aria-labelledby': 'lp-h-ingest' },
    glow('a', 'b'),
    h('div', { class: 'lp-split lp-split--flip' },
      h('div', { class: 'lp-copy lp-reveal' },
        eyebrow('Collections and ingestion'),
        h('h2', { class: 'lp-display lp-h2', id: 'lp-h-ingest' }, 'Every file shows ', h('em', null, 'where it is.')),
        h('p', { class: 'lp-lede' }, 'A collection keeps one set of documents apart from the next, such as Legal documents and Product manuals. An upload returns at once while the file is parsed, split into page-aware chunks, embedded and marked READY.'),
        h('dl', { class: 'lp-facts' },
          fact('Formats', 'PDF, Word, HTML, text and Markdown, plus PNG and JPEG when OCR is on.'),
          fact('Pages', 'Chunks never cross a page, so a citation always names one.'))),
      h('div', { class: 'lp-visual lp-visual--l lp-reveal lp-d1' },
        h('div', { class: 'lp-panel', role: 'group', 'aria-label': 'Example: the documents of the collection "Legal documents"' },
          h('div', { class: 'lp-panel__bar' },
            h('div', null, label('Collection'), h('div', { class: 'collection-name' }, 'Legal documents')),
            h('span', { class: 'dim mono' }, 'Showing 3 of 3')),
          h('div', { class: 'table-wrap' },
            h('table', null,
              h('thead', null, h('tr', null,
                h('th', { class: 'label', scope: 'col' }, 'Document'),
                h('th', { class: 'label num', scope: 'col' }, 'Pages'),
                h('th', { class: 'label', scope: 'col' }, 'Status'))),
              h('tbody', null, DOCS.map(docRow)))),
          h('ol', { class: 'lp-pipe', 'aria-label': 'Ingestion steps' },
            h('li', null, 'parse'),
            h('li', null, 'page-aware chunks'),
            h('li', null, 'embed'),
            h('li', null, h('span', { class: 'badge badge-ready' }, 'READY')))),
        h('div', { class: 'lp-chip lp-chip--inline lp-chip--poll', 'aria-hidden': 'true' },
          h('span', { class: 'lp-dot lp-dot--warn' }), 'refreshes every 2 s while PROCESSING'))));
}

// ---------- 5. Run it ----------

function start() {
  return h('section', { class: 'lp-section lp-start', id: 'lp-start', 'aria-labelledby': 'lp-h-start' },
    glow('a', 'b'),
    h('div', { class: 'lp-split' },
      h('div', { class: 'lp-copy lp-reveal' },
        eyebrow('Run it on your machine'),
        h('h2', { class: 'lp-display lp-h2', id: 'lp-h-start' }, 'Ask your documents, see both searches ', h('em', null, 'agree.')),
        h('p', { class: 'lp-lede' }, 'One Spring Boot app and one PostgreSQL database. The web UI is served by the same app, so there is nothing else to install.'),
        h('div', { class: 'lp-actions' },
          h('a', { class: 'btn btn-primary lp-cta', href: '#/signin' }, 'Open the app'),
          h('a', { class: 'btn lp-cta', href: REPO, rel: 'noopener' }, 'Source on GitHub')),
        h('p', { class: 'lp-foot' }, 'Java 21 · Spring Boot · PostgreSQL 16 · pgvector 0.8')),
      h('div', { class: 'lp-visual lp-visual--r lp-reveal lp-d1' },
        h('div', { class: 'lp-panel' },
          h('div', { class: 'lp-panel__bar' }, label('Run locally'), h('span', { class: 'mono dim' }, 'Docker')),
          h('pre', { class: 'lp-run' },
            h('code', null,
              'cp .env.example .env\n',
              h('span', { class: 'c' }, '# set DB_PASSWORD, JWT_SECRET, OPENAI_API_KEY\n'),
              'docker compose up -d\n',
              h('span', { class: 'c' }, '# then open http://localhost:8080')))),
        h('p', { class: 'lp-note' }, 'No OpenAI key yet? The demo override (docker-compose.demo.yml) starts a stand-in for OpenAI, so you can try upload, search and citations first.'))));
}

// ---------- Page shell, dots, motion ----------

export function mount(root) {
  const header = h('header', { class: 'lp-header' },
    h('div', { class: 'lp-wordmark' }, 'Spring ', h('span', null, 'RAG'), ' Service'),
    h('div', { class: 'lp-header__meta' },
      h('span', { class: 'lp-tracked lp-stack' }, 'Java · Spring Boot · pgvector'),
      h('a', { class: 'lp-tracked lp-repo', href: REPO, rel: 'noopener' }, 'GitHub')));

  const main = h('main', { class: 'lp-main', id: 'main', tabindex: '-1' }, hero(), refusal(), fusion(), ingest(), start());

  const dotButtons = SECTIONS.map((section) => h('button', {
    type: 'button', 'aria-label': section.name, 'data-target': section.id, onClick: () => jump(section.id),
  }, h('span', { class: 'lp-dots__label', 'aria-hidden': 'true' }, section.name)));
  const dots = h('nav', { class: 'lp-dots', 'aria-label': 'Page sections' }, dotButtons);

  const page = h('div', { class: 'lp' }, header, dots, main, h('div', { class: 'lp-grain', 'aria-hidden': 'true' }));
  root.append(page);

  // jsdom (the UI test) and old browsers have neither of these: the page then simply shows everything at once.
  const canObserve = typeof IntersectionObserver === 'function';
  const motionQuery = typeof window.matchMedia === 'function' ? window.matchMedia('(prefers-reduced-motion: reduce)') : null;
  const reducedMotion = () => Boolean(motionQuery && motionQuery.matches);
  const applyMotion = () => page.classList.toggle('lp--motion', canObserve && !reducedMotion());
  applyMotion();
  if (motionQuery && typeof motionQuery.addEventListener === 'function') motionQuery.addEventListener('change', applyMotion);

  function jump(id) {
    const target = document.getElementById(id);
    if (target) target.scrollIntoView({ behavior: reducedMotion() ? 'auto' : 'smooth', block: 'start' });
  }

  function setActive(id) {
    for (const button of dotButtons) {
      if (button.dataset.target === id) button.setAttribute('aria-current', 'location');
      else button.removeAttribute('aria-current');
    }
  }
  setActive(SECTIONS[0].id);

  let reveal = null;
  let spy = null;
  if (canObserve) {
    reveal = new IntersectionObserver((entries) => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue;
        entry.target.classList.add('is-in');
        reveal.unobserve(entry.target);
      }
    }, { threshold: 0.12, rootMargin: '0px 0px -6% 0px' });
    page.querySelectorAll('.lp-reveal').forEach((node) => reveal.observe(node));

    spy = new IntersectionObserver((entries) => {
      for (const entry of entries) if (entry.isIntersecting) setActive(entry.target.id);
    }, { rootMargin: '-45% 0px -45% 0px', threshold: 0 });
    main.querySelectorAll('.lp-section').forEach((node) => spy.observe(node));
  }

  const onScroll = () => header.classList.toggle('is-scrolled', window.scrollY > 24);
  window.addEventListener('scroll', onScroll, { passive: true });
  onScroll();

  // The router listens to hashchange, so the "Skip to content" link (href="#main") must not change the hash on this page.
  const skip = document.querySelector('.skip-link');
  const onSkip = (event) => {
    event.preventDefault();
    main.focus();
  };
  if (skip) skip.addEventListener('click', onSkip);

  return () => {
    if (reveal) reveal.disconnect();
    if (spy) spy.disconnect();
    window.removeEventListener('scroll', onScroll);
    if (skip) skip.removeEventListener('click', onSkip);
    if (motionQuery && typeof motionQuery.removeEventListener === 'function') motionQuery.removeEventListener('change', applyMotion);
  };
}
