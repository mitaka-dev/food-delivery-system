---
name: saga-test
description: Run an end-to-end Saga test against the running system — registration, login, happy-path order (PAID) and failure-path order (FAILED)
disable-model-invocation: true
allowed-tools: Bash(curl *)
---

Run a full end-to-end test of both Saga flows through the gateway on port 8080.

## Test Plan

**Happy path:** `totalAmount=49.99` → payment succeeds → order becomes `PAID`
**Failure path:** `totalAmount=999.99` → payment-service rejects amounts > 500 → order becomes `FAILED`

## Steps

Execute the following bash steps in order and report each result as PASS or FAIL.

### Step 1 — Register a test user
```bash
TS=$(date +%s)
USERNAME="saga_test_$TS"
REGISTER=$(curl -s -w "\n%{http_code}" -X POST http://localhost:8080/api/v1/users \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"$USERNAME\",\"password\":\"Test1234!\",\"email\":\"$USERNAME@test.com\",\"role\":\"USER\"}")
REGISTER_BODY=$(echo "$REGISTER" | head -1)
REGISTER_CODE=$(echo "$REGISTER" | tail -1)
echo "Register → HTTP $REGISTER_CODE: $REGISTER_BODY"
```
PASS if HTTP 200.

### Step 2 — Wait for Saga activation
```bash
echo "Waiting 3s for analytics-service Saga to activate user..."
sleep 3
```

### Step 3 — Login and extract token
```bash
LOGIN=$(curl -s -w "\n%{http_code}" -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"$USERNAME\",\"password\":\"Test1234!\"}")
LOGIN_BODY=$(echo "$LOGIN" | head -1)
LOGIN_CODE=$(echo "$LOGIN" | tail -1)
TOKEN=$(echo "$LOGIN_BODY" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
echo "Login → HTTP $LOGIN_CODE | token: ${TOKEN:0:30}..."
```
PASS if HTTP 200 and token is non-empty.

### Step 4 — Create happy-path order (expect PAID)
```bash
ORDER1=$(curl -s -w "\n%{http_code}" -X POST http://localhost:8080/api/v1/orders \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"items":[{"productId":"00000000-0000-0000-0000-000000000001","quantity":1}],"totalAmount":49.99}')
ORDER1_BODY=$(echo "$ORDER1" | head -1)
ORDER1_CODE=$(echo "$ORDER1" | tail -1)
ORDER1_ID=$(echo "$ORDER1_BODY" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Create order (success) → HTTP $ORDER1_CODE | id: $ORDER1_ID"
```
PASS if HTTP 201.

### Step 5 — Create failure-path order (expect FAILED)
```bash
ORDER2=$(curl -s -w "\n%{http_code}" -X POST http://localhost:8080/api/v1/orders \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"items":[{"productId":"00000000-0000-0000-0000-000000000001","quantity":1}],"totalAmount":999.99}')
ORDER2_BODY=$(echo "$ORDER2" | head -1)
ORDER2_CODE=$(echo "$ORDER2" | tail -1)
ORDER2_ID=$(echo "$ORDER2_BODY" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Create order (failure) → HTTP $ORDER2_CODE | id: $ORDER2_ID"
```
PASS if HTTP 201.

### Step 6 — Wait for Saga to resolve both orders
```bash
echo "Waiting 3s for payment Saga to process both orders..."
sleep 3
```

### Step 7 — Check final order statuses
```bash
STATUS1=$(curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/orders/$ORDER1_ID \
  | grep -o '"status":"[^"]*"' | cut -d'"' -f4)
STATUS2=$(curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/orders/$ORDER2_ID \
  | grep -o '"status":"[^"]*"' | cut -d'"' -f4)
echo "Happy-path order status:  $STATUS1  (expected: PAID)"
echo "Failure-path order status: $STATUS2  (expected: FAILED)"
```

### Final summary
Print a PASS/FAIL table:

| Step | Result |
|------|--------|
| User registration | PASS / FAIL |
| Login + token | PASS / FAIL |
| Create happy-path order | PASS / FAIL |
| Create failure-path order | PASS / FAIL |
| Happy-path → PAID | PASS / FAIL |
| Failure-path → FAILED | PASS / FAIL |

If any step fails, print the relevant response body and suggest checking `/logs <service-name>` for details.
