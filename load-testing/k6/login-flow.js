// k6 load test for the interactive sign-in: authorize, sign in with a password, come back to
// authorize for a code, then exchange the code with the client secret and the PKCE verifier. It is
// the same flow as PkceLoginLoadTest.java (TD-TEST-007), driven the other way round: that test holds
// a fixed number of sign-ins in flight (closed model), this one has people ARRIVE at a rate whether
// or not the server has caught up (open model), which is what a login page really sees, and what
// makes a saturated server show up as a growing queue instead of a polite slowdown.
//
// Every iteration is a fresh browser (its own cookie jar). Each stage is timed on its own, because
// the two expensive ones cost different things: the sign-in is one Argon2id verification of a human
// password (ADR-0005) and the token exchange is the client-secret check.
//
// Environment (from setup-pkce-fixture.sh, which also creates the accounts):
//   CLAVARIS_BASE_URL ORG_ID CLIENT_ID CLIENT_SECRET REDIRECT_URI USER_EMAIL_PREFIX USER_PASSWORD ACCOUNTS
// Choose what to run with  -e SCENARIO=smoke|load|stress  (default smoke), and tune it with
//   -e RATE=<sign-ins per second for "load">  -e DURATION=<e.g. 60s>  -e MAX_RATE=<peak for "stress">
//
// Raise the instance's login and capacity rate limits first (see README.md §5), or the limiter, not
// the server, is what gets measured: the answer is then a stream of 429s and the run says so.

import crypto from "k6/crypto";
import encoding from "k6/encoding";
import exec from "k6/execution";
import http from "k6/http";
import { check } from "k6";
import { Counter, Rate, Trend } from "k6/metrics";

const BASE = (__ENV.CLAVARIS_BASE_URL || "http://localhost:8080").replace(/\/+$/, "");
const ORG = need("ORG_ID");
const CLIENT_ID = need("CLIENT_ID");
const CLIENT_SECRET = need("CLIENT_SECRET");
const REDIRECT_URI = __ENV.REDIRECT_URI || "https://client.example.test/callback";
const EMAIL_PREFIX = need("USER_EMAIL_PREFIX");
const PASSWORD = need("USER_PASSWORD");
const ACCOUNTS = parseInt(__ENV.ACCOUNTS || "30", 10);

function need(name) {
  const value = __ENV[name];
  if (!value) {
    throw new Error(`${name} is not set: run setup-pkce-fixture.sh and export its output`);
  }
  return value;
}

// One Trend per stage, so a threshold can name the stage that matters.
const stage = {
  authorize: new Trend("stage_authorize", true),
  loginPage: new Trend("stage_login_page", true),
  signIn: new Trend("stage_sign_in", true),
  code: new Trend("stage_code", true),
  token: new Trend("stage_token", true),
};
const flowDuration = new Trend("flow_duration", true);
const flowSuccess = new Rate("flow_success");
const rateLimited = new Counter("flow_rate_limited");
// Where the sign-ins that failed stopped, one counter per stage, so the summary says it.
const failedIn = {
  authorize: new Counter("flow_failed_authorize"),
  loginPage: new Counter("flow_failed_login_page"),
  signIn: new Counter("flow_failed_sign_in"),
  code: new Counter("flow_failed_code"),
  token: new Counter("flow_failed_token"),
};

const SCENARIOS = {
  // One sign-in, to prove the script and the fixture before spending time on a real run.
  smoke: { executor: "per-vu-iterations", vus: 1, iterations: 1, maxDuration: "1m" },
  // A steady arrival rate: the number that answers "can it carry N sign-ins a second".
  load: {
    executor: "constant-arrival-rate",
    rate: parseInt(__ENV.RATE || "2", 10),
    timeUnit: "1s",
    duration: __ENV.DURATION || "60s",
    preAllocatedVUs: 20,
    maxVUs: 200,
  },
  // A climb to find where it bends: latency rising, dropped iterations and failures starting.
  stress: {
    executor: "ramping-arrival-rate",
    startRate: 1,
    timeUnit: "1s",
    preAllocatedVUs: 20,
    maxVUs: 300,
    stages: [
      { target: Math.max(2, Math.round(parseInt(__ENV.MAX_RATE || "8", 10) / 4)), duration: "30s" },
      { target: Math.max(3, Math.round(parseInt(__ENV.MAX_RATE || "8", 10) / 2)), duration: "30s" },
      { target: parseInt(__ENV.MAX_RATE || "8", 10), duration: "60s" },
      { target: 0, duration: "15s" },
    ],
  },
};
const chosen = __ENV.SCENARIO || "smoke";
if (!SCENARIOS[chosen]) {
  throw new Error(`SCENARIO must be one of ${Object.keys(SCENARIOS).join(", ")}`);
}

export const options = {
  scenarios: { [chosen]: SCENARIOS[chosen] },
  // The token stage carries the NFR (p95 < 300 ms for /oauth2/token); the whole flow and the sign-in
  // are bounds taken from the 2026-10 measurements, loose on purpose: they catch a regression, not
  // a few milliseconds of noise. Not an SLO until someone decides it is.
  thresholds: {
    flow_success: ["rate>0.99"],
    stage_token: ["p(95)<300"],
    stage_sign_in: ["p(95)<2500"],
    flow_duration: ["p(95)<5000"],
    flow_rate_limited: ["count==0"],
  },
  summaryTrendStats: ["avg", "med", "p(90)", "p(95)", "p(99)", "max"],
};

http.setResponseCallback(http.expectedStatuses({ min: 200, max: 399 }));

function base64url(bytes) {
  return encoding.b64encode(bytes, "rawurl");
}

function pathOf(location) {
  return location.replace(/^https?:\/\/[^/]+/, "");
}

function queryParam(url, name) {
  const match = new RegExp(`[?&]${name}=([^&#]*)`).exec(url);
  return match ? decodeURIComponent(match[1]) : null;
}

function timed(trend, response) {
  trend.add(response.timings.duration);
  return response;
}

function fail(where, response) {
  failedIn[where].add(1);
  if (response && response.status === 429) {
    rateLimited.add(1);
  }
  flowSuccess.add(false);
  return null;
}

export default function () {
  const started = Date.now();
  const jar = new http.CookieJar();
  const params = (extra) => Object.assign({ jar, redirects: 0, tags: {} }, extra || {});

  const verifier = base64url(crypto.randomBytes(48));
  const challenge = base64url(crypto.sha256(verifier, "binary"));
  const state = base64url(crypto.randomBytes(12));
  const email = `${EMAIL_PREFIX}${(exec.scenario.iterationInTest % ACCOUNTS) + 1}@example.test`;

  // 1. Unauthenticated authorize: must bounce to this Organization's hosted login.
  const authorizeQuery =
    `response_type=code&client_id=${encodeURIComponent(CLIENT_ID)}` +
    `&redirect_uri=${encodeURIComponent(REDIRECT_URI)}&scope=openid&state=${state}` +
    `&code_challenge=${challenge}&code_challenge_method=S256`;
  let r = timed(stage.authorize, http.get(`${BASE}/o/${ORG}/oauth2/authorize?${authorizeQuery}`, params({ tags: { stage: "authorize" } })));
  if (r.status !== 302) return fail("authorize", r);

  // 2. The login page, for its CSRF token.
  r = timed(stage.loginPage, http.get(`${BASE}/o/${ORG}/login?clientId=${encodeURIComponent(CLIENT_ID)}`, params({ tags: { stage: "login_page" } })));
  const csrf = r.status === 200 ? /name="_csrf" value="([^"]+)"/.exec(r.body) : null;
  if (!csrf) return fail("loginPage", r);

  // 3. Sign in with a password: one Argon2id verification.
  r = timed(
    stage.signIn,
    http.post(
      `${BASE}/o/${ORG}/login?clientId=${encodeURIComponent(CLIENT_ID)}`,
      { _csrf: csrf[1], email, password: PASSWORD },
      params({ tags: { stage: "sign_in" } }),
    ),
  );
  if (r.status !== 302) return fail("signIn", r);
  const back = r.headers["Location"] || "";
  if (/\/login/.test(back) && !/oauth2\/authorize/.test(back)) return fail("signIn", r);

  // 4. Back to authorize, now signed in: the redirect carries the code.
  r = timed(stage.code, http.get(`${BASE}${pathOf(back)}`, params({ tags: { stage: "code" } })));
  const code = r.status === 302 ? queryParam(r.headers["Location"] || "", "code") : null;
  if (!code) return fail("code", r);

  // 5. Exchange the code: the client secret and the PKCE verifier.
  const basic = encoding.b64encode(`${encodeURIComponent(CLIENT_ID)}:${encodeURIComponent(CLIENT_SECRET)}`);
  r = timed(
    stage.token,
    http.post(
      `${BASE}/o/${ORG}/oauth2/token`,
      { grant_type: "authorization_code", code, redirect_uri: REDIRECT_URI, code_verifier: verifier },
      params({ headers: { Authorization: `Basic ${basic}` }, tags: { stage: "token" } }),
    ),
  );
  const ok = check(r, {
    "token answers 200": (res) => res.status === 200,
    "token response has an id_token": (res) => res.status === 200 && !!res.json("id_token"),
  });
  if (!ok) return fail("token", r);

  flowDuration.add(Date.now() - started);
  flowSuccess.add(true);
}
