---
name: logs
description: Tail logs for a Food Ordering System service, or list all services with their log commands
allowed-tools: Bash(docker *)
argument-hint: "[service-name]"
---

Tail Docker logs for a specific service, or show all available services if no argument is given.

## Available service names
`gateway-service`, `user-service`, `analytics-service`, `order-service`, `payment-service`, `product-service`, `postgres`, `redis`, `kafka`, `grafana`, `loki`, `tempo`

## Behaviour

**If `$ARGUMENTS` is empty:**

Print the following reference and then tail all microservice logs together:

```
Grafana (logs + dashboards): http://localhost:3000

Service log commands:
  docker compose logs -f gateway-service
  docker compose logs -f user-service
  docker compose logs -f analytics-service
  docker compose logs -f order-service
  docker compose logs -f payment-service
  docker compose logs -f product-service

Tailing all microservices now (Ctrl+C to stop)...
```

Then run:
```bash
docker compose logs -f gateway-service user-service analytics-service order-service payment-service product-service
```

**If `$ARGUMENTS` is a service name (e.g. `/logs order-service`):**

Run:
```bash
docker compose logs -f $ARGUMENTS
```
