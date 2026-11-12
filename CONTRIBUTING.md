# Contributing

Thanks for helping. Keep changes small and focused.

1. Open an issue first for anything bigger than a bug fix.
2. Branch from `main` (`feature/short-name` or `fix/short-name`).
3. Backend: JDK 21, `docker compose up -d postgres`, then `mvn verify` (tests and Spotless formatting; `mvn spotless:apply` fixes
   formatting). Frontend: `cd frontend && npm ci && npm run build && npm run lint && npm test`.
4. Add a test for new behaviour, including the error path. Tests must not call the real GitHub or LLM APIs (use WireMock).
5. New configuration goes in `application.yml` with a safe default and in the README configuration table.
6. Never commit secrets, tokens or code from reviewed repositories, and do not log them.
7. Open a pull request. CI must be green.

Design rules: small single-purpose classes, constructor injection, immutable records, and an interface only when there are two real
implementations. See [DEVELOPMENT_PLAN.md](DEVELOPMENT_PLAN.md) for the architecture and the backlog.
