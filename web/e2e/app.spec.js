import { test as base, expect } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
import path from 'node:path';

const API_ORIGIN = 'https://apihub.agnes-ai.com';
const TEST_KEY = 'test-key-memory-only-never-a-real-credential';
const PNG = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/l94AAAAASUVORK5CYII=';
// Tiny locally generated 16×16 MP4 fixture; no remote media dependency.
const MP4 = 'AAAAJGZ0eXBpc29tAAACAGlzb21pc282aXNvMmF2YzFtcDQxAAAC7G1vb3YAAABsbXZoZAAAAAAAAAAAAAAAAAAAA+gAAAAAAAEAAAEAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAIAAAHvdHJhawAAAFx0a2hkAAAAAwAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAQAAAAAAQAAAAEAAAAAABi21kaWEAAAAgbWRoZAAAAAAAAAAAAAAAAAAAMgAAAAAAVcQAAAAAAC1oZGxyAAAAAAAAAAB2aWRlAAAAAAAAAAAAAAAAVmlkZW9IYW5kbGVyAAAAATZtaW5mAAAAFHZtaGQAAAABAAAAAAAAAAAAAAAkZGluZgAAABxkcmVmAAAAAAAAAAEAAAAMdXJsIAAAAAEAAAD2c3RibAAAAKpzdHNkAAAAAAAAAAEAAACaYXZjMQAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAAAQABAASAAAAEgAAAAAAAAAARVMYXZjNjEuMTkuMTAxIGxpYngyNjQAAAAAAAAAAAAAABj//wAAADRhdmNDAWQACv/hABdnZAAKrNlewEQAAAMABAAAAwDIPEiWWAEABmjr48siwP34+AAAAAAQcGFzcAAAAAEAAAABAAAAEHN0dHMAAAAAAAAAAAAAABBzdHNjAAAAAAAAAAAAAAAUc3RzegAAAAAAAAAAAAAAAAAAABBzdGNvAAAAAAAAAAAAAAAobXZleAAAACB0cmV4AAAAAAAAAAEAAAABAAAAAAAAAAAAAAAAAAAAYXVkdGEAAABZbWV0YQAAAAAAAAAhaGRscgAAAAAAAAAAbWRpcmFwcGwAAAAAAAAAAAAAAAAsaWxzdAAAACSpdG9vAAAAHGRhdGEAAAABAAAAAExhdmY2MS43LjEwMwAAAIhtb29mAAAAEG1maGQAAAAAAAAAAQAAAHB0cmFmAAAAJHRmaGQAAAA5AAAAAQAAAAAAAAMQAAACAAAAAsUBAQAAAAAAFHRmZHQBAAAAAAAAAAAAAAAAAAAwdHJ1bgAACgUAAAADAAAAkAIAAAAAAALFAAAEAAAAAAwAAAYAAAAADAAAAgAAAALlbWRhdAAAAq4GBf//qtxF6b3m2Ui3lizYINkj7u94MjY0IC0gY29yZSAxNjQgcjMxMDggMzFlMTlmOSAtIEguMjY0L01QRUctNCBBVkMgY29kZWMgLSBDb3B5bGVmdCAyMDAzLTIwMjMgLSBodHRwOi8vd3d3LnZpZGVvbGFuLm9yZy94MjY0Lmh0bWwgLSBvcHRpb25zOiBjYWJhYz0xIHJlZj0zIGRlYmxvY2s9MTowOjAgYW5hbHlzZT0weDM6MHgxMTMgbWU9aGV4IHN1Ym1lPTcgcHN5PTEgcHN5X3JkPTEuMDA6MC4wMCBtaXhlZF9yZWY9MSBtZV9yYW5nZT0xNiBjaHJvbWFfbWU9MSB0cmVsbGlzPTEgOHg4ZGN0PTEgY3FtPTAgZGVhZHpvbmU9MjEsMTEgZmFzdF9wc2tpcD0xIGNocm9tYV9xcF9vZmZzZXQ9LTIgdGhyZWFkcz0xIGxvb2thaGVhZF90aHJlYWRzPTEgc2xpY2VkX3RocmVhZHM9MCBucj0wIGRlY2ltYXRlPTEgaW50ZXJsYWNlZD0wIGJsdXJheV9jb21wYXQ9MCBjb25zdHJhaW5lZF9pbnRyYT0wIGJmcmFtZXM9MyBiX3B5cmFtaWQ9MiBiX2FkYXB0PTEgYl9iaWFzPTAgZGlyZWN0PTEgd2VpZ2h0Yj0xIG9wZW5fZ29wPTAgd2VpZ2h0cD0yIGtleWludD0yNTAga2V5aW50X21pbj0yNSBzY2VuZWN1dD00MCBpbnRyYV9yZWZyZXNoPTAgcmNfbG9va2FoZWFkPTQwIHJjPWNyZiBtYnRyZWU9MSBjcmY9MjMuMCBxY29tcD0wLjYwIHFwbWluPTAgcXBtYXg9NjkgcXBzdGVwPTQgaXBfcmF0aW89MS40MCBhcT0xOjEuMDAAgAAAAA9liIQAM//+9uy+BTYUyMEAAAAIQZoibEK//sAAAAAIAZ5BeQr/xIEAAABDbWZyYQAAACt0ZnJhAQAAAAAAAAEAAAAAAAAAAQAAAAAAAAQAAAAAAAAAAxABAQEAAAAQbWZybwAAAAAAAABD';
const IMAGE_URL = 'https://media.example.test/result.png';
const VIDEO_URL = 'https://media.example.test/result.mp4';

// Every external request is intercepted: no test can submit paid API work.
const test = base.extend({
  mock: async ({ page }, use) => {
    const mock = { handler: null, requests: [], unexpected: [], errors: [] };
    page.on('pageerror', error => mock.errors.push(error.message));
    page.on('console', message => {
      if (message.type() === 'error') mock.errors.push(message.text());
    });
    await page.route('**/*', async route => {
      const request = route.request(), url = new URL(request.url());
      if (url.origin === 'http://127.0.0.1:4173') return route.continue();
      if (url.origin === 'https://media.example.test') return route.fulfill({
        status: 200,
        contentType: url.pathname.endsWith('.png') ? 'image/png' : 'video/mp4',
        body: url.pathname.endsWith('.png') ? Buffer.from(PNG, 'base64') : Buffer.from(MP4, 'base64'),
      });
      mock.requests.push({ url: request.url(), method: request.method(), headers: request.headers(), body: request.postDataJSON() });
      if (url.origin === API_ORIGIN && mock.handler) return mock.handler(route, request, url);
      mock.unexpected.push(`${request.method()} ${request.url()}`);
      return route.fulfill({ status: 200, json: { error: { message: 'Unexpected external request blocked by test' } } });
    });
    await use(mock);
    expect(mock.unexpected, 'No unhandled external requests').toEqual([]);
    expect(mock.errors, 'No browser exceptions or console errors').toEqual([]);
  },
});

async function openApp(page) {
  await page.goto('/');
  await expect(page.getByRole('heading', { name: '让灵感，成为作品。' })).toBeVisible();
}
async function navigate(page, name) {
  const labels = { create: '创作工作台', chat: '灵感对话', queue: '任务记录', library: '我的作品' };
  if (name === 'settings') await page.locator('.connection').click();
  else await page.getByRole('navigation', { name: '工作区' }).getByRole('link', { name: labels[name], exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`#${name}$`));
}
async function connect(page) {
  await navigate(page, 'settings');
  await page.getByLabel('API Key（仅当前页面会话）').fill(TEST_KEY);
  await page.getByRole('button', { name: '应用连接', exact: true }).click();
  await expect(page.locator('.connection')).toHaveText('Key 已配置');
}
async function getStoredData(page) {
  return page.evaluate(async () => {
    const db = await new Promise((resolve, reject) => {
      const request = indexedDB.open('agnes-studio-web', 1);
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
    const values = await new Promise((resolve, reject) => {
      const request = db.transaction('kv').objectStore('kv').getAll();
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
    db.close();
    return JSON.stringify({ values, local: { ...localStorage }, session: { ...sessionStorage } });
  });
}
async function screenshot(page, name, testInfo) {
  const directory = process.env.E2E_SCREENSHOT_DIR || testInfo.outputDir;
  await mkdir(directory, { recursive: true });
  const file = path.join(directory, `agnes-web-${name}.png`);
  await page.screenshot({ path: file, fullPage: true });
  await testInfo.attach(name, { path: file, contentType: 'image/png' });
}
function sse(content, reasoning = '') {
  return [
    ...(reasoning ? [`data: ${JSON.stringify({ choices: [{ delta: { reasoning_content: reasoning } }] })}\n\n`] : []),
    `data: ${JSON.stringify({ choices: [{ delta: { content } }] })}\n\n`,
    'data: {"choices":[{"delta":{},"finish_reason":"stop"}]}\n\n',
    'data: [DONE]\n\n',
  ].join('');
}

test('desktop routes render and separate image, video, and chat drafts survive navigation', async ({ page, mock }, testInfo) => {
  await openApp(page);
  await screenshot(page, 'desktop', testInfo);
  await page.getByLabel('描述你的创意').fill('Image draft: misty woodland');
  await page.getByLabel('画面比例').selectOption('16:9');
  await page.getByRole('tab', { name: '视频创作' }).click();
  await page.getByLabel('描述你的创意').fill('Video draft: a slow forest tracking shot');
  await page.getByLabel('生成模式').selectOption('keyframe');
  await page.getByLabel('首帧图片 HTTPS URL', { exact: true }).fill('https://media.example.test/frame.png');
  await page.getByRole('tab', { name: '图像创作' }).click();
  await expect(page.getByLabel('描述你的创意')).toHaveValue('Image draft: misty woodland');
  await expect(page.getByLabel('画面比例')).toHaveValue('16:9');
  for (const route of ['library', 'queue', 'settings']) {
    await navigate(page, route);
    await expect(page.locator('main h1')).toBeVisible();
  }
  await navigate(page, 'chat');
  await page.locator('#chat-prompt').fill('Chat draft: outline a visual story');
  await navigate(page, 'create');
  await expect(page.getByLabel('描述你的创意')).toHaveValue('Image draft: misty woodland');
  await page.getByRole('tab', { name: '视频创作' }).click();
  await expect(page.getByLabel('描述你的创意')).toHaveValue('Video draft: a slow forest tracking shot');
  await expect(page.getByLabel('生成模式')).toHaveValue('keyframe');
  await expect(page.getByLabel('首帧图片 HTTPS URL', { exact: true })).toHaveValue('https://media.example.test/frame.png');
  await navigate(page, 'chat');
  await expect(page.locator('#chat-prompt')).toHaveValue('Chat draft: outline a visual story');
  expect(mock.requests).toHaveLength(0);
});

test('mobile routes remain within the viewport with usable navigation', async ({ page, mock }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await openApp(page);
  await screenshot(page, 'mobile', testInfo);
  for (const route of ['create', 'chat', 'library', 'queue', 'settings']) {
    await navigate(page, route);
    await expect(page.locator('main h1')).toBeVisible();
    const dimensions = await page.evaluate(() => ({ page: document.documentElement.scrollWidth, viewport: innerWidth }));
    expect(dimensions.page, `No horizontal page overflow on ${route}`).toBeLessThanOrEqual(dimensions.viewport);
  }
  expect(mock.requests).toHaveLength(0);
});

test('API key is memory-only, models check is non-generating, and refresh forgets the key', async ({ page, mock }) => {
  mock.handler = route => route.fulfill({ json: { object: 'list', data: [{ id: 'agnes-3.0-flash' }] } });
  await openApp(page);
  await connect(page);
  await page.getByRole('button', { name: '检查连接（不生成）' }).click();
  await expect(page.locator('#connection-result')).toContainText('连接成功');
  expect(mock.requests).toHaveLength(1);
  expect(mock.requests[0]).toMatchObject({ method: 'GET', url: `${API_ORIGIN}/v1/models` });
  expect(mock.requests[0].headers.authorization).toBe(`Bearer ${TEST_KEY}`);
  expect(await getStoredData(page)).not.toContain(TEST_KEY);
  await page.reload();
  await expect(page.getByLabel('API Key（仅当前页面会话）')).toHaveValue('');
  await expect(page.locator('.connection')).toHaveText('配置 API Key');
  await expect(page.getByLabel('API Base URL')).toHaveValue(API_ORIGIN);
  expect(await getStoredData(page)).not.toContain(TEST_KEY);
});

test('image submission is single-shot, previews success, and renders malicious prompt as text', async ({ page, mock }) => {
  let release;
  const response = new Promise(resolve => { release = resolve; });
  mock.handler = async route => {
    await response;
    await route.fulfill({ json: { data: [{ url: IMAGE_URL }] } });
  };
  await openApp(page);
  await connect(page);
  await navigate(page, 'create');
  const prompt = '<img src=x onerror="window.promptExecuted=true"> Beautiful forest & <script>window.promptExecuted=true</script>';
  await page.getByLabel('描述你的创意').fill(prompt);
  await page.locator('#generation-form').evaluate(form => { form.requestSubmit(); form.requestSubmit(); form.requestSubmit(); });
  await expect(page).toHaveURL(/#queue$/);
  await expect.poll(() => mock.requests.length).toBe(1);
  expect(mock.requests[0].url).toBe(`${API_ORIGIN}/v1/images/generations`);
  expect(mock.requests[0].body).toMatchObject({ model: 'agnes-image-2.5-flash', prompt, size: '2K', ratio: '9:16' });
  release();
  await expect(page.locator('.job-card .status')).toHaveText('已完成');
  await expect(page.locator('.job-card')).toHaveCount(1);
  await expect(page.locator('.job-card h3')).toHaveText(prompt);
  expect(await page.evaluate(() => Boolean(window.promptExecuted))).toBe(false);
  await expect(page.locator('.job-result')).toHaveAttribute('src', IMAGE_URL);
  await expect.poll(() => page.locator('.job-result').evaluate(img => img.complete && img.naturalWidth > 0)).toBe(true);
  await navigate(page, 'library');
  await expect(page.locator('.job-card')).toHaveCount(1);
  await page.getByRole('button', { name: '复用参数' }).click();
  await expect(page.getByLabel('描述你的创意')).toHaveValue(prompt);
  await expect(page.locator('.preview-media')).toHaveAttribute('src', IMAGE_URL);
  expect(mock.requests).toHaveLength(1);
  expect(await getStoredData(page)).not.toContain(TEST_KEY);
});

test('malformed advanced JSON shows an actionable error before making any API request', async ({ page, mock }) => {
  await openApp(page);
  await connect(page);
  await navigate(page, 'create');
  await page.getByLabel('描述你的创意').fill('Keep this draft after validation');
  await page.getByText('参考素材与高级参数', { exact: true }).click();
  await page.getByLabel('高级 JSON（可选）').fill('{broken');
  await page.getByRole('button', { name: '开始生成图像' }).click();
  await expect(page.locator('#toast')).toContainText('合法的 JSON 对象');
  await expect(page.getByLabel('描述你的创意')).toHaveValue('Keep this draft after validation');
  await expect(page.getByRole('button', { name: '开始生成图像' })).toBeEnabled();
  expect(mock.requests).toHaveLength(0);
});

test('malformed server JSON becomes uncertain without automatically repeating a paid submission', async ({ page, mock }) => {
  mock.handler = route => route.fulfill({ status: 200, contentType: 'application/json', body: '{invalid-json' });
  await openApp(page);
  await connect(page);
  await navigate(page, 'create');
  await page.getByLabel('描述你的创意').fill('Malformed response safety');
  await page.getByRole('button', { name: '开始生成图像' }).click();
  await expect(page.locator('.job-card .status')).toHaveText('提交结果待确认');
  await expect(page.locator('.job-error')).toContainText('不会自动重新提交');
  await page.clock.install();
  await page.clock.fastForward(120_000);
  expect(mock.requests).toHaveLength(1);
});

test('video polls no sooner than 30 seconds, then displays completed media', async ({ page, mock }) => {
  const pollTimes = [];
  mock.handler = async (route, request, url) => {
    if (url.pathname === '/v1/videos') return route.fulfill({ json: { video_id: 'video-test-1', status: 'queued', progress: 0 } });
    expect(url.pathname).toBe('/agnesapi');
    expect(url.searchParams.get('video_id')).toBe('video-test-1');
    expect(url.searchParams.get('model_name')).toBe('agnes-video-2.5-flash');
    expect(request.method()).toBe('GET');
    pollTimes.push(await page.evaluate(() => Date.now()));
    return route.fulfill({ json: pollTimes.length === 1
      ? { video_id: 'video-test-1', status: 'processing', progress: 50 }
      : { video_id: 'video-test-1', status: 'completed', metadata: { url: VIDEO_URL } } });
  };
  await openApp(page);
  await connect(page);
  await navigate(page, 'create');
  await page.getByRole('tab', { name: '视频创作' }).click();
  await page.getByLabel('描述你的创意').fill('A smooth forest tracking shot');
  await page.clock.install({ time: new Date('2026-10-01T12:00:00Z') });
  // Pause before submitting, avoiding a race between reading Date.now() and pausing.
  await page.clock.pauseAt(new Date('2026-10-01T12:00:10Z'));
  await page.getByRole('button', { name: '开始生成视频' }).click();
  await expect(page).toHaveURL(/#queue$/);
  await page.clock.runFor(50);
  await expect(page.locator('.job-card .status')).toHaveText('生成中');
  const queueState = JSON.parse(await getStoredData(page)).values.find(value => Array.isArray(value?.jobs));
  const acceptedAt = queueState.jobs[0].remoteAcceptedAt;
  const now = await page.evaluate(() => Date.now());
  await page.clock.runFor(acceptedAt + 29_999 - now);
  expect(pollTimes).toHaveLength(0);
  await page.clock.runFor(1_001);
  await expect.poll(() => pollTimes.length).toBe(1);
  expect(pollTimes[0] - acceptedAt).toBeGreaterThanOrEqual(30_000);
  await expect(page.locator('.job-card')).toContainText('服务端进度：50%');
  await page.clock.runFor(28_000);
  expect(pollTimes).toHaveLength(1);
  await page.clock.runFor(3_000);
  await expect(page.locator('.job-card .status')).toHaveText('已完成');
  expect(pollTimes).toHaveLength(2);
  expect(pollTimes[1] - pollTimes[0]).toBeGreaterThanOrEqual(30_000);
  expect(mock.requests.filter(request => request.method === 'POST')).toHaveLength(1);
  await expect(page.locator('video.job-result')).toHaveAttribute('src', VIDEO_URL);
  await page.clock.resume();
  await navigate(page, 'create');
  await expect(page.locator('video.preview-media')).toHaveAttribute('src', VIDEO_URL);
});

test('SSE chat displays reasoning, keeps conversations separate, and persists history on refresh', async ({ page, mock }) => {
  mock.handler = async (route, request, url) => {
    expect(url.pathname).toBe('/v1/chat/completions');
    const messages = request.postDataJSON().messages;
    const last = messages.at(-1).content;
    return route.fulfill({ contentType: 'text/event-stream', body: sse(`Reply to ${last}`, 'A short internal planning trace') });
  };
  await openApp(page);
  await connect(page);
  await navigate(page, 'chat');
  await page.locator('#chat-prompt').fill('First conversation');
  await page.getByRole('button', { name: '发送', exact: true }).click();
  await expect(page.locator('.message.assistant .message-content')).toHaveText('Reply to First conversation');
  await expect(page.getByText('思考过程', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '发送', exact: true })).toBeVisible();
  await page.getByRole('button', { name: '新建对话' }).click();
  await expect(page.locator('.message')).toHaveCount(0);
  await page.locator('#chat-prompt').fill('Second conversation');
  await page.locator('#chat-form').evaluate(form => { form.requestSubmit(); form.requestSubmit(); });
  await expect(page.locator('.message.assistant .message-content')).toHaveText('Reply to Second conversation');
  await page.locator('.conversation').filter({ hasText: 'First conversation' }).click();
  await expect(page.locator('.message.assistant .message-content')).toHaveText('Reply to First conversation');
  expect(mock.requests).toHaveLength(2);
  expect(mock.requests[1].body.messages.some(message => message.content === 'First conversation')).toBe(false);
  await page.reload();
  await expect(page.locator('.conversation')).toHaveCount(2);
  await expect(page.locator('.message.assistant .message-content')).toHaveText('Reply to First conversation');
  await expect(page.locator('.connection')).toHaveText('配置 API Key');
  expect(await getStoredData(page)).not.toContain(TEST_KEY);
});

test('progressive SSE stop keeps received text and prevents duplicate in-flight chat submissions', async ({ page, mock }) => {
  // A browser ReadableStream permits deterministic partial chunks and cancellation;
  // Playwright route.fulfill supplies a complete body and cannot model a live SSE stream.
  await page.addInitScript(({ origin }) => {
    const originalFetch = window.fetch.bind(window);
    window.__streamRequests = [];
    window.fetch = async (input, options = {}) => {
      const url = typeof input === 'string' ? input : input.url;
      if (url !== `${origin}/v1/chat/completions`) return originalFetch(input, options);
      window.__streamRequests.push(JSON.parse(options.body));
      const encoder = new TextEncoder();
      const stream = new ReadableStream({
        start(controller) {
          controller.enqueue(encoder.encode('data: {"choices":[{"delta":{"content":"First live chunk"}}]}\n\n'));
          options.signal.addEventListener('abort', () => controller.error(new DOMException('Aborted', 'AbortError')), { once: true });
        },
      });
      return new Response(stream, { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
    };
  }, { origin: API_ORIGIN });
  await openApp(page);
  await connect(page);
  await navigate(page, 'chat');
  await page.locator('#chat-prompt').fill('Stop after the first streamed chunk');
  await page.locator('#chat-form').evaluate(form => { form.requestSubmit(); form.requestSubmit(); form.requestSubmit(); });
  await expect(page.locator('.message.assistant .message-content')).toHaveText('First live chunk');
  await expect(page.getByRole('button', { name: '停止生成', exact: true })).toBeVisible();
  await page.getByRole('button', { name: '停止生成', exact: true }).click();
  await expect(page.locator('.message.assistant .error-text')).toHaveText('已停止生成，已接收内容已保留');
  await expect(page.locator('.message.assistant .message-content')).toHaveText('First live chunk');
  await expect(page.getByRole('button', { name: '发送', exact: true })).toBeVisible();
  expect(await page.evaluate(() => window.__streamRequests.length)).toBe(1);
  expect(mock.requests).toHaveLength(0);
});
