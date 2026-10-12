# Front-end check (development only)

Runs the real UI modules in [jsdom](https://github.com/jsdom/jsdom) against a **running** server and clicks through:
sign-up, collections (with paging), uploads, streamed answers with citations, follow-up questions, the retrieval
inspector, password change, "sign out everywhere" and the e-mail link pages.

The server needs an OpenAI-compatible endpoint. Without an API key use the stand-in in `dev/` (it is not an AI):

```bash
docker compose up -d db
node dev/fake-openai.mjs 8099 &                       # DEMO stand-in for OpenAI
OPENAI_BASE_URL=http://localhost:8099/v1 OPENAI_API_KEY=demo mvn spring-boot:run &
cd frontend-tests && npm install && BASE_URL=http://localhost:8080 npm test
```

jsdom is not a browser: it checks behaviour and DOM structure, not layout, colours, or how the page looks.
