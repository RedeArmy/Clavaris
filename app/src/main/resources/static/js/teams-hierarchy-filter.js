(() => {
  "use strict";

  // Live UX request, 2026-10-02 — workspace-teams-hierarchy.html's own Team hierarchy tab: search
  // teams by name and page through them 5/page. Both client-side, same role-picker.js/
  // roles-table-filter.js convention — every team is already rendered in full either way
  // (loadTeamsAndRoles's own comment: this page reloads the whole catalog on every request
  // regardless), so paging/filtering it again server-side would only add round trips. Independent
  // of membersPage's own real keyset pagination (TD-PERF-027) — that one picks which MEMBERS show
  // under whatever's rendered; this picks which TEAMS render at all.
  const PAGE_SIZE = 5;

  const rowsFor = (container) => Array.from(container.querySelectorAll("[data-team-row]"));

  const searchTerm = (container) =>
    (container.querySelector("[data-teams-search]")?.value || "").trim().toLowerCase();

  const matchingRows = (container) => {
    const term = searchTerm(container);
    return rowsFor(container).filter((row) => {
      const name = (row.dataset.teamName || "").toLowerCase();
      return !term || name.includes(term);
    });
  };

  const currentPage = (container) => Number(container.dataset.currentPage || "1");

  const totalPages = (matches) => Math.max(1, Math.ceil(matches.length / PAGE_SIZE));

  const render = (container) => {
    const matches = matchingRows(container);
    const page = Math.min(currentPage(container), totalPages(matches));
    container.dataset.currentPage = String(page);

    const matchSet = new Set(matches);
    const visibleOnPage = new Set(matches.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE));
    rowsFor(container).forEach((row) => {
      row.hidden = !matchSet.has(row) || !visibleOnPage.has(row);
    });

    const emptyState = container.querySelector("[data-teams-empty]");
    if (emptyState) {
      emptyState.hidden = matches.length > 0;
    }

    const indicator = container.querySelector("[data-teams-page-indicator]");
    if (indicator) {
      indicator.textContent = "Page " + page + " of " + totalPages(matches);
    }
    const prev = container.querySelector("[data-teams-prev]");
    const next = container.querySelector("[data-teams-next]");
    if (prev) {
      prev.disabled = page <= 1;
    }
    if (next) {
      next.disabled = page >= totalPages(matches);
    }
    const pagination = container.querySelector("[data-teams-pagination]");
    if (pagination) {
      pagination.hidden = totalPages(matches) <= 1;
    }
  };

  document.addEventListener("input", (event) => {
    if (!event.target.matches("[data-teams-search]")) {
      return;
    }
    const container = event.target.closest("[data-teams-hierarchy]");
    if (!container) {
      return;
    }
    container.dataset.currentPage = "1";
    render(container);
  });

  document.addEventListener("click", (event) => {
    const prevButton = event.target.closest("[data-teams-prev]");
    const nextButton = event.target.closest("[data-teams-next]");
    const button = prevButton || nextButton;
    if (!button) {
      return;
    }
    const container = button.closest("[data-teams-hierarchy]");
    if (!container) {
      return;
    }
    const matches = matchingRows(container);
    const page = currentPage(container);
    container.dataset.currentPage = String(
      prevButton ? Math.max(1, page - 1) : Math.min(totalPages(matches), page + 1),
    );
    render(container);
  });

  const renderEveryContainer = () => {
    document.querySelectorAll("[data-teams-hierarchy]").forEach(render);
  };
  document.addEventListener("DOMContentLoaded", renderEveryContainer);
  document.body.addEventListener("htmx:afterSwap", renderEveryContainer);
})();
