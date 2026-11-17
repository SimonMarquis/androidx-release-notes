// Runs at the end of <body>, once every release section is parsed.
(() => {
  //region Theme
  // The head bootstrap applies the stored theme before paint; keep it in sync afterwards.
  const root = document.documentElement;
  const storageKey = "androidx-release-notes-theme";
  const system = matchMedia("(prefers-color-scheme: dark)");
  const themeColors = document.querySelectorAll('meta[name="theme-color"][data-theme-color]');
  const themeToggle = document.getElementById("theme-toggle");
  const normalizeTheme = value => ["light", "dark"].includes(value) ? value : null;
  // Re-read in case another tab changed the preference while this document was parsing.
  let preference = ["light", "dark"].find(name => root.classList.contains(name)) ?? null;
  try {
    preference = normalizeTheme(localStorage.getItem(storageKey));
  } catch {
  }
  const isDark = () => preference ? preference === "dark" : system.matches;
  const applyTheme = () => {
    for (const name of ["light", "dark"]) root.classList.toggle(name, preference === name);
    themeColors.forEach(meta => {
      const color = meta.dataset.themeColor;
      meta.media = preference ? (color === preference ? "all" : "not all") : `(prefers-color-scheme: ${color})`;
    });
    themeToggle?.setAttribute("aria-pressed", isDark());
  };
  applyTheme();
  system.addEventListener("change", applyTheme);
  addEventListener("storage", event => {
    if (event.key === storageKey || event.key === null) {
      preference = normalizeTheme(event.newValue);
      applyTheme();
    }
  });
  themeToggle?.addEventListener("click", () => {
    const dark = !isDark();
    preference = dark === system.matches ? null : dark ? "dark" : "light";
    applyTheme();
    try {
      preference ? localStorage.setItem(storageKey, preference) : localStorage.removeItem(storageKey);
    } catch {
    }
  });
  //endregion

  //region Search and filters
  root.classList.remove("loading");
  const isIndex = document.body.hasAttribute("data-date-navigation");
  const header = document.querySelector(".site-header");

  const components = [...document.querySelectorAll(".component")];
  const cards = [...document.querySelectorAll(".card")];
  const groups = [...document.querySelectorAll(".date-group")];
  const search = document.getElementById("release-search");
  const filterButtons = [...document.querySelectorAll("[data-filter]")];
  const dateLinks = [...document.querySelectorAll(".date-link, .date-back")].map(link => [link, new URL(link.href)]);

  const selectedKinds = new Set();
  let lastSearch = location.search;
  const update = () => {
    // Every word must match, in any order, so "compose 1.8" finds "compose ui 1.8.0".
    const terms = search.value.toLowerCase().split(/\s+/).filter(Boolean);
    for (const component of components) {
      const {releaseKind, search: text} = component.dataset;
      const hide = !((selectedKinds.size === 0 || selectedKinds.has(releaseKind)) && terms.every(term => text.includes(term)));
      if (component.hidden !== hide) component.hidden = hide;
    }
    for (const card of cards) {
      const hide = !card.querySelector(".component:not([hidden])");
      if (card.hidden !== hide) card.hidden = hide;
    }
    for (const group of groups) {
      // Dated pages keep their date heading (and back chevron) even when nothing matches.
      const hide = isIndex && !group.querySelector(".component:not([hidden])");
      if (group.hidden !== hide) group.hidden = hide;
    }
  };
  // Keep shareable filters in the URL and in date links, including links opened in a new tab.
  const syncUrl = () => {
    const url = new URL(location.href);
    if (search.value.trim()) url.searchParams.set("q", search.value);
    else url.searchParams.delete("q");
    url.searchParams.delete("s");
    for (const kind of selectedKinds) url.searchParams.append("s", kind);
    history.replaceState(history.state, "", url);
    lastSearch = url.search;
    for (const [link, target] of dateLinks) {
      if (target.search === url.search) continue;
      target.search = url.search;
      link.href = target.href;
    }
  };
  const restoreFilters = (initial = false) => {
    const params = new URL(location.href).searchParams;
    const query = params.get("q") ?? "";
    search.value = query.trim() ? query : "";
    const kinds = new Set(params.getAll("s"));
    selectedKinds.clear();
    for (const button of filterButtons) {
      const selected = kinds.has(button.dataset.filter);
      if (selected) selectedKinds.add(button.dataset.filter);
      button.setAttribute("aria-pressed", selected);
    }
    if (!initial || search.value || selectedKinds.size) update();
  };
  for (const button of filterButtons) {
    button.addEventListener("click", () => {
      const kind = button.dataset.filter;
      if (!selectedKinds.delete(kind)) selectedKinds.add(kind);
      button.setAttribute("aria-pressed", selectedKinds.has(kind));
      syncUrl();
      update();
    });
  }
  let searchTimer = 0;
  search.addEventListener("input", () => {
    syncUrl();
    clearTimeout(searchTimer);
    searchTimer = setTimeout(update, 150);
  });
  search.addEventListener("keydown", event => {
    if (event.key !== "Escape") return;
    clearTimeout(searchTimer);
    search.value = "";
    syncUrl();
    update();
    search.blur();
  });
  addEventListener("popstate", () => {
    if (location.search === lastSearch) return;
    clearTimeout(searchTimer);
    restoreFilters();
    syncUrl();
  });
  restoreFilters(true);
  syncUrl();
  //endregion

  //region Hash navigation and history
  let navigationToken = 0;
  let lastTarget = null;
  let lastNavigationAt = 0;
  const goTo = target => {
    if (!target) return;
    const token = ++navigationToken;
    lastTarget = target;
    lastNavigationAt = Date.now();
    history.replaceState(null, "", `#${target.id}`);
    // Distant components have estimated heights until rendered. Re-align the target
    // as those components acquire their real sizes, while keeping CSS scroll margins.
    let frame = 0;
    const align = () => {
      if (token !== navigationToken) return;
      const desired = parseFloat(getComputedStyle(target).scrollMarginTop);
      if (frame === 0 || Math.abs(target.getBoundingClientRect().top - desired) > 2) {
        target.scrollIntoView({behavior: "instant", block: "start"});
      }
      if (++frame < 30) requestAnimationFrame(align);
    };
    align();
  };
  for (const type of ["wheel", "touchstart", "pointerdown"]) {
    window.addEventListener(type, () => {
      navigationToken++;
    }, {passive: true});
  }
  document.addEventListener("keydown", event => {
    if (["ArrowUp", "ArrowDown", "PageUp", "PageDown", "Home", "End", " "].includes(event.key)) navigationToken++;
  });
  // Record the clicked date in the URL before leaving, so Back lands on it via the alignment below.
  // Manual restoration stops the browser from restoring a pixel offset that no longer matches the
  // reloaded page, where off-screen components only have estimated heights.
  // When the previous history entry is the index, a dated page's back chevron returns to it
  // (keeping its position) instead of loading it again. Without the Navigation API it stays a link.
  document.addEventListener("click", event => {
    if (event.ctrlKey || event.metaKey || event.shiftKey || event.altKey || !(event.target instanceof Element)) return;
    const back = event.target.closest(".date-back");
    const previous = back && window.navigation?.entries()[navigation.currentEntry.index - 1];
    const previousUrl = previous && new URL(previous.url);
    if (previousUrl && previousUrl.pathname.replace(/index\.html$/, "") === back.pathname && previousUrl.search === back.search) {
      event.preventDefault();
      history.back();
      return;
    }
    const link = event.target.closest(".date-link");
    if (!link) return;
    history.replaceState(null, "", `#${link.closest(".date-group").id}`);
    history.scrollRestoration = "manual";
  });
  // Align the URL target under the header once the page is rendered, and whenever the hash changes.
  const goToHash = () => {
    try {
      goTo(document.getElementById(decodeURIComponent(location.hash.slice(1))));
    } catch {
    }
  };
  requestAnimationFrame(goToHash);
  addEventListener("hashchange", goToHash);
  //endregion

  //region Date navigation
  if (!isIndex) return;
  const visibleGroups = () => groups.filter(group => !group.hidden);
  const currentIndex = visible => {
    const edge = header.getBoundingClientRect().bottom + 16;
    const index = visible.findIndex(group => group.getBoundingClientRect().top > edge);
    return (index < 0 ? visible.length : index) - 1;
  };
  const navigate = (direction, from = null) => {
    const visible = visibleGroups();
    const recent = !from && Date.now() - lastNavigationAt < 500 && visible.includes(lastTarget);
    const index = from ? visible.indexOf(from) : recent ? visible.indexOf(lastTarget) : currentIndex(visible);
    if (from && index < 0) return;
    goTo(visible[index + direction]);
  };
  document.addEventListener("click", event => {
    const step = event.target instanceof Element && event.target.closest("[data-date-step]");
    if (!step) return;
    navigate(step.dataset.dateStep === "next" ? 1 : -1, step.closest(".date-group"));
  });
  document.addEventListener("keydown", event => {
    if (event.altKey || event.ctrlKey || event.metaKey) return;
    if (event.target instanceof Element && event.target.closest("input, textarea, select, [contenteditable]")) return;
    const key = event.key.toLowerCase();
    if (key !== "n" && key !== "p") return;
    event.preventDefault();
    navigate(key === "n" ? 1 : -1);
  });
  //endregion
})();
