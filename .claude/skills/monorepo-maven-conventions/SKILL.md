---
name: monorepo-maven-conventions
description: BOM, parent POM, and module conventions for this Maven monorepo. Use when adding dependencies, creating a new module, or editing any pom.xml.
allowed-tools: Read, Edit, Write
---

# Monorepo Maven Conventions

## Golden Rule

**BOM owns all dependency versions. Service POMs never declare `<version>` inside `<dependency>`.**

If you add a dependency to a service POM and it doesn't have a version, that's correct.
If it does have a version, remove it and add the version management to the root `pom.xml` `<dependencyManagement>` block instead.

## Structure

```
food-delivery-system-parent (root pom.xml)
├── common-libs                — shared events, constants, DTOs
├── user-service       :8081  — PostgreSQL (user_db) + Redis
├── analytics-service  :8082  — Redis only
├── order-service      :8083  — PostgreSQL (order_db)
├── payment-service    :8084  — PostgreSQL (payment_db)
├── product-service    :8085  — PostgreSQL (product_db)
├── basket-service     :8086  — Redis only (skeleton)
├── kitchen-service    :8087  — DynamoDB (skeleton)
├── delivery-service   :8088  — PostgreSQL (delivery_db) (skeleton)
├── review-service     :8089  — DynamoDB (skeleton)
├── promotion-service  :8090  — PostgreSQL (promotion_db) (skeleton)
└── notification-service:8091 — Kafka listener only, no DB (skeleton)
```

## Root pom.xml Key Properties

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.0.6</version>
</parent>
<properties>
    <java.version>25</java.version>
    <common.libs.version>1.0.0</common.libs.version>
    <springdoc.version>3.0.3</springdoc.version>
    <loki4j.version>2.0.3</loki4j.version>
</properties>
```

## Adding a Dependency to a Service

**Step 1** — Add to the service's `pom.xml` (NO version):
```xml
<dependency>
    <groupId>com.example</groupId>
    <artifactId>some-library</artifactId>
</dependency>
```

**Step 2** — If the dependency is NOT managed by the Spring Boot BOM, add it to root `pom.xml` `<dependencyManagement>`:
```xml
<dependency>
    <groupId>com.example</groupId>
    <artifactId>some-library</artifactId>
    <version>1.2.3</version>
</dependency>
```

Spring Boot BOM already manages: Spring Data, Spring Security, Spring Kafka, Micrometer, Jackson, Hibernate, PostgreSQL driver, Flyway (core), Redis client, etc.

**Explicitly managed in this project's root POM:**
- `food.ordering.system:common-libs` → `${common.libs.version}`
- `org.springdoc:springdoc-openapi-starter-webmvc-ui` → `${springdoc.version}`
- `com.github.loki4j:loki-logback-appender` → `${loki4j.version}`
- `software.amazon.awssdk:bom` → `2.29.0` (imported as BOM)

## Creating a New Module

1. Create `{service-name}/pom.xml` with parent reference and NO version in `<project>`:
```xml
<parent>
    <groupId>food.ordering.system</groupId>
    <artifactId>food-delivery-system-parent</artifactId>
    <version>1.0.0</version>
    <relativePath>../pom.xml</relativePath>
</parent>
<artifactId>{service-name}</artifactId>
<version>1.0.0</version>
```

2. Add `<module>{service-name}</module>` to root `pom.xml` modules list.

3. Add Dockerfile + docker-compose entry (commented out until implemented).

## Anti-Patterns

| Anti-pattern | Fix |
|---|---|
| `<version>` in service `<dependency>` block | Move to root `<dependencyManagement>` |
| Duplicate entries in `<dependencies>` | Remove one; Spring Boot deduplicates transitively |
| Different version of `spring-boot-starter-parent` in a service POM | Remove — parent is inherited |
| `<module>` added without a `pom.xml` existing | Create the pom.xml first |

## Verification

```bash
./mvnw dependency:tree -q        # no version conflict warnings
./mvnw compile -q                # all 12 modules compile
```
