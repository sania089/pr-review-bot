# Security policy

## Reporting a vulnerability

Please do not open a public issue. Use GitHub's private reporting: the **Security** tab of this repository, then
**Report a vulnerability**. Include steps to reproduce and the impact. You will get a reply within a few days; this is a
volunteer project and fixes are made on a best-effort basis.

## Scope and design notes

- Webhooks are verified with an HMAC-SHA256 signature (constant-time compare) and a body size limit.
- Diffs are treated as untrusted data in the prompt, and the bot can only post a review with the `COMMENT` event.
- The dashboard API requires a login; secrets are read from the environment only.
- Diffs of reviewed pull requests are sent to the configured LLM provider. Use public repositories only (see the README).
