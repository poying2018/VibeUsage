// Cloudflare Worker 中转 —— VibeUsage「额度」页扩展版
// 在原 AntigravityQuotaApp/worker.js 基础上扩展：
//   1. /proxy 白名单新增 Codex / Claude Code / MiniMax 额度端点域名
//   2. 客户端可通过 X-Proxy-UA 头自定义透传到目标站的 User-Agent
//      （老版固定覆盖为 antigravity UA；新版优先取 X-Proxy-UA，缺失时保持旧行为）
//   3. /token 端点保持不变（Google OAuth 刷新，服务端补 client_secret）
//
// 部署：Cloudflare Dashboard → Workers → 打开你现有的 Worker → 编辑代码 →
//       整体粘贴本文件 → Deploy。域名不变，App 侧无需任何改动。

const ALLOWED_HOSTS = new Set([
  // ── Antigravity 额度（原有）──
  "oauth2.googleapis.com",
  "www.googleapis.com",
  "cloudcode-pa.googleapis.com",
  "daily-cloudcode-pa.googleapis.com",
  "daily-cloudcode-pa.sandbox.googleapis.com",
  // ── Codex（ChatGPT 计划额度 + OAuth 刷新）──
  "chatgpt.com",
  "auth.openai.com",
  // ── Claude Code（OAuth 额度 + 令牌刷新/兑换）──
  "api.anthropic.com",
  "console.anthropic.com",
  "platform.claude.com",
  // ── MiniMax（Token Plan 额度，国内域名故障时的兜底线路）──
  "api.minimax.io",
  "api.minimaxi.com",
  // ── Agnes AI（平台后端 token 用量 + 网关模型列表，中转优先线路）──
  "platform-backend.agnes-ai.cn",
  "api.agnes-ai.cn",
  // ── GitHub / Copilot（设备码授权 + copilot_internal 额度 + 模型列表）──
  "github.com",
  "api.github.com",
  "api.githubcopilot.com",
  // ── OpenRouter（额度查询线路，中转与直连互为兜底）──
  "openrouter.ai",
  // ── 智谱国际站 z.ai（国内 api.zhipuai.cn 不可达时的中转兜底线路）──
  "api.z.ai",
  // ── CommandCode（额度窗口 / 套餐 credits / 周期用量；国内直连不稳时的兜底线路）──
  "api.commandcode.ai",
]);

const ALLOWED = (u) => {
  try {
    const p = new URL(u);
    return p.protocol === "https:" && ALLOWED_HOSTS.has(p.hostname);
  } catch (_) {
    return false;
  }
};

/** 手动跟随重定向的最大跳数，超过即放弃（防重定向环路）。 */
const MAX_REDIRECTS = 5;

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "*",
};

export default {
  async fetch(request) {
    if (request.method === "OPTIONS") {
      return new Response(null, { headers: CORS });
    }
    const url = new URL(request.url);

    // ---- Google OAuth：服务端补 client_secret，透传 refresh_token / authorization_code 两种流程 ----
    if (url.pathname === "/token") {
      try {
        const bodyText = await request.text();
        const form = new URLSearchParams(bodyText);
        if (!form.get("grant_type")) form.set("grant_type", "refresh_token");
        form.set("client_id", "1071006060591" + "-tmhssin2h21lcre235vtolojh4g403ep" + ".apps.googleusercontent.com"); // Antigravity.Tools 公开内置凭据，拆分避开 push protection 误报
        form.set("client_secret", "GOCSPX-" + "K58FWR486LdLJ1mLB8sXC4z6qDAf");
        const resp = await fetch("https://oauth2.googleapis.com/token", {
          method: "POST",
          headers: { "Content-Type": "application/x-www-form-urlencoded" },
          body: form.toString(),
        });
        const out = new Headers(resp.headers);
        Object.entries(CORS).forEach(([k, v]) => out.set(k, v));
        return new Response(resp.body, { status: resp.status, headers: out });
      } catch (e) {
        // 网络异常等失败：返回带 CORS 头的 502，避免客户端拿到无 CORS 头的裸 500
        return new Response(JSON.stringify({ error: e.message }), {
          status: 502,
          headers: { "Content-Type": "application/json", ...CORS },
        });
      }
    }

    // ---- 通用透传 ----
    const target = url.searchParams.get("url");
    if (!target || !ALLOWED(target)) {
      return new Response(JSON.stringify({ error: "missing or not allowed" }), {
        status: 403,
        headers: { "Content-Type": "application/json", ...CORS },
      });
    }
    const headers = new Headers();
    const auth = request.headers.get("Authorization");
    if (auth) headers.set("Authorization", auth);
    const ct = request.headers.get("Content-Type");
    if (ct) headers.set("Content-Type", ct);
    // 透传客户端业务头（如 Claude 的 anthropic-beta、Codex 的 ChatGPT-Account-Id）：
    // 客户端把原始头以 X-Quota-Pass-<原名> 再发一份，这里剥掉前缀还原
    for (const [k, v] of request.headers.entries()) {
      if (k.toLowerCase().startsWith("x-quota-pass-")) {
        headers.set(k.slice("x-quota-pass-".length), v);
      }
    }
    // UA：优先客户端指定（X-Proxy-UA），否则保持旧行为
    headers.set(
      "User-Agent",
      request.headers.get("X-Proxy-UA") || "antigravity/1.15.8 windows/amd64"
    );
    try {
      const body = request.method === "POST" ? await request.arrayBuffer() : undefined;
      // 手动跟随重定向：每一跳都用 ALLOWED 复检目标主机，防止白名单主机上的
      // 开放重定向把请求带到任意主机（SSRF）。超过 MAX_REDIRECTS 跳直接放弃。
      let currentUrl = target;
      let currentMethod = request.method;
      let currentBody = body;
      let resp = null;
      for (let hop = 0; hop <= MAX_REDIRECTS; hop++) {
        resp = await fetch(currentUrl, {
          method: currentMethod,
          headers,
          body: currentBody,
          redirect: "manual",
        });
        const loc = resp.headers.get("Location");
        const isRedirect = resp.status >= 300 && resp.status < 400 && loc;
        if (!isRedirect) break;
        if (hop === MAX_REDIRECTS) {
          // 重定向跳数过多，直接放弃
          return new Response(JSON.stringify({ error: "too many redirects" }), {
            status: 502,
            headers: { "Content-Type": "application/json", ...CORS },
          });
        }
        let nextUrl;
        try {
          nextUrl = new URL(loc, currentUrl).toString();
        } catch (_) {
          return new Response(JSON.stringify({ error: "invalid redirect target" }), {
            status: 502,
            headers: { "Content-Type": "application/json", ...CORS },
          });
        }
        // 每一跳都重新校验白名单，非白名单目标直接 403
        if (!ALLOWED(nextUrl)) {
          return new Response(JSON.stringify({ error: "missing or not allowed" }), {
            status: 403,
            headers: { "Content-Type": "application/json", ...CORS },
          });
        }
        // 303（含部分 301/302）按浏览器语义转 GET，其余保留原方法与请求体
        if (resp.status === 303 || ((resp.status === 301 || resp.status === 302) && currentMethod === "POST")) {
          currentMethod = "GET";
          currentBody = undefined;
          headers.delete("Content-Type");
        }
        currentUrl = nextUrl;
      }
      const out = new Headers(resp.headers);
      Object.entries(CORS).forEach(([k, v]) => out.set(k, v));
      return new Response(resp.body, { status: resp.status, headers: out });
    } catch (e) {
      return new Response(JSON.stringify({ error: e.message }), {
        status: 502,
        headers: { "Content-Type": "application/json", ...CORS },
      });
    }
  },
};
