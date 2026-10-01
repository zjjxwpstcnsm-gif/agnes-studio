import test from 'node:test';
import assert from 'node:assert/strict';
import {
  ApiError, DEFAULT_BASE_URL, DEFAULT_SETTINGS, buildChatPayload, buildImagePayload, buildVideoPayload,
  normalizeBaseUrl, validateReferenceUrl, classifyError, redactSecrets, parseRetryAfter, requestJson,
  parseVideoResponse, parseImageResponse, buildVideoPollPath, submitImage, submitVideo, pollVideo,
  createSseParser, createThinkingSplitter, streamChat,
} from '../src/api.js';

const messages = [{role: 'user', content: '你好'}];
const image = 'https://cdn.example.com/image.png';
const video = 'https://cdn.example.com/video.mp4';
const auth = {baseUrl: DEFAULT_BASE_URL, apiKey: 'test-only-not-a-real-key'};
const jsonResponse = (body, init = {}) => new Response(JSON.stringify(body), {headers: {'Content-Type': 'application/json'}, ...init});
const mockFetch = body => async () => jsonResponse(body);
const event = payload => `data: ${JSON.stringify(payload)}\n\n`;
function streamResponse(text, chunkSize = 1) {
  const bytes = new TextEncoder().encode(text);
  return new Response(new ReadableStream({start(controller) {
    for (let index = 0; index < bytes.length; index += chunkSize) controller.enqueue(bytes.slice(index, index + chunkSize));
    controller.close();
  }}), {headers: {'Content-Type': 'text/event-stream'}});
}
const validation = fn => assert.throws(fn, error => error instanceof ApiError && error.kind === 'validation');

test('defaults match the Android Agnes model family and official API host', () => {
  assert.equal(DEFAULT_BASE_URL, 'https://apihub.agnes-ai.com');
  assert.equal(DEFAULT_SETTINGS.chat.model, 'agnes-3.0-flash');
  assert.equal(DEFAULT_SETTINGS.image.model, 'agnes-image-2.5-flash');
  assert.equal(DEFAULT_SETTINGS.video.model, 'agnes-video-2.5-flash');
  assert.equal(DEFAULT_SETTINGS.video.size, '720P');
  assert.ok(Object.isFrozen(DEFAULT_SETTINGS.chat));
});

test('normalizes HTTPS API roots and strips the optional v1 suffix', () => {
  assert.equal(normalizeBaseUrl(' https://api.example.com/v1/// '), 'https://api.example.com');
  assert.equal(normalizeBaseUrl('https://api.example.com/proxy/v1'), 'https://api.example.com/proxy');
  for (const input of ['http://api.example.com', 'file:///tmp/a', 'not a URL', 'https://a:b@api.example.com', 'https://api.example.com?key=x', 'https://api.example.com/#key']) validation(() => normalizeBaseUrl(input));
});

test('references accept public HTTPS and reject implicit local/credential-bearing media', () => {
  assert.equal(validateReferenceUrl(`${image}?signed=token`), `${image}?signed=token`);
  for (const input of ['http://example.com/a.png', 'data:image/png;base64,AA==', 'blob:https://example.com/x', '/local.png', 'https://localhost/a', 'https://127.0.0.1/a', 'https://2130706433/a', 'https://10.0.0.1/a', 'https://172.20.0.1/a', 'https://192.168.1.1/a', 'https://169.254.169.254/a', 'https://[::1]/a', 'https://[fc00::1]/a', 'https://[::ffff:127.0.0.1]/a', 'https://user:pass@example.com/a']) validation(() => validateReferenceUrl(input));
});

test('chat payload snapshots parameters, explicitly disables thinking and overrides advanced core fields', () => {
  const payload = buildChatPayload({messages, enableThinking: false, stream: false, extraJson: JSON.stringify({model: 'wrong', messages: [], temperature: 2, max_tokens: 1, stream: true, chat_template_kwargs: {foo: 'bar', enable_thinking: true}, tools: [{bad: true}], tool_choice: 'required', custom: 'kept'})});
  assert.equal(payload.model, DEFAULT_SETTINGS.chat.model);
  assert.deepEqual(payload.messages, [{role: 'system', content: 'You are a helpful assistant.'}, ...messages]);
  assert.equal(payload.temperature, 0.7);
  assert.equal(payload.max_tokens, 4096);
  assert.equal(payload.stream, false);
  assert.deepEqual(payload.chat_template_kwargs, {foo: 'bar', enable_thinking: false});
  assert.equal(payload.custom, 'kept');
  assert.equal(payload.tools, undefined);
  assert.equal(payload.tool_choice, undefined);
});

test('chat supports HTTPS image messages, tool history and manual tool choices', () => {
  const payload = buildChatPayload({systemPrompt: '', messages: [
    {role: 'user', content: 'Describe', attachments: [image]},
    {role: 'assistant', content: null, tool_calls: [{id: 'call1', type: 'function', function: {name: 'weather', arguments: '{}'}}]},
    {role: 'tool', content: 'sunny', tool_call_id: 'call1'},
  ], toolsJson: '[{"type":"function","function":{"name":"weather","parameters":{"type":"object"}}}]', toolChoice: 'function:weather'});
  assert.deepEqual(payload.messages[0].content, [{type: 'text', text: 'Describe'}, {type: 'image_url', image_url: {url: image}}]);
  assert.equal(payload.messages[1].tool_calls[0].function.name, 'weather');
  assert.equal(payload.messages[2].tool_call_id, 'call1');
  assert.deepEqual(payload.tool_choice, {type: 'function', function: {name: 'weather'}});
});

test('chat validates numeric limits, JSON shapes, role and attachment protocol', () => {
  for (const overrides of [
    {temperature: -1}, {temperature: 2.01}, {topP: 1.1}, {topP: ''}, {maxTokens: 65537}, {maxTokens: 0}, {maxTokens: 1.5},
    {model: ' '}, {messages: []}, {messages: [{role: 'invalid', content: 'a'}]}, {messages: [{role: 'tool', content: 'a'}]},
    {messages: [{role: 'user', content: [{type: 'image_url', image_url: {url: 'data:image/png;base64,AA=='}}]}]},
    {extraJson: '[]'}, {extraJson: '{oops'}, {toolsJson: '{}'}, {toolsJson: '[{}]'},
  ]) validation(() => buildChatPayload({messages, ...overrides}));
  assert.equal(buildChatPayload({messages, maxTokens: '65536', topP: '0', temperature: '2'}).max_tokens, 65536);
});

test('image payload places references and format inside safely merged extra_body', () => {
  const payload = buildImagePayload({prompt: 'portrait', references: [image], extraJson: JSON.stringify({size: 'wrong', prompt: 'wrong', return_base64: true, response_format: 'bad', image: ['bad'], extra_body: {custom: 2, image: ['bad'], response_format: 'bad'}})});
  assert.equal(payload.prompt, 'portrait');
  assert.equal(payload.size, '2K');
  assert.deepEqual(payload.extra_body, {custom: 2, image: [image], response_format: 'url'});
  assert.equal(payload.response_format, undefined);
  assert.equal(payload.image, undefined);
  assert.equal(payload.return_base64, undefined);
});

test('image Base64 flag applies only to text-to-image and references cannot hide in advanced JSON', () => {
  assert.equal(buildImagePayload({prompt: 'a', responseFormat: 'b64_json'}).return_base64, true);
  assert.equal(buildImagePayload({prompt: 'a', responseFormat: 'BASE64'}).extra_body.response_format, 'b64_json');
  assert.equal(buildImagePayload({prompt: 'a', responseFormat: 'b64_json', references: [image]}).return_base64, undefined);
  assert.equal(buildImagePayload({prompt: 'a', extraJson: '{"extra_body":{"image":["http://bad"]}}'}).extra_body.image, undefined);
  for (const overrides of [{prompt: ''}, {size: '1024x1024'}, {ratio: '2:1'}, {responseFormat: 'foo'}, {references: ['http://bad']}]) validation(() => buildImagePayload({prompt: 'a', ...overrides}));
});

test('video text payload has string duration, n=1, and strips hidden advanced media', () => {
  const payload = buildVideoPayload({prompt: 'camera move', seconds: 8, extraJson: '{"n":8,"mode":"reference","seconds":99,"videos":["http://bad"],"images":["http://bad"],"first_frame":"http://bad","seed":999,"custom":true}'});
  assert.equal(payload.seconds, '8');
  assert.equal(payload.n, 1);
  assert.equal(payload.mode, 'text');
  assert.equal(payload.images, undefined);
  assert.equal(payload.videos, undefined);
  assert.equal(payload.first_frame, undefined);
  assert.equal(payload.seed, undefined);
  assert.equal(payload.custom, true);
});

test('video keyframe and full-model reference video fields map to correct Agnes names', () => {
  const keyframe = buildVideoPayload({prompt: 'move', mode: 'keyframe', firstFrame: image, lastFrame: image, seed: '12'});
  assert.equal(keyframe.first_frame, image);
  assert.equal(keyframe.last_frame, image);
  assert.equal(keyframe.seed, 12);
  const reference = buildVideoPayload({prompt: 'move', model: 'agnes-video-2.5', size: '2K', mode: 'reference', videos: [{url: video, startSeconds: 1.5, requireAudio: true}], images: [image], audios: ['https://cdn.example.com/audio.mp3']});
  assert.deepEqual(reference.videos, [{url: video, start_seconds: 1.5, require_audio: true}]);
  assert.deepEqual(reference.images, [image]);
});

test('video validates model size, duration, media counts and mode exclusivity', () => {
  for (const overrides of [
    {size: '2K'}, {seconds: 3}, {seconds: 13}, {seconds: 4.5}, {aspectRatio: '2:3'}, {prompt: ''},
    {mode: 'keyframe'}, {mode: 'reference'}, {images: [image]}, {firstFrame: image},
    {mode: 'keyframe', firstFrame: image, images: [image]},
    {mode: 'reference', firstFrame: image, images: [image]},
    {mode: 'reference', images: Array(6).fill(image)},
    {mode: 'reference', audios: Array(4).fill('https://cdn.example.com/audio.mp3')},
    {mode: 'reference', videos: [video]},
    {mode: 'keyframe', firstFrame: 'http://example.com/a.png'},
    {mode: 'reference', model: 'agnes-video-2.5', videos: [{url: video, startSeconds: -1}]},
    {model: 'agnes-video-2.5', size: '4K'},
  ]) validation(() => buildVideoPayload({prompt: 'move', ...overrides}));
  assert.equal(buildVideoPayload({prompt: 'move', mode: 'reference', images: Array(5).fill(image)}).images.length, 5);
});

test('video response parser accepts official, wrapped and nested task responses', () => {
  const created = parseVideoResponse({data: {video_id: 'v-123', status: 'queued', progress: '2'}});
  assert.equal(created.id, 'v-123');
  assert.equal(created.videoId, 'v-123');
  assert.equal(created.progress, 2);
  assert.equal(created.done, false);
  for (const response of [
    {status: 'completed', progress: 100, metadata: {url: video}},
    {result: {status: 'completed', progress: 100, output: {video_url: video}}},
    {data: {task: {id: 'v1', status: 'succeeded', output: {outputs: [{url: video}]}}}},
  ]) {
    const parsed = parseVideoResponse(response);
    assert.equal(parsed.url, video);
    assert.equal(parsed.done, true);
    assert.equal(parsed.failed, false);
  }
});

test('video parser clamps progress, rejects unsafe result URLs and describes terminal failures', () => {
  assert.equal(parseVideoResponse({progress: 999}).progress, 100);
  assert.equal(parseVideoResponse({progress: 'NaN'}).progress, 0);
  assert.equal(parseVideoResponse({status: 'completed', metadata: {url: 'javascript:alert(1)'}}).url, null);
  assert.equal(parseVideoResponse({status: 'completed', metadata: {url: 'https://u:p@example.com/a.mp4'}}).url, null);
  const failed = parseVideoResponse({data: {status: 'failed', error: {message: 'Safety policy'}}});
  assert.equal(failed.done, true);
  assert.equal(failed.failed, true);
  assert.equal(failed.error, 'Safety policy');
});

test('image parser accepts URLs/Base64 and rejects empty or unsafe successful responses', () => {
  assert.equal(parseImageResponse({data: [{url: image}]})[0].url, image);
  assert.equal(parseImageResponse({data: {data: [{b64_json: 'AA=='}]}})[0].base64, 'AA==');
  assert.equal(parseImageResponse({result: {data: [{b64_json: 'AA==', mime_type: 'image/webp'}]}})[0].mimeType, 'image/webp');
  for (const value of [{}, {data: []}, {data: [{url: 'javascript:alert(1)'}]}, {data: [{b64_json: 'data:text/html,bad'}]}]) assert.throws(() => parseImageResponse(value), error => error.kind === 'invalid_response' && error.ambiguous);
});

test('poll endpoint uses agnesapi and encodes task/model query strings', () => {
  assert.equal(buildVideoPollPath('v/1&evil=1'), '/agnesapi?video_id=v%2F1%26evil%3D1&model_name=agnes-video-2.5-flash');
});

test('requestJson keeps credentials out of URLs and blocks redirects, cookies and insecure roots', async () => {
  let captured;
  const result = await requestJson({...auth, baseUrl: `${DEFAULT_BASE_URL}/v1`, path: '/v1/videos', method: 'POST', body: {prompt: 'a'}, fetchImpl: async (url, init) => { captured = {url, init}; return jsonResponse({id: 'v'}); }});
  assert.deepEqual(result, {id: 'v'});
  assert.equal(captured.url, `${DEFAULT_BASE_URL}/v1/videos`);
  assert.equal(captured.init.headers.Authorization, `Bearer ${auth.apiKey}`);
  assert.equal(captured.init.credentials, 'omit');
  assert.equal(captured.init.redirect, 'error');
  assert.equal(captured.init.referrerPolicy, 'no-referrer');
  assert.equal(captured.init.cache, 'no-store');
  assert.equal(captured.init.body, '{"prompt":"a"}');
  let requested = 0;
  for (const change of [{baseUrl: 'http://example.com'}, {apiKey: ''}, {path: '//evil.example.com'}, {path: 'https://evil.example.com'}, {path: '/\\evil.example.com'}]) {
    await assert.rejects(requestJson({...auth, path: '/v1/videos', ...change, fetchImpl: () => { requested++; }}), ApiError);
  }
  assert.equal(requested, 0);
});

test('HTTP 429, quota, validation and server errors classify correctly without leaking keys', async () => {
  for (const [status, body, kind, ambiguous] of [
    [429, {error: {message: 'too many requests'}}, 'rate_limit', false],
    [429, {error: {message: 'insufficient_quota'}}, 'quota_exhausted', false],
    [401, {error: {message: `Invalid key ${auth.apiKey}`}}, 'authentication', false],
    [400, {error: {message: `bad parameter ${auth.apiKey}`}}, 'validation', false],
    [503, {error: {message: 'queue capacity full'}}, 'remote_queue_full', true],
    [500, {error: {message: 'server down'}}, 'server', true],
  ]) {
    await assert.rejects(requestJson({...auth, path: '/v1/videos', method: 'POST', body: {}, fetchImpl: async () => jsonResponse(body, {status, headers: {'Retry-After': '32'}})}), error => {
      assert.equal(error.kind, kind);
      assert.equal(error.status, status);
      assert.equal(error.httpStatus, status);
      assert.equal(error.ambiguous, ambiguous);
      assert.equal(error.retryAfterMs, 32000);
      assert.ok(!JSON.stringify(error).includes(auth.apiKey));
      assert.ok(!error.message.includes(auth.apiKey));
      return true;
    });
  }
});

test('POST transport, invalid JSON and missing media results are ambiguous and are never retried', async () => {
  let calls = 0;
  const fetchImpl = async () => { calls++; throw new TypeError(`fetch failed ${auth.apiKey}`); };
  await assert.rejects(requestJson({...auth, path: '/v1/videos', method: 'POST', body: {}, fetchImpl}), error => error.kind === 'network' && error.ambiguous && !error.message.includes(auth.apiKey));
  assert.equal(calls, 1);
  await assert.rejects(requestJson({...auth, path: '/agnesapi', fetchImpl}), error => error.kind === 'network' && !error.ambiguous);
  await assert.rejects(requestJson({...auth, path: '/v1/videos', method: 'POST', fetchImpl: async () => new Response('<html>failure</html>')}), error => error.kind === 'invalid_response' && error.ambiguous);
  await assert.rejects(submitImage({prompt: 'a'}, {...auth, fetchImpl: mockFetch({data: []})}), error => error.ambiguous);
  await assert.rejects(submitVideo({prompt: 'a'}, {...auth, fetchImpl: mockFetch({status: 'queued'})}), error => error.ambiguous);
});

test('normalized queue wrappers submit to correct endpoints and preserve result status', async () => {
  let requestedPath;
  const imageResult = await submitImage({prompt: 'a'}, {...auth, fetchImpl: async url => { requestedPath = url; return jsonResponse({data: [{url: image}]}); }});
  assert.ok(requestedPath.endsWith('/v1/images/generations'));
  assert.equal(imageResult.status, 'succeeded');
  assert.equal(imageResult.outputs[0].url, image);
  const created = await submitVideo({prompt: 'a'}, {...auth, fetchImpl: mockFetch({data: {id: 'v1', status: 'queued'}})});
  assert.equal(created.remoteId, 'v1');
  assert.equal(created.status, 'processing');
  const complete = await pollVideo('v1', {...auth, model: 'agnes-video-2.5', fetchImpl: async url => { requestedPath = url; return jsonResponse({status: 'completed', metadata: {url: video}}); }});
  assert.ok(requestedPath.includes('model_name=agnes-video-2.5'));
  assert.equal(complete.status, 'succeeded');
  assert.equal(complete.remoteId, 'v1');
  assert.equal(complete.outputs[0].url, video);
  const failed = await submitVideo({prompt: 'a'}, {...auth, fetchImpl: mockFetch({status: 'failed', failure_reason: 'Rejected'})});
  assert.equal(failed.status, 'failed');
  assert.equal(failed.error, 'Rejected');
});

test('completed task without a downloadable URL stays processing instead of false success', async () => {
  const result = await pollVideo('v1', {...auth, fetchImpl: mockFetch({status: 'completed', progress: 100})});
  assert.equal(result.status, 'processing');
  assert.deepEqual(result.outputs, []);
});

test('Retry-After supports seconds, dates, lower bound and invalid inputs', () => {
  assert.equal(parseRetryAfter('0'), 1000);
  assert.equal(parseRetryAfter('30'), 30000);
  assert.equal(parseRetryAfter('Thu, 01 Oct 2026 14:00:00 GMT', Date.parse('2026-10-01T13:59:00Z')), 60000);
  assert.equal(parseRetryAfter('oops'), null);
  assert.equal(parseRetryAfter(null), null);
});

test('redaction removes explicit secrets, bearer tokens and common key fields', () => {
  const result = redactSecrets(`Key ${auth.apiKey}; Authorization: Bearer sk-abcdefghi; api_key=abc123&x=2`, auth.apiKey);
  assert.ok(!result.includes(auth.apiKey));
  assert.ok(!result.includes('sk-abcdefghi'));
  assert.ok(!result.includes('abc123'));
  assert.equal(classifyError(new DOMException('Aborted', 'AbortError')).kind, 'cancelled');
});

test('SSE parser handles BOM, comments, split CRLF, multi-line data and unterminated EOF', () => {
  const output = [];
  const parser = createSseParser(data => output.push(data));
  const text = '\uFEFF: comment\r\nid: 1\r\ndata: {\r\ndata: "ok":true}\r\n\r\nevent: message\ndata: second\n\ndata: tail';
  for (const char of text) parser.feed(char);
  parser.finish();
  assert.deepEqual(output, ['{\n"ok":true}', 'second', 'tail']);
});

test('thinking splitter handles tags across every character and preserves incomplete literal text', () => {
  const splitter = createThinkingSplitter();
  let content = '', reasoning = '';
  for (const char of 'Hello<think>思考中</think>world<think>more</think>!') {
    const part = splitter.feed(char); content += part.content; reasoning += part.reasoning;
  }
  const tail = splitter.finish(); content += tail.content; reasoning += tail.reasoning;
  assert.equal(content, 'Helloworld!');
  assert.equal(reasoning, '思考中more');
  const literal = createThinkingSplitter();
  assert.equal(literal.feed('a<thi').content, 'a');
  assert.equal(literal.finish().content, '<thi');
});

test('streamChat reassembles UTF-8 one byte at a time and separates all thinking forms', async () => {
  const received = [];
  const data = ': keepalive\r\n\r\n' +
    event({choices: [{delta: {reasoning_content: '显式思考'}}]}) +
    event({choices: [{delta: {content: '<thi'}}]}) +
    event({choices: [{delta: {content: 'nk>隐式思考</thi'}}]}) +
    event({choices: [{delta: {content: 'nk>你好 🌍'}}]}) +
    event({choices: [{delta: {}, finish_reason: 'stop'}]}) +
    event({choices: [], usage: {total_tokens: 20}}) + 'data: [DONE]\n\n';
  const result = await streamChat({...auth, payload: buildChatPayload({messages}), onDelta: part => received.push(part), fetchImpl: async () => streamResponse(data)});
  assert.equal(result.content, '你好 🌍');
  assert.equal(result.reasoning, '显式思考隐式思考');
  assert.equal(result.finishReason, 'stop');
  assert.equal(result.usage.total_tokens, 20);
  assert.ok(received.some(part => part.type === 'text'));
  assert.ok(received.some(part => part.type === 'reasoning'));
  assert.ok(received.some(part => part.type === 'usage'));
});

test('streamChat combines indexed tool call fragments without executing them', async () => {
  const data = event({choices: [{delta: {tool_calls: [{index: 0, id: 'call1', type: 'function', function: {name: 'weather', arguments: '{"city":'}}]}}]}) +
    event({choices: [{delta: {tool_calls: [{index: 0, function: {arguments: '"Paris"}'}}]}}]}) +
    event({choices: [{delta: {}, finish_reason: 'tool_calls'}]}) + 'data: [DONE]\n\n';
  const snapshots = [];
  const result = await streamChat({...auth, payload: buildChatPayload({messages}), onDelta: event => { if (event.type === 'tool') snapshots.push(event.call); }, fetchImpl: async () => streamResponse(data, 17)});
  assert.deepEqual(result.toolCalls, [{index: 0, id: 'call1', type: 'function', function: {name: 'weather', arguments: '{"city":"Paris"}'}}]);
  assert.equal(result.content, '');
  assert.equal(snapshots[0].function.arguments, '{"city":');
  assert.equal(snapshots[1].id, 'call1');
  assert.equal(snapshots[1].function.arguments, '{"city":"Paris"}');
});

test('streamChat supports nonstream JSON and JSON fallback from a streaming endpoint', async () => {
  for (const stream of [false, true]) {
    const result = await streamChat({...auth, payload: buildChatPayload({messages, stream}), fetchImpl: mockFetch({choices: [{message: {content: '<think>Plan</think>Hello', reasoning: 'Reason'}, finish_reason: 'stop'}], usage: {total_tokens: 7}})});
    assert.equal(result.content, 'Hello');
    assert.equal(result.reasoning, 'PlanReason');
    assert.equal(result.usage.total_tokens, 7);
  }
});

test('SSE error bodies redact keys, malformed events fail, and unfinished streams are marked interrupted', async () => {
  await assert.rejects(streamChat({...auth, payload: buildChatPayload({messages}), fetchImpl: async () => streamResponse(event({error: {message: `bad ${auth.apiKey}`}}))}), error => !error.message.includes(auth.apiKey) && error.ambiguous);
  await assert.rejects(streamChat({...auth, payload: buildChatPayload({messages}), fetchImpl: async () => streamResponse('data: {bad}\n\n')}), error => error.kind === 'invalid_response');
  await assert.rejects(streamChat({...auth, payload: buildChatPayload({messages}), fetchImpl: async () => streamResponse(event({choices: [{delta: {content: 'partial'}}]}))}), error => error.kind === 'interrupted');
  await assert.rejects(streamChat({...auth, payload: buildChatPayload({messages}), fetchImpl: async () => streamResponse('data: [DONE]\n\n')}), error => error.kind === 'invalid_response');
});

test('abort signal is passed to fetch and cancellation remains potentially dispatched for POST', async () => {
  const controller = new AbortController();
  controller.abort();
  await assert.rejects(streamChat({...auth, payload: buildChatPayload({messages}), signal: controller.signal, fetchImpl: async (url, init) => {
    assert.equal(init.signal, controller.signal);
    throw new DOMException('Aborted', 'AbortError');
  }}), error => error.kind === 'cancelled' && error.ambiguous);
});

test('explicit failed video task returns a normalized failure and redacts a reflected API key', async () => {
  const result = await submitVideo({prompt: 'a'}, {...auth, fetchImpl: mockFetch({status: 'failed', error: {message: `Rejected ${auth.apiKey}`}})});
  assert.equal(result.status, 'failed');
  assert.ok(!result.error.includes(auth.apiKey));
  const poll = await pollVideo('v1', {...auth, fetchImpl: mockFetch({data: {status: 'failed', error: `Rejected ${auth.apiKey}`}})});
  assert.equal(poll.status, 'failed');
  assert.ok(!poll.error.includes(auth.apiKey));
});

test('malformed numeric and prompt settings remain validation errors', () => {
  for (const overrides of [{temperature: []}, {temperature: {}}, {topP: true}, {maxTokens: '   '}, {systemPrompt: {}}]) validation(() => buildChatPayload({messages, ...overrides}));
});

test('SSE framing caps a large event split across many data lines', () => {
  const parser = createSseParser(() => {});
  assert.throws(() => { for (let i = 0; i < 2100; i++) parser.feed(`data: ${'x'.repeat(1000)}\n`); }, error => error.kind === 'invalid_response');
});
