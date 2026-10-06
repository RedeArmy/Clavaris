(() => {
  "use strict";

  // Translation helper: the shared i18n.js when the page loads it, plain English otherwise.
  const t = (text, ...args) =>
    globalThis.clavarisI18n?.t(text, ...args) ??
    args.reduce((out, arg, index) => out.split("{" + index + "}").join(String(arg)), text);

  // Live UX request, 2026-10-01 — workspace-detail.html's own "Add existing role" popup: the
  // single-select <select> became a paginated (10/page), multi-select checkbox list, confirmed by
  // one "Add selected roles" submit. Pagination is purely client-side — every checkbox for this
  // team's own ungroupedRoles is already in the DOM (the server renders the full list, same as the
  // <select> it replaces did), so paging never loses a selection made on an earlier page the way a
  // server round-trip per page would. Fully event-delegated (same convention organization-dialog.js
  // already establishes) so it needs no re-init after an htmx swap re-renders #teams-content —
  // and, with it, every per-team dialog — from scratch.
  const PAGE_SIZE = 10;

  const optionsFor = (picker) =>
    Array.from(picker.querySelectorAll(".clavaris-role-picker__option"));

  const currentPage = (picker) => Number(picker.dataset.currentPage || "1");

  const totalPages = (picker) => Math.max(1, Math.ceil(optionsFor(picker).length / PAGE_SIZE));

  const renderPage = (picker) => {
    const page = Math.min(currentPage(picker), totalPages(picker));
    picker.dataset.currentPage = String(page);
    optionsFor(picker).forEach((option, index) => {
      option.hidden = Math.floor(index / PAGE_SIZE) + 1 !== page;
    });
    const indicator = picker.querySelector(".clavaris-role-picker__page-indicator");
    if (indicator) {
      indicator.textContent = t("Page {0} of {1}", page, totalPages(picker));
    }
    const prev = picker.querySelector("[data-role-picker-prev]");
    const next = picker.querySelector("[data-role-picker-next]");
    if (prev) {
      prev.disabled = page <= 1;
    }
    if (next) {
      next.disabled = page >= totalPages(picker);
    }
  };

  document.addEventListener("click", (event) => {
    const prevButton = event.target.closest("[data-role-picker-prev]");
    const nextButton = event.target.closest("[data-role-picker-next]");
    const button = prevButton || nextButton;
    if (button) {
      const picker = button.closest("[data-role-picker]");
      if (picker) {
        const page = currentPage(picker);
        picker.dataset.currentPage = String(
          prevButton ? Math.max(1, page - 1) : Math.min(totalPages(picker), page + 1),
        );
        renderPage(picker);
      }
      return;
    }

    // Every picker inside the dialog this trigger is about to open starts back on page 1 — without
    // this, reopening a dialog left on page 2 from an earlier visit would hide its own first 10
    // roles until the operator clicked Previous first.
    const opener = event.target.closest("[data-dialog-open]");
    if (opener) {
      document
        .getElementById(opener.dataset.dialogOpen)
        ?.querySelectorAll("[data-role-picker]")
        .forEach((picker) => {
          picker.dataset.currentPage = "1";
          renderPage(picker);
        });
    }
  });

  const renderEveryPicker = () => {
    document.querySelectorAll("[data-role-picker]").forEach(renderPage);
  };
  document.addEventListener("DOMContentLoaded", renderEveryPicker);
  document.body.addEventListener("htmx:afterSwap", renderEveryPicker);
})();
