# PR Reviewer Bot

A Spring Boot service that receives GitHub `pull_request` webhooks, sends the diff to Claude, and posts
the result back as a PR review with inline comments.

```
GitHub ──webhook──▶ WebhookController ──▶ ReviewService (async)
                     (HMAC check)            │ 1. GitHubClient.fetchDiff
                                             │ 2. DiffAnnotator (add line numbers)
                                             │ 3. ClaudeClient.review (forced tool call → JSON)
                                             │ 4. drop comments outside the diff
                                             ▼ 5. GitHubClient.postReview
```

## Tech stack
- Java 21, Spring Boot 3.3 (Web, Actuator, Validation), Maven
- `RestClient` for the GitHub REST API and the Anthropic Messages API
- Structured output via a forced `submit_review` tool call (no free-text JSON parsing)
- JUnit 5 tests; later: Testcontainers + WireMock, Docker, Postgres, Micrometer

## Run it
```bash
export GITHUB_TOKEN=ghp_...            # fine-grained PAT: Pull requests (rw), Contents (r)
export GITHUB_WEBHOOK_SECRET=some-long-random-string
export ANTHROPIC_API_KEY=sk-ant-...
mvn spring-boot:run
ngrok http 8080                         # expose it for GitHub
```
In your test repo: Settings → Webhooks → payload URL `https://<ngrok>/webhook/github`, content type
`application/json`, the same secret, event "Pull requests".

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
