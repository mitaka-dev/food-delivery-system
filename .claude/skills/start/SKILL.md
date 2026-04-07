---
name: start
description: Start the Food Ordering System — build Docker images, start all services, wait for health checks
disable-model-invocation: true
allowed-tools: Bash
---

Start the Food Ordering System by running the automated startup script, then verify all services are healthy.

## Steps

1. Run the startup script from the project root:
   ```
   ./start.sh
   ```
   This generates secrets if missing, builds JARs (skipping if up-to-date), starts Docker Compose, and waits for the gateway and user-service to be healthy.

2. Once the script exits, verify all 6 services independently by curling their actuator health endpoints:

   ```bash
   for entry in "gateway-service:8080" "user-service:8081" "analytics-service:8082" "order-service:8083" "payment-service:8084" "product-service:8085"; do
     name="${entry%%:*}"
     port="${entry##*:}"
     status=$(curl -s -o /dev/null -w "%{http_code}" --max-time 3 http://localhost:$port/actuator/health)
     if [ "$status" = "200" ]; then
       echo "✓  $name ($port)  UP"
     else
       echo "✗  $name ($port)  DOWN (HTTP $status)"
     fi
   done
   ```

3. Report the results in a table. If any service is DOWN, show the last 20 lines of its Docker logs:
   ```bash
   docker compose logs --tail=20 <service-name>
   ```

4. Print the quick-access URLs:
   - Gateway:  http://localhost:8080
   - Grafana:  http://localhost:3000
   - Swagger (user):    http://localhost:8081/swagger-ui.html
   - Swagger (orders):  http://localhost:8083/swagger-ui.html
   - Swagger (products):http://localhost:8085/swagger-ui.html
