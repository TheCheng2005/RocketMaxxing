// Diagnostics for scripts/test_rocketmaxxing.py: the state of each page in RocketMaxxing's window, read through
// Chromium's DevTools (the app started with -Drocketmaxxing.chromium.args=--remote-debugging-port=PORT).
//   node scripts/devtools_pages.mjs PORT
const port = process.argv[2];
const pages = (await (await fetch(`http://127.0.0.1:${port}/json`)).json()).filter((t) => t.type === "page");
const expr = `({href: location.href, visible: document.visibilityState, focus: document.hasFocus(),
  header: document.querySelector("header")?.innerText.replace(/\\s+/g, " ").slice(0, 120) ?? null,
  loading: !!document.querySelector(".loading"), alive: performance.now() | 0})`;
for (const p of pages) {
  const ws = new WebSocket(p.webSocketDebuggerUrl);
  const state = await new Promise((ok) => {
    const t = setTimeout(() => ok("no answer in 5 s"), 5000);
    ws.onerror = () => ok("cannot connect");
    ws.onopen = () => ws.send(JSON.stringify({ id: 1, method: "Runtime.evaluate", params: { expression: expr, returnByValue: true } }));
    ws.onmessage = (m) => { clearTimeout(t); const r = JSON.parse(m.data); ok(r.result?.result?.value ?? r); };
  });
  ws.close();
  console.log(p.id.slice(0, 8), JSON.stringify(state));
}
