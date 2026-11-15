<script lang="ts">
  import { tick } from "svelte";
  import { X, SquarePen, Send, Square, Check, CircleAlert, Settings2, Bot } from "@lucide/svelte";
  import { assistant, send, stop, reset, who, missing, MODEL_NAMES, CLAUDE_CODE_MODELS, PROVIDER_NAMES } from "./assistant.svelte";
  import AssistantSetup from "./AssistantSetup.svelte";
  import { markdown } from "./text";
  import RocketLoader from "../RocketLoader.svelte";

  // Claude beside the design: what the team asks, what Claude answers, and each tool it used (open one to see what it
  // sent and got back). Changes it makes show in the app at once and undo like any other edit.
  let text = $state("");
  // Setup shows while something is missing and stays until Start (so the team sees it worked), and again from the
  // header's connection button.
  let setup = $state(false);
  let need = $derived(missing());
  $effect(() => {
    if (need && assistant.settings) setup = true;
  });
  let showSetup = $derived(setup || !!need);
  let list: HTMLDivElement | undefined = $state();

  const SUGGESTIONS = [
    "Does the design pass the rules? Fix what fails.",
    "Size the main parachute for 20 ft/s.",
    "Which motor gets us closest to 10,000 ft?",
    "Will the fins flutter?",
  ];

  /** How it is connected, under the name: the plan or the key, and the model. */
  let via = $derived.by(() => {
    const s = assistant.settings;
    if (!s || !s.enabled) return "Not set up yet";
    if (s.provider === "claude-code") return `Claude Code, your Claude plan · ${CLAUDE_CODE_MODELS[s.claudeCodeModel] ?? s.claudeCodeModel}`;
    if (s.provider === "codex") return `Codex, your ChatGPT plan${s.codexModel ? " · " + s.codexModel : ""}`;
    return `${PROVIDER_NAMES.api} · ${MODEL_NAMES[s.model] ?? s.model}`;
  });

  $effect(() => {
    assistant.events.length;
    tick().then(() => list?.scrollTo({ top: list.scrollHeight, behavior: "smooth" }));
  });

  function submit() {
    const t = text.trim();
    if (!t || assistant.running) return;
    text = "";
    send(t);
  }

  /** A tool's result, indented when it is whole JSON (long ones arrive cut and stay as they are). */
  function pretty(r: string): string {
    try {
      return JSON.stringify(JSON.parse(r), null, 1);
    } catch {
      return r;
    }
  }

  function key(e: KeyboardEvent) {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      submit();
    }
  }
</script>

<aside class="drawer" aria-label="Assistant">
  <header>
    <div class="who">
      <b>{assistant.settings?.enabled ? who() : "AI assistant"}</b>
      <span class="muted">{via}</span>
    </div>
    {#if !need}
      <button class="tool" class:on={setup} title="How the assistant connects" aria-pressed={setup} onclick={() => (setup = !setup)}><Settings2 size={16} /></button>
    {/if}
    <button class="tool" title="New conversation" disabled={assistant.running || showSetup} onclick={reset}><SquarePen size={16} /></button>
    <button class="tool" title="Close" onclick={() => (assistant.open = false)}><X size={16} /></button>
  </header>

  {#if showSetup}
  <div class="setup">
    <div class="hello">
      <span class="bot"><Bot size={22} /></span>
      <div>
        <b>Ask an AI about your rocket</b>
        <p>
          It works through RocketMaxxing's own tools: it runs simulations, the rule check, recovery and structures
          analyses, and can change the design (every change can be undone). Choose how it connects:
        </p>
      </div>
    </div>
    <AssistantSetup />
    {#if need}
      <p class="todo"><b>Still needed:</b> {need}</p>
    {:else}
      <button class="btn primary start" onclick={() => (setup = false)}><Bot size={15} /> Start asking {who()}</button>
    {/if}
  </div>
  {:else}
  <div class="list" bind:this={list}>
    {#if !assistant.events.length}
      <p class="muted intro">
        Ask about the open design. {who()} uses the same tools as the app (simulations, rule checks, recovery,
        structures) and can change the design; every change can be undone.
      </p>
      <div class="ideas">
        {#each SUGGESTIONS as s (s)}<button class="idea" onclick={() => (text = s)}>{s}</button>{/each}
      </div>
    {/if}
    {#each assistant.events as e, i (i)}
      {#if e.kind === "you"}
        <div class="you">{e.text}</div>
      {:else if e.kind === "claude"}
        <div class="claude">{@html markdown(e.text ?? "")}</div>
      {:else if e.kind === "tool"}
        <details class="tool-call" class:bad={!e.ok}>
          <summary>
            {#if e.ok}<Check size={13} />{:else}<CircleAlert size={13} />{/if}
            <span>{e.title ?? e.tool}</span>
            {#if e.changed}<span class="changed">changed the design</span>{/if}
          </summary>
          <div class="io"><b>{e.tool}</b> {e.input}</div>
          {#if e.result}<pre>{pretty(e.result)}</pre>{/if}
          {#each e.images ?? [] as src, j (j)}<img {src} alt="Result of {e.tool}" />{/each}
        </details>
      {:else}
        <div class="note" class:error={e.kind === "error"}>{e.text}</div>
      {/if}
    {/each}
    {#if assistant.running}<div class="working"><RocketLoader size="small" /></div>{/if}
  </div>

  <footer>
    <textarea rows="3" bind:value={text} onkeydown={key} placeholder={`Ask ${who()} about this design (Enter to send)`}></textarea>
    {#if assistant.running}
      <button class="btn" onclick={stop}><Square size={14} /> Stop</button>
    {:else}
      <button class="btn primary" disabled={!text.trim()} onclick={submit}><Send size={14} /> Send</button>
    {/if}
  </footer>
  {/if}
</aside>

<style>
  .setup {
    flex: 1;
    min-height: 0;
    overflow: auto;
    padding: 12px;
  }
  .hello {
    display: flex;
    gap: 10px;
    margin-bottom: 10px;
  }
  .hello p {
    margin: 4px 0 0;
    font-size: calc(12.5px * var(--text));
    color: var(--ink-2);
  }
  .bot {
    flex: none;
    display: grid;
    place-items: center;
    width: 38px;
    height: 38px;
    border-radius: 50%;
    background: var(--select-soft);
    color: var(--select);
  }
  .todo {
    margin: 10px 0 0;
    padding: 6px 10px;
    border-left: 3px solid var(--warn);
    border-radius: var(--radius);
    background: var(--warn-soft);
    font-size: calc(12.5px * var(--text));
  }
  .start {
    margin-top: 10px;
    width: 100%;
    justify-content: center;
  }
  .tool.on {
    color: var(--select);
  }
  .drawer {
    width: 400px;
    flex: none;
    display: flex;
    flex-direction: column;
    border-left: 1px solid var(--line);
    background: var(--surface);
    min-height: 0;
  }
  header {
    display: flex;
    align-items: center;
    gap: 4px;
    padding: 6px 6px 6px 12px;
    border-bottom: 1px solid var(--line);
  }
  .who {
    flex: 1;
    display: flex;
    flex-direction: column;
    line-height: 1.25;
    min-width: 0;
  }
  .who span {
    font-size: calc(11.5px * var(--text));
  }
  .tool {
    display: grid;
    place-items: center;
    width: 28px;
    height: 28px;
    border: 0;
    border-radius: 4px;
    background: transparent;
    color: var(--ink-2);
  }
  .tool:hover:not(:disabled) {
    background: var(--surface-3);
  }
  .list {
    flex: 1;
    overflow: auto;
    padding: 10px 12px;
    display: flex;
    flex-direction: column;
    gap: 8px;
  }
  .intro {
    margin: 0;
    font-size: calc(13px * var(--text));
  }
  .ideas {
    display: flex;
    flex-direction: column;
    gap: 6px;
  }
  .idea {
    text-align: left;
    border: 1px solid var(--line);
    border-radius: var(--radius);
    background: var(--surface-2);
    padding: 6px 8px;
    font-size: calc(12.5px * var(--text));
    color: var(--ink-2);
  }
  .idea:hover {
    border-color: var(--select);
    color: var(--ink);
  }
  .you {
    align-self: flex-end;
    max-width: 88%;
    background: var(--select);
    color: #fff;
    padding: 6px 10px;
    border-radius: 10px 10px 2px 10px;
    white-space: pre-wrap;
    font-size: calc(13px * var(--text));
  }
  .claude {
    font-size: calc(13px * var(--text));
    line-height: 1.45;
  }
  .claude :global(p) {
    margin: 0 0 6px;
  }
  .claude :global(p.h) {
    font-weight: 600;
  }
  .claude :global(ul),
  .claude :global(ol) {
    margin: 0 0 6px;
    padding-left: 20px;
  }
  .claude :global(code) {
    background: var(--surface-3);
    padding: 0 3px;
    border-radius: 3px;
    font-size: calc(12px * var(--text));
  }
  .claude :global(table) {
    border-collapse: collapse;
    margin: 4px 0 8px;
    font-size: calc(12px * var(--text));
  }
  .claude :global(th),
  .claude :global(td) {
    border: 1px solid var(--line);
    padding: 2px 6px;
    text-align: left;
  }
  .tool-call {
    font-size: calc(12px * var(--text));
    border: 1px solid var(--line);
    border-radius: var(--radius);
    background: var(--surface-2);
  }
  .tool-call summary {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 4px 8px;
    cursor: pointer;
    color: var(--ink-2);
  }
  .tool-call summary :global(svg) {
    color: var(--good);
  }
  .tool-call.bad summary :global(svg) {
    color: var(--bad);
  }
  .changed {
    margin-left: auto;
    font-size: calc(11px * var(--text));
    color: var(--select);
  }
  .io {
    padding: 0 8px 4px;
    color: var(--ink-3);
    word-break: break-all;
  }
  pre {
    margin: 0;
    padding: 6px 8px;
    max-height: 220px;
    overflow: auto;
    white-space: pre-wrap;
    font-size: calc(11.5px * var(--text));
    border-top: 1px solid var(--line);
  }
  img {
    display: block;
    max-width: 100%;
    border-top: 1px solid var(--line);
  }
  .note {
    font-size: calc(12px * var(--text));
    color: var(--ink-3);
  }
  .note.error {
    color: var(--bad);
  }
  .working {
    font-size: calc(12px * var(--text));
  }
  footer {
    display: flex;
    gap: 6px;
    align-items: flex-end;
    padding: 8px;
    border-top: 1px solid var(--line);
  }
  textarea {
    flex: 1;
    resize: none;
    font: inherit;
    font-size: calc(13px * var(--text));
    padding: 6px 8px;
    border: 1px solid var(--line);
    border-radius: var(--radius);
    background: var(--surface);
    color: var(--ink);
  }
  textarea:focus {
    outline: none;
    border-color: var(--select);
  }
</style>
