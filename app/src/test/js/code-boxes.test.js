"use strict";

const { test } = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");

const SCRIPT_PATH = path.join(
  __dirname, "..", "..", "main", "resources", "static", "js", "code-boxes.js",
);

// A box the script creates: records its listeners and which one holds focus.
function fakeElement(focusOwner) {
  const listeners = {};
  const element = {
    value: "",
    attrs: {},
    children: [],
    classes: new Set(),
    classList: { add: (name) => element.classes.add(name) },
    addEventListener: (name, listener) => { listeners[name] = listener; },
    setAttribute: (name, value) => { element.attrs[name] = value; },
    appendChild: (child) => { element.children.push(child); },
    focus: () => { focusOwner.current = element; },
    select: () => { element.selected = true; },
    after: (group) => { element.group = group; },
    listeners,
  };
  return element;
}

function load({ length = "6", withForm = true, withLabel = false } = {}) {
  const focusOwner = { current: null };
  const created = [];
  const form = {
    submits: 0,
    requestSubmit() { this.submits += 1; },
    addEventListener(name, listener) { this.submitListener = listener; },
  };
  const source = fakeElement(focusOwner);
  Object.assign(source, {
    id: "code",
    dataset: { codeBoxes: length },
    form: withForm ? form : null,
    value: "",
    tabIndex: 0,
  });
  const label = { id: "" };
  const windowListeners = {};
  global.globalThis.clavarisI18n = undefined;
  global.addEventListener = (name, listener) => { windowListeners[name] = listener; };
  global.document = {
    createElement: () => {
      const element = fakeElement(focusOwner);
      created.push(element);
      return element;
    },
    querySelector: () => (withLabel ? label : null),
    querySelectorAll: () => [source],
  };
  delete require.cache[require.resolve(SCRIPT_PATH)];
  require(SCRIPT_PATH);
  const group = created[0];
  const boxes = created.slice(1);
  return { source, form, group, boxes, focusOwner, label, windowListeners };
}

const press = (box, key) => {
  const event = { key, prevented: false, preventDefault() { this.prevented = true; } };
  box.listeners.keydown(event);
  return event;
};

const paste = (box, text) => {
  box.listeners.paste({ preventDefault() {}, clipboardData: { getData: () => text } });
};

test("builds one box per character and focuses the first", () => {
  const { boxes, focusOwner } = load();
  assert.equal(boxes.length, 6);
  assert.equal(focusOwner.current, boxes[0]);
  assert.equal(boxes[0].autocomplete, "one-time-code");
  assert.equal(boxes[1].autocomplete, "off");
});

test("the length comes from the attribute and falls back to six", () => {
  assert.equal(load({ length: "8" }).boxes.length, 8);
  assert.equal(load({ length: "x" }).boxes.length, 6);
});

test("each box says which character of how many it is", () => {
  const { boxes } = load({ length: "4" });
  assert.equal(boxes[2].attrs["aria-label"], "Character 3 of 4");
});

test("the group takes its name from the field's label", () => {
  const { group, label } = load({ withLabel: true });
  assert.equal(label.id, "code-label");
  assert.equal(group.attrs["aria-labelledby"], "code-label");
});

test("the real field is hidden from tab order and the boxes go after it", () => {
  const { source, group } = load();
  assert.ok(source.classes.has("clavaris-code-boxes__source"));
  assert.equal(source.tabIndex, -1);
  assert.equal(source.group, group);
});

test("a paste fills the boxes from the start, upper-cased, ignoring separators", () => {
  const { boxes, source } = load();
  paste(boxes[3], "ab-12 c");
  assert.equal(source.value, "AB12C");
  assert.deepEqual(boxes.map((b) => b.value), ["A", "B", "1", "2", "C", ""]);
});

test("the form is submitted once when the last box fills, and only once", () => {
  const { boxes, form } = load();
  paste(boxes[0], "abc123");
  paste(boxes[0], "xyz789");
  assert.equal(form.submits, 1);
});

test("a back-navigation restore lets the code be submitted again", () => {
  const { boxes, form, windowListeners } = load();
  paste(boxes[0], "abc123");
  windowListeners.pageshow();
  paste(boxes[0], "abc123");
  assert.equal(form.submits, 2);
});

test("a code in a page without a form fills the field but submits nothing", () => {
  const { boxes, source } = load({ withForm: false });
  paste(boxes[0], "abc123");
  assert.equal(source.value, "ABC123");
});

test("typing a character moves it into place and focus to the next box", () => {
  const { boxes, focusOwner, source } = load();
  boxes[0].value = "q";
  boxes[0].listeners.input();
  assert.equal(boxes[0].value, "Q");
  assert.equal(focusOwner.current, boxes[1]);
  assert.equal(source.value, "Q");
});

test("typing something that is not a letter or digit is dropped", () => {
  const { boxes, source } = load();
  boxes[0].value = "-";
  boxes[0].listeners.input();
  assert.equal(boxes[0].value, "");
  assert.equal(source.value, "");
});

test("beforeinput takes over an insertion and fills from that box", () => {
  const { boxes } = load();
  const event = {
    inputType: "insertText", data: "k", cancelable: true, prevented: false,
    preventDefault() { this.prevented = true; },
  };
  boxes[2].listeners.beforeinput(event);
  assert.equal(event.prevented, true);
  assert.equal(boxes[2].value, "K");
});

test("beforeinput leaves a deletion or a composition alone", () => {
  const { boxes } = load();
  const deletion = { inputType: "deleteContentBackward", cancelable: true, preventDefault() { throw new Error("no"); } };
  const composing = { inputType: "insertText", data: "k", cancelable: true, isComposing: true, preventDefault() { throw new Error("no"); } };
  assert.doesNotThrow(() => boxes[1].listeners.beforeinput(deletion));
  assert.doesNotThrow(() => boxes[1].listeners.beforeinput(composing));
});

test("backspace on an empty box clears and focuses the previous one", () => {
  const { boxes, focusOwner, source } = load();
  paste(boxes[0], "ab");
  boxes[2].value = "";
  press(boxes[2], "Backspace");
  assert.equal(boxes[1].value, "");
  assert.equal(focusOwner.current, boxes[1]);
  assert.equal(source.value, "A");
});

test("backspace on the first box, or on a filled one, is left to the browser", () => {
  const { boxes } = load();
  assert.equal(press(boxes[0], "Backspace").prevented, false);
  boxes[1].value = "A";
  assert.equal(press(boxes[1], "Backspace").prevented, false);
});

test("arrows, Home and End move focus within the boxes", () => {
  const { boxes, focusOwner } = load();
  press(boxes[3], "ArrowLeft");
  assert.equal(focusOwner.current, boxes[2]);
  press(boxes[2], "ArrowRight");
  assert.equal(focusOwner.current, boxes[3]);
  press(boxes[3], "Home");
  assert.equal(focusOwner.current, boxes[0]);
  press(boxes[0], "End");
  assert.equal(focusOwner.current, boxes[5]);
});

test("the arrows stop at either end", () => {
  const { boxes } = load();
  assert.equal(press(boxes[0], "ArrowLeft").prevented, false);
  assert.equal(press(boxes[5], "ArrowRight").prevented, false);
});

test("focusing a box selects what it holds", () => {
  const { boxes } = load();
  boxes[1].listeners.focus();
  assert.equal(boxes[1].selected, true);
});

test("focusing the hidden field hands focus to the first empty box", () => {
  const { source, boxes, focusOwner } = load();
  paste(boxes[0], "ab");
  source.listeners.focus();
  assert.equal(focusOwner.current, boxes[2]);
});

test("the value is upper-cased again on submit", () => {
  const { source, form } = load();
  source.value = "ab12";
  form.submitListener();
  assert.equal(source.value, "AB12");
});
