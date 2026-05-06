# Food Ordering System — Getting Started

## Prerequisites

Make sure the following are installed on your machine before doing anything else.

| Tool | Minimum Version | Check |
|------|----------------|-------|
| Java | 25 | `java -version` |
| Docker | 24+ | `docker --version` |
| Docker Compose | v2 plugin | `docker compose version` |
| curl | any | `curl --version` |
| jq *(optional, pretty-prints JSON)* | any | `jq --version` |

Install jq on Ubuntu:
```bash
sudo apt install jq
```

---

## Project Structure

```
Food Ordering System/
├── common-libs/          # Shared DTOs, enums, Kafka constants
├── user-service/         # User registration, JWT auth (port 8081)
├── analytics-service/    # Consumes Kafka events, Redis counters (port 8082)
├── gateway-service/      # API gateway, JWT validation, rate limiting (port 8080)
├── order-service/        # Order CRUD, Kafka producer + consumer (port 8083)
├── init-db/              # PostgreSQL init scripts
├── docker-compose.yml    # Full infrastructure + services
├── start.sh              # One-command build & run script
├── generate-secrets.sh   # Generates .env with secure secrets
├── .env.example          # Template showing required env variables
└── CLAUDE.md             # Project context for Claude Code
```

---

## Quick Start (recommended)

Everything is handled by a single script:

```bash
./start.sh
```

That's it. The script will:
1. Check that Java, Docker, and Docker Compose are installed
2. Generate a `.env` file with a secure JWT secret (only on first run)
3. Build all services with Maven (skipped if JARs already exist)
4. Start all containers with Docker Compose
5. Wait until the gateway and user-service are healthy
6. Print the URLs and a quick test command

### Flags

```bash
./start.sh             # Normal start — skips Maven if JARs exist
./start.sh --rebuild   # Force Maven rebuild + Docker image rebuild
./start.sh --clean     # Wipe database volumes and start completely fresh
```

Use `--rebuild` after changing Java code.
Use `--clean` when you want a fresh database (all data is deleted).

---

## Manual Steps (if you prefer full control)

### 1. Generate secrets

```bash
./generate-secrets.sh
```

Creates a `.env` file at the project root with a cryptographically secure `JWT_SECRET`.
Run this once. If `.env` already exists the script will not overwrite it.

To regenerate:
```bash
rm .env && ./generate-secrets.sh
```

### 2. Build with Maven

```bash
./mvnw clean install -DskipTests
```

This compiles all modules in the correct dependency order:
`common-libs` → `user-service`, `analytics-service`, `gateway-service`

Each service gets a `.jar` file inside its `target/` directory.
The Dockerfiles copy these JARs — so **Maven must run before Docker**.

> `-DskipTests` skips unit tests to speed up the build.
> Remove it if you want tests to run: `./mvnw clean install`

### 3. Start Docker Compose

```bash
docker compose up --build
```

`--build` rebuilds the Docker images from the new JARs.
On the first run Docker will download all base images (~2-3 minutes).

Run in detached mode (background) to get your terminal back:
```bash
docker compose up --build -d
```

### 4. Check that everything is running

```bash
docker compose ps
```

All services should show `running`. Then check health endpoints:
```bash
curl http://localhost:8080/actuator/health   # gateway
curl http://localhost:8081/actuator/health   # user-service
```

Both should return `{"status":"UP"}`.

---

## Services & Ports

| Service | Port | URL |
|---------|------|-----|
| **Gateway** (entry point) | 8080 | http://localhost:8080 |
| **User Service** | 8081 | http://localhost:8081 |
| **Analytics Service** | 8082 | http://localhost:8082 |
| **Order Service** | 8083 | http://localhost:8083 |
| **Grafana** (logs & traces) | 3000 | http://localhost:3000 |
| **PostgreSQL** | 5432 | localhost:5432 |
| **Redis** | 6379 | localhost:6379 |
| **Kafka** | 9092 | localhost:9092 |
| **Loki** (log storage) | 3100 | http://localhost:3100 |
| **Tempo** (trace storage) | 3200 | http://localhost:3200 |

> Always send requests through the **Gateway on port 8080**, not directly to services.

---

## Understanding docker-compose.yml

The file is split into three logical groups:

### 1. Infrastructure
Core dependencies that all services rely on.

| Container | Image | Role |
|-----------|-------|------|
| `postgres-db` | postgres:16 | Single Postgres instance hosting three databases: `user_db`, `order_db`, `payment_db`. Databases are created at first startup by `init-db/init.sh` via the `docker-entrypoint-initdb.d` mount. |
| `redis-cache` | redis:alpine | Shared Redis instance. Used by user-service (refresh token storage) and gateway-service (rate limiting). Also used by analytics-service (role counters). |
| `zookeeper` | confluentinc/cp-zookeeper:7.5.0 | Required coordinator for Kafka. Not used directly by any application. |
| `kafka` | confluentinc/cp-kafka:7.5.0 | Message broker. Exposes two listeners: `kafka:29092` for internal Docker communication and `localhost:9092` for access from your host machine. |

### 2. Observability
Monitoring and tracing stack — all optional for functionality, required for dashboards.

| Container | Role |
|-----------|------|
| `loki` | Collects and stores logs shipped by each service via the Loki4j logback appender |
| `tempo` | Receives and stores distributed traces. Exposes a Zipkin-compatible endpoint on port 9411 which Spring Boot services write to |
| `grafana` | Dashboard UI. Reads from both Loki (logs) and Tempo (traces). Available at http://localhost:3000 |

### 3. Microservices
All services are built locally from source — there are no pre-built images pulled from a registry.

**Build pattern** (same for every service):
```yaml
build:
  context: .                          # build context is the project root
  dockerfile: user-service/Dockerfile # each service has its own Dockerfile
  args:
    SERVICE_PATH: user-service        # Dockerfile uses this to find the JAR in target/
```

The Dockerfiles copy the pre-built JAR from `<SERVICE_PATH>/target/*.jar`. This means **Maven must run before `docker compose up --build`**, otherwise there is no JAR to copy.

**Environment variable overrides** follow Spring Boot's convention — any `application.yaml` property can be overridden at runtime using its uppercase, dot-to-underscore equivalent:

| yaml property | docker-compose env var |
|---|---|
| `spring.datasource.url` | `SPRING_DATASOURCE_URL` |
| `spring.kafka.bootstrap-servers` | `SPRING_KAFKA_BOOTSTRAP_SERVERS` |
| `spring.data.redis.host` | `SPRING_DATA_REDIS_HOST` |

`JWT_SECRET` is the only secret — read from the `.env` file, which is generated by `start.sh` or `generate-secrets.sh` and never committed.

### Volume
`postgres_data` is a named Docker volume that persists the database across restarts. Running `docker compose down` preserves it. Running `docker compose down -v` or `./start.sh --clean` deletes it (wipes all data).

---

## Testing the API

### Step 1 — Register a user

```bash
curl -s -X POST http://localhost:8080/api/v1/users \
  -H "Content-Type: application/json" \
  -d '{"username":"john","password":"secret123","email":"john@example.com","role":"USER"}' \
  | jq
```

The user is created with `status=PENDING`. In the background:
- `user-service` publishes a `UserCreatedEvent` to Kafka
- `analytics-service` consumes it and increments a Redis counter
- `analytics-service` sends a confirmation back via Kafka
- `user-service` receives the confirmation and sets `status=ACTIVE`

**Wait 2-3 seconds** before logging in — the Saga needs to complete first.
Only `ACTIVE` users can log in. Attempting login while still `PENDING` returns `401`.

### Step 2 — Login

```bash
curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"john","password":"secret123"}' \
  | jq
```

Response:
```json
{
  "accessToken": "eyJ...",
  "refreshToken": "eyJ...",
  "tokenType": "Bearer",
  "expiresIn": 900000
}
```

- **Access token** — valid for 15 minutes. Use this in the `Authorization` header.
- **Refresh token** — valid for 7 days. Stored in Redis. Use it to get a new access token.

### Step 3 — Call a protected endpoint

```bash
TOKEN="eyJ..."   # paste your accessToken here

curl -s http://localhost:8080/api/v1/orders \
  -H "Authorization: Bearer $TOKEN"
```

- `200` or `404` → token was accepted by the gateway
- `401` → token is missing, expired, or invalid

### Step 4 — Refresh the access token

When the access token expires (after 15 min), use the refresh token to get a new one:

```bash
REFRESH="eyJ..."   # paste your refreshToken here

curl -s -X POST http://localhost:8080/api/v1/auth/refresh \
  -H "Content-Type: application/json" \
  -d "{\"refreshToken\":\"$REFRESH\"}" \
  | jq
```

Returns a new `accessToken` and a new `refreshToken`.
The old refresh token is automatically revoked in Redis.

### Step 5 — Logout

Revokes the refresh token from Redis so it can no longer be used:

```bash
curl -s -X POST http://localhost:8080/api/v1/auth/logout \
  -H "Content-Type: application/json" \
  -d "{\"refreshToken\":\"$REFRESH\"}"
```

Returns `204 No Content` on success.

---

## Viewing Logs & Traces in Grafana

Open **http://localhost:3000** in your browser.

### Logs (via Loki)
1. Go to **Explore** (compass icon in the left sidebar)
2. Select **Loki** as the data source
3. Use LogQL to query:
   ```
   {app="user-service"}
   {app="gateway-service", level="WARN"}
   {app="analytics-service"} |= "Saga"
   ```

### Traces (via Tempo)
1. Go to **Explore**
2. Select **Tempo** as the data source
3. Search by **Service Name** or paste a `traceId` from a log line

Every log line includes a `traceId` that links directly to the full distributed trace across all services.

---

## Common Commands

```bash
# Follow logs for a specific service
docker compose logs -f user-service
docker compose logs -f gateway-service
docker compose logs -f analytics-service

# Follow logs for all services at once
docker compose logs -f

# Stop all containers (data is preserved)
docker compose down

# Stop and delete all volumes (wipes the database)
docker compose down -v

# Restart a single service after a code change
./mvnw -pl user-service clean install -DskipTests
docker compose up --build -d user-service

# Check which containers are running
docker compose ps

# Open a shell inside a running container
docker compose exec user-service sh
```

---

## Secrets & Environment Variables

The project uses a `.env` file for local development.
This file is **gitignored** and must never be committed.

| Variable | Used by | Description |
|----------|---------|-------------|
| `JWT_SECRET` | user-service, gateway-service | 256-bit secret for signing/validating JWTs |
| `POSTGRES_USER` | postgres, user-service | Database username |
| `POSTGRES_PASSWORD` | postgres, user-service | Database password |
| `REDIS_HOST` | user-service, gateway-service, analytics-service | Redis hostname |
| `REDIS_PORT` | user-service, gateway-service, analytics-service | Redis port |

See `.env.example` for the full template.

### For cloud deployments
Do **not** use a `.env` file in production. Instead set the same variable names in your platform:
- **AWS ECS** — Task definition environment variables or Secrets Manager
- **Kubernetes** — `kubectl create secret` referenced in pod spec
- **Render / Railway / Fly.io** — Environment Variables section in the dashboard
- **GitHub Actions** — Repository Secrets passed to your deployment step

The application code never changes — it always reads from environment variables regardless of where they come from.

---

## Startup Order & Health Checks

Docker Compose enforces a strict startup sequence using health checks. Services only start after their dependencies are confirmed ready — not just running.

```
zookeeper → kafka ──────────────────────────────┐
postgres ───────────────────────────────────────┼→ user-service, order-service, payment-service
redis ──────────────────────────────────────────┘→ analytics-service, gateway-service
```

| Service | Health check | Notes |
|---------|-------------|-------|
| `postgres` | `pg_isready -U user` | Accepts connections |
| `redis` | `redis-cli ping` | Responsive |
| `zookeeper` | `echo ruok \| nc localhost 2181` | 4-letter protocol |
| `kafka` | `kafka-topics --bootstrap-server localhost:29092 --list` | Broker fully up; 30s grace period before checks start |

Kafka is the slowest to become healthy (~30-60s on first run). Microservices will not start until it passes.

---

## Troubleshooting

**Login returns 401 immediately after registration**
> The Saga hasn't completed yet. Wait 2-3 seconds and try again.

**`./start.sh` fails at the Maven step**
> Check the error output. Common cause: Java version mismatch.
> Verify with `java -version` — must be Java 25.

**Services start but requests return 502**
> A downstream service is still starting. Wait a few seconds and retry.
> Check with: `docker compose logs -f user-service`

**`docker compose up` fails with "JWT_SECRET is not set"**
> The `.env` file is missing. Run `./generate-secrets.sh` first.

**Port already in use**
> Another process is using one of the ports. Find and stop it:
> ```bash
> sudo lsof -i :8080    # replace with the conflicting port
> ```
