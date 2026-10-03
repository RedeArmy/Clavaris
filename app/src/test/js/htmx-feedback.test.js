"use strict";

const { test } = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");

const SCRIPT_PATH = path.join(__dirname, "..", "..", "main", "resources", "static", "js", "htmx-feedback.js");

function element(tag) {
  const node = {
    tag,
    className: "",
    dataset: {},
    attributes: {},
    children: [],
    parent: null,
    textContent: "",
    listeners: {},
    shown: 0,
    hidden: 0,
    setAttribute(name, value) { this.attributes[name] = value; },
    addEventListener(name, handler) { this.listeners[name] = handler; },
    append(...nodes) { for (const child of nodes) { child.parent = this; this.children.push(child); } },
    appendChild(child) { this.append(child); },
    remove() { if (this.parent) { this.parent.children = this.parent.children.filter((c) => c !== this); this.parent = null; } },
    showPopover() { this.shown += 1; },
    hidePopover() { this.hidden += 1; },
    get firstElementChild() { return this.children[0]; },
  };
  return node;
}

function setup() {
  const listeners = {};
  const body = element("body");
  body.addEventListener = (name, handler) => { listeners[name] = handler; };
  const created = [];
  global.document = {
    body,
    createElement: (tag) => { const node = element(tag); created.push(node); return node; },
  };
  let reloaded = 0;
  global.window = { location: { reload: () => { reloaded += 1; } } };
  global.setTimeout = () => 1;
  global.clearTimeout = () => {};
  delete require.cache[require.resolve(SCRIPT_PATH)];
  const api = require(SCRIPT_PATH);
  const region = () => body.children.find((c) => c.className === "clavaris-toast-region");
  return { api, listeners, region, reloaded: () => reloaded };
}

test("messageFor explains the statuses people actually hit", () => {
  const { api } = setup();
  assert.match(api.messageFor(0), /reach the server/);
  assert.match(api.messageFor(401), /session has expired/);
  assert.match(api.messageFor(403), /permission/);
  assert.match(api.messageFor(404), /no longer exists/);
  assert.match(api.messageFor(409), /conflicts/);
  assert.match(api.messageFor(429), /Too many/);
  assert.match(api.messageFor(500), /our side/);
  assert.match(api.messageFor(503), /our side/);
  assert.match(api.messageFor(418), /didn't work/);
});

test("isLoginRedirect recognises the login page and ignores everything else", () => {
  const { api } = setup();
  assert.equal(api.isLoginRedirect("http://localhost:8080/platform/login"), true);
  assert.equal(api.isLoginRedirect("/platform/login?continue"), true);
  assert.equal(api.isLoginRedirect("http://localhost:8080/platform/dashboard/organizations/1/users"), false);
  assert.equal(api.isLoginRedirect(""), false);
  assert.equal(api.isLoginRedirect(undefined), false);
});

test("a failed response shows an announced toast in a popover region", () => {
  const { listeners, region } = setup();

  listeners["htmx:responseError"]({ detail: { xhr: { status: 403 } } });

  const toasts = region().children;
  assert.equal(toasts.length, 1);
  assert.equal(toasts[0].attributes.role, "alert");
  assert.match(toasts[0].children[0].textContent, /permission/);
  assert.equal(region().attributes.popover, "manual");
  assert.equal(region().shown, 1);
});

test("the same message is not stacked twice", () => {
  const { listeners, region } = setup();

  listeners["htmx:responseError"]({ detail: { xhr: { status: 500 } } });
  listeners["htmx:responseError"]({ detail: { xhr: { status: 500 } } });

  assert.equal(region().children.length, 1);
});

test("at most three toasts are kept, oldest first out", () => {
  const { listeners, region } = setup();

  for (const status of [403, 404, 409, 429]) {
    listeners["htmx:responseError"]({ detail: { xhr: { status } } });
  }

  assert.equal(region().children.length, 3);
  assert.match(region().children[0].children[0].textContent, /no longer exists/);
});

test("the dismiss button removes its toast and closes the empty region", () => {
  const { listeners, region } = setup();
  listeners["htmx:responseError"]({ detail: { xhr: { status: 403 } } });
  const toast = region().children[0];
  const close = toast.children[1];
  const hiddenBefore = region().hidden;

  close.listeners.click();

  assert.equal(region().children.length, 0);
  assert.ok(region().hidden > hiddenBefore);
});

test("a send error and a timeout both report", () => {
  const { listeners, region } = setup();

  listeners["htmx:sendError"]();
  listeners["htmx:timeout"]();

  assert.equal(region().children.length, 2);
  assert.match(region().children[1].children[0].textContent, /took too long/);
});

test("a response that landed on the login page cancels the swap and reloads", () => {
  const { listeners, reloaded } = setup();
  const detail = { xhr: { responseURL: "http://localhost:8080/platform/login" }, shouldSwap: true };

  listeners["htmx:beforeSwap"]({ detail });

  assert.equal(detail.shouldSwap, false);
  assert.equal(reloaded(), 1);
});

test("an ordinary response is left to swap normally", () => {
  const { listeners, reloaded } = setup();
  const detail = { xhr: { responseURL: "http://localhost:8080/platform/dashboard" }, shouldSwap: true };

  listeners["htmx:beforeSwap"]({ detail });

  assert.equal(detail.shouldSwap, true);
  assert.equal(reloaded(), 0);
});
