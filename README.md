# MoSes Monitor

AI-assisted log + process monitoring system (Splunk-like), built around a custom
inverted-index search algorithm.

- **[ARCHITECTURE.md](ARCHITECTURE.md)** - service topology, data ownership, every
  architectural decision made and why, known limitations, what's not done yet.
- **[project_specification.md](project_specification.md)** - the search algorithm
  itself (time-bucketed inverted index on MongoDB).

## Services

| Service | Port (local) | Role |
|---|---|---|
| `ingestion-service` | 8081 | Receives logs, tokenizes, writes to Mongo |
| `search-service` | 8082 | Search (macro-filter → intersection → micro-filter) |
| `process-monitor-service` | 8083 | Process/service heartbeats + flow state |
| `ai-service` | 8084 | NL query → search, anomaly/summarization (skeleton) |
| `api-gateway` | 8085 (local) / `PORT` on Render | Public entry point, reverse proxy + CORS |
| `frontend` | 5173 (dev) | React UI (search + flow diagram) |

## Running locally

Requires Java 21, Maven, Node.js, and a local MongoDB on `localhost:27017`.

```bash
mvn clean package -DskipTests
java -jar ingestion-service/target/ingestion-service.jar &
java -jar search-service/target/search-service.jar &
java -jar process-monitor-service/target/process-monitor-service.jar &
java -jar ai-service/target/ai-service.jar &
java -jar api-gateway/target/api-gateway.jar &

cd frontend && npm install && npm run dev
```

## Deployment

`render.yaml` deploys all 6 services to [Render](https://render.com). Requires an
external MongoDB (e.g. MongoDB Atlas free tier) - set `MONGODB_URI` manually per
service in the Render dashboard after the first deploy.
