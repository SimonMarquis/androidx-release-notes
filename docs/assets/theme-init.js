// Apply a saved theme before the first paint; CSS follows the system when none is saved.
(() => {
  const root = document.documentElement;
  root.classList.add("loading");
  try {
    const theme = localStorage.getItem("androidx-release-notes-theme");
    if (theme !== "light" && theme !== "dark") return;
    root.classList.add(theme);
    for (const meta of document.querySelectorAll('meta[name="theme-color"][data-theme-color]')) {
      meta.media = meta.dataset.themeColor === theme ? "all" : "not all";
    }
  } catch {}
})();
