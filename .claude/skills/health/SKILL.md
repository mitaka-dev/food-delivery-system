---
name: health
description: Check health of all running Food Ordering System services without starting anything. Use when the user asks if services are up, whether the system is running, wants a status check, or says things like "is everything healthy?", "are the services running?", or "what's the system status?".
allowed-tools: Bash(curl *), Bash(docker *)
---

Check the health of all 6 microservices by curling their Spring Actuator endpoints. Does not start or restart anything.

## Steps

1. Run the health checks:

```bash
for entry in "gateway-service:8080" "user-service:8081" "analytics-service:8082" "order-service:8083" "payment-service:8084" "product-service:8085"; do
  name="${entry%%:*}"
  port="${entry##*:}"
  http_code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 3 http://localhost:$port/actuator/health 2>/dev/null)
  if [ "$http_code" = "200" ]; then
    echo "✓  $name  (port $port)  UP"
  elif [ -z "$http_code" ] || [ "$http_code" = "000" ]; then
    echo "✗  $name  (port $port)  NOT REACHABLE"
  else
    echo "✗  $name  (port $port)  DOWN  (HTTP $http_code)"
  fi
done
```

2. Present the results as a clean status table.

3. For any service that is NOT REACHABLE or DOWN, show its last 20 log lines:
```bash
docker compose logs --tail=20 <service-name>
```

4. If any service is down, suggest running `/start` to bring the system up.
