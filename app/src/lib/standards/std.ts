// Reading the team standards: what is a note, what is a table, and where a value comes from.

import { readNum } from "../units.svelte";

export type Json = null | boolean | number | string | Json[] | { [k: string]: Json };

/** Tables whose rows the team names (pins by size, material properties by material, packing factors by bay). */
export const NAMED = new Set(["measuredPackingFactors", "shearPins", "shearModulus", "youngsModulus", "poissonRatio", "strength", "maxServiceTemperature"]);

/** Free text that explains its neighbours: shown as help, not edited here. */
export function isNote(key: string): boolean {
  return /(^notes?$|Notes?$|^source$|Source$)/.test(key);
}

export function isObject(v: unknown): v is { [k: string]: Json } {
  return v !== null && typeof v === "object" && !Array.isArray(v);
}

export function at(root: unknown, path: string[]): Json | undefined {
  let cur: unknown = root;
  for (const p of path) {
    if (!isObject(cur) || !(p in cur)) return undefined;
    cur = cur[p];
  }
  return cur as Json;
}

/** {a: {b: value}} for the path [a, b]: the shape the engine merges into the team file. */
export function patchFor(path: string[], value: Json): Json {
  return path.reduceRight<Json>((acc, k) => ({ [k]: acc }), value);
}

/** Text typed in a box as the value to store: a number when the default is a number, otherwise the text as typed. */
export function parse(text: string, like: unknown): Json {
  const t = readNum(text);
  if (t === "") return null;
  // A number when the default is one, or when there is no default yet (latitude, longitude).
  if ((typeof like === "number" || like === null || like === "") && /^[-+]?\d*\.?\d+(e[-+]?\d+)?$/i.test(t)) return Number(t);
  return t;
}
