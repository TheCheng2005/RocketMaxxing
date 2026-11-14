import type { Page } from "./state.svelte";

/** Each page's name as the left rail shows it. */
export const PAGE_NAMES: Record<Page, string> = {
  home: "Start", design: "Design", motors: "Motors", flights: "Flights", flightdata: "Flight data", targets: "Targets",
  standards: "Standards", analysis: "Analysis",
};
