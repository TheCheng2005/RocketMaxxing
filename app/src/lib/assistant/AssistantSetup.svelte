<script lang="ts">
  import { assistant, configure, checkConnections, MODEL_NAMES, CLAUDE_CODE_MODELS, type Provider } from "./assistant.svelte";

  // How the assistant connects: the team's Claude or ChatGPT plan through Claude Code or Codex on this computer, or an
  // Anthropic API key. Shown in the assistant panel until it is ready, and in Settings. Choosing a way turns it on.
  let key = $state("");

  const WAYS: { id: Provider; title: string; how: string }[] = [
    { id: "claude-code", title: "Claude subscription (Claude Code)", how: "Uses Claude Code on this computer, signed in with your Claude Pro or Max plan." },
    { id: "codex", title: "ChatGPT subscription (Codex)", how: "Uses Codex on this computer, signed in with your ChatGPT plan." },
    { id: "api", title: "Anthropic API key", how: "Pay per use with your own API key." },
  ];

  $effect(() => {
    if (!assistant.connections && !assistant.checking) checkConnections();
  });

  function pick(id: Provider) {
    configure(assistant.settings?.enabled ? { provider: id } : { enabled: true, provider: id });
  }
</script>

{#if assistant.settings}
  {@const a = assistant.settings}
  <div class="ways" role="radiogroup" aria-label="Connect with">
    {#each WAYS as w (w.id)}
      {@const c = assistant.connections?.find((x) => x.id === w.id)}
      <label class="way" class:on={a.enabled && a.provider === w.id}>
        <input type="radio" name="provider" checked={a.enabled && a.provider === w.id} onchange={() => pick(w.id)} />
        <div>
          <b>{w.title}</b>
          <div class="note">{w.how}</div>
          {#if w.id !== "api"}
            <div class="state" class:good={c?.installed && c?.signedIn} class:bad={c && (!c.installed || c.signedIn === false)}>
              {#if !assistant.connections}{assistant.checking ? "Checking…" : ""}
              {:else if c}{c.installed ? `${c.label} ${c.version ?? ""}: ` : ""}{c.detail}{/if}
            </div>
          {/if}
        </div>
      </label>
    {/each}
  </div>
  <button class="link again" disabled={assistant.checking} onclick={checkConnections}>{assistant.checking ? "Checking…" : "Check Claude Code and Codex again"}</button>

  {#if a.enabled}
    {#if a.provider === "api"}
      <label class="row">
        <span>Model</span>
        <select class="select" value={a.model} onchange={(e) => configure({ model: (e.target as HTMLSelectElement).value })}>
          {#each a.models as m (m)}<option value={m}>{MODEL_NAMES[m] ?? m}</option>{/each}
        </select>
      </label>
      <div class="row">
        <span>API key</span>
        <div class="keyrow">
          <input class="input" type="password" bind:value={key} placeholder={a.keySource === "saved" ? "Saved (type to replace)" : a.keySource === "environment" ? "From ANTHROPIC_API_KEY" : "sk-ant-…"} autocomplete="off" />
          <button class="btn" disabled={!key.trim()} onclick={() => configure({ key }).then(() => (key = ""))}>Save</button>
        </div>
      </div>
      {#if a.keySource === "saved"}
        <div class="row"><span></span><button class="link" onclick={() => configure({ forgetKey: true })}>Forget the saved key</button></div>
      {/if}
    {:else if a.provider === "claude-code"}
      <label class="row">
        <span>Model</span>
        <select class="select" value={a.claudeCodeModel} onchange={(e) => configure({ claudeCodeModel: (e.target as HTMLSelectElement).value })}>
          {#each a.claudeCodeModels as m (m)}<option value={m}>{CLAUDE_CODE_MODELS[m] ?? m}</option>{/each}
        </select>
      </label>
    {:else}
      <label class="row">
        <span>Model</span>
        <input class="input" value={a.codexModel} placeholder="Codex's default" onchange={(e) => configure({ codexModel: (e.target as HTMLInputElement).value })} />
      </label>
    {/if}
  {/if}
  <p class="note">
    Your messages, the open design's data and the tools' results go to
    {a.provider === "codex" ? "OpenAI" : "Anthropic"}{a.provider === "api" ? ", and use is charged to your API account. The key is kept in " + a.settingsFile + ", not in the designs folder." : ". Use counts against your plan's limits, as it does in a terminal; RocketMaxxing never sees your sign-in."}
    {#if a.provider !== "api"}Claude Code runs without its own tools (no shell, files or web), Codex in its read-only sandbox: they work only through RocketMaxxing's tools.{/if}
  </p>
{:else}
  <p class="note">Loading…</p>
{/if}

<style>
  .row {
    display: grid;
    grid-template-columns: 1fr 200px;
    align-items: center;
    gap: 10px;
    min-height: 32px;
    color: var(--ink-2);
  }
  .ways {
    display: flex;
    flex-direction: column;
    gap: 6px;
    margin: 4px 0;
  }
  .way {
    display: flex;
    gap: 10px;
    align-items: flex-start;
    padding: 8px 10px;
    border: 1px solid var(--line);
    border-radius: var(--radius);
    cursor: pointer;
  }
  .way.on {
    border-color: var(--select);
    background: var(--surface-2);
  }
  .way input {
    margin-top: 3px;
  }
  .way .note {
    margin: 2px 0 0;
  }
  .state {
    font-size: calc(12px * var(--text));
    margin-top: 3px;
    color: var(--ink-3);
  }
  .state.good {
    color: var(--good);
  }
  .state.bad {
    color: var(--warn);
  }
  .keyrow {
    display: flex;
    gap: 6px;
  }
  .keyrow .input {
    flex: 1;
    min-width: 0;
  }
  .again {
    display: block;
    margin: 2px 0 6px auto;
  }
  .link {
    justify-self: start;
    border: 0;
    background: transparent;
    color: var(--select);
    padding: 0;
  }
  .note {
    margin: 4px 0;
    font-size: calc(12px * var(--text));
    color: var(--ink-3);
  }
</style>
