// The launch planner's form and last result, kept while the app runs (the page itself is unloaded when left).

export interface Ring {
  percent: number;
  size: string;
  semiMajor: number;
  semiMinor: number;
  polygon: [number, number][];
}
export interface Area {
  branch: string;
  landings: number;
  centre: string;
  centreLatLon: [number, number];
  areas: Ring[];
  points: [number, number][];
  beyondRadius?: string;
}
export interface Track {
  branch: string;
  path: [number, number, number][];
  apogee: [number, number, number];
  landing?: [number, number];
  landingFromPad?: string;
}
export interface Plan {
  site: { latitude: number; longitude: number; elevation: number };
  forecast: { model: string; time: string; timezone: string; forecasts: number; note: string };
  runs: number;
  failedRuns: number;
  summary: Record<string, string>;
  verdict: string;
  landing: Area[];
  forecastAsIs?: { apogee: string; tracks: Track[] };
  winds: { height: string; speed: string; ensembleRange: string; from: string }[];
  notes: string[];
  design?: string;
  simulation?: string;
}

export const launch = $state({
  latitude: "",
  longitude: "",
  day: "",
  hour: "10",
  model: "ecmwf",
  runs: "500",
  radius: "",
  simulation: "",
  plan: null as Plan | null,
  /** The plan shown came from a saved file (its name), not a run just now. */
  from: "",
});
