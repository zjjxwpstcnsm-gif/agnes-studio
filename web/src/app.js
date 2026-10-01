import * as api from "./api.js";
import { createStorage, assertNoCredentials } from "./storage.js";
import { createQueue } from "./queue.js";

const icons = {
  spark: "m12 3 2.5 6.5L21 12l-6.5 2.5L12 21l-2.5-6.5L3 12l6.5-2.5L12 3Z",
  grid: "M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z",
  chat: "M21 11.5a8.4 8.4 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.4 8.4 0 0 1-3.8-.9L3 21l1.9-5.7a8.4 8.4 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.4 8.4 0 0 1 3.8-.9h.5a8.5 8.5 0 0 1 8 8v.5Z",
  image:
    "M4 3h16a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1ZM3 16l6-6 4 4 3-3 5 5M8 7h.01",
  video:
    "M4 5h11a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1Zm12 4 5-3v12l-5-3",
  clock: "M12 8v5l3 2M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0Z",
  folder: "M3 7V5h6l2 2h10v13H3V7Z",
  settings:
    "M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8ZM12 2v3M12 19v3M2 12h3M19 12h3M5 5l2 2M17 17l2 2M5 19l2-2M17 7l2-2",
  arrow: "M4 12h16m-6-6 6 6-6 6",
  down: "M12 3v12m-5-5 5 5 5-5M4 16v5h16v-5",
  plus: "M12 5v14M5 12h14",
  shield: "M12 3 3 7v6c0 5 9 9 9 9s9-4 9-9V7l-9-4Zm-4 9 3 3 5-6",
  leaf: "M20 3C9 1 2 8 5 16c4 8 16 2 15-13ZM4 21 15 9",
  cube: "m12 2 10 6v9l-10 5-10-5V8l10-6ZM2 8l10 6 10-6M12 14v8",
  stop: "M5 5h14v14H5z",
  trash: "M3 6h18M6 6v15h12V6M9 6V3h6v3M10 10v7M14 10v7",
  copy: "M8 8h13v13H8zM16 8V3H3v13h5",
  external: "M14 3h7v7M21 3 10 14M10 3H3v18h18v-7",
  key: "M14 6a5 5 0 1 1-2 8l-6 6H3v-3l6-6a5 5 0 0 1 5-5Z",
};
const icon = (name) =>
  `<svg aria-hidden="true" viewBox="0 0 24 24"><path d="${icons[name] || icons.spark}"/></svg>`;
const e = (text) =>
  String(text ?? "").replace(
    /[&<>"']/g,
    (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[
        c
      ],
  );
const clone = (value) => structuredClone(value);
const id = () => crypto.randomUUID();
const $ = (selector) => document.querySelector(selector);
const storage = createStorage({ getSecrets: () => [apiKey] });
let settings = clone(api.DEFAULT_SETTINGS);
let apiKey = ""; // Deliberately memory-only. Never persisted or exported.
let conversations = [],
  currentChatId = null,
  jobs = [],
  queue,
  storageWarning = "";
let mode = "image",
  filter = "all",
  chatController = null,
  activeChatId = null,
  submitting = false;
let imageDraft = { prompt: "", references: "", ...clone(settings.image) };
let videoDraft = {
  prompt: "",
  firstFrame: "",
  lastFrame: "",
  images: "",
  audios: "",
  videos: "[]",
  ...clone(settings.video),
};
let chatDraft = "",
  chatImage = "",
  chatSettingsOpen = false;
let saveTimer, toastTimer;
const titles = {
  create: "创作工作台",
  chat: "灵感对话",
  library: "我的作品",
  queue: "任务记录",
  settings: "连接与设置",
};
const statusNames = {
  queued: "已排队",
  waiting: "等待连接 / 限流",
  sending: "正在提交",
  processing: "生成中",
  retry: "等待查询",
  succeeded: "已完成",
  failed: "失败",
  cancelled: "已停止跟踪",
  uncertain: "提交结果待确认",
};
const finished = new Set(["succeeded", "failed", "cancelled", "uncertain"]);
const examples = {
  image: [
    [
      "自然摄影",
      "晨雾中的森林湖泊，柔和的清晨光线，胶片摄影质感，极简构图",
      "leaf",
    ],
    [
      "产品视觉",
      "奶油色背景上的极简陶瓷花瓶，窗边自然光，柔和阴影，高级产品摄影",
      "cube",
    ],
    [
      "概念艺术",
      "一座漂浮在云海上的未来城市，温暖的夕阳，细腻的建筑结构，电影概念艺术",
      "spark",
    ],
  ],
  video: [
    [
      "自然瞬间",
      "清晨阳光穿过森林，镜头缓缓向前推进，薄雾飘动，宁静的电影氛围",
      "leaf",
    ],
    [
      "产品故事",
      "镜头缓慢环绕桌面上的极简香水瓶，光线掠过玻璃表面，细腻质感",
      "cube",
    ],
    [
      "电影镜头",
      "一列火车穿过山间云海，远景航拍，镜头平稳跟随，温暖的黄昏光线",
      "video",
    ],
  ],
};

function toast(message) {
  const el = $("#toast");
  el.textContent = message;
  el.classList.add("show");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove("show"), 5200);
}
function route() {
  const name = location.hash.slice(1);
  return titles[name] ? name : "create";
}
function currentChat() {
  return conversations.find((c) => c.id === currentChatId);
}
async function persist() {
  try {
    await storage.set("settings", settings);
    await storage.set("conversations", conversations);
    await storage.set("currentChatId", currentChatId);
  } catch (err) {
    toast(`记录未保存：${err.message}`);
    throw err;
  }
}
function persistSoon() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => persist().catch(() => {}), 350);
}
function ensureChat() {
  if (!currentChat()) {
    const c = {
      id: id(),
      title: "新的灵感",
      messages: [],
      parameters: clone(settings.chat),
      createdAt: Date.now(),
    };
    conversations.unshift(c);
    currentChatId = c.id;
  }
  return currentChat();
}
function navItem(name, label, glyph, extra = "") {
  return `<a href="#${name}" class="${route() === name ? "active " : ""}${extra}" ${route() === name ? 'aria-current="page"' : ""} aria-label="${label}">${icon(glyph)}<span>${label}</span>${name === "queue" && jobs.filter((j) => !finished.has(j.status)).length ? `<b>${jobs.filter((j) => !finished.has(j.status)).length}</b>` : ""}</a>`;
}
function render() {
  const page = route();
  $("#app").innerHTML =
    `<aside class="sidebar"><a href="#create" class="brand"><img src="./favicon.svg" alt=""><span>Agnes Studio <small>WEB</small></span></a><div class="nav-label">WORKSPACE</div><nav class="nav" aria-label="工作区">${navItem("create", "创作工作台", "grid")}${navItem("chat", "灵感对话", "chat")}${navItem("library", "我的作品", "folder")}${navItem("queue", "任务记录", "clock")}${navItem("settings", "设置", "settings", "mobile-only")}</nav><div class="sidebar-bottom"><nav class="nav" aria-label="其他">${navItem("settings", "连接与设置", "settings")}</nav><div class="sidebar-note"><strong>${icon("shield")} 你的空间，你的灵感</strong>历史记录保存在此浏览器<br>API Key 仅用于当前页面会话</div><div class="version"><span>AGNES STUDIO</span><span>WEB 1.0</span></div></div></aside><div class="shell"><header class="topbar"><div class="crumb"><span class="desktop-only">工作空间</span><span class="desktop-only">/</span><strong>${titles[page]}</strong></div><div class="top-actions"><button class="connection" data-action="settings"><span class="dot ${apiKey ? "ready" : ""}"></span>${apiKey ? "Key 已配置" : "配置 API Key"}</button><span class="avatar">A</span></div></header><main class="main" id="main">${storageWarning ? `<div class="notice">${e(storageWarning)}</div>` : ""}${page === "create" ? renderCreate() : page === "chat" ? renderChat() : page === "settings" ? renderSettings() : renderJobs(page)}</main></div>`;
  bind();
}
function head(title, subtitle, label = "YOUR CREATIVE SPACE") {
  return `<div class="eyebrow"><span></span>${label}</div><div class="page-head"><div><h1>${title}</h1><p>${subtitle}</p></div><span class="pill">由 Agnes 驱动 · 为灵感而造</span></div>`;
}
function select(name, label, values, value) {
  return `<div class="field"><label for="${name}">${label}</label><select id="${name}" name="${name}">${values
    .map((v) => {
      const [val, text] = Array.isArray(v) ? v : [v, v];
      return `<option value="${e(val)}" ${String(val) === String(value) ? "selected" : ""}>${e(text)}</option>`;
    })
    .join("")}</select></div>`;
}
function input(name, label, value, options = "") {
  return `<div class="field"><label for="${name}">${label}</label><input id="${name}" name="${name}" value="${e(value)}" ${options}></div>`;
}
function area(name, label, value, placeholder = "") {
  return `<div class="field"><label for="${name}">${label}</label><textarea id="${name}" name="${name}" placeholder="${e(placeholder)}">${e(value)}</textarea></div>`;
}
function renderCreate() {
  const d = mode === "image" ? imageDraft : videoDraft;
  const latest = jobs.find((j) => j.type === mode && j.status === "succeeded");
  return `${head("让灵感，成为作品。", "从一句想法开始，探索文字、图像与动态影像的可能。")}<div class="workspace"><section class="panel editor"><div class="tabs" role="tablist" aria-label="创作类型"><button role="tab" aria-selected="${mode === "image"}" data-mode="image">${icon("image")}图像创作</button><button role="tab" aria-selected="${mode === "video"}" data-mode="video">${icon("video")}视频创作</button><button role="tab" aria-selected="false" data-action="chat">${icon("chat")}文字对话</button></div><form id="generation-form"><div class="label-row"><label for="prompt">描述你的创意</label><span class="subtle">PROMPT</span></div><div class="prompt-box"><textarea id="prompt" name="prompt" maxlength="20000" required placeholder="${mode === "image" ? "例如：晨光下的一间森林小屋，薄雾环绕，柔和的自然光线，细腻的胶片质感…" : "例如：镜头缓缓穿过晨雾中的森林，阳光洒落在苔藓上，电影感运镜…"}">${e(d.prompt)}</textarea><div class="prompt-footer"><span class="tiny-button">${icon("spark")} 越具体，越接近你的想象</span><span class="subtle" id="prompt-count">${d.prompt.length} / 20,000</span></div></div><div class="section-label">试试这些方向</div><div class="chips">${examples[mode].map((v, i) => `<button type="button" class="chip" data-example="${i}">${v[0]}</button>`).join("")}</div><div class="field-grid">${
    mode === "image"
      ? `${select("model", "生成模型", [["agnes-image-2.5-flash", "Agnes Image 2.5 Flash"]], d.model)}${select("size", "分辨率", ["1K", "2K", "3K", "4K"], d.size)}${select("ratio", "画面比例", ["1:1", "3:4", "4:3", "16:9", "9:16", "2:3", "3:2", "21:9"], d.ratio)}${select(
          "responseFormat",
          "返回格式",
          [
            ["url", "图片链接"],
            ["b64_json", "Base64 图片"],
          ],
          d.responseFormat,
        )}`
      : `${select(
          "model",
          "生成模型",
          [
            ["agnes-video-2.5-flash", "Agnes Video 2.5 Flash"],
            ["agnes-video-2.5", "Agnes Video 2.5（完整）"],
          ],
          d.model,
        )}${select(
          "mode",
          "生成模式",
          [
            ["text", "文生视频"],
            ["keyframe", "首尾帧"],
            ["reference", "参考素材"],
          ],
          d.mode,
        )}${select(
          "seconds",
          "视频时长",
          Array.from({ length: 9 }, (_, i) => [i + 4, `${i + 4} 秒`]),
          d.seconds,
        )}${select("aspectRatio", "画面比例", ["21:9", "16:9", "4:3", "1:1", "3:4", "9:16"], d.aspectRatio)}`
  }</div><details class="details" ${mode === "video" && d.mode !== "text" ? "open" : ""}><summary>参考素材与高级参数</summary>${mode === "image" ? `${area("references", "参考图片 URL（每行一个）", d.references, "https://…\n图片会发送给设置中的 API 服务")}` : `<div class="field-grid">${select("size", "分辨率", d.model === "agnes-video-2.5-flash" ? ["720P"] : ["720P", "960P", "2K"], d.size)}${input("seed", "Seed（可选）", d.seed ?? "", 'type="number" step="1"')}</div>${d.mode === "keyframe" ? `${input("firstFrame", "首帧图片 HTTPS URL", d.firstFrame, 'type="url"')}${input("lastFrame", "尾帧图片 HTTPS URL（可选）", d.lastFrame, 'type="url"')}` : d.mode === "reference" ? `${area("images", "参考图片 URL（最多 5 张，每行一个）", d.images, "https://…")}${area("audios", "参考音频 URL（最多 3 段，每行一个）", d.audios, "https://…")}${d.model === "agnes-video-2.5" ? area("videos", "参考视频 JSON", d.videos, '[{"url":"https://…","startSeconds":0,"requireAudio":false}]') : ""}` : ""}`}${area("extraJson", "高级 JSON（可选）", d.extraJson, "{}")}<p class="storage-note">仅接受公开 HTTPS 素材链接，不会自动上传本地文件至第三方。生成将使用你所配置的 API Key，可能消耗额度。</p></details><button class="primary generate" type="submit" ${submitting ? "disabled" : ""}>${icon("spark")}${submitting ? "正在加入任务…" : mode === "image" ? "开始生成图像" : "开始生成视频"} ${icon("arrow")}</button><p class="form-footnote">提交后可在「任务记录」查看进度 · 请保持页面开启</p></form></section><section class="panel preview" aria-label="生成结果预览"><div class="preview-top"><span>${icon("grid")}画布预览</span><span>${mode === "image" ? "IMAGE" : "VIDEO"} / ${e(mode === "image" ? d.ratio : d.aspectRatio)}</span></div>${latest ? renderPreview(latest) : `<div class="preview-surface"><div class="orbit-art"><div class="orbit-inner">${icon(mode)}</div><span class="star one">✦</span><span class="star two">✧</span></div><h2>给你的想象，一个画面</h2><p>在左侧写下灵感，选择你喜欢的参数<br>你的下一件作品，即将从这里诞生</p></div>`}<div class="preview-bottom"><span>每一次创作，都是新的可能</span><span>POWERED BY AGNES</span></div></section></div><div class="recent-heading"><h2>灵感起点 <span>一点方向，无限可能</span></h2><a href="#library">我的作品 ${icon("arrow")}</a></div><div class="inspirations">${examples[mode].map((v, i) => `<button class="inspiration" data-example="${i}"><span>${icon(v[2])}</span><div><h3>${v[0]}</h3><p>${i === 0 ? "捕捉光线与自然的诗意" : i === 1 ? "让每一个细节都值得注目" : "把脑海中的世界变成画面"}</p></div></button>`).join("")}</div>${footer()}`;
}
function safeUrl(value, image = false) {
  if (typeof value !== "string") return "";
  try {
    if (
      image &&
      /^data:image\/(png|jpeg|webp|gif);base64,[A-Za-z0-9+/=\s]+$/.test(value)
    )
      return value;
    const u = new URL(value);
    return u.protocol === "https:" ? u.href : "";
  } catch {
    return "";
  }
}
function outputUrl(job) {
  const out = job.outputs?.[0];
  if (!out) return "";
  return safeUrl(
    out.url ||
      (out.base64
        ? `data:${out.mimeType || "image/png"};base64,${out.base64}`
        : ""),
    job.type === "image",
  );
}
function renderPreview(job) {
  const url = outputUrl(job);
  return `<div class="preview-surface">${url ? (job.type === "image" ? `<img class="preview-media" src="${e(url)}" alt="${e(job.input.prompt)}" loading="lazy">` : `<video class="preview-media" src="${e(url)}" controls preload="metadata"></video>`) : icon("image")}<p>${e(job.input.prompt)}</p><a class="button" href="#library">查看作品 ${icon("arrow")}</a></div>`;
}
function footer() {
  return `<footer class="footer"><span>以想象开始，以创作表达。</span><a href="https://github.com/zjjxwpstcnsm-gif/agnes-studio" target="_blank" rel="noopener noreferrer">Agnes Studio · 开源创作工作台 ↗</a></footer>`;
}
function renderJobs(page) {
  const library = page === "library";
  let items = jobs.filter((j) => !library || j.status === "succeeded");
  if (filter !== "all")
    items = items.filter((j) =>
      filter === "active"
        ? !finished.has(j.status)
        : filter === "image" || filter === "video"
          ? j.type === filter
          : j.status === filter,
    );
  return `${head(library ? "你的创作，值得收藏。" : "每一个灵感，都有迹可循。", library ? "查看图片与视频结果，在当前浏览器保留你的创作历程。" : "追踪提交与生成进度，了解任务当前所处的每一步。", library ? "YOUR COLLECTION" : "GENERATION HISTORY")}${!library ? `<div class="notice">视频每 30 秒查询一次。关闭页面后不会继续后台运行；重新打开并配置 Key 后，可恢复已取得远端 ID 的查询。停止跟踪不会取消服务端生成或退款。</div>` : ""}<div class="filterbar">${(library
    ? [
        ["all", "全部作品"],
        ["image", "图片"],
        ["video", "视频"],
      ]
    : [
        ["all", "全部记录"],
        ["active", "进行中"],
        ["succeeded", "已完成"],
        ["failed", "失败"],
        ["uncertain", "待确认"],
        ["cancelled", "已停止"],
      ]
  )
    .map(
      ([v, t]) =>
        `<button class="button" data-filter="${v}" aria-pressed="${filter === v}">${t}</button>`,
    )
    .join(
      "",
    )}</div>${items.length ? `<div class="cards">${items.map(renderJob).join("")}</div>` : `<div class="empty">${icon(library ? "folder" : "clock")}<h2>${library ? "让第一件作品在这里落地" : "这里还没有任务"}</h2><p>写下一句灵感，开启你的创作旅程<br>生成结果与任务记录只保存在当前浏览器</p><a class="button" href="#create">开始创作 ${icon("arrow")}</a></div>`}${footer()}`;
}
function renderJob(j) {
  const url = outputUrl(j);
  return `<article class="panel job-card" data-job-id="${e(j.id)}"><div class="job-meta"><span>${j.type === "image" ? "图像" : "视频"} · ${new Date(j.createdAt).toLocaleString("zh-CN", { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" })}</span><span class="status ${e(j.status)}">${statusNames[j.status] || e(j.status)}</span></div>${url ? (j.type === "image" ? `<img class="job-result" src="${e(url)}" alt="${e(j.input.prompt)}" loading="lazy">` : `<video class="job-result" src="${e(url)}" controls preload="metadata"></video>`) : ""}<h3>${e(j.input.prompt)}</h3>${j.progress ? `<p class="status-line">服务端进度：${e(j.progress)}%</p>` : ""}${j.error ? `<p class="job-error">${e(typeof j.error === "string" ? j.error : j.error.message || JSON.stringify(j.error))}</p>` : ""}${j.waitReason ? `<p class="status-line">${e({ host_mismatch: "当前 API 地址与此任务不同；切换回原地址后可继续", missing_key: "请在连接与设置中重新配置 API Key", rate_limit: "已达到本地限流阈值，稍后自动继续" }[j.waitReason] || j.waitReason)}${j.nextRunAt ? " · 下次尝试 " + e(new Date(j.nextRunAt).toLocaleTimeString("zh-CN")) : ""}</p>` : ""}${j.remoteId ? `<p class="status-line">任务 ID：${e(j.remoteId)}</p>` : ""}<details class="details"><summary>查看生成参数</summary><pre>${e(JSON.stringify(j.input, null, 2))}</pre><p>API：${e(j.baseUrl)}</p></details><div class="row-actions"><button class="button" data-job-copy="${j.id}">${icon("copy")}提示词</button><button class="button" data-job-reuse="${j.id}">复用参数</button>${url ? `<a class="button" href="${e(url)}" ${url.startsWith("data:") ? 'download="agnes-image.png"' : 'target="_blank" rel="noopener noreferrer"'}>${icon("external")}打开原图${j.type === "video" ? " / 视频" : ""}</a>` : ""}${!finished.has(j.status) ? `<button class="button" data-job-cancel="${j.id}">停止跟踪</button>` : ""}${j.type === "video" && j.remoteId && j.status !== "succeeded" ? `<button class="button" data-job-refresh="${j.id}">恢复查询</button>` : ""}${finished.has(j.status) ? `<button class="button danger" data-job-delete="${j.id}" aria-label="删除这条任务记录">${icon("trash")}</button>` : ""}</div></article>`;
}
function renderSettings() {
  return `${head("连接灵感的下一步。", "配置你自己的 API 连接，数据与创作由你掌握。", "CONNECTION & PREFERENCES")}<section class="panel settings-card"><h2>API 连接</h2><p>Key 仅保留在当前页面内存中，刷新或关闭页面后需要重新填写。仅向你指定的 API 服务发送请求。</p><div class="notice">GitHub Pages 是静态网站，无法绕过浏览器跨域限制。官方 API 当前的跨域响应未明确允许 Authorization 请求头，直接调用可能被浏览器拦截。请使用你信任且支持 CORS 的 HTTPS 网关，或等待官方修复。不要把 Key 交给不可信代理。</div><form id="settings-form"><div class="field-grid">${input("baseUrl", "API Base URL", settings.baseUrl, 'type="url" required spellcheck="false" autocomplete="off"')}${input("apiKey", "API Key（仅当前页面会话）", apiKey, 'type="password" autocomplete="off" spellcheck="false" placeholder="输入你的 API Key"')}</div><p class="storage-note">保存表示同意将后续请求的 Key、提示词与参考素材 URL 发送至上述 API 地址。不会把 Key 写入本地记录、源码或导出文件。</p><div class="row-actions"><button class="primary" type="submit">${icon("key")}应用连接</button><button class="button" type="button" data-action="forget-key">清除当前 Key</button><button class="button" type="button" data-action="test-connection">检查连接（不生成）</button></div><div class="status-line" id="connection-result" role="status">${apiKey ? "已配置 Key，尚不代表已成功连接 API" : "尚未配置 API Key"}</div></form></section><section class="panel settings-card"><h2>本机数据</h2><p>对话、生成参数与作品链接保存在当前浏览器的 IndexedDB 中，与 Android App 独立。清除浏览器数据会丢失这些记录；原始媒体链接也可能过期，请及时打开并保存作品。</p><div class="row-actions"><button class="button" data-action="export">${icon("down")}导出本机记录</button><button class="button danger" data-action="clear-data">${icon("trash")}清空本机记录</button></div><p class="storage-note">导出不包含 API Key；可能包含私人对话、提示词及素材地址，请妥善保存。网页不提供 Android 级后台任务保证。</p></section>${footer()}`;
}
function renderChat() {
  const c = ensureChat(),
    p = c.parameters;
  return `${head("把想法，说给灵感听。", "用 Agnes 3.0 Flash 梳理思路、探索创意，让对话成为创作的起点。", "THINK • EXPLORE • CREATE")}<div class="chat-layout"><aside class="panel conversations"><button class="button" data-action="new-chat">${icon("plus")}新建对话</button>${conversations.map((item) => `<button class="conversation ${item.id === c.id ? "active" : ""}" data-conversation="${item.id}">${e(item.title)}</button>`).join("")}</aside><section class="panel chat-panel"><div class="chat-toolbar"><span>${e(p.model)} · ${p.enableThinking ? "Thinking 开启" : "Thinking 关闭"}</span><button class="tiny-button" data-action="chat-settings">${icon("settings")}对话参数</button></div>${chatSettingsOpen ? renderChatSettings(p) : ""}<div class="chat-messages" id="chat-messages" aria-live="polite">${c.messages.length ? c.messages.map(renderMessage).join("") : `<div class="chat-welcome">${icon("spark")}<h2>今天，想创造些什么？</h2><p>聊一个大胆的想法，打磨一段文字<br>或者一起寻找新的灵感方向</p></div>`}</div><form class="chat-compose" id="chat-form"><div class="prompt-box"><textarea name="chatPrompt" id="chat-prompt" placeholder="输入你的想法…（Ctrl / ⌘ + Enter 发送）" maxlength="60000" ${chatController && activeChatId === c.id ? "disabled" : ""}>${e(chatDraft)}</textarea></div><details class="details"><summary>添加图片视觉输入</summary>${input("chatImage", "公开 HTTPS 图片 URL（可选）", chatImage, 'type="url" placeholder="https://…"')}</details><div class="row-actions"><span class="storage-note">历史记录仅保存在本机</span>${chatController ? `<button class="button" type="button" data-action="stop-chat">${icon("stop")}停止生成</button>` : `<button class="primary" type="submit">发送 ${icon("arrow")}</button>`}</div></form></section></div>${footer()}`;
}
function renderMessage(m) {
  return `<article class="message ${e(m.role)}" data-message-id="${m.id}"><span class="role">${m.role === "user" ? "你" : m.role === "tool" ? "工具结果" : "AGNES"}</span><span class="message-content">${e(m.displayContent ?? (typeof m.content === "string" ? m.content : ""))}</span>${m.image ? `<p class="storage-note">图片参考：${e(m.image)}</p>` : ""}${m.reasoning ? `<details><summary>思考过程</summary><pre>${e(m.reasoning)}</pre></details>` : ""}${(m.toolCalls || []).map((call) => `<div class="tool-card"><strong>工具请求：${e(call.function?.name || call.name)}</strong><pre>${e(call.function?.arguments || call.arguments)}</pre><p class="storage-note">客户端不会自动执行工具。请自行核对并执行，再填入结果。</p>${!m.toolResults?.includes(call.id) ? `<textarea aria-label="${e(call.function?.name || call.name)} 工具执行结果" data-tool-result="${e(call.id)}" placeholder="填入实际执行结果"></textarea><button class="button" data-tool-submit="${e(call.id)}" data-message="${m.id}">提交结果并继续</button>` : '<span class="muted">已填写结果</span>'}</div>`).join("")}${m.error ? `<p class="error-text">${e(m.error)}</p>` : ""}</article>`;
}
function renderChatSettings(p) {
  return `<form id="chat-settings-form" class="notice"><div class="field-grid">${input("chatModel", "模型", p.model, "required")}${input("maxTokens", "最大输出 Token", p.maxTokens, 'type="number" min="1" max="65536" required')}${input("temperature", "Temperature", p.temperature, 'type="number" min="0" max="2" step="0.1" required')}${input("topP", "Top P", p.topP, 'type="number" min="0" max="1" step="0.05" required')}</div>${area("systemPrompt", "System Prompt", p.systemPrompt)}<div class="row-actions"><label class="check-label"><input type="checkbox" name="enableThinking" ${p.enableThinking ? "checked" : ""}>Thinking</label><label class="check-label"><input type="checkbox" name="stream" ${p.stream ? "checked" : ""}>流式输出</label></div><details class="details"><summary>工具与高级 JSON</summary>${area("toolsJson", "Tools JSON", p.toolsJson)}${input("toolChoice", "Tool Choice", p.toolChoice)}${area("chatExtraJson", "高级 JSON", p.extraJson)}</details><button type="submit" class="button">保存此会话参数</button></form>`;
}

function bind() {
  document
    .querySelectorAll("[data-action]")
    .forEach((el) =>
      el.addEventListener("click", () => action(el.dataset.action)),
    );
  document.querySelectorAll("[data-mode]").forEach((el) =>
    el.addEventListener("click", () => {
      captureGeneration();
      mode = el.dataset.mode;
      render();
    }),
  );
  document.querySelectorAll("[data-example]").forEach((el) =>
    el.addEventListener("click", () => {
      captureGeneration();
      const d = mode === "image" ? imageDraft : videoDraft;
      d.prompt = examples[mode][Number(el.dataset.example)][1];
      render();
      $("#prompt")?.focus();
    }),
  );
  $("#generation-form")?.addEventListener("submit", submitGeneration);
  $("#generation-form")?.addEventListener("input", () => {
    captureGeneration();
    const count = $("#prompt-count");
    if (count) count.textContent = `${$("#prompt").value.length} / 20,000`;
  });
  $("#generation-form")?.addEventListener("change", (evt) => {
    captureGeneration();
    if (["mode", "model"].includes(evt.target.name)) {
      if (mode === "video" && videoDraft.model === "agnes-video-2.5-flash")
        videoDraft.size = "720P";
      render();
    }
  });
  $("#settings-form")?.addEventListener("submit", saveSettings);
  document.querySelectorAll("[data-filter]").forEach((el) =>
    el.addEventListener("click", () => {
      filter = el.dataset.filter;
      render();
    }),
  );
  for (const [attr, fn] of [
    ["jobCopy", (j) => copy(j.input.prompt)],
    ["jobReuse", reuseJob],
    [
      "jobCancel",
      async (j) => {
        await queue.cancel(j.id);
        toast("已停止本地跟踪；服务端任务可能继续运行");
      },
    ],
    [
      "jobRefresh",
      async (j) => {
        await queue.refresh(j.id);
        toast("已安排查询，遵守至少 30 秒间隔");
      },
    ],
    [
      "jobDelete",
      async (j) => {
        if (confirm("删除这条本机任务记录？已生成的远端内容不会删除。"))
          await queue.remove(j.id);
      },
    ],
  ])
    document
      .querySelectorAll(
        `[data-${attr.replace(/[A-Z]/g, (x) => "-" + x.toLowerCase())}]`,
      )
      .forEach((el) =>
        el.addEventListener("click", () => {
          const j = jobs.find((j) => j.id === el.dataset[attr]);
          Promise.resolve(fn(j)).catch((err) => toast(err.message));
        }),
      );
  document.querySelectorAll("[data-conversation]").forEach((el) =>
    el.addEventListener("click", async () => {
      currentChatId = el.dataset.conversation;
      chatDraft = "";
      chatImage = "";
      // Commit the selected conversation before showing it, including immediate reloads.
      try {
        await persist();
        render();
      } catch { /* persist already reports the storage error */ }
    }),
  );
  $("#chat-form")?.addEventListener("submit", sendChat);
  $("#chat-prompt")?.addEventListener("input", (ev) => {
    chatDraft = ev.target.value;
  });
  $("#chat-prompt")?.addEventListener("keydown", (ev) => {
    if (ev.key === "Enter" && (ev.ctrlKey || ev.metaKey)) {
      ev.preventDefault();
      $("#chat-form").requestSubmit();
    }
  });
  $("#chatImage")?.addEventListener(
    "input",
    (ev) => (chatImage = ev.target.value),
  );
  $("#chat-settings-form")?.addEventListener("submit", saveChatSettings);
  document
    .querySelectorAll("[data-tool-submit]")
    .forEach((el) => el.addEventListener("click", () => submitTool(el)));
}
function captureGeneration() {
  const form = $("#generation-form");
  if (!form) return;
  const d = mode === "image" ? imageDraft : videoDraft;
  Object.assign(d, Object.fromEntries(new FormData(form)));
}
const lines = (text) =>
  String(text || "")
    .split(/\r?\n/)
    .map((x) => x.trim())
    .filter(Boolean);
async function submitGeneration(ev) {
  ev.preventDefault();
  if (submitting) return;
  captureGeneration();
  if (!apiKey) {
    toast("请先在连接与设置中配置 API Key");
    location.hash = "settings";
    return;
  }
  const d = clone(mode === "image" ? imageDraft : videoDraft);
  try {
    const inp =
      mode === "image"
        ? { ...d, references: lines(d.references) }
        : {
            ...d,
            seconds: Number(d.seconds),
            seed: d.seed === "" ? null : d.seed,
            images: lines(d.images),
            audios: lines(d.audios),
            videos: JSON.parse(d.videos || "[]"),
          };
    if (mode === "image") api.buildImagePayload(inp);
    else api.buildVideoPayload(inp);
    submitting = true;
    $("#generation-form button[type=submit]").disabled = true;
    await queue.add({ type: mode, input: inp, baseUrl: settings.baseUrl });
    toast("已加入任务记录，请保持页面开启");
    filter = "all";
    location.hash = "queue";
  } catch (err) {
    toast(err.message);
  } finally {
    submitting = false;
    if (route() === "create") render();
  }
}
async function saveSettings(ev) {
  ev.preventDefault();
  const form = new FormData(ev.target);
  try {
    const base = api.normalizeBaseUrl(form.get("baseUrl"));
    if (
      apiKey &&
      base !== settings.baseUrl &&
      !confirm(
        `切换 API 地址后，请求内容和 Key 将发送至 ${new URL(base).host}。确定信任此服务并继续吗？`,
      )
    )
      return;
    settings.baseUrl = base;
    apiKey = String(form.get("apiKey")).trim();
    await persist();
    await queue.refresh();
    render();
    toast("连接已应用；Key 仅保留在当前页面");
  } catch (err) {
    toast(err.message);
  }
}
async function testConnection() {
  const result = $("#connection-result");
  if (!apiKey) {
    result.textContent = "请先点击「应用连接」配置 API Key";
    return;
  }
  result.textContent = "正在检查 /v1/models（不会提交生成任务）…";
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 15000);
  try {
    await api.requestJson({
      baseUrl: settings.baseUrl,
      apiKey,
      path: "/v1/models",
      signal: controller.signal,
    });
    result.textContent =
      "连接成功：API 接受了浏览器请求。此检查不验证生成权限或剩余额度";
  } catch (err) {
    result.textContent = `检查未通过：${err.message}。此接口也可能未被网关实现。`;
  } finally {
    clearTimeout(timer);
  }
}
function reuseJob(j) {
  mode = j.type;
  const inp = clone(j.input);
  if (mode === "image")
    imageDraft = { ...inp, references: (inp.references || []).join("\n") };
  else
    videoDraft = {
      ...inp,
      images: (inp.images || []).join("\n"),
      audios: (inp.audios || []).join("\n"),
      videos: JSON.stringify(inp.videos || []),
    };
  location.hash = "create";
  render();
  toast("已载入原参数，检查后可再次提交（可能再次计费）");
}
async function copy(text) {
  try {
    await navigator.clipboard.writeText(text);
    toast("提示词已复制");
  } catch {
    toast("浏览器禁止剪贴板访问，请从生成参数中手动复制");
  }
}
async function saveChatSettings(ev) {
  ev.preventDefault();
  const form = new FormData(ev.target),
    p = {
      model: form.get("chatModel"),
      systemPrompt: form.get("systemPrompt"),
      temperature: Number(form.get("temperature")),
      topP: Number(form.get("topP")),
      maxTokens: Number(form.get("maxTokens")),
      stream: form.has("stream"),
      enableThinking: form.has("enableThinking"),
      toolsJson: form.get("toolsJson"),
      toolChoice: form.get("toolChoice"),
      extraJson: form.get("chatExtraJson"),
    };
  try {
    assertNoCredentials(p);
    api.buildChatPayload({
      messages: [{ role: "user", content: "validation" }],
      ...p,
    });
    currentChat().parameters = p;
    settings.chat = clone(p);
    await persist();
    chatSettingsOpen = false;
    render();
    toast("会话参数已保存");
  } catch (err) {
    toast(err.message);
  }
}
function toApiMessages(messages) {
  return messages
    .filter(
      (m) => !(m.role === "assistant" && !m.content && !m.toolCalls?.length),
    )
    .map((m) => ({
      role: m.role,
      content:
        m.role === "user" && m.image
          ? [
              { type: "text", text: m.content },
              { type: "image_url", image_url: { url: m.image } },
            ]
          : m.content,
      ...(m.toolCalls?.length
        ? {
            tool_calls: m.toolCalls.map((c) => ({
              id: c.id,
              type: "function",
              function: {
                name: c.function?.name || c.name,
                arguments: c.function?.arguments || c.arguments || "",
              },
            })),
          }
        : {}),
      ...(m.toolCallId ? { tool_call_id: m.toolCallId } : {}),
    }));
}
async function sendChat(ev, continuation = false) {
  ev?.preventDefault();
  if (chatController) return;
  if (!apiKey) {
    toast("请先配置 API Key");
    location.hash = "settings";
    return;
  }
  const c = ensureChat();
  const text = chatDraft.trim();
  if (!continuation && !text) {
    toast("写下一点想法，再发送吧");
    return;
  }
  try {
    if (chatImage && !safeUrl(chatImage))
      throw new Error("图片需要公开 HTTPS URL");
    if (!continuation) {
      if (
        c.messages.some((m) =>
          m.toolCalls?.some((call) => !m.toolResults?.includes(call.id)),
        )
      )
        throw new Error("请先填写未完成的工具结果，再继续对话");
      c.messages.push({
        id: id(),
        role: "user",
        content: text,
        image: chatImage.trim(),
      });
      if (c.messages.length === 1) c.title = text.slice(0, 22);
      chatDraft = "";
      chatImage = "";
    }
    const payload = api.buildChatPayload({
      messages: toApiMessages(c.messages),
      ...c.parameters,
    });
    const answer = {
      id: id(),
      role: "assistant",
      content: "",
      reasoning: "",
      toolCalls: [],
    };
    c.messages.push(answer);
    chatController = new AbortController();
    activeChatId = c.id;
    render();
    await persist();
    const result = await api.streamChat({
      baseUrl: settings.baseUrl,
      apiKey,
      payload,
      signal: chatController.signal,
      onDelta: (delta) => {
        if (delta.type === "text") answer.content += delta.text || "";
        if (delta.type === "reasoning") answer.reasoning += delta.text || "";
        if (delta.type === "tool") {
          const call = delta.call,
            idx = answer.toolCalls.findIndex((t) => t.index === call.index);
          if (idx < 0) answer.toolCalls.push(call);
          else answer.toolCalls[idx] = call;
        }
        updateStreamingMessage(answer, c.id);
        persistSoon();
      },
    });
    answer.content = result.content;
    answer.reasoning = result.reasoning;
    answer.toolCalls = result.toolCalls || [];
    answer.usage = result.usage;
  } catch (err) {
    const last = c.messages.at(-1);
    if (last?.role === "assistant") {
      last.error =
        err.name === "AbortError" || err.kind === "cancelled"
          ? "已停止生成，已接收内容已保留"
          : err.message;
    } else toast(err.message);
  } finally {
    chatController = null;
    activeChatId = null;
    await persist();
    if (route() === "chat") render();
  }
}
function updateStreamingMessage(answer, conversationId) {
  if (route() !== "chat" || currentChatId !== conversationId) return;
  const box = $("#chat-messages");
  const bottom = box.scrollHeight - box.scrollTop - box.clientHeight < 90;
  const old = document.querySelector(`[data-message-id="${answer.id}"]`);
  if (old) old.outerHTML = renderMessage(answer);
  if (bottom) box.scrollTop = box.scrollHeight;
}
async function submitTool(el) {
  if (chatController) {
    toast("请等待当前回复完成");
    return;
  }
  const c = currentChat(),
    m = c.messages.find((m) => m.id === el.dataset.message),
    call = m?.toolCalls.find((call) => call.id === el.dataset.toolSubmit);
  const field = document.querySelector(
    `[data-tool-result="${CSS.escape(el.dataset.toolSubmit)}"]`,
  );
  if (!call || !field?.value.trim()) {
    toast("请填写真实的工具执行结果");
    return;
  }
  c.messages.push({
    id: id(),
    role: "tool",
    content: field.value.trim(),
    toolCallId: call.id,
  });
  m.toolResults = [...(m.toolResults || []), call.id];
  await persist();
  if (
    c.messages.some((m) =>
      m.toolCalls?.some((call) => !m.toolResults?.includes(call.id)),
    )
  ) {
    render();
    toast("结果已保存，请继续填写其余工具结果");
  } else await sendChat(null, true);
}
async function action(name) {
  try {
    if (name === "settings" || name === "chat") {
      location.hash = name;
      return;
    }
    if (name === "forget-key") {
      apiKey = "";
      await queue.refresh();
      render();
      toast("已清除内存中的 Key");
    }
    if (name === "test-connection") await testConnection();
    if (name === "new-chat") {
      currentChatId = null;
      ensureChat();
      chatDraft = "";
      chatImage = "";
      await persist();
      render();
    }
    if (name === "chat-settings") {
      chatSettingsOpen = !chatSettingsOpen;
      render();
    }
    if (name === "stop-chat") chatController?.abort();
    if (name === "export") {
      const data = {
        version: 1,
        exportedAt: new Date().toISOString(),
        settings,
        conversations,
        jobs,
      };
      const blob = new Blob([JSON.stringify(storage.sanitize(data), null, 2)], {
        type: "application/json",
      });
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = `agnes-studio-${new Date().toISOString().slice(0, 10)}.json`;
      link.click();
      setTimeout(() => URL.revokeObjectURL(url), 10000);
      toast("已导出本机记录（不含 API Key）");
    }
    if (name === "clear-data") {
      if (chatController || jobs.some((j) => !finished.has(j.status))) {
        toast("请先停止生成与所有任务跟踪，再清空记录");
        return;
      }
      if (
        !confirm(
          "永久删除当前浏览器中的全部对话和任务记录？此操作无法撤销，请先导出。",
        )
      )
        return;
      for (const j of jobs) await queue.remove(j.id);
      conversations = [];
      currentChatId = null;
      await persist();
      render();
      toast("本机记录已清空");
    }
  } catch (err) {
    toast(err.message);
  }
}
window.addEventListener("hashchange", () => {
  filter = "all";
  render();
});
window.addEventListener("beforeunload", (ev) => {
  if (
    chatController ||
    jobs.some((j) => ["sending", "processing"].includes(j.status))
  ) {
    ev.preventDefault();
    ev.returnValue = "";
  }
});
async function init() {
  await storage.init();
  storageWarning = storage.warning || "";
  const saved = await storage.get("settings");
  if (saved)
    settings = {
      ...settings,
      ...saved,
      chat: { ...settings.chat, ...saved.chat },
      image: { ...settings.image, ...saved.image },
      video: { ...settings.video, ...saved.video },
    };
  conversations = (await storage.get("conversations")) || [];
  currentChatId = await storage.get("currentChatId");
  imageDraft = { ...imageDraft, ...settings.image };
  videoDraft = { ...videoDraft, ...settings.video };
  queue = createQueue({
    storage,
    api,
    getSettings: () => ({ baseUrl: settings.baseUrl, apiKey }),
    getApiKey: () => apiKey,
    onChange: (items, meta) => {
      const signature = (list) => JSON.stringify(list
        .filter((job) => route() !== "library" || job.status === "succeeded")
        .map((job) => [job.id, job.status, job.updatedAt, job.progress,
          job.nextRunAt, job.error, job.waitReason, job.remoteId]));
      const previous = signature(jobs);
      const previousWarning = storageWarning;
      jobs = [...items].sort((a, b) => b.createdAt - a.createdAt);
      storageWarning = meta?.warning || storage.warning || "";
      // Idle scheduler ticks must not reset media players or close inspected parameters.
      if (["library", "queue"].includes(route()) &&
          (previous !== signature(jobs) || previousWarning !== storageWarning)) {
        const openJobs = [...document.querySelectorAll(".job-card details[open]")]
          .map((details) => details.closest(".job-card").dataset.jobId);
        render();
        for (const jobId of openJobs) {
          const details = document.querySelector(`[data-job-id="${CSS.escape(jobId)}"] details`);
          if (details) details.open = true;
        }
      }
    },
  });
  await queue.init();
  jobs = queue.list().sort((a, b) => b.createdAt - a.createdAt);
  render();
}
init().catch((err) => {
  document.getElementById("app").textContent =
    `页面初始化失败：${err.message}。请检查浏览器是否允许本机存储。`;
});
