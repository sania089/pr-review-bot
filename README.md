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
GITHUB_WEBHOOK_SECRET=some-long-random-string
GEMINI_API_KEY=...              # or GROQ_API_KEY / ANTHROPIC_API_KEY, see LLM_PROVIDER below
```
```bash
mvn spring-boot:run
ngrok http 8080                         # expose it for GitHub
```
The app refuses to start if the GitHub token, the webhook secret, or the API key of the selected
provider is missing.

In your test repo: Settings → Webhooks → payload URL `https://<ngrok>/webhook/github`, content type
`application/json`, the same secret, event "Pull requests".

`mvn verify` runs the tests and checks formatting; `mvn spotless:apply` fixes formatting.

## Configuration

| Property | Env var | Default | Notes |
|----------|---------|---------|-------|
| `prbot.github-token` | `GITHUB_TOKEN` | – (required) | Replaced by GitHub App auth later |
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
| `prbot.max-comments` | – | `15` | Cap on inline comments per review |
| `prbot.executor.threads` | – | `2` | Concurrent reviews |
| `prbot.executor.queue-capacity` | – | `20` | Waiting reviews; beyond this the webhook gets 503 and the review is skipped |

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
