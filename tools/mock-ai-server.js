#!/usr/bin/env node
/*
 * Fake OpenAI-compatible AI server for trying BuildAegis's AI features WITHOUT an API key or a real model.
 *
 *   node tools/mock-ai-server.js            # listens on http://localhost:1234
 *   PORT=9000 node tools/mock-ai-server.js
 *   RATE_LIMIT_EVERY=3 node tools/mock-ai-server.js   # every 3rd request answers 429 (tests retry handling)
 *
 * In BuildAegis: AI settings -> preset "LM Studio (local, any API key)" (endpoint
 * http://localhost:1234/v1/chat/completions), type any API key, Save, Test connection.
 *
 * It does not call any model: answers are canned, built from what the prompt contains, so you can check that the
 * prompts reach the AI, that the responses are parsed and shown, and that failures and rate limits are handled.
 * Every request is logged to the console, including the prompt, so you can see exactly what BuildAegis sends.
 */
const http = require('http');

const PORT = Number(process.env.PORT || 1234);
const RATE_LIMIT_EVERY = Number(process.env.RATE_LIMIT_EVERY || 0);
let requests = 0;

function pick(re, text, fallback) {
  const m = re.exec(text);
  return m ? m[1].trim() : fallback;
}

function answerFor(prompt) {
  if (/Respond with 'OK'/.test(prompt)) {
    return 'OK';
  }
  if (/Reply with ONLY a JSON object/.test(prompt)) {
    const dep = pick(/Dependency:\s*(.+)/, prompt, pick(/(\S+:\S+:\S+)/, prompt, 'this dependency'));
    const fix = pick(/Smallest version fixing all known vulnerabilities:\s*(\S+)/, prompt, null);
    const body = {
      explanation: `[mock AI] ${dep} was flagged from the vulnerability data in the prompt. This text is canned; ` +
        'a real model would explain the impact in plain English here.',
      recommendations: fix
        ? [`[mock AI] Upgrade to ${fix}.`, '[mock AI] Run your tests after the upgrade.']
        : ['[mock AI] No fixed version is known; monitor the advisories.']
    };
    if (/"behaviourChanges"/.test(prompt)) {
      body.behaviourChanges = '[mock AI] Check the release notes for behaviour changes.';
    }
    return JSON.stringify(body);
  }
  const question = pick(/Question:\s*([\s\S]+)$/, prompt, null);
  return `[mock AI] You asked: "${question || prompt.slice(-200)}". A real model would answer using the finding data in the prompt.`;
}

http.createServer((req, res) => {
  let raw = '';
  req.on('data', c => { raw += c; });
  req.on('end', () => {
    requests++;
    if (req.method !== 'POST' || !req.url.includes('/chat/completions')) {
      res.writeHead(404, { 'Content-Type': 'application/json' });
      return res.end(JSON.stringify({ error: { message: 'only POST /v1/chat/completions is supported' } }));
    }
    if (RATE_LIMIT_EVERY > 0 && requests % RATE_LIMIT_EVERY === 0) {
      console.log(`#${requests} -> 429 (simulated rate limit)`);
      res.writeHead(429, { 'Content-Type': 'application/json', 'Retry-After': '1' });
      return res.end(JSON.stringify({ error: { message: 'Rate limit reached (simulated)' } }));
    }
    let prompt = '';
    try {
      const body = JSON.parse(raw);
      prompt = (body.messages || []).map(m => m.content).join('\n');
    } catch (e) {
      res.writeHead(400, { 'Content-Type': 'application/json' });
      return res.end(JSON.stringify({ error: { message: 'invalid JSON body' } }));
    }
    console.log(`#${requests} <- prompt (${prompt.length} chars):\n${prompt.slice(0, 1200)}${prompt.length > 1200 ? '\n...' : ''}\n`);
    const content = answerFor(prompt);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      id: 'mock-' + requests,
      object: 'chat.completion',
      model: 'mock',
      choices: [{ index: 0, finish_reason: 'stop', message: { role: 'assistant', content } }]
    }));
  });
}).listen(PORT, '127.0.0.1', () => {
  console.log(`Mock AI server on http://localhost:${PORT}/v1/chat/completions` +
    (RATE_LIMIT_EVERY ? ` (every ${RATE_LIMIT_EVERY}th request is rate limited)` : ''));
});
