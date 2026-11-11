# PR Reviewer Bot

A Spring Boot service that receives GitHub `pull_request` webhooks, sends the diff to an LLM (Gemini, Groq or Claude), and posts
the result back as a PR review with inline comments.

```
GitHub ──webhook──▶ WebhookController ──▶ ReviewService (async)
                     (HMAC check)            │ 1. GitHubClient.fetchDiff
                                             │ 2. DiffAnnotator (add line numbers)
                                             │ 3. LlmClient.review (JSON-schema output)
                                             │ 4. drop comments outside the diff
                                             ▼ 5. GitHubClient.postReview
```

## Tech stack
- Java 21, Spring Boot 3.3 (Web, Actuator, Validation), Maven
- `RestClient` for the GitHub REST API and the LLM APIs (Gemini, Groq, Anthropic), chosen with `prbot.llm.provider`
- Structured output via JSON schema (Gemini, Groq) or a forced tool call (Claude); no free-text JSON parsing
- JUnit 5 tests; later: Testcontainers + WireMock, Docker, Postgres, Micrometer

## Run it
Needs JDK 21 and Maven. Put secrets in environment variables, or in a git-ignored `.env` file in the
project root (`KEY=value` per line, read automatically at startup):
```bash
GITHUB_TOKEN=ghp_...            # fine-grained PAT: Pull requests (rw), Contents (r)
# or, for a GitHub App: GITHUB_AUTH=app, GITHUB_APP_ID=..., GITHUB_APP_PRIVATE_KEY="-----BEGIN RSA PRIVATE KEY-----
..."
GITHUB_WEBHOOK_SECRET=some-long-random-string
ADMIN_PASSWORD=at-least-12-characters   # dashboard login (user name: admin)
GEMINI_API_KEY=...              # or GROQ_API_KEY / ANTHROPIC_API_KEY, see LLM_PROVIDER below
```
```bash
mvn spring-boot:run
ngrok http 8080                         # expose it for GitHub
```
The app refuses to start if the GitHub credentials, the webhook secret, or the API key of the selected
provider is missing.

In your test repo: Settings → Webhooks → payload URL `https://<ngrok>/webhook/github`, content type
`application/json`, the same secret, event "Pull requests".

`mvn verify` runs the tests and checks formatting; `mvn spotless:apply` fixes formatting.

## Configuration

| Property | Env var | Default | Notes |
|----------|---------|---------|-------|
| `prbot.github.auth` | `GITHUB_AUTH` | `pat` | `pat` (local dev, one token) or `app` (GitHub App, works on every repo it is installed on) |
| `prbot.github.token` | `GITHUB_TOKEN` | – | Required when `auth=pat` |
| `prbot.github.app-id` | `GITHUB_APP_ID` | – | Required when `auth=app` |
| `prbot.github.app-private-key` | `GITHUB_APP_PRIVATE_KEY` | – | Required when `auth=app`. The `.pem` text GitHub gives you (PKCS#1 or PKCS#8); literal `
` sequences are accepted so it fits on one env line |
| `prbot.webhook-secret` | `GITHUB_WEBHOOK_SECRET` | – (required) | Webhook HMAC secret |
| `prbot.llm.provider` | `LLM_PROVIDER` | `gemini` | `gemini`, `groq` or `claude` |
| `prbot.llm.gemini.api-key` / `.model` | `GEMINI_API_KEY` | – / `gemini-3.5-flash-lite` | |
| `prbot.llm.groq.api-key` / `.model` | `GROQ_API_KEY` | – / `openai/gpt-oss-120b` | Free tier caps at ~8k tokens/min, so only small diffs fit |
| `prbot.llm.claude.api-key` / `.model` | `ANTHROPIC_API_KEY` | – / `claude-sonnet-5-5` | Only needed when `provider=claude` |
| `prbot.max-diff-chars` | – | `60000` | Size budget for the diff sent to the model. Whole files only: files that do not fit are skipped and named in the review summary |
| `prbot.http.connect-timeout` | – | `10s` | Connect timeout for GitHub and LLM calls |
| `prbot.http.read-timeout` | – | `60s` | Read timeout for GitHub and LLM calls |
| `prbot.http.max-attempts` | – | `3` | Tries per call on HTTP 429, 5xx or network errors |
| `prbot.http.retry-base-delay` | – | `1s` | First backoff, doubled each retry (a `Retry-After` header wins) |
| `prbot.http.retry-max-delay` | – | `30s` | Upper bound for any single wait |
| `spring.datasource.url` / `.username` / `.password` | `DB_URL`, `DB_USER`, `DB_PASSWORD` | local Postgres from `docker-compose.yml` | JDBC URL, e.g. Neon: `jdbc:postgresql://<host>/<db>?sslmode=require`. Flyway creates the schema on startup. The DB is not part of `/actuator/health`, so uptime pings do not keep Neon awake |
| `prbot.max-comments` | – | `15` | Hard upper bound on inline comments per review (`.prbot.yml` strictness can only lower it) |
| `prbot.max-batch-chars` | – | `24000` | Diff characters per model request. A bigger PR is reviewed in several requests, each with its own set of files. Lower it (about `12000`) for Groq's free tier |
| `prbot.quota.requests-per-minute` | – | `12` | Model requests are spaced evenly to stay under this. Default leaves headroom under Gemini's 15 RPM |
| `prbot.quota.daily-limit` | – | `450` | Model requests per day, counted in the database. When used up, new reviews are recorded as SKIPPED and nothing is posted. Default leaves headroom under Gemini's 500 RPD |
| `prbot.quota.reset-zone` | – | `America/Los_Angeles` | Time zone in which the day (and the provider's quota) rolls over |
| `prbot.admin.username` | `ADMIN_USERNAME` | `admin` | User name of the dashboard login |
| `prbot.admin.password` | `ADMIN_PASSWORD` | – (required, 12+ chars) | Password of the dashboard login (`POST /api/login`). The app refuses to start without it |
| `server.servlet.session.cookie.secure` | `SESSION_COOKIE_SECURE` | `true` | Session cookie is HttpOnly and SameSite=Strict. Set `false` only to test over plain http on a non-localhost host |
| `prbot.executor.threads` | – | `2` | Concurrent reviews |
| `prbot.executor.queue-capacity` | – | `20` | Waiting reviews; beyond this the webhook gets 503 and the review is skipped |

## Per-repo settings: `.prbot.yml`

Put a `.prbot.yml` in the root of a repository. It is read from the pull request's **base branch**, so a PR cannot change its own review rules.

```yaml
# Files to leave out of the review, on top of lockfiles, generated and vendored files.
ignore:
  - docs/          # a directory: everything below it
  - "*.md"         # no slash: matches at any depth
  - /build         # leading slash: only at the repo root
  - src/**/gen/**  # ** crosses directories, * stays inside one

# low:    only high severity findings, at most 5 comments
# normal: medium and high, at most 10 comments (default)
# high:   everything, at most 15 comments
strictness: normal
```

Unknown keys, a wrong type or invalid YAML make the bot ignore the whole file and use the defaults. The review summary says so. `prbot.max-comments` stays an upper bound.

## Milestones

| # | Milestone | Done when |
|---|-----------|-----------|
| 1 | **Skeleton + webhook** (day 1) | Webhook reaches the app, bad signatures get 401, `ping` returns 200. |
| 2 | **End-to-end review** (day 1-2) | Opening a PR produces a review with a summary and inline comments. |
| 3 | **Robustness** (day 2-3) | Truncation of huge diffs, retries with backoff on 429/5xx, dedupe per head SHA, skip bot PRs and lockfiles. |
| 4 | **Demo polish** (weekend end) | README GIF, a deliberately buggy demo PR, deployed on a free tier. |
| 5 | **GitHub App auth** (week 2) | Replace the PAT with a GitHub App (JWT → installation token), so it works across repos. |
| 6 | **Persistence + dashboard** (week 2-3) | Postgres table of reviews (repo, PR, tokens, cost, latency), simple React page. |
| 7 | **Context + config** (week 3) | Fetch full contents of changed files, read `.prbot.yml` from the repo (ignore paths, strictness). |
| 8 | **Evals** (week 3-4) | A set of PRs with known bugs; measure recall and false-positive rate per prompt version. |
| 9 | **Observability** (week 4) | Micrometer metrics for latency, token usage, comments posted; trace per review. |
