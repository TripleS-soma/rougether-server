# AGENTS.md

This file gives instructions to AI coding agents working in this repository.

## Project

Rougether is a social routine app backend. It connects routine completion with personal room growth and shared household experiences.

## Stack

- Java 17
- Spring Boot 4.1
- Gradle
- Spring Web MVC
- Spring Security
- Spring Data JPA
- Flyway
- MySQL
- H2 for local development and tests

## Working Rules

- Follow `CONTRIBUTING.md` for branch, commit, PR, review, and merge rules.
- Keep commit messages simple Conventional Commits, for example `feat: 루틴 완료 API 추가`.
- Do not require commit scopes unless the user explicitly asks.
- Prefer normal merge over squash merge so PR commit history remains visible.
- Never commit secrets, tokens, private keys, or `.env`.
- Keep `.env.example` safe and non-secret.

## Architecture Direction

- API prefix is `/api/v1`.
- Keep controllers thin.
- Add package boundaries when implementing concrete features.
- Use Flyway migrations for schema changes.
- Use `@Transactional` for write flows that update multiple tables.
- Use `@Transactional(readOnly = true)` for query services.

## Verification

Before reporting backend code changes as complete, run:

```bash
./gradlew test
```

For server smoke checks, run:

```bash
./gradlew bootRun
curl http://localhost:8080/api/v1/health
```

Do not leave `bootRun` or other long-running sessions active when finishing a task.
