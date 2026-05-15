---
name: logs
description: Tail logs for a Food Ordering System service. Use this skill whenever the user wants to view, watch, follow, or stream logs for any service — even if they say things like "show me what order-service is doing", "what's happening in payment", "watch the gateway logs", "tail kafka", or "list available log commands". Trigger even when the user doesn't say /logs explicitly.
allowed-tools: Bash(docker *)
argument-hint: "[service-name] [lines]"
---

Tail Docker logs for a specific service, or show all available services and their log commands if no argument is given.

## Available service names

**Microservices:** `gateway-service`, `user-service`, `analytics-service`, `order-service`, `payment-service`, `product-service`

**Infrastructure:** `postgres`, `redis`, `kafka`, `grafana`, `loki`, `tempo`

## Behaviour

**If `$ARGUMENTS` is empty:**

Print the following reference, then tail all microservices:

```
Grafana (logs + dashboards): http://localhost:3000

Microservice log commands:
  docker compose logs -f gateway-service
  docker compose logs -f user-service
  docker compose logs -f analytics-service
  docker compose logs -f order-service
  docker compose logs -f payment-service
  docker compose logs -f product-service

Infrastructure log commands:
  docker compose logs -f postgres
  docker compose logs -f redis
  docker compose logs -f kafka
  docker compose logs -f grafana

Tailing all microservices now (Ctrl+C to stop)...
```

Then run:
```bash
docker compose logs -f gateway-service user-service analytics-service order-service payment-service product-service
```

**If `$ARGUMENTS` contains a service name:**

1. Validate the service name against the known list above. If it does not match, print:
   ```
   Unknown service: <name>
   Valid services: gateway-service, user-service, analytics-service, order-service, payment-service, product-service, postgres, redis, kafka, grafana, loki, tempo
   ```
   and stop.

2. If a number is also present in `$ARGUMENTS` (e.g. `/logs order-service 100`), use it as `--tail`:
   ```bash
   docker compose logs -f --tail=<N> <service>
   ```

3. Otherwise:
   ```bash
   docker compose logs -f <service>
   ```
