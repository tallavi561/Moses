# Changelog

Every change ships as a new version: the same number in the root `pom.xml`, every module's
`pom.xml` and `frontend/package.json`, an entry here, and a git tag `vX.Y.Z`.
Versions follow [semver](https://semver.org): a new feature bumps the minor version, a fix the
patch version, and a breaking change to an API that monitored services or the SKILL use bumps
the major version. The running version is shown at the bottom of the sidebar.

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
