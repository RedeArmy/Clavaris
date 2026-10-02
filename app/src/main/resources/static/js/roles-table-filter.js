(() => {
  "use strict";

  // Live UX request, 2026-10-02 — workspace-detail.html's own unified "Roles" table (every role
  // this Organization has, grouped and ungrouped alike, each with its own Team column): searchable
  // by name, filterable by team, paginated 10/page. All client-side, same role-picker.js convention
  // — the full role list is already rendered server-side either way (loadTeamsAndRoles's own
  // comment: this page reloads this Organization's whole role catalog on every request regardless),
  // so paging/filtering it again server-side would only add round trips, not reduce any real query
  // cost. Fully event-delegated so it needs no re-init after an htmx swap re-renders #teams-content
  // from scratch.
  const PAGE_SIZE = 10;

  const rowsFor = (table) => Array.from(table.querySelectorAll("[data-roles-row]"));

  const searchTerm = (table) =>
    (table.querySelector("[data-roles-search]")?.value || "").trim().toLowerCase();

  const teamFilter = (table) => table.querySelector("[data-roles-team-filter]")?.value || "";

  const matchingRows = (table) => {
    const term = searchTerm(table);
    const team = teamFilter(table);
    return rowsFor(table).filter((row) => {
      const name = (row.querySelector("[data-roles-name]")?.textContent || "").toLowerCase();
      const matchesSearch = !term || name.includes(term);
      const matchesTeam = !team || row.dataset.rolesTeamId === team;
      return matchesSearch && matchesTeam;
    });
  };

  const currentPage = (table) => Number(table.dataset.currentPage || "1");

  const totalPages = (matches) => Math.max(1, Math.ceil(matches.length / PAGE_SIZE));

  const render = (table) => {
    const matches = matchingRows(table);
    const page = Math.min(currentPage(table), totalPages(matches));
    table.dataset.currentPage = String(page);

    const matchSet = new Set(matches);
    const visibleOnPage = new Set(matches.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE));
    rowsFor(table).forEach((row) => {
      row.hidden = !matchSet.has(row) || !visibleOnPage.has(row);
    });

    const wrapper = table.querySelector(".clavaris-table-wrapper");
    if (wrapper) {
      wrapper.hidden = matches.length === 0;
    }
    const emptyState = table.querySelector("[data-roles-empty]");
    if (emptyState) {
      emptyState.hidden = matches.length > 0;
    }

    const indicator = table.querySelector("[data-roles-page-indicator]");
    if (indicator) {
      indicator.textContent = "Page " + page + " of " + totalPages(matches);
    }
    const prev = table.querySelector("[data-roles-prev]");
    const next = table.querySelector("[data-roles-next]");
    if (prev) {
      prev.disabled = page <= 1;
    }
    if (next) {
      next.disabled = page >= totalPages(matches);
    }
    const pagination = table.querySelector("[data-roles-pagination]");
    if (pagination) {
      pagination.hidden = totalPages(matches) <= 1;
    }
  };

  document.addEventListener("input", (event) => {
    if (!event.target.matches("[data-roles-search]")) {
      return;
    }
    const table = event.target.closest("[data-roles-table]");
    if (!table) {
      return;
    }
    table.dataset.currentPage = "1";
    render(table);
  });

  document.addEventListener("change", (event) => {
    if (!event.target.matches("[data-roles-team-filter]")) {
      return;
    }
    const table = event.target.closest("[data-roles-table]");
    if (!table) {
      return;
    }
    table.dataset.currentPage = "1";
    render(table);
  });

  document.addEventListener("click", (event) => {
    const prevButton = event.target.closest("[data-roles-prev]");
    const nextButton = event.target.closest("[data-roles-next]");
    const button = prevButton || nextButton;
    if (!button) {
      return;
    }
    const table = button.closest("[data-roles-table]");
    if (!table) {
      return;
    }
    const matches = matchingRows(table);
    const page = currentPage(table);
    table.dataset.currentPage = String(
      prevButton ? Math.max(1, page - 1) : Math.min(totalPages(matches), page + 1),
    );
    render(table);
  });

  const renderEveryTable = () => {
    document.querySelectorAll("[data-roles-table]").forEach(render);
  };
  document.addEventListener("DOMContentLoaded", renderEveryTable);
  document.body.addEventListener("htmx:afterSwap", renderEveryTable);
})();
