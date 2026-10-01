/** Agnes API domain layer. No storage, uploads, automatic retries, or paid test calls. */
export const DEFAULT_BASE_URL = 'https://apihub.agnes-ai.com';
export const DEFAULT_SETTINGS = Object.freeze({
  baseUrl: DEFAULT_BASE_URL,
  chat: Object.freeze({model: 'agnes-3.0-flash', systemPrompt: 'You are a helpful assistant.', temperature: 0.7, topP: 1, maxTokens: 4096, stream: true, enableThinking: true, toolsJson: '[]', toolChoice: 'auto', extraJson: '{}'}),
  image: Object.freeze({model: 'agnes-image-2.5-flash', size: '2K', ratio: '9:16', responseFormat: 'url', extraJson: '{}'}),
  video: Object.freeze({model: 'agnes-video-2.5-flash', mode: 'text', seconds: 5, size: '720P', aspectRatio: '9:16', seed: null, extraJson: '{}'}),
});
export const IMAGE_RATIOS = Object.freeze(['1:1', '3:4', '4:3', '16:9', '9:16', '2:3', '3:2', '21:9']);
export const VIDEO_RATIOS = Object.freeze(['21:9', '16:9', '4:3', '1:1', '3:4', '9:16']);
const FLASH_VIDEO_MODEL = 'agnes-video-2.5-flash';
const FULL_VIDEO_MODEL = 'agnes-video-2.5';
const isObject = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const nonempty = value => typeof value === 'string' && value.trim().length > 0;

export class ApiError extends Error {
  constructor(message, {kind = 'unknown', status = null, retryable = false, retryAfterMs = null, ambiguous = false} = {}) {
    super(message);
    this.name = 'ApiError';
    this.kind = kind;
    this.status = status;
    this.httpStatus = status;
    this.retryable = retryable;
    this.retryAfterMs = retryAfterMs;
    this.ambiguous = ambiguous;
  }
}
function requireValue(condition, message) {
  if (!condition) throw new ApiError(message, {kind: 'validation'});
}
function textField(value, label) {
  requireValue(nonempty(value), `${label}不能为空`);
  return value.trim();
}
function jsonObject(value, label = '高级 JSON') {
  if (value === undefined || value === null || value === '') return {};
  let result = value;
  if (typeof value === 'string') {
    try { result = JSON.parse(value); } catch { throw new ApiError(`${label}必须是合法的 JSON 对象`, {kind: 'validation'}); }
  }
  requireValue(isObject(result), `${label}必须是合法的 JSON 对象`);
  return {...result};
}
function jsonArray(value, label) {
  if (value === undefined || value === null || value === '') return [];
  let result = value;
  if (typeof value === 'string') {
    try { result = JSON.parse(value); } catch { throw new ApiError(`${label}必须是合法的 JSON 数组`, {kind: 'validation'}); }
  }
  requireValue(Array.isArray(result), `${label}必须是合法的 JSON 数组`);
  return result;
}
function numberIn(value, minimum, maximum, label, integer = false) {
  requireValue((typeof value === 'number' || typeof value === 'string') && String(value).trim() !== '', `${label}必须是有效数字`);
  const number = Number(value);
  requireValue(Number.isFinite(number) && number >= minimum && number <= maximum && (!integer || Number.isInteger(number)), `${label}必须在 ${minimum}–${maximum} 之间${integer ? '，且为整数' : ''}`);
  return number;
}
function omit(source, keys) {
  return Object.fromEntries(Object.entries(source).filter(([key]) => !keys.includes(key)));
}

export function normalizeBaseUrl(baseUrl = DEFAULT_BASE_URL) {
  let url;
  try { url = new URL(String(baseUrl).trim()); } catch { throw new ApiError('Base URL 必须是有效的 HTTPS 地址', {kind: 'validation'}); }
  requireValue(url.protocol === 'https:' && !url.username && !url.password && !url.search && !url.hash, 'Base URL 必须使用 HTTPS，且不能包含账号、密码、查询参数或片段');
  url.pathname = url.pathname.replace(/\/+$/, '').replace(/\/v1$/, '');
  return url.toString().replace(/\/+$/, '');
}

/** References are passed directly to Agnes; this client never uploads to a relay. */
export function validateReferenceUrl(value, label = '参考素材') {
  let url;
  try { url = new URL(String(value).trim()); } catch { throw new ApiError(`${label}必须是公开 HTTPS URL`, {kind: 'validation'}); }
  const host = url.hostname.toLowerCase();
  const ipv4 = host.split('.').map(Number);
  const privateIPv4 = ipv4.length === 4 && ipv4.every(n => Number.isInteger(n)) && (
    ipv4[0] === 0 || ipv4[0] === 10 || ipv4[0] === 127 || ipv4[0] >= 224 ||
    (ipv4[0] === 169 && ipv4[1] === 254) || (ipv4[0] === 172 && ipv4[1] >= 16 && ipv4[1] <= 31) ||
    (ipv4[0] === 192 && ipv4[1] === 168) || (ipv4[0] === 100 && ipv4[1] >= 64 && ipv4[1] <= 127));
  const ipv6 = host.replace(/^\[|\]$/g, '');
  const privateIPv6 = host.startsWith('[') && (ipv6 === '::' || ipv6 === '::1' || /^(fc|fd|fe[89ab])/i.test(ipv6) || ipv6.startsWith('::ffff:'));
  requireValue(url.protocol === 'https:' && !url.username && !url.password && host !== 'localhost' && !host.endsWith('.localhost') && !host.endsWith('.local') && !privateIPv4 && !privateIPv6, `${label}必须是公开 HTTPS URL；本地素材不会自动上传`);
  return url.href;
}
function urls(value, label) {
  const values = typeof value === 'string' ? value.split(/\r?\n/).map(s => s.trim()).filter(Boolean) : (value ?? []);
  requireValue(Array.isArray(values), `${label}必须是 URL 数组`);
  return values.map(item => validateReferenceUrl(typeof item === 'string' ? item : item?.url ?? item?.remoteUrl, label));
}
function normalizeToolCall(call) {
  requireValue(isObject(call), '工具调用必须是 JSON 对象');
  const fn = isObject(call.function) ? call.function : {name: call.name, arguments: call.arguments};
  requireValue(nonempty(call.id) && nonempty(fn.name) && typeof fn.arguments === 'string', '工具调用需要 id、函数名和字符串 arguments');
  return {id: call.id, type: 'function', function: {name: fn.name, arguments: fn.arguments}};
}
function chatMessage(message) {
  requireValue(isObject(message) && ['system', 'user', 'assistant', 'tool'].includes(message.role), '消息角色必须是 system、user、assistant 或 tool');
  const result = {role: message.role};
  if (Array.isArray(message.content)) {
    result.content = message.content.map(part => {
      requireValue(isObject(part), '消息内容格式无效');
      if (part.type === 'text') {
        requireValue(typeof part.text === 'string', '文本消息内容必须是字符串');
        return {type: 'text', text: part.text};
      }
      requireValue(part.type === 'image_url', '仅支持文本与公开 HTTPS 图片消息');
      return {type: 'image_url', image_url: {url: validateReferenceUrl(typeof part.image_url === 'string' ? part.image_url : part.image_url?.url, '聊天图片')}};
    });
  } else {
    requireValue(message.content === null || message.content === undefined || typeof message.content === 'string', '消息内容必须是字符串或内容数组');
    result.content = message.content ?? '';
  }
  if (message.attachments?.length) {
    requireValue(Array.isArray(message.attachments), '聊天图片必须是数组');
    const parts = Array.isArray(result.content) ? result.content : (result.content ? [{type: 'text', text: result.content}] : []);
    result.content = [...parts, ...urls(message.attachments, '聊天图片').map(url => ({type: 'image_url', image_url: {url}}))];
  }
  const calls = message.tool_calls ?? message.toolCalls;
  if (calls?.length) {
    requireValue(message.role === 'assistant' && Array.isArray(calls), '工具调用只能出现在 assistant 消息中');
    result.tool_calls = calls.map(normalizeToolCall);
  }
  if (message.role === 'tool') result.tool_call_id = textField(message.tool_call_id ?? message.toolCallId, 'tool_call_id');
  requireValue(Boolean(result.content?.length || result.tool_calls?.length), '消息内容不能为空');
  return result;
}
export function buildChatPayload(form = {}) {
  const settings = {...DEFAULT_SETTINGS.chat, ...form};
  const extra = jsonObject(settings.extraJson ?? settings.extra, '文本高级 JSON');
  const tools = jsonArray(form.tools ?? settings.toolsJson, '工具定义');
  requireValue(Array.isArray(settings.messages) && settings.messages.length > 0, '请至少添加一条消息');
  const messages = settings.messages.map(chatMessage);
  requireValue(typeof settings.systemPrompt === 'string', 'System Prompt 必须是字符串');
  if (settings.systemPrompt.trim()) messages.unshift({role: 'system', content: settings.systemPrompt.trim()});
  const payload = {
    ...omit(extra, ['tools', 'tool_choice']),
    model: textField(settings.model, '文本模型'), messages,
    temperature: numberIn(settings.temperature, 0, 2, 'temperature'),
    top_p: numberIn(form.top_p ?? settings.topP, 0, 1, 'top_p'),
    max_tokens: numberIn(form.max_tokens ?? settings.maxTokens, 1, 65536, 'max_tokens', true),
    stream: Boolean(settings.stream),
    chat_template_kwargs: {...jsonObject(extra.chat_template_kwargs, 'chat_template_kwargs'), enable_thinking: Boolean(settings.enableThinking)},
  };
  if (tools.length) {
    requireValue(tools.every(tool => isObject(tool) && tool.type === 'function' && isObject(tool.function) && nonempty(tool.function.name)), '工具必须为含函数名的 function 定义');
    const choice = settings.toolChoice || 'auto';
    if (isObject(choice)) payload.tool_choice = choice;
    else if (typeof choice === 'string' && choice.startsWith('function:')) payload.tool_choice = {type: 'function', function: {name: textField(choice.slice(9), '工具函数名')}};
    else {
      requireValue(['auto', 'none', 'required'].includes(choice), 'tool_choice 必须为 auto、none、required 或 function:函数名');
      payload.tool_choice = choice;
    }
    payload.tools = tools;
  }
  return payload;
}
export function buildImagePayload(form = {}) {
  const settings = {...DEFAULT_SETTINGS.image, ...form};
  const extra = jsonObject(settings.extraJson, '图片高级 JSON');
  const references = urls(form.references ?? form.images, '参考图片');
  const responseFormat = String(settings.responseFormat).toLowerCase() === 'base64' ? 'b64_json' : String(settings.responseFormat).toLowerCase();
  requireValue(['1K', '2K', '3K', '4K'].includes(settings.size), '图片尺寸必须为 1K、2K、3K 或 4K');
  requireValue(IMAGE_RATIOS.includes(settings.ratio), '不支持的图片比例');
  requireValue(['url', 'b64_json'].includes(responseFormat), '图片响应格式必须为 url 或 b64_json');
  const extraBody = {...omit(jsonObject(extra.extra_body, 'extra_body'), ['image', 'response_format']), response_format: responseFormat};
  if (references.length) extraBody.image = references;
  const payload = {...omit(extra, ['extra_body', 'response_format', 'image', 'return_base64']), model: textField(settings.model, '图片模型'), prompt: textField(settings.prompt, '图片提示词'), size: settings.size, ratio: settings.ratio, extra_body: extraBody};
  if (responseFormat === 'b64_json' && !references.length) payload.return_base64 = true;
  return payload;
}
export function buildVideoPayload(form = {}) {
  const settings = {...DEFAULT_SETTINGS.video, ...form};
  const extra = jsonObject(settings.extraJson, '视频高级 JSON');
  const model = textField(settings.model, '视频模型');
  const mode = String(settings.mode).toLowerCase();
  const seconds = numberIn(settings.seconds, 4, 12, '视频时长（秒）', true);
  const aspectRatio = form.aspect_ratio ?? settings.aspectRatio;
  const first = form.firstFrame ?? form.first_frame;
  const last = form.lastFrame ?? form.last_frame;
  const firstFrame = nonempty(first) ? validateReferenceUrl(first, '首帧') : null;
  const lastFrame = nonempty(last) ? validateReferenceUrl(last, '尾帧') : null;
  const images = urls(form.images, '参考图片');
  const audios = urls(form.audios, '参考音频');
  const inputVideos = form.videos ?? [];
  requireValue(Array.isArray(inputVideos), '参考视频必须是数组');
  const videos = inputVideos.map(item => {
    const ref = typeof item === 'string' ? {url: item} : item;
    requireValue(isObject(ref), '参考视频格式无效');
    return {url: validateReferenceUrl(ref.url, '参考视频'), start_seconds: numberIn(ref.start_seconds ?? ref.startSeconds ?? 0, 0, Number.MAX_SAFE_INTEGER, '视频起始秒数'), require_audio: Boolean(ref.require_audio ?? ref.requireAudio ?? false)};
  });
  requireValue(['text', 'keyframe', 'reference'].includes(mode), '视频模式必须为 text、keyframe 或 reference');
  requireValue(VIDEO_RATIOS.includes(aspectRatio), '不支持的视频比例');
  if (model === FLASH_VIDEO_MODEL) {
    requireValue(settings.size === '720P', 'Video 2.5 Flash 仅支持 720P');
    requireValue(images.length <= 5, 'Video 2.5 Flash 最多支持 5 张参考图');
    requireValue(audios.length <= 3, 'Video 2.5 Flash 最多支持 3 段参考音频');
    requireValue(videos.length === 0, 'Video 2.5 Flash 不支持参考视频，请切换 agnes-video-2.5');
  } else if (model === FULL_VIDEO_MODEL) requireValue(['720P', '960P', '2K'].includes(settings.size), 'Video 2.5 仅支持 720P、960P 或 2K');
  else textField(settings.size, '视频尺寸');
  const references = images.length + audios.length + videos.length;
  if (mode === 'text') requireValue(!firstFrame && !lastFrame && !references, '文本模式不应包含参考素材');
  if (mode === 'keyframe') {
    requireValue(firstFrame || lastFrame, '关键帧模式至少需要首帧或尾帧');
    requireValue(!references, '关键帧模式只能使用首帧和尾帧');
  }
  if (mode === 'reference') {
    requireValue(references > 0, '参考模式至少需要一项图片、音频或视频素材');
    requireValue(!firstFrame && !lastFrame, '参考模式不能同时使用首尾帧');
  }
  const payload = {...omit(extra, ['seed', 'first_frame', 'last_frame', 'images', 'audios', 'videos']), model, prompt: textField(settings.prompt, '视频提示词'), mode, seconds: String(seconds), size: settings.size, aspect_ratio: aspectRatio, n: 1};
  if (settings.seed !== null && settings.seed !== undefined && settings.seed !== '') payload.seed = numberIn(settings.seed, -Number.MAX_SAFE_INTEGER, Number.MAX_SAFE_INTEGER, 'seed', true);
  if (mode === 'keyframe') { if (firstFrame) payload.first_frame = firstFrame; if (lastFrame) payload.last_frame = lastFrame; }
  if (mode === 'reference') {
    if (images.length) payload.images = images;
    if (audios.length) payload.audios = audios;
    if (videos.length) payload.videos = videos;
  }
  return payload;
}

/** Safe for display/logging: known key, bearer tokens, key-shaped values and query credentials are removed. */
export function redactSecrets(value, apiKey = '') {
  let text = String(value ?? '');
  if (apiKey) text = text.split(String(apiKey)).join('[REDACTED]');
  return text.replace(/\bBearer\s+[^\s"',;}]+/gi, 'Bearer [REDACTED]')
    .replace(/\bsk-[A-Za-z0-9_-]+\b/g, '[REDACTED]')
    .replace(/((?:api[_-]?key|access[_-]?token|authorization|password)["']?\s*[:=]\s*["']?)[^\s"',;&}]+/gi, '$1[REDACTED]');
}
function errorDetail(body) {
  let value = body;
  if (typeof body === 'string') { try { value = JSON.parse(body); } catch { return body; } }
  if (isObject(value)) {
    for (const key of ['error', 'detail', 'message', 'failure_reason']) {
      const part = value[key];
      if (typeof part === 'string') return part;
      if (isObject(part)) return String(part.message ?? part.detail ?? part.code ?? '');
    }
  }
  return '';
}
export function parseRetryAfter(value, now = Date.now()) {
  if (value === undefined || value === null || String(value).trim() === '') return null;
  const text = String(value).trim();
  if (/^\d+(?:\.\d+)?$/.test(text)) return Math.max(1000, Number(text) * 1000);
  const timestamp = Date.parse(text);
  return Number.isFinite(timestamp) ? Math.max(1000, timestamp - now) : null;
}
export function classifyError(error, options = {}) {
  if (error instanceof ApiError) return error;
  const status = Number(options.status ?? error?.status ?? error?.httpStatus) || null;
  const detail = redactSecrets(errorDetail(options.body ?? error?.body) || (typeof error === 'string' ? error : error?.message) || '', options.apiKey).replace(/\s+/g, ' ').slice(0, 500);
  const retryAfterMs = parseRetryAfter(options.retryAfter ?? error?.retryAfter);
  const lower = detail.toLowerCase();
  let kind = 'unknown', message = '请求失败，请检查设置后重试', retryable = false;
  if (error?.name === 'AbortError' || options.cancelled) { kind = 'cancelled'; message = '操作已取消'; }
  else if (status === 401 || status === 403) { kind = 'authentication'; message = 'API Key 无效、无权限或账户状态异常，请检查设置'; }
  else if (status === 429 && /quota|credit|balance|exhausted/.test(lower)) { kind = 'quota_exhausted'; message = '当前账户额度已用尽，请查看 Agnes 控制台中的配额或余额'; }
  else if (status === 429) { kind = 'rate_limit'; message = '请求触发服务端限流，请稍后重试'; retryable = true; }
  else if ((status === 409 || status >= 500) && /queue|capacity|busy|overload|concurrency|队列|繁忙/.test(lower)) { kind = 'remote_queue_full'; message = 'Agnes 生成队列繁忙，请稍后重试'; retryable = true; }
  else if ((status === 400 || status === 422) && /content policy|safety|moderation|nsfw|sensitive/.test(lower)) { kind = 'content_policy'; message = '请求未通过内容安全检查，请调整提示词或参考素材'; }
  else if (status === 400 || status === 422) { kind = 'validation'; message = `参数校验失败${detail ? `：${detail}` : ''}`; }
  else if (status === 404) { kind = 'not_found'; message = '接口、模型或任务暂未找到，请检查模型与任务 ID'; }
  else if (status === 408 || error?.name === 'TimeoutError') { kind = 'timeout'; message = '请求等待超时，请检查远程任务后再决定是否重试'; retryable = true; }
  else if (status >= 500) { kind = 'server'; message = `Agnes 服务暂时异常（HTTP ${status}）`; retryable = true; }
  else if (!status && (error instanceof TypeError || /network|fetch|cors|load failed|connection/i.test(detail))) { kind = 'network'; message = '无法连接 Agnes。请检查网络与 Base URL；浏览器跨域限制（CORS）也可能阻止直连'; retryable = true; }
  else if (detail) message = detail;
  return new ApiError(redactSecrets(message, options.apiKey), {kind, status, retryable, retryAfterMs: retryAfterMs ?? (['rate_limit', 'remote_queue_full'].includes(kind) ? 60000 : null), ambiguous: Boolean(options.ambiguous)});
}
function requestOptions({baseUrl = DEFAULT_BASE_URL, apiKey, path, method = 'GET', body, signal}, accept = 'application/json') {
  requireValue(nonempty(apiKey), '请先在设置中填写 Agnes API Key');
  requireValue(!/[\r\n]/.test(apiKey), 'API Key 格式无效');
  requireValue(typeof path === 'string' && /^\/(?!\/)/.test(path) && !path.includes('\\') && !/[\r\n]/.test(path), 'API 路径必须是同源相对路径');
  const root = normalizeBaseUrl(baseUrl);
  const url = new URL(root + path);
  requireValue(url.origin === new URL(root).origin, 'API 路径必须是同源相对路径');
  return {url: url.href, init: {method: method.toUpperCase(), headers: {Authorization: `Bearer ${apiKey.trim()}`, Accept: accept, ...(body === undefined ? {} : {'Content-Type': 'application/json'})}, ...(body === undefined ? {} : {body: JSON.stringify(body)}), signal, credentials: 'omit', redirect: 'error', cache: 'no-store', referrerPolicy: 'no-referrer'}};
}
function dispatchedAmbiguity(method, status) { return method !== 'GET' && (status === null || status === 408 || status >= 500); }
async function fetchResponse(options, accept) {
  const {url, init} = requestOptions(options, accept);
  try {
    const response = await (options.fetchImpl ?? globalThis.fetch)(url, init);
    if (!response.ok) {
      const body = await response.text();
      throw classifyError(null, {status: response.status, body, apiKey: options.apiKey, retryAfter: response.headers.get('Retry-After'), ambiguous: dispatchedAmbiguity(init.method, response.status)});
    }
    return response;
  } catch (error) {
    if (error instanceof ApiError) throw error;
    throw classifyError(error, {apiKey: options.apiKey, cancelled: options.signal?.aborted, ambiguous: dispatchedAmbiguity(init.method, null)});
  }
}
async function readJson(response, options) {
  let result;
  try { result = JSON.parse(await response.text()); } catch (error) {
    if (error?.name === 'AbortError' || options.signal?.aborted) throw classifyError(error, {cancelled: true, ambiguous: options.method?.toUpperCase() !== 'GET'});
    throw new ApiError('服务器返回了无法解析的响应；提交请求可能已被接收，请先检查记录', {kind: 'invalid_response', status: response.status, ambiguous: (options.method ?? 'GET').toUpperCase() !== 'GET'});
  }
  if (!isObject(result)) throw new ApiError('服务器响应必须是 JSON 对象', {kind: 'invalid_response', status: response.status, ambiguous: (options.method ?? 'GET').toUpperCase() !== 'GET'});
  const taskFailure = ['failed', 'error', 'cancelled', 'canceled', 'rejected'].includes(String(result.status).toLowerCase());
  if (result.error && !taskFailure) throw classifyError(null, {status: Number(result.error.status ?? result.error.code) || null, body: result, apiKey: options.apiKey, ambiguous: (options.method ?? 'GET').toUpperCase() !== 'GET'});
  return result;
}
export async function requestJson(options) { return readJson(await fetchResponse(options), options); }

function outputUrl(value) {
  if (!nonempty(value)) return null;
  try { const url = new URL(value); return url.protocol === 'https:' && !url.username && !url.password ? url.href : null; } catch { return null; }
}
function findVideoUrl(value, depth = 0) {
  if (depth > 6 || value == null) return null;
  if (typeof value === 'string') return /\.mp4(?:[?#]|$)|\/video/i.test(value) ? outputUrl(value) : null;
  if (Array.isArray(value)) return value.map(v => findVideoUrl(v, depth + 1)).find(Boolean) ?? null;
  if (!isObject(value)) return null;
  for (const key of ['url', 'video_url', 'output_url', 'download_url']) { const url = outputUrl(value[key]); if (url) return url; }
  for (const key of ['metadata', 'output', 'result', 'data', 'video', 'task', 'outputs']) { const url = findVideoUrl(value[key], depth + 1); if (url) return url; }
  return null;
}
function unwrapVideo(root) {
  let task = root;
  for (let i = 0; i < 6; i++) {
    const next = ['data', 'result', 'video', 'task'].map(key => task[key]).find(value => isObject(value) && ['id', 'video_id', 'task_id', 'status', 'progress', 'metadata', 'task', 'data', 'result'].some(key => key in value));
    if (!next) break;
    task = next;
  }
  return task;
}
export function parseVideoResponse(response) {
  requireValue(isObject(response), '视频响应格式无效');
  const task = unwrapVideo(response);
  const id = [task.video_id, response.video_id, task.id, task.task_id, response.id, response.task_id].find(nonempty) ?? null;
  const url = findVideoUrl(task) ?? findVideoUrl(response);
  const status = String(task.status ?? response.status ?? (url ? 'completed' : 'queued')).toLowerCase();
  const failed = ['failed', 'error', 'cancelled', 'canceled', 'rejected'].includes(status);
  const done = failed || ['completed', 'succeeded', 'success', 'done'].includes(status);
  const numeric = Number(task.progress ?? response.progress ?? (done && !failed ? 100 : 0));
  return {id, videoId: id, status, progress: Number.isFinite(numeric) ? Math.max(0, Math.min(100, Math.trunc(numeric))) : 0, url, error: failed ? redactSecrets(errorDetail(task) || errorDetail(response) || '视频生成失败') : null, done, failed};
}
export function parseImageResponse(response) {
  requireValue(isObject(response), '图片响应格式无效');
  const data = Array.isArray(response.data) ? response.data : Array.isArray(response.data?.data) ? response.data.data : Array.isArray(response.result?.data) ? response.result.data : [];
  const outputs = data.filter(isObject).map(item => {
    const url = outputUrl(item.url);
    const base64 = nonempty(item.b64_json) && /^[A-Za-z0-9+/=\s]+$/.test(item.b64_json) ? item.b64_json : null;
    return {url, base64, mimeType: /^image\/(png|jpeg|webp)$/.test(item.mime_type ?? '') ? item.mime_type : 'image/png'};
  }).filter(item => item.url || item.base64);
  if (!outputs.length) throw new ApiError('图片接口已响应，但没有可展示的图片；请先检查记录再重试', {kind: 'invalid_response', ambiguous: true});
  return outputs;
}
export function buildVideoPollPath(id, model = FLASH_VIDEO_MODEL) {
  return `/agnesapi?${new URLSearchParams({video_id: textField(id, '视频任务 ID'), model_name: textField(model, '视频模型')})}`;
}
export async function submitImage(input, options) {
  const response = await requestJson({...options, path: '/v1/images/generations', method: 'POST', body: buildImagePayload(input)});
  return {status: 'succeeded', outputs: parseImageResponse(response), progress: 100};
}
function normalizedVideo(result, remoteId = result.id, apiKey = '') {
  return {status: result.failed ? 'failed' : result.done && result.url ? 'succeeded' : 'processing', remoteId, outputs: result.url ? [{url: result.url, mimeType: 'video/mp4'}] : [], error: result.error ? redactSecrets(result.error, apiKey) : null, progress: result.progress};
}
export async function submitVideo(input, options) {
  const result = parseVideoResponse(await requestJson({...options, path: '/v1/videos', method: 'POST', body: buildVideoPayload(input)}));
  if (!result.id && !result.failed) throw new ApiError('视频任务已响应，但缺少 video_id；请在 Agnes 控制台确认后再重试', {kind: 'invalid_response', ambiguous: true});
  return normalizedVideo(result, result.id, options?.apiKey);
}
export async function pollVideo(remoteId, options = {}) {
  const result = parseVideoResponse(await requestJson({...options, method: 'GET', path: buildVideoPollPath(remoteId, options.model)}));
  return normalizedVideo(result, remoteId, options.apiKey);
}

/** Incremental SSE framing, including split CRLF, comments, multiline data and EOF. */
export function createSseParser(onData) {
  let buffer = '', data = [], first = true, eventLength = 0;
  const dispatch = () => { if (data.length) onData(data.join('\n')); data = []; eventLength = 0; };
  const line = value => {
    if (first) { value = value.replace(/^\uFEFF/, ''); first = false; }
    if (!value) { dispatch(); return; }
    if (value.startsWith(':')) return;
    const colon = value.indexOf(':');
    const name = colon < 0 ? value : value.slice(0, colon);
    let content = colon < 0 ? '' : value.slice(colon + 1);
    if (content.startsWith(' ')) content = content.slice(1);
    if (name === 'data') {
      eventLength += content.length + 1;
      if (eventLength > 2_000_000) throw new ApiError('流式响应事件过大，已停止读取', {kind: 'invalid_response', ambiguous: true});
      data.push(content);
    }
  };
  const drain = final => {
    for (;;) {
      const index = buffer.search(/[\r\n]/);
      if (index < 0 || (!final && buffer[index] === '\r' && index === buffer.length - 1)) break;
      const count = buffer[index] === '\r' && buffer[index + 1] === '\n' ? 2 : 1;
      line(buffer.slice(0, index));
      buffer = buffer.slice(index + count);
    }
    if (buffer.length > 2_000_000) throw new ApiError('流式响应事件过大，已停止读取', {kind: 'invalid_response', ambiguous: true});
  };
  return {feed(chunk) { buffer += chunk; drain(false); }, finish() { drain(true); if (buffer) line(buffer); buffer = ''; dispatch(); }};
}
export function createThinkingSplitter() {
  let pending = '', inside = false;
  return {
    feed(chunk) {
      pending += chunk;
      let content = '', reasoning = '';
      for (;;) {
        const tag = inside ? '</think>' : '<think>';
        const index = pending.indexOf(tag);
        if (index >= 0) {
          if (inside) reasoning += pending.slice(0, index); else content += pending.slice(0, index);
          pending = pending.slice(index + tag.length);
          inside = !inside;
          continue;
        }
        let keep = 0;
        for (let length = 1; length < tag.length; length++) if (pending.endsWith(tag.slice(0, length))) keep = length;
        const ready = pending.slice(0, pending.length - keep);
        if (inside) reasoning += ready; else content += ready;
        pending = pending.slice(pending.length - keep);
        break;
      }
      return {content, reasoning};
    },
    finish() { const result = {content: inside ? '' : pending, reasoning: inside ? pending : ''}; pending = ''; return result; },
  };
}

/** onDelta emits text/reasoning/tool/usage/finish events; tool calls are displayed, never executed. */
export async function streamChat({onDelta = () => {}, payload, ...options}) {
  const request = {...options, path: '/v1/chat/completions', method: 'POST', body: payload};
  const response = await fetchResponse(request, payload?.stream === false ? 'application/json' : 'text/event-stream, application/json');
  const result = {content: '', reasoning: '', toolCalls: [], usage: null, finishReason: null};
  const splitter = createThinkingSplitter();
  const calls = new Map();
  let terminal = false, done = false;
  const emitSplit = split => {
    if (split.content) { result.content += split.content; onDelta({type: 'text', text: split.content}); }
    if (split.reasoning) { result.reasoning += split.reasoning; onDelta({type: 'reasoning', text: split.reasoning}); }
  };
  const accept = root => {
    if (!isObject(root)) throw new ApiError('流式响应格式无效', {kind: 'invalid_response', ambiguous: true});
    if (root.error) throw classifyError(null, {body: root, apiKey: options.apiKey, ambiguous: true});
    if (root.usage) { result.usage = root.usage; onDelta({type: 'usage', usage: root.usage}); }
    const choice = root.choices?.[0];
    const delta = choice?.delta ?? choice?.message;
    if (delta) {
      const content = typeof delta.content === 'string' ? delta.content : Array.isArray(delta.content) ? delta.content.filter(part => part.type === 'text').map(part => part.text ?? '').join('') : '';
      if (content) emitSplit(splitter.feed(content));
      const reasoning = [delta.reasoning_content, delta.reasoning, delta.thinking].find(nonempty);
      if (reasoning) emitSplit({reasoning});
      if (Array.isArray(delta.tool_calls)) delta.tool_calls.forEach((part, fallback) => {
        if (!isObject(part)) return;
        const index = Number.isInteger(part.index) ? part.index : fallback;
        const call = calls.get(index) ?? {index, id: '', type: 'function', function: {name: '', arguments: ''}};
        if (part.id) call.id += part.id;
        if (part.type) call.type = part.type;
        if (part.function?.name) call.function.name += part.function.name;
        if (part.function?.arguments) call.function.arguments += part.function.arguments;
        calls.set(index, call);
        onDelta({type: 'tool', call: {...call, function: {...call.function}}});
      });
    }
    if (choice?.finish_reason != null) { terminal = true; result.finishReason = choice.finish_reason; onDelta({type: 'finish', reason: choice.finish_reason}); }
  };
  try {
    if (payload?.stream === false || response.headers.get('content-type')?.includes('application/json')) {
      accept(await readJson(response, request));
      terminal = true;
    } else {
      if (!response.body?.getReader) throw new ApiError('浏览器无法读取流式响应，请关闭流式输出后重试', {kind: 'invalid_response', ambiguous: true});
      const parser = createSseParser(data => {
        if (done) return;
        if (data.trim() === '[DONE]') { terminal = true; done = true; return; }
        let root;
        try { root = JSON.parse(data); } catch { throw new ApiError('服务器返回了无效的 SSE 数据，回答可能不完整', {kind: 'invalid_response', ambiguous: true}); }
        accept(root);
      });
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      try {
        while (!done) {
          const chunk = await reader.read();
          if (chunk.done) break;
          parser.feed(decoder.decode(chunk.value, {stream: true}));
        }
        if (!done) { parser.feed(decoder.decode()); parser.finish(); }
      } finally {
        try { await reader.cancel(); } catch { /* The connection may already be closed. */ }
        reader.releaseLock();
      }
    }
    emitSplit(splitter.finish());
    result.toolCalls = [...calls.values()].sort((a, b) => a.index - b.index);
    if (!terminal) throw new ApiError('流式连接提前结束，回答可能不完整', {kind: 'interrupted', ambiguous: true});
    if (!result.content && !result.reasoning && !result.toolCalls.length) throw new ApiError('接口已响应，但未返回有效文本或工具调用', {kind: 'invalid_response', ambiguous: true});
    return result;
  } catch (error) {
    throw classifyError(error, {apiKey: options.apiKey, cancelled: options.signal?.aborted, ambiguous: true});
  }
}
