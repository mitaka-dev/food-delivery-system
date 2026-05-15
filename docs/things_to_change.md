Codebase Profile

 - Language: Java 25
 - Framework: Spring Boot 3.5.11, Spring Cloud 2025.1.1
 - Architecture: Multi-module Maven, 7 microservices (gateway, user, order, product, payment, analytics, common-libs)
 - Key Libraries: Spring Kafka, Spring Data JPA, PostgreSQL, Micrometer + OpenTelemetry, Loki4j, SpringDoc OpenAPI 2.x, Prometheus
 - Existing Skills: commit, health, logs, plan-session, saga-test, start (6 skills)
 - Existing Plugins (global): context7 ✓, superpowers ✓, feature-dev ✓, claude-md-management ✓, skill-creator ✓

 ---
 Recommendations

 1. Fix Project Permissions (settings.local.json)

 Problem: Current project permissions are broken (malformed paths) and missing critical commands the project needs daily.

 Current (broken):
 "allow": [
   "Bash(cd /home/warrick/Projects/Java/\"Food Ordering System\")",  // never runs
   "Bash(mvn compile:*)",     // wrong binary (should be ./mvnw)
   "Skill(health)",
   "Bash(grep -r ...)"        // oddly specific
 ]

 Fix — replace settings.local.json with:
 {
   "permissions": {
     "allow": [
       "Bash(./mvnw *)",
       "Bash(./start.sh *)",
       "Bash(docker compose *)",
       "Bash(docker-compose *)",
       "Bash(grep *)",
       "Bash(find *)",
       "Bash(curl *)",
       "Bash(cat *)",
       "Skill(*)"
     ]
   },
   "outputStyle": "Explanatory"
 }

 ▎ Note: Global settings.json denies Bash(docker *) — this catches docker compose too. The project-level allow takes precedence for docker compose and docker-compose commands
 ▎  when working in this project.

 ---
 2. Hook: Block .env Edits (PreToolUse)

 Why: The .env file contains JWT_SECRET and DB credentials. The global settings deny Read(.env*) but don't block writes/edits at the project level. A rogue edit here would
 silently break auth across all services.

 Add to .claude/settings.local.json → hooks:
 "hooks": {
   "PreToolUse": [
     {
       "matcher": "Edit|Write|MultiEdit",
       "hooks": [
         {
           "type": "command",
           "command": "echo $CLAUDE_TOOL_INPUT | python3 -c \"import json,sys; d=json.load(sys.stdin); p=d.get('file_path',''); sys.exit(1 if '.env' in p and not
 p.endswith('.example') else 0)\"",
           "blockOnFailure": true
         }
       ]
     }
   ]
 }

 ---
 3. MCP Server: PostgreSQL

 Why: During debugging, you frequently need to inspect order states, user records, and payment results directly. Right now that requires docker exec -it postgres psql ...
 manually. A PostgreSQL MCP lets Claude run SELECT queries inline during debugging sessions.

 Install:
 claude mcp add postgres
 # Connection string: postgresql://postgres:<password>@localhost:5432/foodorderingdb

 ▎ This pairs well with the existing saga-test skill — when orders end up in unexpected states, you can immediately query the DB to see why.

 ---
 4. Skill: kafka-inspect

 Why: The Saga pattern lives and dies by Kafka. When saga-test reports a FAIL, the next step is always "what's in the topic?" Right now there's no shortcut — you'd have to
 manually docker exec into a Kafka container. A kafka-inspect skill would let you run /kafka-inspect to list topics, check consumer group lag, and tail recent messages.

 Create .claude/skills/kafka-inspect/SKILL.md:
 ---
 name: kafka-inspect
 description: Inspect Kafka topics, consumer group lag, and recent messages for the Food Ordering System
 disable-model-invocation: true
 allowed-tools: Bash(docker *)
 ---

 Show Kafka health for the running system.

 ## Topics
 \`\`\`bash
 docker exec kafka kafka-topics.sh --bootstrap-server localhost:9092 --list
 \`\`\`

 ## Consumer Group Lag
 \`\`\`bash
 docker exec kafka kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --all-groups
 \`\`\`

 ## Tail Recent Messages on a Topic
 Usage: /kafka-inspect <topic-name>
 \`\`\`bash
 docker exec kafka kafka-console-consumer.sh \
   --bootstrap-server localhost:9092 \
   --topic ${1:-order-topics} \
   --from-beginning \
   --max-messages 10
 \`\`\`

 Report the output, highlight any consumer lag > 0, and flag unprocessed messages.

 ---
 5. Subagent: saga-reviewer

 Why: The Kafka choreography saga across 4 services (order → product + payment → order-confirmation → order status) is easy to break with subtle ordering bugs. A
 purpose-built subagent that knows the saga invariants can catch issues during code review that a general reviewer would miss.

 Create .claude/agents/saga-reviewer.md:
 ---
 name: saga-reviewer
 description: Review Kafka saga choreography changes — checks topic ordering, compensating transactions, idempotency, and consumer group isolation across
 order/payment/product/analytics services
 ---

 You are a specialist in event-driven saga choreography for the Food Ordering System.

 ## Saga Invariants to Enforce
 You are a specialist in event-driven saga choreography for the Food Ordering System.

 ## Saga Invariants to Enforce

 **Topic flow:** order-topics → (product-service + payment-service) → order-confirmation-topic → order-service status update

 **Rules:**
 1. Every saga step must be idempotent — duplicate Kafka messages must not create duplicate DB records
 2. Payment failure (amount > 500) must always trigger a stock compensation in product-service
 3. Analytics-service consumes from order-confirmation-topic — it must never write back to order-topics
 4. Consumer groups: each service must have its own consumer group ID; shared groups cause missed messages
 5. Order status transitions: PENDING → CONFIRMED/FAILED only (no backward transitions)
 6. No synchronous HTTP calls between services — all cross-service communication via Kafka

 ## Review Checklist

 For any changed file in order-service, payment-service, product-service, analytics-service, or common-libs:

 - [ ] Kafka listener method is annotated correctly (@KafkaListener with explicit groupId)
 - [ ] No @Transactional wrapping a Kafka send (send happens after commit)
 - [ ] Compensation path exists for every happy-path step
 - [ ] Entity saves use the correct optimistic locking version field
 - [ ] No new synchronous REST calls added between services

 Report findings as HIGH/MEDIUM/LOW with the file:line reference.

 ---
 What's Already Good

 - context7 is installed globally — use use context7 in prompts when looking up Spring Boot, Kafka, or OpenAPI docs
 - superpowers plugin provides TDD, debugging, brainstorming, and plan workflows
 - 6 custom skills are already well-crafted, especially saga-test which is a complete E2E smoke test
 - Global Stop hook plays a bell — useful for long builds

 ---
 Implementation Order

 1. Fix settings.local.json — unblocks daily workflow immediately (broken permissions cause constant prompts)
 2. Add .env block hook — safety-critical, 5 minutes to add
 3. Add kafka-inspect skill — highest-value new skill given the Saga architecture
 4. Add saga-reviewer subagent — invest when actively working on Saga changes
 5. PostgreSQL MCP — add when you need DB inspection during a debugging session

 ---
 Verification

 After implementing:
 1. Run /health — should execute without permission prompts for grep/curl
 2. Try editing .env — should be blocked by the hook
 3. Run /kafka-inspect — should list topics (requires running system)
 4. Ask Claude to review a Kafka listener change — saga-reviewer should activate