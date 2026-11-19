#!/usr/bin/env python3
"""Runs the OpenID Foundation Basic OP plan against a Clavaris Organization, module by module.

Why a script: the plan has 35 modules, the suite's scripted browser has to be told what each one
will see, and a result is only worth keeping if it can be repeated. See
docs/05-engineering/openid-conformance-plan.md, section 11, for what each result means.

Standard library only. Nothing secret lives here: the clients and the test account come from a JSON
file kept outside the repository (copy config.example.json, fill it in, and keep it out of Git).

  scripts/conformance/run-basic-op.py --config conformance.local.json
  scripts/conformance/run-basic-op.py --config conformance.local.json --module oidcc-server

The suite is reached through its API (no token in a development suite). Three plans are created,
because the scripted browser has to behave differently for three kinds of module:

  default  sign in when asked, then wait for the callback
  errors   the request is refused and the browser stays on Clavaris's error page: the page's heading
           is waited for and captured as the evidence the suite asks for
  login    the sign-in page shown a second time (prompt=login, max-age=1) is captured before the
           script signs in again

Things worth knowing, each learned the hard way:
  * `wait contains` waits for the URL to contain the text, not the page. Text on the page needs
    `wait xpath`.
  * oidcc-server-client-secret-post copies config.client_secret_post into config.client for a
    static client; without that key it stops with "must contain a client configuration".
  * The suite's scripted browser stops delivering the callback after the first module of a run, so
    the suite's server is restarted before every module (`suite_container` in the config).
  * The scripted browser cannot produce an image. A module that asks for a screenshot ends in REVIEW
    with the page's source stored as its evidence.

Exit code: 0 when every module finished and none failed, 1 otherwise.
"""
import argparse
import json
import ssl
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

PLAN = "oidcc-basic-certification-test-plan"
PLAN_VARIANT = {"server_metadata": "discovery", "client_registration": "static_client"}
ERROR_PAGE_MODULES = {
    "oidcc-response-type-missing",
    "oidcc-ensure-registered-redirect-uri",
    "oidcc-ensure-request-object-with-redirect-uri",
}
SECOND_LOGIN_MODULES = {"oidcc-prompt-login", "oidcc-max-age-1"}
ERROR_HEADING = "This request cannot be completed"
PASSING = {"PASSED", "WARNING", "SKIPPED", "REVIEW"}


class Suite:
    """The conformance suite's REST API."""

    def __init__(self, api, verify_tls, container):
        self.api = api.rstrip("/")
        self.container = container
        self.ctx = ssl.create_default_context()
        if not verify_tls:
            self.ctx.check_hostname = False
            self.ctx.verify_mode = ssl.CERT_NONE

    def call(self, method, path, body=None, query=None):
        url = self.api + path + ("?" + urllib.parse.urlencode(query) if query else "")
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(
            url, data=data, method=method, headers={"Content-Type": "application/json"}
        )
        last = None
        for _ in range(5):
            try:
                with urllib.request.urlopen(request, context=self.ctx, timeout=60) as response:
                    raw = response.read().decode()
                    return json.loads(raw) if raw else None
            except urllib.error.HTTPError:
                raise
            except OSError as error:  # the suite restarting, a reset socket
                last = error
                time.sleep(4)
        raise last

    def restart(self):
        if not self.container:
            return
        subprocess.run(["docker", "restart", self.container], check=True, capture_output=True)
        for _ in range(90):
            try:
                self.call("GET", "/api/server")
                return
            except OSError:
                time.sleep(3)
        sys.exit("the suite did not come back after a restart")

    def create_plan(self, config):
        created = self.call(
            "POST", "/api/plan", config, {"planName": PLAN, "variant": json.dumps(PLAN_VARIANT)}
        )
        return created["id"]

    def modules(self, plan_id):
        return self.call("GET", "/api/plan/" + plan_id)["modules"]

    def run_module(self, plan_id, module, variant, timeout):
        variant = json.dumps({**PLAN_VARIANT, **(variant or {})})
        started = self.call(
            "POST", "/api/runner", query={"test": module, "plan": plan_id, "variant": variant}
        )
        test_id = started["id"]
        deadline = time.time() + timeout
        info = {}
        while time.time() < deadline:
            time.sleep(3)
            try:
                info = self.call("GET", "/api/info/" + test_id)
            except OSError:
                continue
            if info.get("status") in ("FINISHED", "INTERRUPTED"):
                break
        return test_id, info.get("status"), info.get("result")


def plan_config(settings, kind):
    """The suite's test configuration for one kind of module."""
    client1, client2 = settings["client1"], settings["client2"]
    account = settings["account"]
    login_commands = [
        ["text", "name", "email", account["email"]],
        ["text", "name", "password", account["password"]],
        ["click", "css", "button[type=submit]"],
    ]
    login = {
        "task": "Login",
        "match": settings["login_url_pattern"],
        "optional": True,
        "commands": login_commands,
    }
    callback = {
        "task": "Verify Complete",
        "match": settings["suite_url"] + "/test/a/" + settings["alias"] + "/callback*",
        "commands": [["wait", "contains", "callback", 10]],
    }
    tasks = [login, callback]
    if kind == "errors":
        heading = "//h1[contains(text(),'%s')]" % ERROR_HEADING
        tasks = [
            login,
            {
                "task": "Verify error page",
                "match": settings["authorize_url_pattern"],
                "commands": [
                    ["wait", "xpath", heading, 10, ".*%s.*" % ERROR_HEADING, "update-image-placeholder"]
                ],
            },
        ]
    elif kind == "login":
        capture = ["wait", "xpath", "//input[@name='email']", 10, ".*", "update-image-placeholder-optional"]
        login["commands"] = [capture] + login_commands
    return {
        "alias": settings["alias"],
        "description": "Clavaris Basic OP, " + kind,
        "server": {"discoveryUrl": settings["discovery_url"]},
        "client": {"client_id": client1["client_id"], "client_secret": client1["client_secret"]},
        "client2": {"client_id": client2["client_id"], "client_secret": client2["client_secret"]},
        "client_secret_post": {
            "client_id": client1["client_id"],
            "client_secret": client1["client_secret"],
        },
        "browser": [{"match": settings["authorize_url_pattern"], "tasks": tasks}],
    }


def kind_of(module):
    if module in ERROR_PAGE_MODULES:
        return "errors"
    return "login" if module in SECOND_LOGIN_MODULES else "default"


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n", maxsplit=1)[0])
    parser.add_argument("--config", required=True, help="JSON file; see config.example.json")
    parser.add_argument("--module", action="append", help="run only this module (repeatable)")
    parser.add_argument("--results", default="basic-op-results.json", help="where to write results")
    parser.add_argument("--timeout", type=int, default=240, help="seconds allowed per module")
    args = parser.parse_args()
    with open(args.config, encoding="utf-8") as handle:
        settings = json.load(handle)

    suite = Suite(
        settings["suite_api"], settings.get("verify_suite_tls", False), settings.get("suite_container")
    )
    plans = {kind: suite.create_plan(plan_config(settings, kind)) for kind in ("default", "errors", "login")}
    print("plans", plans, flush=True)

    results = []
    for module in suite.modules(plans["default"]):
        name = module["testModule"]
        if args.module and name not in args.module:
            continue
        plan_id = plans[kind_of(name)]
        suite.restart()
        test_id, status, result = suite.run_module(plan_id, name, module.get("variant"), args.timeout)
        results.append(
            {"module": name, "plan": plan_id, "testId": test_id, "status": status, "result": result}
        )
        print(name, test_id, status, result, flush=True)
        with open(args.results, "w", encoding="utf-8") as handle:
            json.dump(results, handle, indent=1)

    failed = [r["module"] for r in results if r["status"] != "FINISHED" or r["result"] not in PASSING]
    summary = {}
    for r in results:
        summary[r["result"]] = summary.get(r["result"], 0) + 1
    print("summary", summary, "not passing:", failed or "none")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
