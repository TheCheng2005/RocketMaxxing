// The decimal mark chosen in Settings, for engine sentences ("1.34 at 905.9 m"). The engine writes a point so that
// assistants and the app's own readers always parse its numbers; the app changes it only where a person reads it.
// Plain module state (no runes), so the wording check can bundle it.

let comma = false;

export function setCommaMark(on: boolean) {
  comma = on;
}

/**
 * Decimal numbers in a sentence written with the chosen mark: "1.34 at 905.9 m" becomes "1,34 at 905,9 m". A number
 * that is part of something else is left: rule references (R10.3.1), versions (v1.6.0), file names, ids.
 */
export function localNumbers(text: string): string {
  if (!comma || !text) return text;
  return text.replace(/(?<!(?:OpenRocket|RocketMaxxing|RASAero|version|engine|Java) )(?<![\w.,/-])(-?\d+)\.(\d+)(?![\w.]|,\d)/g, "$1,$2");
}
