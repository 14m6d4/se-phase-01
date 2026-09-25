## Conventions

- Tests need Docker running (Testcontainers starts PostgreSQL).
- Access rules live in `config/SecurityConfig` alone. A protected operation also carries `@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)` so Swagger shows its lock.
- Persistence naming: Spring Data interface `<X>EntityRepository`, adapter `Jpa<X>Repository` implementing the business `<X>Repository`.
- Business errors are exceptions extending `ConflictException` / `NotFoundException`; `ApiExceptionHandler` turns them into problem+json.

## Agent skills

### Issue tracker

Issues are tracked in GitHub Issues through the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Uses the five default labels: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `CONTEXT.md` and `docs/adr/` at the repo root. See `docs/agents/domain.md`.
