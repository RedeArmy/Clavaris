window.ClavarisCategoryPicker = (() => {
  "use strict";

  // Shared by scope-picker.js and event-type-picker.js: both wire an identical parent/child
  // category-checkbox shape (a category checkbox reflecting whether none/some/all of its own
  // child option checkboxes are checked) over two structurally identical but differently-scoped
  // DOM trees. Extracted here once SonarCloud's new-code duplication check flagged the two
  // scripts' near-identical updateXCategoryCheckboxState/DOMContentLoaded logic — each picker
  // keeps only what's genuinely its own (scope-picker's plain toggle, event-type-picker's "All
  // Events" wildcard). Loaded as a plain global (no bundler in this codebase's static JS), so it
  // must appear before either picker script's own <script> tag in page order.

  const updateCategoryCheckboxState = (containerId, categoryCheckboxClass, changedCheckbox) => {
    const category = changedCheckbox.dataset.category;
    const categoryCheckbox = document.querySelector(
      "#" + containerId + " ." + categoryCheckboxClass + '[data-category="' + category + '"]',
    );
    if (!categoryCheckbox) {
      return;
    }
    const children = document.querySelectorAll(
      "#" + containerId + ' .clavaris-event-option input[data-category="' + category + '"]',
    );
    let checkedCount = 0;
    children.forEach((checkbox) => {
      if (checkbox.checked) {
        checkedCount += 1;
      }
    });
    categoryCheckbox.checked = checkedCount === children.length;
    categoryCheckbox.indeterminate = checkedCount > 0 && checkedCount < children.length;
  };

  const initializeCategoryCheckboxes = (containerId, categoryCheckboxClass) => {
    document
      .querySelectorAll("#" + containerId + " ." + categoryCheckboxClass)
      .forEach((categoryCheckbox) => {
        const firstChild = document.querySelector(
          "#" +
            containerId +
            ' .clavaris-event-option input[data-category="' +
            categoryCheckbox.dataset.category +
            '"]',
        );
        if (firstChild) {
          updateCategoryCheckboxState(containerId, categoryCheckboxClass, firstChild);
        }
      });
  };

  return { updateCategoryCheckboxState, initializeCategoryCheckboxes };
})();
