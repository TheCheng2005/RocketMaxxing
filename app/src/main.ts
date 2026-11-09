import { mount } from "svelte";
import "./app.css";
import "./schemes.css";
import { loadStoredPrefs } from "./lib/storedPrefs";

// The interface's modules read their preferences when they load: put the stored ones in place first.
async function start() {
  await loadStoredPrefs();
  const [{ default: App }, { applyTheme }] = await Promise.all([import("./App.svelte"), import("./lib/prefs.svelte")]);
  applyTheme();
  mount(App, { target: document.getElementById("app")! });
}

start();
