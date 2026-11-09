// The page shown last in this run, so the window returns to it if its page has to load again (after a crash).

const PAGE = "rocketmaxxing.page";

export function rememberPage(page: string): void {
  try {
    localStorage.setItem(PAGE, page);
  } catch {
    /* private mode */
  }
}

export function lastPage(): string | null {
  try {
    return localStorage.getItem(PAGE);
  } catch {
    return null;
  }
}
