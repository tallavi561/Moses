# Changelog

Every change ships as a new version: the same number in the root `pom.xml`, every module's
`pom.xml` and `frontend/package.json`, an entry here, and a git tag `vX.Y.Z`.
Which number goes up:
- **Patch** (1.3.0 -> 1.3.1) for small changes: fixes, UI improvements, redesigns of an existing
  page, internal refactors that don't change an API.
- **Minor** (1.3 -> 1.4) only for a substantial new capability.
- **Major** (1 -> 2) only for a breaking change to an API that monitored services or the SKILL use.

`python scripts/bump-version.py X.Y.Z` sets the number everywhere. The running version is shown
at the bottom of the sidebar.

## [1.3.2] - 2026-11-22

### Security
- The agent's git no longer uses the credentials of the machine MoSes runs on. A project's
  repository URL is typed by a user, so on a host with saved git credentials a private repository
  could have been cloned and read through the agent. Git now runs anonymously: no credential
  helper, git config (except HTTPS trust settings), .netrc, askpass or inherited secrets. Only
  public repositories can be read, as documented.
- Clones cached by earlier versions are no longer read (new ones go in `repos/anonymous`). On a
  host that ran an earlier version, delete the other folders in the repository cache.

## [1.3.1] - 2026-11-22

### Fixed
- Phones: the sidebar is a slide-in menu (menu button in the top bar; closes on navigation, a tap
  outside or Esc) instead of taking most of the screen, and pages use the full width. The search
  box gets its own row, the dashboard chart fits without sideways scrolling, and the Architecture
  page opens on the list on phones (the graph is still one tap away). Desktop is unchanged.

### Added
- `scripts/bump-version.py` sets the version in every pom and in the frontend package files.

## [1.3.0] - 2026-11-22

### Added
- MoSes can be installed on a phone's home screen as a web app, with the eagle icon: a web app
  manifest, icons (including an Android "maskable" one) and a service worker that caches nothing,
  so data is always live. On Android a banner offers "Install" (the browser's own install dialog);
  on iPhone it explains Share > Add to Home Screen. Not shown once installed, on desktops, or for
  two weeks after "Not now". Installing needs the site on HTTPS (or localhost).

### Changed
- The iPhone home-screen icon is a full square tile (iOS rounds it; transparent corners showed black).

## [1.2.0] - 2026-11-22

### Added
- Search: a time range for logs - quick ranges (15 minutes to 14 days) or a custom range from a
  date and time to another (or until now), in local time. The range is in the URL (`?range=` or
  `?from=&to=`), so a search can be shared. Logs are kept 14 days, so earlier starts are refused
  with an explanation. A fixed past range doesn't auto-refresh; ranges ending now still do.
- An incident's "service logs" link opens Search on the incident's time (5 minutes before and after).

## [1.1.0] - 2026-11-22

### Added
- **Incidents** (no AI): every minute MoSes looks for error spikes (ERROR/FATAL logs of a service
  against its own normal amount and its alert rule) and for services that went DOWN. A problem is
  one incident until it is quiet again. Opening and closing are posted to the team chat. New
  Incidents page with open incidents, history and per-service alert rules (min errors, x normal,
  mute).
- **Incident library**: the team reviews each incident (solved / no solution found / not a real
  problem, root cause, fix). Incidents with the same kind of error, in any service or environment,
  are "seen before", and a new alert quotes the last fix. Library tab with search.
- **Request flows**: MoSes learns from traces which services requests of an entry point reach and
  suggests them. Only flows the team approves (or adds by hand) are checked; too many broken
  requests open an incident that shows where each one stopped.
- **Investigate with AI**: on one incident, the project's model proposes a root cause and fix from
  the logs, traces, code and similar past incidents. The team decides what goes into its review.
  The chat agent can search the incident library.
- **Dashboards**: environment or service dashboards with a CPU / memory / errors-per-minute chart;
  shared with everyone or private to their creator; editable in free text with the project's AI
  model; the chart's Y axis is the highest point plus 10%.
- **Monitoring access** page: what MoSes receives per signal and per service, and what it can't
  see yet.
- Chat: @-mentions with a filtered list of who can be tagged; the eagle animates while the agent
  works.
- Settings: the integration SKILL can be copied, not only downloaded; the owner can show an
  environment's API key again.
- Search links: `?service=` and `?trace=` open Search on a service's logs or a trace.
- The version is shown in the sidebar.

### Changed
- Metrics charts are served from a shared in-memory cache (per environment and range), so changing
  the selected services no longer queries the database.

### Fixed
- Right after an owner set up an AI model, the agent kept answering "no model" for up to 30 seconds.

## [1.0.0] - 2026-11-18

First baseline release: log ingestion and search (structured and natural language), traces,
service status and architecture, CPU/memory heartbeats, projects with environments and API keys,
team chat with the AI agent, and the integration SKILL.
