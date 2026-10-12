// DEMO / TEST STAND-IN for the OpenAI API. Not an AI: it does no reasoning.
//
//  * embeddings: a hashed bag of words (matches shared WORDS, not meaning)
//  * chat:       replies with the sentence of the best-matching source plus its [n] marker
//  * follow-ups: "rewrites" a question by returning it unchanged
//
// It exists so the whole application (upload, search, citations, streaming, UI) can be tried and tested
// without an API key. Answers produced with it say nothing about the quality of a real model.
//
// Usage: node dev/fake-openai.mjs [port]      then start the app with OPENAI_BASE_URL=http://localhost:<port>/v1
import http from 'node:http';

const PORT = Number(process.argv[2] || process.env.PORT || 8099);
const STOP = new Set(['the', 'is', 'are', 'was', 'what', 'which', 'how', 'for', 'and', 'you', 'your', 'can', 'does', 'with', 'from', 'this', 'that', 'much', 'many']);

function hash(word) {
  let h = 0;
  for (const c of word) h = (Math.imul(h, 31) + c.charCodeAt(0)) | 0;
  return h;
}

function words(text) {
  return [...text.toLowerCase().matchAll(/[\p{L}\p{N}]{2,}/gu)].map((m) => m[0]).filter((w) => !STOP.has(w));
}

function embed(text, dimension) {
  const v = new Float64Array(dimension);
  for (const w of words(text)) {
    const h = hash(w);
    v[(h & 0x7fffffff) % dimension] += (h >>> 16) & 1 ? -1 : 1;
  }
  v[0] += 0.001;
  const norm = Math.hypot(...v);
  return Array.from(v, (x) => x / norm);
}

const NOT_FOUND = 'I could not find this in the uploaded documents.';

function answerFor(system, user) {
  if (system.includes('You rewrite the last question')) {
    return (user.split('Last question:')[1] || user).trim();
  }
  const question = (user.split('Question:').pop() || '').trim();
  const wanted = new Set(words(question));
  let best = null;
  for (const m of user.matchAll(/<source id="(\d+)"[^>]*>\n([\s\S]*?)\n<\/source>/g)) {
    for (const sentence of m[2].split(/(?<=[.!?])\s+|\n+/).filter((s) => s.trim().length > 3)) {
      const score = words(sentence).filter((w) => wanted.has(w)).length;
      if (!best || score > best.score) best = { score, id: m[1], sentence: sentence.trim() };
    }
  }
  return best && best.score > 0 ? `${best.sentence} [${best.id}]` : NOT_FOUND;
}

function pieces(text) {
  return text.match(/.{1,6}/gs) || [text];
}

http.createServer((req, res) => {
  let raw = '';
  req.on('data', (chunk) => { raw += chunk; });
  req.on('end', () => {
    const json = (o) => { res.writeHead(200, { 'content-type': 'application/json' }); res.end(JSON.stringify(o)); };
    let body = {};
    try { body = raw ? JSON.parse(raw) : {}; } catch { res.writeHead(400); res.end(); return; }

    if (req.url.endsWith('/embeddings')) {
      const dimension = body.dimensions || 1536;
      return json({ data: body.input.map((t, index) => ({ index, embedding: embed(t, dimension) })) });
    }
    if (req.url.endsWith('/chat/completions')) {
      const answer = answerFor(body.messages[0].content, body.messages[1].content);
      if (!body.stream) return json({ choices: [{ message: { content: answer } }] });
      res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache' });
      const parts = pieces(answer);
      let i = 0;
      const timer = setInterval(() => {
        if (i < parts.length) {
          res.write(`data: ${JSON.stringify({ choices: [{ delta: { content: parts[i++] } }] })}\n\n`);
        } else {
          res.write('data: [DONE]\n\n');
          res.end();
          clearInterval(timer);
        }
      }, 15);
      return undefined;
    }
    res.writeHead(404);
    res.end();
  });
}).listen(PORT, () => console.log(`DEMO stand-in for OpenAI listening on http://localhost:${PORT}/v1 (not a real model)`));
