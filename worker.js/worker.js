const MODEL = "@cf/qwen/qwen3-30b-a3b-fp8";

function corsHeaders(origin) {
  const allowed = [
    "https://appassets.androidplatform.net",
    "https://digital-assistant-ai.workers.dev"
  ];

  return {
    "Access-Control-Allow-Origin": allowed.includes(origin) ? origin : "https://appassets.androidplatform.net",
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type",
    "Content-Type": "application/json; charset=utf-8"
  };
}

function json(data, status, origin) {
  return new Response(JSON.stringify(data), {
    status: status || 200,
    headers: corsHeaders(origin)
  });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const origin = request.headers.get("Origin") || "";

    if (request.method === "OPTIONS") {
      return new Response(null, {
        status: 204,
        headers: corsHeaders(origin)
      });
    }

    if (url.pathname === "/api/health" && request.method === "GET") {
      return json({
        ok: true,
        model: MODEL,
        speech: "android-browser",
        paidTts: false
      }, 200, origin);
    }

    if (url.pathname === "/api/chat" && request.method === "POST") {
      try {
        const body = await request.json();

        const messages = Array.isArray(body.messages)
          ? body.messages
              .filter(m =>
                m &&
                ["user", "assistant"].includes(m.role) &&
                typeof m.content === "string"
              )
              .slice(-20)
              .map(m => ({
                role: m.role,
                content: m.content.slice(0, 6000)
              }))
          : [];

        if (!messages.length) {
          return json({ error: "Сообщение не найдено" }, 400, origin);
        }

        const system = typeof body.system === "string"
          ? body.system.slice(0, 5000)
          : "Ты дружелюбный голосовой ИИ-помощник. Отвечай по-русски, понятно и естественно.";

        const result = await env.AI.run(MODEL, {
          messages: [
            { role: "system", content: system },
            ...messages
          ],
          max_tokens: 500,
          temperature: 0.7,
          top_p: 0.85
        });

        const reply =
          result?.response ||
          result?.result?.response ||
          result?.output?.[0]?.content?.[0]?.text;

        if (!reply) {
          return json({
            error: "ИИ не вернул текст ответа",
            details: result
          }, 502, origin);
        }

        return json({ reply }, 200, origin);
      } catch (error) {
        return json({
          error: "Ошибка сервера ИИ",
          details: String(error?.message || error)
        }, 500, origin);
      }
    }

    if (url.pathname === "/api/tts" && request.method === "POST") {
      return json({
        error: "Используется системная озвучка Android",
        paidTts: false
      }, 501, origin);
    }

    if (env.ASSETS) {
      return env.ASSETS.fetch(request);
    }

    return json({ error: "Not found" }, 404, origin);
  }
};
