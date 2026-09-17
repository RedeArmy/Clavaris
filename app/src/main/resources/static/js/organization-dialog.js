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
    const dialog = document.querySelector("dialog[data-open-on-load='true']");
    if (dialog?.showModal) {
      dialog.showModal();
    }
  });
})();
