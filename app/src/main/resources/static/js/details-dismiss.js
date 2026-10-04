(() => {
  "use strict";

  // Close-on-outside-click and Escape for any <details data-dismissible> (the environment switcher
  // in the Organization header). The disclosure itself is native and needs no script; this only
  // adds the dismissal a native <details> lacks. Escape returns focus to the summary so keyboard
  // users land where they started.

  const openDismissible = () => [...document.querySelectorAll("details[data-dismissible][open]")];

  document.addEventListener("click", (event) => {
    for (const details of openDismissible()) {
      if (!details.contains(event.target)) {
        details.open = false;
      }
    }
  });

  document.addEventListener("keydown", (event) => {
    if (event.key !== "Escape") {
      return;
    }
    for (const details of openDismissible()) {
      details.open = false;
      details.querySelector("summary")?.focus();
    }
  });
})();
