/** Credential-free key/value persistence. IndexedDB failure is visible, never silent. */
const SECRET_FIELD = /^(?:(?:x[_-])?api[_-]?key|(?:proxy[_-])?authorization|access[_-]?token|refresh[_-]?token|password|client[_-]?secret)$/i;
const REDACTED = '[已隐藏凭据]';

function embeddedJson(value) {
  if (typeof value !== 'string' || !/^[\s]*[\[{]/.test(value)) return null;
  try {
    const parsed = JSON.parse(value);
    return parsed && typeof parsed === 'object' ? parsed : null;
  } catch { return null; }
}

export function assertNoCredentials(value, path = '') {
  const parsed = embeddedJson(value);
  if (parsed) return assertNoCredentials(parsed, path);
  if (!value || typeof value !== 'object') return;
  for (const [key, child] of Object.entries(value)) {
    if (SECRET_FIELD.test(key) && child !== undefined && child !== null && child !== '' && child !== REDACTED) {
      throw new Error(`凭据不能写入浏览器存储（${path ? `${path}.` : ''}${key}）`);
    }
    assertNoCredentials(child, path ? `${path}.${key}` : key);
  }
}

/** Reject a configured secret pasted into a job, without rejecting signed media URLs. */
export function assertNoKnownSecrets(value, secrets = []) {
  const known = secrets.filter(secret => typeof secret === 'string' && secret.length);
  const contains = item => {
    if (typeof item === 'string') return known.some(secret => item.includes(secret)) || (embeddedJson(item) && contains(embeddedJson(item)));
    if (item && typeof item === 'object') return Object.entries(item).some(([key, child]) => contains(key) || contains(child));
    return false;
  };
  if (contains(value)) throw new Error('内容包含当前会话的 API Key，请移除密钥后再保存或提交。');
}

/** Safe for local records and exports; URL query parameters are not guessed to be keys. */
export function sanitizeForStorage(value, secrets = []) {
  const known = secrets.filter(secret => typeof secret === 'string' && secret.length).sort((a, b) => b.length - a.length);
  const redact = text => known.reduce((result, secret) => result.split(secret).join(REDACTED), text);
  const visit = item => {
    if (typeof item === 'string') {
      const parsed = embeddedJson(item);
      return parsed ? JSON.stringify(visit(parsed)) : redact(item);
    }
    if (Array.isArray(item)) return item.map(visit);
    if (item && typeof item === 'object') return Object.fromEntries(Object.entries(item).map(([key, child]) => [redact(key), SECRET_FIELD.test(key) && child ? REDACTED : visit(child)]));
    return item;
  };
  return visit(value);
}

function copy(value) {
  if (value === undefined) return undefined;
  return typeof structuredClone === 'function' ? structuredClone(value) : JSON.parse(JSON.stringify(value));
}

export class BrowserStorage {
  constructor({ name = 'agnes-studio-web', indexedDB = globalThis.indexedDB, onWarning = () => {}, getSecrets = () => [] } = {}) {
    this.name = name;
    this.indexedDB = indexedDB;
    this.onWarning = onWarning;
    this.warning = '';
    this.persistent = false;
    this.memory = new Map();
    this.db = null;
    this.ready = null;
    this.getSecrets = getSecrets;
    this.knownSecrets = new Set();
  }

  async init() {
    if (this.ready) return this.ready;
    this.ready = (async () => {
      if (!this.indexedDB) {
        this._fallback();
        return this;
      }
      try {
        this.db = await new Promise((resolve, reject) => {
          const request = this.indexedDB.open(this.name, 1);
          request.onupgradeneeded = () => {
            if (!request.result.objectStoreNames.contains('kv')) request.result.createObjectStore('kv');
          };
          request.onsuccess = () => resolve(request.result);
          request.onerror = () => reject(request.error);
          request.onblocked = () => reject(new Error('IndexedDB upgrade blocked'));
        });
        this.db.onversionchange = () => this._fallback();
        this.persistent = true;
      } catch {
        this._fallback();
      }
      return this;
    })();
    return this.ready;
  }

  _fallback() {
    this.db?.close();
    this.db = null;
    this.persistent = false;
    this.warning = '浏览器持久存储不可用，当前仅保存在内存中；关闭或刷新页面会丢失记录。';
    this.onWarning(this.warning);
  }

  async _request(mode, operation) {
    await this.init();
    if (!this.db) return { available: false };
    try {
      const value = await new Promise((resolve, reject) => {
        const tx = this.db.transaction('kv', mode);
        const request = operation(tx.objectStore('kv'));
        let result;
        request.onsuccess = () => { result = request.result; };
        request.onerror = () => reject(request.error);
        tx.oncomplete = () => resolve(result);
        tx.onerror = () => reject(tx.error);
        tx.onabort = () => reject(tx.error || new Error('Storage transaction aborted'));
      });
      return { available: true, value };
    } catch {
      this._fallback();
      return { available: false };
    }
  }

  async get(key, fallback = undefined) {
    const result = await this._request('readonly', store => store.get(key));
    if (result.available) {
      if (result.value === undefined) this.memory.delete(key);
      else this.memory.set(key, copy(result.value));
    }
    return this.sanitize(copy(this.memory.has(key) ? this.memory.get(key) : fallback));
  }

  sanitize(value) {
    for (const secret of this.getSecrets() || []) if (typeof secret === 'string' && secret) this.knownSecrets.add(secret);
    return sanitizeForStorage(value, [...this.knownSecrets]);
  }

  async set(key, value) {
    if (SECRET_FIELD.test(String(key))) throw new Error('凭据不能写入浏览器存储');
    assertNoCredentials(value);
    const safe = copy(this.sanitize(value));
    this.memory.set(key, safe);
    await this._request('readwrite', store => store.put(safe, key));
    return copy(safe);
  }

  async delete(key) {
    this.memory.delete(key);
    await this._request('readwrite', store => store.delete(key));
  }

  async clear() {
    this.memory.clear();
    await this._request('readwrite', store => store.clear());
  }

  close() { this.db?.close(); this.db = null; }
}

export function createStorage(options) { return new BrowserStorage(options); }
