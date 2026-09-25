## Agent skills

Project-local skills live in `.agents/skills/<id>/SKILL.md`; apply a skill when its description matches the task.

Phase 1 is a cinema-domain Spring Boot project; its [assignment](https://raw.githubusercontent.com/maytinhdibo/KTPM-architecture-solution/main/2026.md) takes precedence over every external skill when they conflict.

- Implement only requested behavior; do not invent cinema policies.
- Provide JSON REST GET, POST, and DELETE endpoints with an OpenAPI description.
- Keep API → business → data access; business imports neither Spring Web nor database libraries. Confine the chosen ORM/JPA implementation and persistence types to data access; generic JPA/`@Transactional` advice does not override this boundary.
- Provide login and protect at least one GET and POST through a shared Spring Security filter/security chain, not per-controller checks.
- Provide Docker and a README covering architecture/API. Test load on Kaggle CPU only when a complete backend exists; do not scaffold for docs-only changes. Use existing Java test tooling (Ponytail's Python self-checks are illustrative); Phase 2 optimization is out of scope.
- Claude Code uses the committed `.claude/skills/` links; if your checkout cannot preserve symlinks, copy the matching skill directories there locally.

### Issue tracker

Issues are tracked in GitHub Issues through the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Uses the five default labels: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `CONTEXT.md` and `docs/adr/` at the repo root. See `docs/agents/domain.md`.
