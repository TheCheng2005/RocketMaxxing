// Dialogs opened from anywhere (the search, a shortcut, a page): the search itself, Settings at a section ("" for the
// top), and the Guide at a tab.
export const ui = $state({
  search: false,
  settings: null as string | null,
  guide: null as string | null,
  /** The import check of the open design is on screen. */
  importReport: false,
  /**
   * Something for a page to do once it is on screen (its code loads when first shown): open the video, a view mode,
   * a team standard, a part's setting... The page that handles it clears it.
   */
  request: null as null | { name: string; detail?: string },
});

/** Asks the page that handles {@code name} to do it, once it is shown. */
export function request(name: string, detail?: string) {
  ui.request = { name, detail };
}

/** The request for this page, taken (so it runs once), or null. */
export function take(...names: string[]): { name: string; detail?: string } | null {
  const r = ui.request;
  if (!r || !names.includes(r.name)) return null;
  ui.request = null;
  return r;
}
