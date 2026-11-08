// Runs in <head> so a stored theme applies before the first paint, and owns the theme from then on.
// CSS follows the system theme on its own; a "light" or "dark" class only marks a manual pick.
// Other controls stay inert until site.js wires them up.
(() => {
  const root = document.documentElement;
  const storageKey = "androidx-release-notes-theme";
  const system = matchMedia("(prefers-color-scheme: dark)");
  const themeColors = [...document.querySelectorAll('meta[name="theme-color"]')].map(meta => [meta, meta.media]);
  const stored = () => { try { return localStorage.getItem(storageKey); } catch { return null; } };
  const isDark = () => root.classList.contains("dark") || (!root.classList.contains("light") && system.matches);
  const setTheme = (theme = stored()) => {
    for (const name of ["light", "dark"]) root.classList.toggle(name, theme === name);
    // A manual pick keeps only its own media-based theme-color, so the browser UI matches it.
    for (const [meta, media] of themeColors) meta.media = !theme ? media : media.includes(theme) ? "all" : "not all";
    document.getElementById("theme-toggle")?.setAttribute("aria-pressed", isDark());
  };
  setTheme();
  root.classList.add("loading");
  document.addEventListener("DOMContentLoaded", () => setTheme());
  system.addEventListener("change", () => setTheme());
  // Keeps other open tabs in sync with a theme picked here.
  addEventListener("storage", event => { if (event.key === storageKey) setTheme(event.newValue); });
  document.addEventListener("click", event => {
    if (!event.target.closest?.("#theme-toggle")) return;
    // Picking the system theme goes back to following it.
    const dark = !isDark();
    const theme = dark === system.matches ? null : dark ? "dark" : "light";
    setTheme(theme);
    try { theme ? localStorage.setItem(storageKey, theme) : localStorage.removeItem(storageKey); } catch {}
  });
})();
