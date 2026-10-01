import test from 'node:test';
import assert from 'node:assert/strict';
import { createStorage, assertNoCredentials, assertNoKnownSecrets, sanitizeForStorage } from '../src/storage.js';
import { createQueue, recoverJob, recoverJobs, rateLimitDelay, rateLimitBucket, submissionFailure, QUEUE_STORAGE_KEY, MIN_POLL_INTERVAL, QUEUE_LIMIT } from '../src/queue.js';

const BASE = 'https://api.example.com';
const fakeApi = overrides => ({
  DEFAULT_BASE_URL: BASE,
  buildImagePayload: input => input,
  buildVideoPayload: input => input,
  submitImage: async () => ({ status: 'succeeded', outputs: [{ url: 'https://media.example.com/image.png' }] }),
  submitVideo: async () => ({ status: 'processing', remoteId: 'video-123', outputs: [] }),
  pollVideo: async () => ({ status: 'succeeded', outputs: [{ url: 'https://media.example.com/video.mp4' }] }),
  ...overrides,
});
const record = overrides => ({ id: 'job-1', type: 'image', input: { prompt: 'Test', size: '1K' }, baseUrl: BASE, status: 'queued', nextRunAt: 0, submitAttempts: 0, pollFailures: 0, outputs: [], ...overrides });
const flush = () => new Promise(resolve => setImmediate(resolve));

async function harness({ api, state, settings = { baseUrl: BASE, apiKey: 'session-secret-test' }, ...options } = {}) {
  let time = 100_000;
  const storage = options.storage || createStorage({ indexedDB: null });
  await storage.init();
  if (state) await storage.set(QUEUE_STORAGE_KEY, state);
  const queue = createQueue({ storage, api: fakeApi(api), getSettings: () => settings, now: () => time,
    autoStart: false, locks: null, channelFactory: null, setTimer: () => 1, clearTimer: () => {}, ...options });
  await queue.init();
  queue.isLeader = true;
  return {
    queue, storage, settings, time: () => time, advance: value => { time += value; },
    run: async () => { await queue.tick(); await Promise.all([...queue.inflight.values()].map(request => request.promise)); },
  };
}

test('memory fallback is visible and stores independent copies', async () => {
  const warnings = [];
  const storage = createStorage({ indexedDB: null, onWarning: text => warnings.push(text) });
  assert.equal(await storage.init(), storage);
  assert.equal(storage.persistent, false);
  assert.match(storage.warning, /刷新页面会丢失/);
  assert.equal(warnings.length, 1);
  const input = { nested: [1] };
  await storage.set('settings', input);
  input.nested.push(2);
  const output = await storage.get('settings');
  output.nested.push(3);
  assert.deepEqual(await storage.get('settings'), { nested: [1] });
  await storage.delete('settings');
  assert.equal(await storage.get('settings', 'fallback'), 'fallback');
});

test('credential fields are refused recursively, including direct storage keys', async () => {
  const storage = createStorage({ indexedDB: null });
  await assert.rejects(storage.set('settings', { nested: { apiKey: 'secret' } }), /凭据/);
  await assert.rejects(storage.set('authorization', 'Bearer secret'), /凭据/);
  await assert.rejects(storage.set('settings', { api_key: 'secret' }), /凭据/);
  await assert.rejects(storage.set('settings', { extraJson: '{"headers":{"Authorization":"Bearer secret"}}' }), /凭据/);
  await assert.rejects(storage.set('settings', { extraJson: '{"headers":{"x-api-key":"secret"}}' }), /凭据/);
  assert.doesNotThrow(() => assertNoCredentials({ apiKey: '', video: { seed: 5 } }));
  assert.doesNotThrow(() => assertNoCredentials({ apiKey: '[已隐藏凭据]' }));
});

test('known secrets are redacted in local text records and remembered after key removal', async () => {
  let activeKey = 'sk-current-secret';
  const storage = createStorage({ indexedDB: null, getSecrets: () => [activeKey] });
  await storage.set('chat', { text: `Accidentally pasted ${activeKey}`, extraJson: '{"label":"ordinary"}' });
  assert.equal((await storage.get('chat')).text, 'Accidentally pasted [已隐藏凭据]');
  activeKey = '';
  const exported = storage.sanitize({ conversations: [{ text: 'old key sk-current-secret' }] });
  assert.ok(!JSON.stringify(exported).includes('sk-current-secret'));
  assert.equal(storage.knownSecrets.size, 1);
});

test('export sanitizer redacts nested auth JSON but preserves unrelated media token URLs', () => {
  const input = { extraJson: '{"headers":{"Authorization":"Bearer unknown-key"}}', url: 'https://media.example.com/img.png?token=public-media-token&expires=100' };
  const sanitized = sanitizeForStorage(input);
  assert.ok(!JSON.stringify(sanitized).includes('unknown-key'));
  assert.equal(sanitized.url, input.url);
  assert.doesNotThrow(() => assertNoCredentials({ references: [input.url] }));
  assert.doesNotThrow(() => assertNoKnownSecrets(input, ['a-different-secret']));
  assert.throws(() => assertNoKnownSecrets({ text: 'A key containing a"b' }, ['a"b']), /API Key/);
});

test('queue rejects auth hidden in advanced JSON or the active key in free text before dispatch', async () => {
  let calls = 0;
  const h = await harness({ api: { submitImage: async () => { calls += 1; } } });
  await assert.rejects(h.queue.add({ type: 'image', input: { prompt: 'test', extraJson: '{"api_key":"hidden-secret"}' } }), /凭据/);
  await assert.rejects(h.queue.add({ type: 'image', input: { prompt: 'echo session-secret-test' } }), /API Key/);
  await assert.rejects(h.queue.add({ type: 'image', input: { prompt: 'test', references: ['https://media.example.com/image.png?token=session-secret-test'] } }), /API Key/);
  await h.run();
  assert.equal(calls, 0);
  assert.equal(h.queue.list().length, 0);
  h.queue.dispose();
});

test('failed IndexedDB open visibly falls back', async () => {
  const storage = createStorage({ indexedDB: { open() { throw new Error('denied'); } } });
  await storage.init();
  await storage.set('test', 42);
  assert.equal(await storage.get('test'), 42);
  assert.equal(storage.persistent, false);
  assert.ok(storage.warning);
});

test('recovery never replays a sending POST or untrusted legacy retry', () => {
  for (const status of ['sending', 'processing', 'retry']) {
    const recovered = recoverJob(record({ status }), 1000);
    assert.equal(recovered.status, 'uncertain');
    assert.match(recovered.error, /计费/);
    assert.equal(recovered.nextRunAt, null);
  }
  assert.equal(recoverJob(record({ status: 'retry', retryReason: 'rate_limit' })).status, 'retry');
  assert.equal(recoverJob(record({ status: 'queued' })).status, 'queued');
});

test('known video ID recovers to GET polling with a minimum interval', () => {
  const original = record({ type: 'video', status: 'sending', remoteId: 'vid', lastPollAt: 5000, attemptId: 'old', nextRunAt: 10_000 });
  const result = recoverJob(original, 6000);
  assert.equal(result.status, 'processing');
  assert.equal(result.nextRunAt, 35_000);
  assert.equal(result.attemptId, undefined);
  assert.equal(original.attemptId, 'old');
  assert.equal(recoverJobs([record({ status: 'cancelled', remoteId: 'vid' })], 6000)[0].status, 'cancelled');
});

test('free tier limits use separate resolution buckets and a strict sliding minute', () => {
  assert.equal(rateLimitBucket(record({ input: { size: '2K' } })), 'image:2K');
  assert.equal(rateLimitBucket(record({ type: 'video' })), 'video');
  const times = [{ bucket: 'image:3K', baseUrl: BASE, at: 1000 }];
  assert.equal(rateLimitDelay(times, 'image:3K', 2000, 1, BASE), 59_000);
  assert.equal(rateLimitDelay(times, 'image:3K', 61_000, 1, BASE), 0);
  assert.equal(rateLimitDelay(times, 'image:4K', 2000, 1, BASE), 0);
  assert.equal(rateLimitDelay(times, 'image:3K', 2000, 1, 'https://other.example.com'), 0);
  assert.equal(rateLimitDelay(Array.from({ length: 19 }, () => ({ bucket: 'image:1K', at: 1000 })), 'image:1K', 2000), 0);
  assert.equal(rateLimitDelay(Array.from({ length: 20 }, () => ({ bucket: 'image:1K', at: 1000 })), 'image:1K', 2000), 59_000);
  assert.equal(rateLimitDelay(Array.from({ length: 10 }, () => ({ bucket: 'image:2K', at: 1000 })), 'image:2K', 2000), 59_000);
});

test('only an explicit HTTP 429 may schedule another POST and respects Retry-After', () => {
  assert.deepEqual(submissionFailure({ status: 429, retryable: true, retryAfterMs: 95_000 }, 1, 3, 1000), { status: 'retry', retryReason: 'rate_limit', nextRunAt: 96_000 });
  assert.equal(submissionFailure({ status: 429, retryable: true }, 4, 3, 1000).status, 'failed');
  assert.equal(submissionFailure({ status: 429, retryable: false }, 1, 3, 1000).status, 'failed');
  for (const error of [{ status: 500 }, { status: 503 }, { status: 408 }, { kind: 'network' }, { kind: 'parse', ambiguous: true }]) {
    assert.equal(submissionFailure(error).status, 'uncertain');
  }
  assert.equal(submissionFailure({ status: 401 }).status, 'failed');
  assert.equal(submissionFailure({ kind: 'validation' }).status, 'failed');
});

test('image completion persists outputs and never persists the runtime API key', async () => {
  let options;
  const h = await harness({ api: { submitImage: async (input, supplied) => {
    options = supplied;
    return { status: 'succeeded', outputs: [{ base64: 'aW1hZ2U=', mimeType: 'image/png' }] };
  } } });
  const job = await h.queue.add({ type: 'image', input: { prompt: 'hello', size: '1K' }, baseUrl: BASE });
  await h.run();
  assert.equal(options.apiKey, 'session-secret-test');
  assert.equal(options.baseUrl, BASE);
  const saved = await h.storage.get(QUEUE_STORAGE_KEY);
  assert.equal(saved.jobs[0].status, 'succeeded');
  assert.equal(saved.jobs[0].id, job.id);
  assert.deepEqual(saved.jobs[0].outputs, [{ base64: 'aW1hZ2U=', mimeType: 'image/png' }]);
  assert.ok(!JSON.stringify(saved).includes('session-secret-test'));
  h.queue.dispose();
});

test('queue snapshot getters cannot mutate persisted jobs', async () => {
  const h = await harness();
  await h.queue.add({ type: 'image', input: { prompt: 'immutable' } });
  const jobs = h.queue.list();
  jobs[0].input.prompt = 'tampered';
  assert.equal(h.queue.list()[0].input.prompt, 'immutable');
  h.queue.dispose();
});

test('a host change blocks submission and the saved job host remains unchanged', async () => {
  let calls = 0;
  const h = await harness({ api: { submitImage: async () => { calls += 1; return { status: 'succeeded', outputs: [] }; } } });
  await h.queue.add({ type: 'image', input: { prompt: 'host test' } });
  h.settings.baseUrl = 'https://other.example.com';
  await h.run();
  assert.equal(calls, 0);
  assert.equal(h.queue.list()[0].waitReason, 'host_mismatch');
  assert.equal(h.queue.list()[0].baseUrl, BASE);
  h.settings.baseUrl = BASE;
  await h.queue.refresh();
  await h.run();
  assert.equal(calls, 1);
  h.queue.dispose();
});

test('missing key waits and settings refresh resumes without saving key', async () => {
  const h = await harness({ settings: { baseUrl: BASE, apiKey: '' } });
  await h.queue.add({ type: 'image', input: { prompt: 'key test' } });
  await h.run();
  assert.equal(h.queue.list()[0].status, 'waiting');
  assert.equal(h.queue.list()[0].waitReason, 'missing_key');
  h.settings.apiKey = 'new-session-secret';
  await h.queue.refresh();
  await h.run();
  assert.equal(h.queue.list()[0].status, 'succeeded');
  assert.ok(!JSON.stringify(await h.storage.get(QUEUE_STORAGE_KEY)).includes('new-session-secret'));
  h.queue.dispose();
});

test('queue enforces 20 pending jobs but completed history does not consume slots', async () => {
  const h = await harness();
  for (let i = 0; i < QUEUE_LIMIT; i++) await h.queue.add({ type: 'image', input: { prompt: `job ${i}` } });
  await assert.rejects(h.queue.add({ type: 'video', input: { prompt: 'too many' } }), /最多 20/);
  await h.queue.cancel(h.queue.list()[0].id);
  await h.queue.add({ type: 'video', input: { prompt: 'available again' } });
  assert.equal(h.queue.list().length, QUEUE_LIMIT + 1);
  h.queue.dispose();
});

test('persisted 3K rate limit holds and Refresh cannot bypass it', async () => {
  const h = await harness({ state: { version: 1, jobs: [], rateReservations: [{ bucket: 'image:3K', baseUrl: BASE, at: 99_000 }] } });
  const job = await h.queue.add({ type: 'image', input: { prompt: 'large', size: '3K' } });
  await h.run();
  assert.equal(h.queue.list()[0].status, 'waiting');
  assert.equal(h.queue.list()[0].nextRunAt, 159_000);
  await h.queue.refresh(job.id);
  assert.equal(h.queue.list()[0].nextRunAt, 159_000);
  h.advance(59_000);
  await h.run();
  assert.equal(h.queue.list()[0].status, 'succeeded');
  h.queue.dispose();
});

test('ambiguous POST failures do not replay on refresh or later ticks', async () => {
  let calls = 0;
  const h = await harness({ api: { submitImage: async () => { calls += 1; throw Object.assign(new Error('network lost'), { kind: 'network', ambiguous: true }); } } });
  const job = await h.queue.add({ type: 'image', input: { prompt: 'one only' } });
  await h.run();
  assert.equal(h.queue.list()[0].status, 'uncertain');
  await h.queue.refresh(job.id);
  h.advance(600_000);
  await h.run();
  assert.equal(calls, 1);
  h.queue.dispose();
});

test('429 resubmission is bounded, delayed, and survives recovery safely', async () => {
  let calls = 0;
  const h = await harness({ maxSubmitRetries: 1, api: { submitImage: async () => {
    calls += 1;
    throw Object.assign(new Error('rate limited'), { status: 429, retryable: true, retryAfterMs: 95_000 });
  } } });
  await h.queue.add({ type: 'image', input: { prompt: '429' } });
  await h.run();
  assert.equal(h.queue.list()[0].status, 'retry');
  assert.equal(h.queue.list()[0].nextRunAt, 195_000);
  await h.queue._recover();
  assert.equal(h.queue.list()[0].status, 'retry');
  h.advance(94_999);
  await h.run();
  assert.equal(calls, 1);
  h.advance(1);
  await h.run();
  assert.equal(calls, 2);
  assert.equal(h.queue.list()[0].status, 'failed');
  h.queue.dispose();
});

test('video resumes only GET with 30-second minimum even on manual refresh', async () => {
  let posts = 0, gets = 0;
  const h = await harness({ api: {
    submitVideo: async () => { posts += 1; return { status: 'processing', remoteId: 'remote-1' }; },
    pollVideo: async (id, options) => { gets += 1; assert.equal(id, 'remote-1'); assert.equal(options.model, 'agnes-video-2.5'); return { status: gets === 2 ? 'succeeded' : 'processing', progress: 40, outputs: [] }; },
  } });
  const job = await h.queue.add({ type: 'video', input: { prompt: 'video', model: 'agnes-video-2.5' } });
  await h.run();
  assert.equal(posts, 1);
  assert.equal(gets, 0);
  await h.queue.refresh(job.id);
  await h.run();
  assert.equal(gets, 0, 'manual refresh must not skip the first 30-second wait');
  h.advance(MIN_POLL_INTERVAL);
  await h.run();
  assert.equal(gets, 1);
  await h.queue.refresh(job.id);
  await h.run();
  assert.equal(gets, 1);
  h.advance(MIN_POLL_INTERVAL);
  await h.run();
  assert.equal(gets, 2);
  assert.equal(posts, 1);
  assert.equal(h.queue.list()[0].status, 'succeeded');
  h.queue.dispose();
});

test('bounded GET failures pause locally and explicit refresh safely resumes GET', async () => {
  let posts = 0, gets = 0;
  const h = await harness({ maxPollRetries: 1, state: { version: 1, jobs: [record({ type: 'video', remoteId: 'known-video', status: 'processing' })], rateReservations: [] }, api: {
    submitVideo: async () => { posts += 1; },
    pollVideo: async () => { gets += 1; throw Object.assign(new Error('temporarily offline'), { retryable: true }); },
  } });
  await h.run();
  assert.equal(h.queue.list()[0].status, 'retry');
  h.advance(MIN_POLL_INTERVAL);
  await h.run();
  assert.equal(h.queue.list()[0].status, 'failed');
  assert.match(h.queue.list()[0].error, /远端任务可能仍在运行/);
  assert.equal(posts, 0);
  await h.queue.refresh('job-1');
  h.advance(MIN_POLL_INTERVAL);
  await h.run();
  assert.equal(gets, 3);
  assert.equal(posts, 0);
  h.queue.dispose();
});

test('cancel during POST aborts local request and explicitly warns remote may continue', async () => {
  let signal;
  const h = await harness({ api: { submitVideo: async (input, options) => {
    signal = options.signal;
    return new Promise((resolve, reject) => options.signal.addEventListener('abort', () => reject(Object.assign(new Error('aborted'), { ambiguous: true })), { once: true }));
  } } });
  const job = await h.queue.add({ type: 'video', input: { prompt: 'cancel' } });
  await h.queue.tick();
  while (!signal) await flush();
  const done = [...h.queue.inflight.values()].map(request => request.promise);
  await h.queue.cancel(job.id);
  await Promise.all(done);
  assert.equal(signal.aborted, true);
  assert.equal(h.queue.list()[0].status, 'cancelled');
  assert.match(h.queue.list()[0].error, /不代表远端已取消/);
  h.queue.dispose();
});

test('settings refresh does not restart cancelled known-video polling', async () => {
  const h = await harness({ state: { version: 1, jobs: [record({ type: 'video', remoteId: 'known', status: 'cancelled' })], rateReservations: [] } });
  await h.queue.refresh();
  assert.equal(h.queue.list()[0].status, 'cancelled');
  await h.queue.refresh('job-1');
  assert.equal(h.queue.list()[0].status, 'processing');
  h.queue.dispose();
});

test('late successful creation after Cancel saves remote ID without restarting polling', async () => {
  let finish;
  const h = await harness({ api: { submitVideo: async () => new Promise(resolve => { finish = resolve; }) } });
  const job = await h.queue.add({ type: 'video', input: { prompt: 'late response' } });
  await h.queue.tick();
  while (!finish) await flush();
  const done = [...h.queue.inflight.values()].map(request => request.promise);
  await h.queue.cancel(job.id);
  finish({ status: 'processing', remoteId: 'late-id' });
  await Promise.all(done);
  assert.equal(h.queue.list()[0].status, 'cancelled');
  assert.equal(h.queue.list()[0].remoteId, 'late-id');
  h.queue.dispose();
});

test('remove refuses active jobs and removes cancelled records', async () => {
  const h = await harness();
  const job = await h.queue.add({ type: 'image', input: { prompt: 'delete' } });
  await assert.rejects(h.queue.remove(job.id), /先取消/);
  await h.queue.cancel(job.id);
  await h.queue.remove(job.id);
  assert.equal(h.queue.list().length, 0);
  h.queue.dispose();
});

test('API keys reflected in error messages are redacted before persistence', async () => {
  const h = await harness({ api: { submitImage: async () => { throw Object.assign(new Error('Invalid session-secret-test'), { status: 401 }); } } });
  await h.queue.add({ type: 'image', input: { prompt: 'redaction' } });
  await h.run();
  assert.match(h.queue.list()[0].error, /已隐藏凭据/);
  assert.ok(!JSON.stringify(await h.storage.get(QUEUE_STORAGE_KEY)).includes('session-secret-test'));
  h.queue.dispose();
});

function fakeLocks() {
  const tails = new Map();
  return {
    request(name, options, callback) {
      if (typeof options === 'function') { callback = options; options = {}; }
      const previous = tails.get(name) || Promise.resolve();
      const result = previous.catch(() => {}).then(() => {
        if (options.signal?.aborted) throw Object.assign(new Error('aborted'), { name: 'AbortError' });
        return callback({ name });
      });
      tails.set(name, result.catch(() => {}));
      return result;
    },
  };
}

test('Web Locks permits one queue executor and hands leadership to the next tab', async () => {
  const storage = createStorage({ indexedDB: null });
  const locks = fakeLocks();
  const settings = { baseUrl: BASE, apiKey: 'session-only' };
  const options = { storage, locks, getSettings: () => settings, api: fakeApi(), channelFactory: null, setTimer: () => 1, clearTimer: () => {} };
  const first = createQueue(options);
  const second = createQueue(options);
  await first.init();
  await second.init();
  await flush();
  assert.equal(first.isLeader, true);
  assert.equal(second.isLeader, false);
  first.dispose();
  await flush();
  await flush();
  assert.equal(second.isLeader, true);
  second.dispose();
});

test('leader recovery turns orphan sending job uncertain before any API dispatch', async () => {
  let calls = 0;
  const h = await harness({ state: { version: 1, jobs: [record({ status: 'sending' })], rateReservations: [] }, api: { submitImage: async () => { calls += 1; } } });
  await h.queue._recover();
  await h.run();
  assert.equal(calls, 0);
  assert.equal(h.queue.list()[0].status, 'uncertain');
  h.queue.dispose();
});

test('orphan sending job is never dispatched even without a full reload', async () => {
  let calls = 0;
  const h = await harness({ state: { version: 1, jobs: [record({ status: 'sending' })], rateReservations: [] }, api: { submitImage: async () => { calls += 1; } } });
  await h.run();
  assert.equal(calls, 0);
  assert.equal(h.queue.list()[0].status, 'uncertain');
  h.queue.dispose();
});

test('poll Retry-After cannot be shortened by manual refresh', async () => {
  const h = await harness({ state: { version: 1, jobs: [record({ type: 'video', remoteId: 'known-video', status: 'processing' })], rateReservations: [] }, api: {
    pollVideo: async () => { throw Object.assign(new Error('limited'), { status: 429, retryable: true, retryAfterMs: 120_000 }); },
  } });
  await h.run();
  assert.equal(h.queue.list()[0].nextRunAt, 220_000);
  await h.queue.refresh('job-1');
  assert.equal(h.queue.list()[0].nextRunAt, 220_000);
  h.queue.dispose();
});

test('default timers preserve the browser Window receiver instead of using the queue as this', () => {
  const originalSet = globalThis.setTimeout;
  const originalClear = globalThis.clearTimeout;
  let scheduled = 0, cleared = 0;
  try {
    globalThis.setTimeout = function (callback, delay) {
      assert.equal(this, globalThis, 'setTimeout must retain its host receiver');
      assert.equal(typeof callback, 'function');
      assert.equal(delay, 25);
      scheduled += 1;
      return 123;
    };
    globalThis.clearTimeout = function (timer) {
      assert.equal(this, globalThis, 'clearTimeout must retain its host receiver');
      assert.equal(timer, 123);
      cleared += 1;
    };
    const queue = createQueue({ autoStart: false, locks: null, channelFactory: null });
    queue.isLeader = true;
    queue._wake(25);
    queue._wake(25);
    queue.dispose();
    assert.equal(scheduled, 2);
    assert.equal(cleared, 2);
  } finally {
    globalThis.setTimeout = originalSet;
    globalThis.clearTimeout = originalClear;
  }
});
