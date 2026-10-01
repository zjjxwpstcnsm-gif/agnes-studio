import * as defaultApi from './api.js';
import { createStorage, assertNoCredentials, assertNoKnownSecrets } from './storage.js';

export const QUEUE_STORAGE_KEY = 'agnes.queue.v1';
export const MIN_POLL_INTERVAL = 30_000;
export const QUEUE_LIMIT = 20;
export const FREE_LIMITS = Object.freeze({ 'image:1K': 20, 'image:2K': 10, 'image:3K': 1, 'image:4K': 1, video: 1 });
export const TERMINAL_STATUSES = new Set(['succeeded', 'failed', 'cancelled', 'uncertain']);
const EXECUTION_LOCK = 'agnes-studio-web-queue-executor-v1';
const MUTATION_LOCK = 'agnes-studio-web-queue-state-v1';
const UNCERTAIN_MESSAGE = '请求可能已被服务端接受并计费，结果尚未确认。为避免重复扣费，不会自动重新提交；请先检查服务端记录。';
const clone = value => JSON.parse(JSON.stringify(value));
const normalizeBaseUrl = value => defaultApi.normalizeBaseUrl(value);
const active = job => !TERMINAL_STATUSES.has(job.status);
const emptyState = () => ({ version: 1, jobs: [], rateReservations: [] });

export function rateLimitBucket(job) {
  return job.type === 'video' ? 'video' : `image:${String(job.input?.size || '2K').toUpperCase()}`;
}

/** Milliseconds until another submission fits the persisted sliding-minute window. */
export function rateLimitDelay(reservations, bucket, now, limit = FREE_LIMITS[bucket] || 1, baseUrl) {
  const times = reservations
    .filter(entry => entry.bucket === bucket && (!baseUrl || entry.baseUrl === baseUrl) && entry.at > now - 60_000)
    .map(entry => entry.at).sort((a, b) => a - b);
  return times.length < limit ? 0 : Math.max(0, times[times.length - limit] + 60_000 - now);
}

/** Recovery never repeats an unacknowledged POST. A known video ID only needs GET. */
export function recoverJob(job, now = Date.now()) {
  const recovered = clone(job);
  if (!active(recovered)) return recovered;
  if (recovered.remoteId && recovered.type === 'video') {
    recovered.status = 'processing';
    recovered.waitReason = '';
    recovered.nextRunAt = Math.max(Number(recovered.nextRunAt) || 0, (Number(recovered.lastPollAt) || now) + MIN_POLL_INTERVAL);
  } else if (recovered.status === 'sending' || recovered.status === 'processing' || (recovered.status === 'retry' && recovered.retryReason !== 'rate_limit')) {
    recovered.status = 'uncertain';
    recovered.error = UNCERTAIN_MESSAGE;
    recovered.nextRunAt = null;
  }
  delete recovered.attemptId;
  return recovered;
}

export function recoverJobs(jobs, now) { return jobs.map(job => recoverJob(job, now)); }

export function submissionFailure(error, attempt = 1, maxRetries = 3, now = Date.now()) {
  const status = Number(error?.status ?? error?.httpStatus) || 0;
  // Only an explicit rejection proves that resubmitting cannot duplicate work.
  if (status === 429 && error?.retryable !== false && attempt <= maxRetries) {
    return { status: 'retry', retryReason: 'rate_limit', nextRunAt: now + Math.max(MIN_POLL_INTERVAL, Number(error.retryAfterMs) || MIN_POLL_INTERVAL) };
  }
  if (error?.ambiguous || status >= 500 || status === 408 || (!status && !['validation', 'auth', 'configuration'].includes(error?.kind))) {
    return { status: 'uncertain', error: UNCERTAIN_MESSAGE, nextRunAt: null };
  }
  return { status: 'failed', nextRunAt: null };
}

function errorText(error, apiKey = '') {
  let message = String(error?.message || error || '请求失败');
  if (apiKey) message = message.split(apiKey).join('[已隐藏凭据]');
  return message.slice(0, 2000);
}

function randomId() {
  return globalThis.crypto?.randomUUID?.() || `job-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

/**
 * Durable, credential-free browser queue. Never invoke a paid API from tests.
 * onChange(jobs, { warning, isLeader }); subscribe has the same signature.
 * add({type, input, baseUrl}); getSettings returns the current baseUrl; getApiKey
 * supplies a session-only key (getSettings().apiKey is also accepted).
 */
export class GenerationQueue {
  constructor({ storage = createStorage(), api = defaultApi, getSettings = () => ({}), getApiKey,
    onChange, now = () => Date.now(), locks = globalThis.navigator?.locks,
    channelFactory = typeof window !== 'undefined' && typeof BroadcastChannel !== 'undefined' ? name => new BroadcastChannel(name) : null,
    setTimer = (...args) => globalThis.setTimeout(...args), clearTimer = (...args) => globalThis.clearTimeout(...args), maxPollRetries = 5, maxSubmitRetries = 3, maxConcurrent = 2,
    autoStart = true } = {}) {
    this.storage = storage;
    this.api = api;
    this.getSettings = getSettings;
    this.getApiKey = getApiKey;
    this.now = now;
    this.locks = locks;
    this.channelFactory = channelFactory;
    this.setTimer = setTimer;
    this.clearTimer = clearTimer;
    this.maxPollRetries = maxPollRetries;
    this.maxSubmitRetries = maxSubmitRetries;
    this.maxConcurrent = maxConcurrent;
    this.autoStart = autoStart;
    this.listeners = new Set(onChange ? [onChange] : []);
    this.state = emptyState();
    this.inflight = new Map();
    this.isLeader = false;
    this.disposed = false;
    this.initialized = false;
    this.serial = Promise.resolve();
    this.timer = null;
    this.channel = null;
    this.lockAbort = new AbortController();
  }

  get warning() {
    return [this.storage.warning, this.runtimeWarning, !this.locks && '当前浏览器不支持跨标签页队列互斥；请只在一个标签页运行生成任务。'].filter(Boolean).join(' ');
  }

  list() { return clone(this.state.jobs); }

  subscribe(listener) {
    this.listeners.add(listener);
    listener(this.list(), { warning: this.warning, isLeader: this.isLeader });
    return () => this.listeners.delete(listener);
  }

  _emit() {
    const jobs = this.list();
    const metadata = { warning: this.warning, isLeader: this.isLeader };
    for (const listener of this.listeners) {
      try { listener(jobs, metadata); } catch { /* A view must not stop queue persistence. */ }
    }
  }

  async init() {
    if (this.initialized) return this;
    await this.storage.init();
    await this._load();
    this.initialized = true;
    if (this.channelFactory) {
      this.channel = this.channelFactory('agnes-studio-queue-updates-v1');
      this.channel.onmessage = () => {
        if (this.disposed) return;
        this._load().then(() => { this._cancelStaleRequests(); this._wake(); }).catch(() => {});
      };
    }
    if (this.autoStart) this.start();
    this._emit();
    return this;
  }

  start() {
    if (this.started || this.disposed) return;
    this.started = true;
    if (this.locks?.request) {
      this.lockPromise = this.locks.request(EXECUTION_LOCK, { mode: 'exclusive', signal: this.lockAbort.signal }, async () => {
        if (this.disposed) return;
        this.isLeader = true;
        await this._recover();
        this._wake();
        await new Promise(resolve => { this.releaseLock = resolve; if (this.disposed) resolve(); });
        this.isLeader = false;
      }).catch(error => {
        if (!this.disposed && error?.name !== 'AbortError') {
          this.runtimeWarning = '无法取得生成队列锁，已停止自动执行。';
          this._emit();
        }
      });
    } else {
      this.isLeader = true;
      this._recover().then(() => this._wake());
    }
  }

  async _load() {
    const state = await this.storage.get(QUEUE_STORAGE_KEY, emptyState());
    this.state = state && Array.isArray(state.jobs) ? { ...emptyState(), ...state } : emptyState();
    this._emit();
  }

  async _mutate(callback) {
    const perform = async () => {
      await this._load();
      const result = await callback(this.state);
      this.state = await this.storage.set(QUEUE_STORAGE_KEY, this.state);
      this._emit();
      this.channel?.postMessage({ changed: true });
      return result;
    };
    // A second lock serializes cross-tab writes, including follower Cancel/Add.
    const operation = () => this.locks?.request ? this.locks.request(MUTATION_LOCK, perform) : perform();
    const result = this.serial.then(operation, operation);
    this.serial = result.catch(() => {});
    return result;
  }

  async _recover() {
    await this._mutate(state => {
      state.jobs = recoverJobs(state.jobs, this.now());
      state.rateReservations = state.rateReservations.filter(entry => entry.at > this.now() - 60_000);
    });
  }

  async add(spec, optionalInput) {
    if (!this.initialized) await this.init();
    if (typeof spec === 'string') spec = { type: spec, input: optionalInput };
    if (!['image', 'video'].includes(spec?.type)) throw new Error('任务类型必须为 image 或 video');
    const input = clone(spec.input || {});
    assertNoCredentials(input);
    const settings = this.getSettings() || {};
    assertNoKnownSecrets(input, [this.getApiKey ? this.getApiKey() : settings.apiKey]);
    const baseUrl = normalizeBaseUrl(spec.baseUrl || settings.baseUrl || this.api.DEFAULT_BASE_URL);
    const parsed = new URL(baseUrl);
    if (parsed.protocol !== 'https:' || parsed.username || parsed.password || parsed.search || parsed.hash) throw new Error('API 地址必须为不含凭据的 HTTPS 地址');
    // Validate before enqueueing; the original, credential-free form is kept.
    if (spec.type === 'image') this.api.buildImagePayload?.(input);
    else this.api.buildVideoPayload?.(input);
    const job = {
      id: randomId(), type: spec.type, baseUrl, input, status: 'queued',
      createdAt: this.now(), updatedAt: this.now(), nextRunAt: this.now(),
      remoteId: null, outputs: [], progress: null, error: '', waitReason: '',
      submitAttempts: 0, pollFailures: 0, lastPollAt: null,
    };
    await this._mutate(state => {
      if (state.jobs.filter(active).length >= QUEUE_LIMIT) throw new Error(`待处理队列最多 ${QUEUE_LIMIT} 个任务`);
      state.jobs.push(job);
    });
    this._wake();
    return clone(job);
  }

  async cancel(id) {
    await this._mutate(state => {
      const job = state.jobs.find(item => item.id === id);
      if (!job || TERMINAL_STATUSES.has(job.status)) return;
      job.cancelledWhileSending = job.status === 'sending';
      job.status = 'cancelled';
      job.updatedAt = this.now();
      job.nextRunAt = null;
      job.error = job.remoteId || job.cancelledWhileSending
        ? '已停止本地等待；服务端任务可能仍在运行并计费，不代表远端已取消。'
        : '已取消尚未提交的本地任务。';
    });
    this.inflight.get(id)?.controller.abort();
    this._wake();
  }

  async remove(id) {
    await this._mutate(state => {
      const job = state.jobs.find(item => item.id === id);
      if (job && active(job)) throw new Error('请先取消任务，再删除记录');
      state.jobs = state.jobs.filter(item => item.id !== id);
    });
  }

  /** Safe manual refresh: GET-only for known remote IDs; never repeats a POST. */
  async refresh(id) {
    await this._mutate(state => {
      for (const job of state.jobs) {
        if (id && job.id !== id) continue;
        // Settings changes resume eligible work, not explicitly stopped jobs.
        if (!id && !active(job)) continue;
        if (job.remoteId && job.type === 'video' && job.status !== 'succeeded') {
          job.status = 'processing';
          job.pollFailures = 0;
          job.error = '';
          job.waitReason = '';
          job.nextRunAt = Math.max(this.now(), job.nextRunAt || 0, Math.max(job.lastPollAt || 0, job.remoteAcceptedAt || 0) + MIN_POLL_INTERVAL);
        } else if (['queued', 'waiting', 'retry'].includes(job.status)) {
          // Rate-limit waits remain in force even after the Refresh button.
          if (job.retryReason !== 'rate_limit' && job.waitReason !== 'rate_limit') job.nextRunAt = this.now();
        }
      }
    });
    this._wake();
    return this.list();
  }

  _cancelStaleRequests() {
    for (const [id, request] of this.inflight) {
      const job = this.state.jobs.find(item => item.id === id);
      if (!job || job.status === 'cancelled') request.controller.abort();
    }
  }

  _wake(delay = 0) {
    if (this.disposed || !this.isLeader) return;
    if (this.timer !== null) this.clearTimer(this.timer);
    this.timer = this.setTimer(() => {
      this.timer = null;
      this.tick().catch(error => {
        this.runtimeWarning = errorText(error);
        this._emit();
        this._wake(1000);
      });
    }, Math.max(0, delay));
    this.timer?.unref?.();
  }

  async tick() {
    if (!this.isLeader || this.disposed || this.ticking) return;
    this.ticking = true;
    try {
      await this._load();
      this._cancelStaleRequests();
      const ready = this.state.jobs.filter(job => active(job) && !this.inflight.has(job.id) && Number(job.nextRunAt || 0) <= this.now());
      for (const job of ready) {
        if (this.inflight.size >= this.maxConcurrent) break;
        const request = { controller: new AbortController(), promise: null };
        this.inflight.set(job.id, request);
        request.promise = this._execute(job.id, request.controller).finally(() => {
          this.inflight.delete(job.id);
          this._wake();
        });
        // Errors are surfaced by _execute, preventing unhandled rejections.
        request.promise.catch(() => {});
      }
    } finally {
      this.ticking = false;
      const waiting = this.state.jobs.filter(job => active(job) && !this.inflight.has(job.id));
      if (waiting.length) this._wake(Math.max(250, Math.min(1000, Math.min(...waiting.map(job => (job.nextRunAt || 0) - this.now())))));
    }
  }

  _credentials(job) {
    const settings = this.getSettings() || {};
    if (normalizeBaseUrl(settings.baseUrl || this.api.DEFAULT_BASE_URL) !== job.baseUrl) return { reason: 'host_mismatch', message: '此任务属于另一 API 地址。请切换回任务原地址，再填写对应 API Key。' };
    const apiKey = this.getApiKey ? this.getApiKey() : settings.apiKey;
    if (!String(apiKey || '').trim()) return { reason: 'missing_key', message: '请在当前页面填写 API Key 后继续。密钥不会保存在队列中。' };
    return { apiKey: String(apiKey).trim() };
  }

  async _execute(id, controller) {
    let job, credentials;
    const attemptId = randomId();
    try {
      await this._mutate(state => {
        const current = state.jobs.find(item => item.id === id);
        if (!current || !active(current) || controller.signal.aborted || current.nextRunAt > this.now()) return;
        if (!current.remoteId && ['sending', 'processing'].includes(current.status)) {
          Object.assign(current, { status: 'uncertain', error: UNCERTAIN_MESSAGE, nextRunAt: null });
          return;
        }
        credentials = this._credentials(current);
        if (!credentials.apiKey) {
          Object.assign(current, { status: 'waiting', waitReason: credentials.reason, error: credentials.message, nextRunAt: this.now() + 1000 });
          return;
        }
        const polling = current.type === 'video' && current.remoteId;
        if (!polling) {
          const bucket = rateLimitBucket(current);
          const delay = rateLimitDelay(state.rateReservations, bucket, this.now(), FREE_LIMITS[bucket] || 1, current.baseUrl);
          if (delay) {
            Object.assign(current, { status: 'waiting', waitReason: 'rate_limit', error: '', nextRunAt: this.now() + delay });
            return;
          }
          state.rateReservations = state.rateReservations.filter(entry => entry.at > this.now() - 60_000);
          state.rateReservations.push({ bucket, baseUrl: current.baseUrl, at: this.now() });
          current.status = 'sending';
          current.submitAttempts += 1;
        } else {
          if (current.lastPollAt && this.now() < current.lastPollAt + MIN_POLL_INTERVAL) {
            current.nextRunAt = current.lastPollAt + MIN_POLL_INTERVAL;
            return;
          }
          current.status = 'processing';
          current.lastPollAt = this.now();
        }
        Object.assign(current, { attemptId, updatedAt: this.now(), waitReason: '', error: '' });
        job = clone(current);
      });
      if (!job || controller.signal.aborted || this.disposed) return;
      const options = { baseUrl: job.baseUrl, apiKey: credentials.apiKey, signal: controller.signal, model: job.input.model };
      const result = job.remoteId
        ? await this.api.pollVideo(job.remoteId, options)
        : await (job.type === 'image' ? this.api.submitImage(job.input, options) : this.api.submitVideo(job.input, options));
      await this._mutate(state => {
        const current = state.jobs.find(item => item.id === id);
        if (!current || current.attemptId !== attemptId) return;
        // A late response may provide the ID needed for safe recovery after Cancel.
        if (result.remoteId) {
          current.remoteId = String(result.remoteId);
          if (!job.remoteId) current.remoteAcceptedAt = this.now();
        }
        if (current.status === 'cancelled') return;
        if (controller.signal.aborted || this.disposed) return;
        current.updatedAt = this.now();
        current.pollFailures = 0;
        current.progress = result.progress ?? current.progress;
        current.outputs = result.outputs || current.outputs;
        if (result.status === 'succeeded') {
          current.status = 'succeeded';
          current.nextRunAt = null;
          current.error = '';
        } else if (result.status === 'failed') {
          current.status = 'failed';
          current.error = errorText(result.error || '服务端生成失败', credentials.apiKey);
          current.nextRunAt = null;
        } else if (current.remoteId && current.type === 'video') {
          current.status = 'processing';
          current.nextRunAt = this.now() + MIN_POLL_INTERVAL;
        } else {
          current.status = 'uncertain';
          current.error = UNCERTAIN_MESSAGE;
          current.nextRunAt = null;
        }
        delete current.attemptId;
      });
    } catch (error) {
      if (!job) {
        this.runtimeWarning = errorText(error, credentials?.apiKey);
        this._emit();
        return;
      }
      await this._mutate(state => {
        const current = state.jobs.find(item => item.id === id);
        if (!current || current.attemptId !== attemptId || current.status === 'cancelled') return;
        current.updatedAt = this.now();
        if (current.remoteId) {
          current.pollFailures += 1;
          const status = Number(error?.status ?? error?.httpStatus) || 0;
          const permanent = (status >= 400 && status < 500 && ![408, 429].includes(status)) || error?.retryable === false;
          if (permanent || current.pollFailures > this.maxPollRetries) {
            current.status = 'failed';
            current.nextRunAt = null;
            current.error = `${errorText(error, credentials.apiKey)}。本地查询已暂停，可用“查询状态”继续安全查询；远端任务可能仍在运行。`;
          } else {
            current.status = 'retry';
            current.retryReason = 'poll';
            current.error = errorText(error, credentials.apiKey);
            current.nextRunAt = this.now() + Math.max(MIN_POLL_INTERVAL, Number(error?.retryAfterMs) || 0, Math.min(300_000, MIN_POLL_INTERVAL * 2 ** (current.pollFailures - 1)));
          }
        } else {
          const decision = submissionFailure(error, current.submitAttempts, this.maxSubmitRetries, this.now());
          Object.assign(current, { error: errorText(error, credentials.apiKey), ...decision });
        }
        delete current.attemptId;
      });
    }
  }

  /** Stops this tab, not the server. Reload recovery handles an interrupted POST. */
  dispose() {
    this.disposed = true;
    if (this.timer !== null) this.clearTimer(this.timer);
    this.timer = null;
    for (const request of this.inflight.values()) request.controller.abort();
    this.channel?.close();
    this.lockAbort.abort();
    this.releaseLock?.();
    this.isLeader = false;
  }
}

export function createQueue(options) { return new GenerationQueue(options); }
