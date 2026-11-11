// Runs at the end of <body>, once every release section is parsed.
(() => {
  document.documentElement.classList.remove("loading");
  const isIndex = document.body.hasAttribute("data-date-navigation");
  const header = document.querySelector(".site-header");

  const components = [...document.querySelectorAll(".component")];
  const cards = [...document.querySelectorAll(".card")];
  const groups = [...document.querySelectorAll(".date-group")];
  const search = document.getElementById("release-search");

  const selectedKinds = new Set();
  const update = () => {
    // Every word must match, in any order, so "compose 1.8" finds "compose ui 1.8.0".
    const terms = search.value.toLowerCase().split(/\s+/).filter(Boolean);
    for (const component of components) {
      const { releaseKind, search: text } = component.dataset;
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
  for (const button of document.querySelectorAll("[data-filter]")) {
    button.addEventListener("click", () => {
      const kind = button.dataset.filter;
      if (!selectedKinds.delete(kind)) selectedKinds.add(kind);
      button.setAttribute("aria-pressed", selectedKinds.has(kind));
      update();
    });
  }
  let searchTimer = 0;
  search.addEventListener("input", () => {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(update, 150);
  });
  search.addEventListener("keydown", event => {
    if (event.defaultPrevented || event.isComposing || event.key !== "Escape") return;
    clearTimeout(searchTimer);
    search.value = "";
    update();
    search.blur();
  });
  // Applies a query typed (or restored by the browser) before this script ran.
  if (search.value) update();

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
        target.scrollIntoView({ behavior: "instant", block: "start" });
      }
      if (++frame < 30) requestAnimationFrame(align);
    };
    align();
  };
  const cancelNavigation = () => {
    navigationToken++;
    lastTarget = null;
  };
  for (const type of ["wheel", "touchstart", "pointerdown"]) {
    window.addEventListener(type, cancelNavigation, { passive: true });
  }
  document.addEventListener("keydown", event => {
    if (["ArrowUp", "ArrowDown", "PageUp", "PageDown", "Home", "End", " "].includes(event.key)) cancelNavigation();
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
    if (previous && new URL(previous.url).pathname.replace(/index\.html$/, "") === back.pathname) {
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
    let id;
    try { id = decodeURIComponent(location.hash.slice(1)); } catch { return; }
    const target = document.getElementById(id);
    if (target?.closest("[hidden]")) {
      clearTimeout(searchTimer);
      search.value = "";
      selectedKinds.clear();
      document.querySelectorAll("[data-filter]").forEach(button => button.setAttribute("aria-pressed", "false"));
      update();
    }
    goTo(target);
  };
  requestAnimationFrame(goToHash);
  addEventListener("hashchange", goToHash);

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
    if (event.defaultPrevented || event.isComposing || event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return;
    if (event.target instanceof Element && event.target.closest("input, textarea, select, [contenteditable]")) return;
    const key = event.key.toLowerCase();
    if (key !== "n" && key !== "p") return;
    event.preventDefault();
    navigate(key === "n" ? 1 : -1);
  });
})();

