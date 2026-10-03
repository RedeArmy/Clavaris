(() => {
  "use strict";

  const findDialog = (trigger) => document.getElementById(trigger.dataset.dialogOpen);

  // A click that lands on a modal <dialog> element itself (not one of its children) can only have
  // hit its ::backdrop or its own edge — treat a click outside its box as "dismiss", the same way
  // Esc already does.
  const isBackdropClick = (event) => {
    const dialog = event.target;
    if (dialog?.tagName !== "DIALOG" || !dialog.open || !dialog.getBoundingClientRect) {
      return false;
    }
    const box = dialog.getBoundingClientRect();
    return (
      event.clientX < box.left ||
      event.clientX > box.right ||
      event.clientY < box.top ||
      event.clientY > box.bottom
    );
  };

  document.addEventListener("click", (event) => {
    if (isBackdropClick(event)) {
      event.target.close();
      return;
    }

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

  // Delete Workspace feature, 2026-09-28: a name-mismatch error re-rendered via an HTMX fragment
  // swap (the per-row delete-workspace dialog's own hx-post/hx-target) never fires
  // DOMContentLoaded — same re-init gap assign-role-picker.js's own htmx:afterSwap listener
  // already closes for an unrelated dialog. Extracted so the real full-page-load case and the
  // HTMX-swap case share one implementation.
  const openDialogMarkedForAutoOpen = () => {
    const dialog = document.querySelector("[data-dialog-open-on-load]")?.closest("dialog");
    if (dialog?.showModal) {
      dialog.showModal();
    }
  };
  document.addEventListener("DOMContentLoaded", openDialogMarkedForAutoOpen);
  document.body.addEventListener("htmx:afterSwap", openDialogMarkedForAutoOpen);

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
