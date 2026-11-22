# End-to-end tests

```
mvn package
python e2e/run.py                 # every suite, about 4 minutes
python e2e/run.py incidents chat  # only these
```

Needs Java 21, Python 3.11+ and MongoDB on `127.0.0.1:27017` (`--mongo-port` for another port).
CI runs the same commands on every branch push and pull request.

## Isolation

- The seven services run from copies of the jars, on free loopback ports, so a local stack can keep
  running. (Building in place still needs that stack stopped on Windows: it locks its jars.)
- Each run creates a database `moses_e2e_<random>` and drops it. The runner can't drop any other name.
- Children don't inherit `MONGODB_URI`, API keys, tokens or `SPRING_*` from your shell. Secrets are
  generated per run.
- HTTP(S) proxies point at a closed port. This guards against accidents; it is not a sandbox.
- The AI model is `fake_model.py`. No key is needed and no test calls a paid model.

## Writing a suite

A suite is a script in `suites/` that uses `support.py` and ends with `finish(expected=N)`, N being
the number of checks it must run. A failed check, a missing or extra check, a crash or a timeout
all fail the run. When you add a check, raise N.

Suites run in parallel on one stack: create your own users and project, and give the fake model an
API key containing `RUN`.

Logs of the last run (services and suites) are in `e2e/.out/`.

## Not covered

- Real models. The chat suite asserts that the agent explains it can't use tools with an
  OpenAI-compatible model; that changes when the agent supports tools for every provider.
- The browser UI. CI only checks that the frontend builds.
- The Docker images and Render.
