(() => {
  "use strict";

  // TD-ARCH-031 (SDE-III review, 2026-10-01): the OAuth client detail page's "Scopes" dialog
  // (organization-oauth-client-detail.html) used to wire its add/remove row buttons via inline
  // onclick="..." attributes plus an inline <script> block — both silently blocked by
  // ContentSecurityPolicyHeaderWriter's own DASHBOARD_PAGE_POLICY (script-src 'self', deliberately
  // never 'unsafe-inline'), same bug class event-type-picker.js's own comment already documents
  // being found and fixed 2026-09-26. MockMvc content-assertion tests render the markup but never
  // execute JS or enforce CSP, so this passed every automated check while doing nothing in a real
  // browser. Same event-delegation shape organization-dialog.js/event-type-picker.js already
  // establish: one listener on document, safe to load unconditionally (a no-op wherever
  // #scopes-rows doesn't exist).

  const ROWS_CONTAINER_ID = "scopes-rows";
  const TEMPLATE_ID = "scope-row-template";

  // Seeded past every server-rendered row's own rowStat.index, same counter shape the removed
  // inline script used, so a newly added row's id/for pair never collides with one already on the
  // page.
  let scopeRowCounter = 0;

  const addScopeRow = () => {
    const template = document.getElementById(TEMPLATE_ID);
    const rowsContainer = document.getElementById(ROWS_CONTAINER_ID);
    if (!template || !rowsContainer) {
      return;
    }
    const clone = template.content.cloneNode(true);
    const id = "scope" + scopeRowCounter++;
    clone.querySelector("input").id = id;
    clone.querySelector("label").setAttribute("for", id);
    rowsContainer.appendChild(clone);
  };

  document.addEventListener("click", (event) => {
    if (event.target.dataset.action === "add-scope-row") {
      addScopeRow();
      return;
    }
    if (event.target.dataset.action === "remove-scope-row") {
      event.target.closest(".clavaris-uri-row")?.remove();
    }
  });

  document.addEventListener("DOMContentLoaded", () => {
    scopeRowCounter = document.querySelectorAll("#" + ROWS_CONTAINER_ID + " .clavaris-uri-row").length;
  });
})();
