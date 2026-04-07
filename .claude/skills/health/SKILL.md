---
name: health
description: Check health of all running Food Ordering System services without starting anything
allowed-tools: Bash(curl *)
---

Check the health of all 6 microservices by curling their Spring Actuator endpoints. Does not start or restart anything.

## Steps

Run the following health checks and report results:

```bash
for entry in "gateway-service:8080" "user-service:8081" "analytics-service:8082" "order-service:8083" "payment-service:8084" "product-service:8085"; do
  name="${entry%%:*}"
  port="${entry##*:}"
  response=$(curl -s --max-time 3 http://localhost:$port/actuator/health 2>/dev/null)
  http_code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 3 http://localhost:$port/actuator/health 2>/dev/null)
  if [ "$http_code" = "200" ]; then
    echo "✓  $name  (port $port)  UP"
  elif [ -z "$http_code" ] || [ "$http_code" = "000" ]; then
    echo "✗  $name  (port $port)  NOT REACHABLE (container may be down)"
  else
    echo "✗  $name  (port $port)  DOWN  (HTTP $http_code)"
  fi
done
```

Present the results as a clean status table. If any service is not reachable, suggest running `/start` to bring it up.
