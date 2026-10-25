// Behaviour test for the GitHub download accelerator worker (_cf-worker/index.js).
//
// The worker is a minified bundle, so the only way to be sure the redirect
// handling is right is to drive it with a stubbed fetch and look at what the
// upstream actually received.
//
// Run: node _cf-worker/test-redirect.mjs

import app from './index.js';

const calls = [];
globalThis.fetch = async (url, init) => {
  calls.push({
    url: String(url),
    method: init?.method,
    hasBody: init?.body != null,
    // The body is buffered into an ArrayBuffer so it can be replayed on a
    // 307/308 redirect - decode it for the assertions.
    body:
      init?.body == null
        ? null
        : typeof init.body === 'string'
          ? init.body
          : Buffer.from(init.body).toString('utf8'),
    contentType: init?.headers?.get?.('content-type') ?? null,
  });
  const n = calls.length;
  if (n === 1) {
    // First hop: 302 to another allowed host.
    return new Response(null, { status: 302, headers: { Location: 'https://github.com/second' } });
  }
  if (n === 2) {
    // Second hop: 307 keeps the method and body.
    return new Response(null, { status: 307, headers: { Location: 'https://github.com/third' } });
  }
  return new Response('final-body', { status: 200 });
};
globalThis.caches = { default: { match: async () => undefined, put: async () => {} } };

const env = { AUTH_TOKEN: 'secret' };
const TARGET = 'https://worker.example/https://github.com/start';

let failures = 0;
function check(name, cond, detail) {
  if (cond) {
    console.log(`  PASS: ${name}`);
  } else {
    console.log(`  FAIL: ${name} ${detail ?? ''}`);
    failures++;
  }
}

async function run(method, body) {
  calls.length = 0;
  const req = new Request(TARGET, {
    method,
    body,
    headers: { Authorization: 'Bearer secret', 'Content-Type': 'text/plain' },
  });
  const res = await app.fetch(req, env);
  return { status: res.status, calls: calls.map((c) => ({ ...c })) };
}

console.log('POST that redirects (302) then 307');
{
  const r = await run('POST', 'payload-1');
  check('responds 200', r.status === 200, `got ${r.status}`);
  check('hop 1 is POST with body', r.calls[0].method === 'POST' && r.calls[0].body === 'payload-1',
    JSON.stringify(r.calls[0]));
  // 302 + POST must degrade to GET without a body (browser semantics).
  check('hop 2 is GET without body', r.calls[1].method === 'GET' && !r.calls[1].hasBody,
    JSON.stringify(r.calls[1]));
  check('hop 2 drops content-type', r.calls[1].contentType === null, JSON.stringify(r.calls[1]));
  check('hop 3 is GET without body', r.calls[2].method === 'GET' && !r.calls[2].hasBody,
    JSON.stringify(r.calls[2]));
}

console.log('GET that redirects');
{
  const r = await run('GET', undefined);
  check('responds 200', r.status === 200, `got ${r.status}`);
  check('hop 1 is GET', r.calls[0].method === 'GET', JSON.stringify(r.calls[0]));
  check('hop 2 is GET', r.calls[1].method === 'GET', JSON.stringify(r.calls[1]));
}

console.log('auth is enforced before anything else');
{
  calls.length = 0;
  const res = await app.fetch(
    new Request(TARGET, { headers: { Authorization: 'Bearer wrong' } }),
    env
  );
  check('401 on bad token', res.status === 401, `got ${res.status}`);
  check('no upstream request was made', calls.length === 0, `calls=${calls.length}`);
}

console.log('fails closed when no secret is configured');
{
  calls.length = 0;
  const res = await app.fetch(new Request(TARGET), {});
  check('401 with empty env', res.status === 401, `got ${res.status}`);
  check('no upstream request was made', calls.length === 0, `calls=${calls.length}`);
}

console.log('redirect to a non-allowed host is rejected');
{
  globalThis.fetch = async () =>
    new Response(null, { status: 302, headers: { Location: 'https://evil.example/x' } });
  const res = await app.fetch(
    new Request(TARGET, { headers: { Authorization: 'Bearer secret' } }),
    env
  );
  check('403 on disallowed redirect target', res.status === 403, `got ${res.status}`);
}

if (failures === 0) {
  console.log('ALL PASS');
  process.exit(0);
}
console.log(`${failures} assertion(s) failed`);
process.exit(1);
