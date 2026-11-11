# PR Review Bot – Development Plan

Single source of truth for building and launching the bot. Free tiers and open-source tools only.
Day 1 = Mon 2 Nov 2026, Day 11 = Thu 12 Nov 2026 if worked daily. Dates are targets, the order is what matters.

## How to use this file

**Picking up work**
1. Find the first unchecked task in the lowest-numbered day (`- [ ]`). Finish the current day before starting the next unless blocked.
2. Create a branch: `feature/day-N-short-name` (or `fix/...`). One branch per day or per task group.
3. Implement the task, with tests where the task says so.
4. Run the checks below, then commit with a message like `day-N: short description`.
5. Tick the box (`- [x]`), add a short note after it if something differs from the plan, and commit the file with the code.

**Definition of done for every task**
- `mvn verify` passes (and `npm run lint && npm test` once `frontend/` exists).
- No secrets, tokens or code from reviewed repos in logs or commits.
- New config is in `application.yml` with a safe default and documented in the README config table.
- New behaviour has at least one test; error and edge paths are handled, not just the happy path.
- A task that grew or changed is updated here instead of silently diverging.

**Rules of thumb**
- Keep it simple. Add an interface only when there are two real implementations (`LlmClient`, `TokenProvider`).
- Keep classes small and single-purpose: controllers parse and respond, services orchestrate, clients talk to one external API, repositories talk to the database.
- Constructor injection, immutable records for data, no static state except pure helpers.
- Anything not needed for launch goes to the backlog at the bottom, not into the current day.

**Status key:** `[ ]` todo, `[x]` done, `[~]` in progress (use only for the task you are on), `[!]` blocked (add the reason after it).

## Architecture at a glance

```
GitHub ──webhook──▶ WebhookController ──▶ ReviewService (bounded executor)
                     (HMAC check)            │ 1. dedupe in DB (repo, PR, head SHA)
                                             │ 2. GitHubClient.fetchDiff (TokenProvider)
                                             │ 3. filter files, truncate at file boundary, DiffAnnotator
                                             │ 4. LlmClient.review (free-tier provider, optional Claude)
                                             │ 5. drop comments outside the diff, apply .prbot.yml
                                             ▼ 6. GitHubClient.postReview, update review row

React (Vite, served from Spring static/) ──▶ /api/login, /api/reviews, /api/stats ──▶ Postgres (Neon)
```

Package layout (`dev.prbot`): `webhook`, `review`, `github`, `llm`, `config`, `persistence`, `api`, plus `frontend/` at the repo root.

## Free stack

| Need | Choice |
|------|--------|
| LLM | Free-tier provider (Gemini free tier or Groq; decide on Day 1), `ClaudeClient` optional |
| Database | Neon free Postgres |
| Hosting | Render free web service (Docker) |
| Keep-alive and alerts | UptimeRobot free, 5-minute check on `/actuator/health` |
| CI | GitHub Actions (public repo) |
| Frontend | Vite, React, TypeScript, Tailwind, shadcn/ui, React Router, TanStack Query |
| Tests | JUnit 5, WireMock, Testcontainers (repository only), Vitest, React Testing Library |

Free-tier limits change. Confirm them on Day 1 and record the numbers in the notes section below.

## Baseline (already done)

- [x] Spring Boot skeleton, `@EnableAsync`, `BotProperties`
- [x] Webhook endpoint with HMAC signature check, `ping` handling, action and draft filtering
- [x] `GitHubClient`, `ClaudeClient`, `DiffAnnotator`, `ReviewService` end-to-end flow (PAT auth, no retries)
- [x] Unit tests for `SignatureVerifier` and `DiffAnnotator`

---

## Day 1 – Foundation and quality gates
*Done when: CI is green, the app will not start without secrets, and a free LLM provider is chosen.*

- [x] **CI pipeline.** GitHub Actions running `mvn verify` on every push and PR; add Spotless for formatting. _Note: GitHub Actions runs `mvn verify`, which includes the Spotless check (google-java-format, AOSP style, 4-space indent). Also untracked the committed `target/` folder and added `.gitignore`._
- [x] **Confirm free tiers.** Check current limits for the LLM provider, Neon, Render and UptimeRobot. Run one real diff through the candidate LLM and pick the default provider. _Note: LLM trial done, provider picked and Gemini limits recorded (see notes log). Neon, Render and UptimeRobot limits deferred to Day 3 / Day 10 when they are set up._
- [x] **`LlmClient` interface.** Move `ClaudeClient` behind it, add the free-tier implementation with JSON-schema structured output, select with `prbot.llm.provider`. _Note: Gemini, Groq and Claude implementations; Claude is only created when `provider=claude`, so no Anthropic key is needed otherwise._
- [x] **Fail-fast config and errors.** `@Validated` `BotProperties` with `@NotBlank` secrets; a `@RestControllerAdvice` for the webhook; no secrets in logs; a request ID in the log context. _Note: also fixed a skeleton bug where `owner/repo` was sent as `owner%2Frepo` and GitHub would 404._
- [x] **Bounded executor.** Replace the default `@Async` executor with a small `ThreadPoolTaskExecutor` and bounded queue; rejected work is logged and recorded as SKIPPED. _Note: the SKIPPED row is a log line plus HTTP 503 until the `reviews` table exists on Day 3 (TODO in `WebhookController`)._

## Day 2 – Review pipeline robustness
*Done when: a 429 or 5xx is retried, and huge or noisy PRs produce a sensible review.*

- [x] **Retries with backoff.** One small retry helper used by `GitHubClient` and the LLM client: retry on 429/5xx and network errors, honour `Retry-After`, 3 attempts, explicit connect and read timeouts. _Note: `Retrier` in `dev.prbot.http`; timeouts via a `RestClientCustomizer` (10s connect, 60s read), all under `prbot.http.*`. `Retry-After` is capped at `retry-max-delay` (30s) so a worker thread is never parked for minutes. Known trade-off: a `postReview` that times out after GitHub accepted it is retried and could post twice; Day 3 dedupe does not cover this._
- [x] **File filtering.** Parse the diff per file and drop lockfiles, minified or generated files, binaries and vendored paths with a list of predicates. _Note: `FileDiff` + `DiffFilter`. Also drops deleted files, pure renames and mode changes (no hunks), and files with a "generated" banner._
- [x] **Smarter truncation.** Cut at file boundaries, not at a character offset mid-hunk (the current `substring` can split a line). Review the files that fit and say which were skipped. _Note: `DiffTruncator`; a file too big on its own is skipped, later smaller ones can still fit. Hunk-level splitting of a single huge file is not done._
- [x] **Skip rules.** Ignore bot-authored PRs, drafts (already done) and PRs with an empty filtered diff. _Note: bot = PR author type `Bot` or login ending `[bot]` (so Dependabot and Renovate are skipped). Empty-diff and "everything too large" skips are log-only until the Day 3 `reviews` table (TODO in `ReviewService`)._
- [x] **Tests.** Unit tests for the filters, truncation and retry helper (no network).

## Day 3 – Persistence and idempotency
*Done when: the same PR + SHA delivered twice is reviewed once, and every review is stored.*

- [x] **Neon Postgres + Flyway.** Free Neon database, `JdbcClient` (no ORM for one table), Flyway migration for `reviews`: repo, PR, head SHA, status, provider, model, tokens in/out, latency, error, timestamps. Neon scales to zero: small Hikari pool, connection timeout and validation. _Note: `spring-boot-starter-jdbc` + `JdbcClient`, migration `V1__reviews.sql` (also has `comments_posted` for Day 6 stats), Hikari pool of 3 with 0 idle and 20s connect timeout, env vars `DB_URL`/`DB_USER`/`DB_PASSWORD`. The DB health check is disabled so the 5-minute UptimeRobot ping does not keep Neon awake. Creating the actual Neon project is still a manual step._
- [x] **Dedupe by unique key.** `UNIQUE (repo, pr_number, head_sha)`; insert as QUEUED before dispatch and skip on conflict. _Note: one `INSERT ... ON CONFLICT` in `WebhookController`. Deviation: a FAILED or SKIPPED row is re-queued when the webhook is redelivered (so a retry is possible); QUEUED and POSTED are never re-run. A duplicate returns 200 `duplicate`._
- [x] **Status lifecycle.** QUEUED → POSTED / FAILED / SKIPPED, written by `ReviewService`; failures store a short error. _Note: queue-full and empty/oversized diffs are now SKIPPED rows with a reason; exceptions are FAILED with a 500-char error. This resolved the Day 1 and Day 2 TODOs._
- [x] **Usage tracking.** Store token counts and latency per review (feeds the quota guard on Day 5). _Note: `LlmClient.review` now returns `LlmResponse` (review + input/output tokens); latency is the LLM call time. Gemini output tokens include thinking tokens._
- [x] **Repository test.** One Testcontainers test for insert, conflict and status updates. `docker compose` Postgres for local dev. _Note: `ReviewRepositoryTest` skips itself when Docker is missing (it was skipped on the dev machine, runs in CI)._

## Day 4 – GitHub App authentication
*Done when: the bot reviews PRs on two different repos where the App is installed, with no PAT.*

- [x] **Create the GitHub App.** Permissions: Pull requests (rw), Contents (r), Metadata (r); subscribe to Pull request events; set the webhook secret.
- [x] **`TokenProvider` interface.** `PatTokenProvider` (local dev) and `GitHubAppTokenProvider` (RS256 JWT → installation token). _Note: selected with `prbot.github.auth` (`pat`/`app`); JWT is signed with the JDK (`AppJwt`, no library) and accepts PKCS#1 and PKCS#8 PEM keys. `prbot.github-token` became `prbot.github.token`._
- [x] **Token cache.** Cache installation tokens per installation until about 5 minutes before expiry; read `installation.id` from the webhook payload. _Note: `ConcurrentHashMap.compute` per installation, so concurrent reviews mint one token._
- [x] **Wire `GitHubClient`.** Take the token per call from the provider. _Note: `fetchDiff` and `postReview` take `installationId`, passed from the webhook through `ReviewService`._
- [x] **Tests.** Unit-test the JWT claims and cache expiry; manual check on two repos. _Note: unit tests done (`AppJwtTest`, `GitHubAppTokenProviderTest`); manual check with the real App on real PRs done by the owner (review comment received)._

## Day 5 – Review quality and per-repo config
*Done when: reviews on about 10 real PRs are useful with few false alarms, and repos can tune the bot.*

- [x] **Prompt pass.** Versioned prompt resource file; bugs, security and correctness only, with severity, no style nits; store the prompt version per review. Review large PRs in per-file batches (free models have smaller context windows). _Note: prompt in `prompts/review-v1.txt` (`ReviewPrompt.VERSION`), stored in `reviews.prompt_version` (migration V2). `DiffBatcher` groups whole files up to `prbot.max-batch-chars` (24000); one model request per batch, summaries joined, findings merged, most severe first. A single file over the batch size is sent alone, so Groq's 8k TPM can still 413 on it._
- [x] **`.prbot.yml` support.** Fetch from the PR's base branch; support `ignore` paths and `strictness` (low / normal / high maps to comment cap and minimum severity). Invalid file falls back to defaults and is mentioned in the summary. _Note: `RepoConfig` (SnakeYAML safe constructor, unknown keys are errors, size and pattern limits). Strictness: low = high only, 5 comments; normal = medium+, 10; high = all, 15; capped by `prbot.max-comments`. Behaviour change: `low` severity findings are no longer posted by default. Ignore globs are gitignore-like (see README)._
- [x] **Spot-check set.** Run on about 10 PRs (some with seeded bugs), note misses and false positives, adjust the prompt once or twice. A formal eval harness is post-launch.
- [x] **Quota guard.** Pace LLM calls under the provider's requests-per-minute limit and stop at a configurable daily request cap; when hit, post nothing and record SKIPPED with the reason. _Note: `QuotaGuard` reserves a review's requests up front against a per-day counter in `llm_usage` (survives restarts, resets at midnight Pacific), then spaces calls at `prbot.quota.requests-per-minute` (12). Provider-side retries after a 429 are not counted._

## Day 6 – Dashboard API and login
*Done when: the API returns real review history and stats and is not publicly readable.*

- [x] **Read endpoints.** `GET /api/reviews` (paged, filter by repo/status), `GET /api/reviews/{id}`, `GET /api/stats` (reviews, comments posted, failure rate, p50/p95 latency, tokens over 7/30 days) via SQL aggregates. _Note: `ReviewsController` (`dev.prbot.api`), `ReviewRepository.findPage/count/findById`, `StatsRepository` (7 and 30 day windows). Failure rate = FAILED / (POSTED + FAILED); percentiles are over POSTED only. `size` is capped at 100. Responses include a `prUrl`. The SQL tests need Docker (they run in CI). Posted comment bodies are not stored, so the Day 7 detail view can show only the count unless a comments column is added._
- [x] **Login for the React app.** Spring Security with one admin credential from the environment: `POST /api/login` sets an HttpOnly, SameSite=Strict session cookie; `/api/**` requires it; webhook, health and static files stay public (webhook exempt from CSRF, it has its own HMAC). _Note: `SecurityConfig` + `AuthController` (`dev.prbot.api`). Credential is `prbot.admin.username/password` (`ADMIN_USERNAME`/`ADMIN_PASSWORD`, password required and 12+ chars, so the app will not start without it). Added `GET /api/me` for the Day 7 route guard and `POST /api/logout` (needs CSRF). CSRF is on with an `XSRF-TOKEN` cookie that the SPA echoes in `X-XSRF-TOKEN`; the webhook and `/api/login` are exempt. Session cookie is HttpOnly, SameSite=Strict, Secure by default (`SESSION_COOKIE_SECURE`), 8h. Sessions live in memory, so a restart logs you out. No brute-force throttle yet (backlog). `@ConfigurationPropertiesScan` moved from `PrBotApplication` to `PropertiesConfig` so `@WebMvcTest` works._
- [x] **Metrics without a metrics stack.** Micrometer counters for reviews by status, LLM latency and tokens, surfaced via `/api/stats` and `health`. _Note: `ReviewMetrics` (`dev.prbot.metrics`) records counters from the `ReviewRepository` status transitions, so every path (queue full, quota, failure) is counted. They are process-lifetime and shown in `/api/stats` as `sinceStartup`; the history comes from SQL. `/actuator/health` gets a `reviews` indicator with the counters, but `show-details` is `when-authorized`, so the public UptimeRobot ping sees only UP and the DB is never touched._
- [x] **API tests.** `@WebMvcTest` for the endpoints, login flow and 401 behaviour. _Note: `ReviewsControllerTest` (standalone MockMvc: paging, filters, 400s, 404, stats) and `ApiSecurityTest` (`@WebMvcTest` with the real filter chain: 401 everywhere, login, wrong credentials, logout with CSRF, public paths). Written with the tasks above, not at the end._

## Day 7 – React app: scaffold, login and dashboard
*Done when: you can log in to the React app and see real review history with stats from your local backend.*

- [x] **Scaffold.** Vite + React + TypeScript in `frontend/`, Tailwind with shadcn/ui, React Router, TanStack Query, ESLint. Dev proxy sends `/api` to Spring Boot. _Note: Vite 8, React 19, Tailwind 4 (`@tailwindcss/vite`), React Router, TanStack Query, ESLint flat config, Vitest. shadcn/ui is used as a pattern (hand-written `Button`, `Input`, `Card` with `cva` + `cn` in `src/components/ui`), not via its CLI. `npm run dev` proxies `/api` to `localhost:8080`._
- [x] **Typed API client.** One module wraps `fetch`, handles 401 by redirecting to login, defines response types in one place. _Note: `src/lib/api.ts` is the only `fetch` caller. It sends the `X-XSRF-TOKEN` header on non-GET calls and calls an unauthorized handler on 401 (except `/api/me` and `/api/login`)._
- [x] **Login page and route guard.** Form posting to `/api/login`; private routes check the session via a TanStack Query call. _Note: `RequireAuth` + `useSession` (`/api/me`, 401 means logged out); after login the user returns to the page they came from._
- [x] **Dashboard page.** Stat tiles, reviews table with status badges, repo and status filters, pagination, link to each PR, loading, empty and error states. _Note: Stat tiles with a 7/30 day switch, filters (repo text, status), pagination, PR links, loading/empty/error states._
- [x] **Review detail.** Side panel or page with summary, posted comments, tokens, latency and any error reason. _Note: Page at `/reviews/:id`. It shows the count of comments posted, not their text (not stored). Not yet run against a real backend; SPA deep links (refresh on `/reviews/1`) need a Spring forward to `index.html`, added on Day 10 with the Docker build._

## Day 8 – Landing page, polish and frontend tests
*Done when: a stranger understands the product from the landing page and the whole app looks finished.*

- [x] **Landing page.** What the bot does, a real review screenshot or GIF, three-step install (install the App, open a PR, get a review), install button. _Note: public route `/` (`LandingPage`); the dashboard moved to `/dashboard`. The screenshot is a real review the bot posted on this repo (`public/demo-review.png`). The install button reads `VITE_GITHUB_APP_URL` (set to the public App URL in the committed `frontend/.env.production`, non-secret) and shows a disabled state if empty._
- [x] **Docs page.** In-app setup and `.prbot.yml` reference. _Note: public route `/docs` with setup, what is skipped, `.prbot.yml` reference and data handling (`/docs#data`)._
- [x] **Visual polish.** One set of Tailwind design tokens, light and dark theme, responsive to phone width, keyboard focus states, readable contrast, page titles and favicon. _Note: tokens are CSS variables in `index.css` with a `.dark` override; theme toggle follows the OS by default and is remembered in localStorage. `usePageTitle` per page, SVG favicon. Not yet checked by eye at phone width or in a contrast tool (owner check)._
- [x] **Frontend tests and CI.** Vitest + React Testing Library for dashboard states and the login guard; add `tsc`, ESLint and tests to the CI workflow. _Note: 11 tests (added landing, docs, theme toggle, login redirect back). New `frontend` CI job runs `npm ci`, `npm run build` (tsc + vite), lint and tests._

## Day 9 – End-to-end tests and security pass
*Done when: the webhook-to-posted-review flow is tested offline and the security checklist is clear.*

- [x] **WireMock E2E.** `@SpringBootTest` posts a signed webhook, WireMock plays GitHub and the LLM; cover happy path, duplicate delivery, LLM 429 then success, LLM failure (FAILED), quota exhausted (SKIPPED), diff over the limit, comment outside the diff. _Note: `ReviewFlowE2ETest`, real Postgres (Testcontainers in CI, or `E2E_DB_URL`/`E2E_DB_USER`/`E2E_DB_PASSWORD` for a throwaway local database, because it truncates tables; skipped when neither exists). GitHub and Gemini base URLs are now properties (`prbot.github.api-url`, `prbot.llm.gemini.base-url`). The datasource is always overridden, so the test never touches the `.env` database. Also covers bad signature (401), oversized body (413) and security headers._
- [x] **Security checklist.** Constant-time signature compare (verify), reject oversized webhook bodies, no secrets or code in logs, least-privilege App permissions, Dependabot and GitHub code scanning, `npm audit`, no secrets in the frontend bundle, Content-Security-Policy header. _Note: signature compare was already `MessageDigest.isEqual`. New: `BodySizeLimitFilter` (413 over `prbot.webhook.max-bytes`, 411 without Content-Length), CSP (`default-src 'self'`, no inline) and Referrer-Policy, failed-review reasons no longer store upstream response bodies, `.github/dependabot.yml` (Maven, npm, Actions), CodeQL workflow (Java, JS/TS), `npm audit --omit=dev --audit-level=high` in CI. `npm audit` of dev tools shows one moderate `minimatch` advisory via ESLint (not shipped). Frontend bundle grepped for key patterns: clean. Owner checks: App permissions in GitHub settings, and Code scanning / Dependabot alerts enabled in the repo's Security settings._
- [x] **Prompt-injection guard.** The diff is untrusted data: the prompt says to ignore instructions inside code, and the bot can only post a review. _Note: the prompt already had the rule; added defusing of `</diff>` (any case or spacing) inside the diff so it cannot close the data block, plus tests. The review event is fixed to `COMMENT`, asserted in the E2E test, so the bot cannot approve or request changes. Prompt version stays v1 (the text did not change)._
- [x] **Burst test.** 20 webhooks in a row locally; queue and pacing keep requests under the free-tier limit. _Note: automated in the E2E class: 20 signed webhooks, all accepted, all POSTED, and the gaps between model requests are at least 150 ms at 300 requests/minute (200 ms spacing). Run against a mock, so it checks pacing and queueing, not the real provider._

## Day 10 – Containerise and deploy on free tiers
*Done when: the production instance reviews a real PR through the real GitHub App.*

- [ ] **Dockerfile.** Three stages: Node builds the React app, Maven builds the JAR with the build output in `static/`, slim JRE image runs as non-root. `docker-compose.yml` for local app + Postgres. _Note: files written (`Dockerfile`, `.dockerignore`, `docker-compose.yml` app profile, `render.yaml`, `SpaForwardController` for deep links, `server.port` reads `PORT`); `mvn verify` passes (140 tests). Not ticked until the image has been built and run on a machine with Docker._
- [ ] **Deploy.** Render free web service from the Dockerfile, connected to Neon. Secrets in Render (App private key, webhook secret, LLM API key, admin password); Flyway runs on startup.
- [ ] **Point the GitHub App** at the Render webhook URL; install on a test repo and one real repo you own.
- [ ] **Keep awake and watched.** UptimeRobot checks `/actuator/health` every 5 minutes (prevents sleep, emails on downtime). Render auto-deploy from `main`, only after CI passes.
- [ ] **Smoke test.** PR with a seeded bug, confirm the review, check the dashboard row, redeliver the webhook and confirm no duplicate. Test once after idle for cold-start behaviour.

## Day 11 – Docs and launch
*Done when: the project is public, documented and announced.*

- [ ] **README rewrite.** Architecture diagram, 5-minute setup, configuration table, `.prbot.yml` reference, data-handling note (diffs go to the configured LLM; free tiers of some providers may use prompts to improve products, so recommend public repos only).
- [ ] **Demo assets.** Public demo repo with a buggy PR the bot has reviewed; short GIF for README and landing page.
- [ ] **Repo hygiene.** LICENSE, CONTRIBUTING (short), issue templates, SECURITY.md, tagged `v1.0.0`.
- [ ] **Final regression.** Backend and frontend tests, then the production smoke test again; fix only launch blockers.
- [ ] **Announce.** Post where your audience is (LinkedIn, X, Reddit, Show HN); watch dashboard and logs for 24 hours.

---

## Launch gate (all must be ticked before announcing)

- [ ] A real PR on a real repo gets a correct review through the production GitHub App.
- [ ] Redelivered webhooks never produce a duplicate review.
- [ ] LLM or GitHub outages and exhausted quota produce a FAILED or SKIPPED row, not a crash or a silent miss.
- [ ] The React dashboard is behind login and shows real data; the landing page has a working install link.
- [ ] CI is green, secrets are only in the environment, and the UptimeRobot check is passing.
- [ ] README setup works from a clean machine in under 10 minutes, and the data-handling note is published.

## Post-launch backlog (do not start before launch)

1. Fetch full contents of changed files for better context (largest quality gain).
2. Eval harness with seeded-bug PRs; track recall and false positives per prompt version.
3. GitHub OAuth login and per-installation dashboards.
4. Re-review on request (`/review` comment) and a resolve-on-fix flow.
5. Move to a paid or higher-quota LLM tier (Claude already supported through `LlmClient`).
6. Tracing, richer metrics and a Postgres-backed queue if one instance is no longer enough.

## Notes and decisions log

Add dated one-liners here when something differs from the plan (chosen LLM provider and its limits, Render or Neon quirks, deferred tasks).

- 2026-11-02: Plan created. Free-tier limits not yet verified (Day 1 task).
- 2026-11-03: Default LLM = Gemini `gemini-3.5-flash-lite` (reliable, ~2s, found all 4 seeded bugs; weaker on real PR #1). Larger Gemini models (`gemini-3.5` to `3.8-flash`) were better on quality but returned frequent 503 "high demand", 40-140s latencies and one connection reset, so they are opt-in via `prbot.llm.gemini.model`. `gemini-2.5-flash` is closed to new keys.
- 2026-11-03: Groq free tier: TPM 8000 (gpt-oss-120b/20b), ITPM 7000 (qwen3.8-27b). A ~27 KB diff (~10k tokens) is rejected with 413, so Groq only works with per-file batching (Day 5). `gpt-oss-20b` failed JSON-schema output. `gpt-oss-120b` and `qwen3.8-27b` were accurate on a small diff.
- 2026-11-03: Gemini free-tier request limits are not published in the docs; read them from the AI Studio rate-limit page for the project and record them here before Day 5 (quota guard). Neon, Render and UptimeRobot limits not yet checked (needed Day 3 and Day 10).
- 2026-11-03: Day 2 must add explicit read timeouts: one Gemini call hung 155s before the connection reset, and 503s were frequent, so retries matter for the default provider.
- 2026-11-05: Day 2 done. 69 tests pass. Retries cover the Day 1 finding of Gemini 503s and hangs (60s read timeout instead of 155s).
- 2026-11-06: Day 3 done. 75 tests (3 need Docker).
- 2026-11-03: AI Studio rate limits for `gemini-3.5-flash-lite` (free tier): 15 RPM, 250K TPM, 500 RPD (the 3.6/3.8 Flash models: 5 RPM, 20 RPD, so unusable for volume). Day 5 quota guard defaults: pace at ~12 RPM (headroom under 15) and daily cap ~450 (headroom under 500); both configurable. Limits are per project and the RPD resets at midnight Pacific.
- 2026-11-10: Day 5 code done (prompt v1, batching, `.prbot.yml`, quota guard); 112 tests (4 need Docker, including the new quota SQL test). Spot-check set is still open and needs real PRs.
- 2026-11-11: Day 6 code done: read endpoints, session login, metrics, API tests. New required env var `ADMIN_PASSWORD` (12+ chars): add it to your local `.env` or the app will not start. SQL for `/api/reviews` and `/api/stats` is only exercised by the Testcontainers test (needs Docker, runs in CI). Not yet booted against a real Postgres.
- 2026-11-11: Day 7 code done in `frontend/`: 6 Vitest tests pass, `tsc`, ESLint and `vite build` clean. Frontend CI is the Day 8 task.
- 2026-11-11: Day 8 code done. Owner task left: check the UI by eye (phone width, dark theme).
- 2026-11-11: Day 9 code done: 149 tests (6 need Docker, the 11 E2E tests ran locally against a throwaway Postgres 18). Nothing was exercised against the real GitHub or Gemini.
