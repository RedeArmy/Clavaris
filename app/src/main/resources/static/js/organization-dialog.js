(() => {
  "use strict";

  const findDialog = (trigger) => document.getElementById(trigger.dataset.dialogOpen);

  document.addEventListener("click", (event) => {
    const openTrigger = event.target.closest("[data-dialog-open]");
    if (openTrigger) {
      const dialog = findDialog(openTrigger);
      if (dialog?.showModal) {
        dialog.showModal();
      }
      return;
    }

    const closeTrigger = event.target.closest("[data-dialog-close]");
    if (closeTrigger) {
      closeTrigger.closest("dialog")?.close();
    }
  });

  document.addEventListener("DOMContentLoaded", () => {
    const dialog = document.querySelector("[data-dialog-open-on-load]")?.closest("dialog");
    if (dialog?.showModal) {
      dialog.showModal();
    }
  });

  // Live UX request, 2026-09-27 (real gap found live): workspace-teams-hierarchy.html's own
  // "Assign role" dialog had no way to close itself after a successful save — unlike the Users
  // tab's identical popup, this page's own list refreshes in place via HTMX (no full page reload),
  // so assign-role-refresh.js's own close-then-reload isn't loaded here and wouldn't fit anyway.
  // Handled once, globally, rather than per-page: any dashboard dialog left open when this event
  // fires gets closed, harmless to redeclare on a page (like organization-users.html) whose own
  // assign-role-refresh.js also closes it before reloading regardless.
  document.addEventListener("workspace-role-assigned", () => {
    document.querySelector("dialog[open]")?.close();
  });
})();
