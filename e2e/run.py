"""
Runs the end-to-end suites against a stack of their own.

    mvn package
    python e2e/run.py                 # every suite
    python e2e/run.py incidents chat  # only these

Needs Java 21, Python 3.11+ and a MongoDB on 127.0.0.1. Safe next to a running local stack: it
picks free ports, makes (and drops) its own database, inherits no secrets and can't reach the
internet. Logs: e2e/.out/.
"""
import argparse
import os
import re
import shutil
import signal
import subprocess
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import fake_model
from stack import Stack, clean_environment

HERE = Path(__file__).resolve().parent
OUT = HERE / ".out"
SUITE_TIMEOUT = 600
SUMMARY = re.compile(r"\d+/\d+ passed")  # printed by support.finish()


def all_suites():
    return sorted(path.stem for path in (HERE / "suites").glob("*.py"))


def run_suite(name, environment):
    started = time.time()
    log = OUT / f"suite-{name}.log"
    with log.open("wb") as output:
        try:
            code = subprocess.run([sys.executable, "-u", str(HERE / "suites" / f"{name}.py")],
                                  env=environment, stdout=output, stderr=subprocess.STDOUT,
                                  timeout=SUITE_TIMEOUT).returncode
        except subprocess.TimeoutExpired:
            output.write(f"\nFAIL timed out after {SUITE_TIMEOUT}s\n".encode())
            code = 124
    text = log.read_text(encoding="utf-8", errors="replace")
    summary = next((line for line in reversed(text.splitlines()) if SUMMARY.fullmatch(line)), None)
    if summary is None:
        summary = "did not finish"
        if code == 0:  # exited cleanly without reaching finish(), so nobody counted the checks
            code = 1
            text += "\nFAIL exited without calling finish()"
    return name, code, summary, time.time() - started, text


def main():
    for stream in (sys.stdout, sys.stderr):
        stream.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("suites", nargs="*", help="suite names (default: all)")
    parser.add_argument("--jobs", type=int, default=4, help="suites at a time (default 4)")
    parser.add_argument("--mongo-port", type=int, default=27017, help="port of the MongoDB on 127.0.0.1")
    args = parser.parse_args()

    known = all_suites()
    if not known:
        parser.error("no suites found in e2e/suites")
    chosen = args.suites or known
    if unknown := set(chosen) - set(known):
        parser.error(f"unknown suite {', '.join(sorted(unknown))}; there are: {', '.join(known)}")

    # A cancelled CI job sends SIGTERM: still stop the services and drop the database.
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(143))
    shutil.rmtree(OUT, ignore_errors=True)
    OUT.mkdir(parents=True, exist_ok=True)
    model = fake_model.start()
    threading.Thread(target=model.serve_forever, daemon=True).start()
    stack = Stack(args.mongo_port, OUT)
    failed = []
    try:
        print("starting the test stack...", flush=True)
        stack.start()
        environment = clean_environment(os.environ)
        environment.update(
            PYTHONPATH=str(HERE),
            PYTHONUTF8="1",
            E2E_GATEWAY_URL=stack.url("api-gateway"),
            E2E_CHAT_URL=stack.url("chat-service"),
            E2E_AI_URL=stack.url("ai-service"),
            E2E_AUTH_URL=stack.url("auth-service"),
            E2E_MODEL_URL=f"http://127.0.0.1:{model.server_port}")
        with ThreadPoolExecutor(max_workers=max(1, args.jobs)) as pool:
            for name, code, summary, seconds, text in pool.map(lambda n: run_suite(n, environment), chosen):
                print(f"{'ok  ' if code == 0 else 'FAIL'} {name:16} {summary:>14} {seconds:5.0f}s", flush=True)
                if code != 0:
                    failed.append(name)
                    lines = text.splitlines()
                    shown = [line for line in lines if line.startswith("FAIL")] or lines[-15:]
                    print("     " + "\n     ".join(shown), flush=True)
    except RuntimeError as error:
        print(f"FAIL {error}")
        return 1
    finally:
        stack.stop()
        model.shutdown()
    print(f"\n{len(chosen) - len(failed)}/{len(chosen)} suites passed" + (f"; failed: {', '.join(failed)}" if failed else ""))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
