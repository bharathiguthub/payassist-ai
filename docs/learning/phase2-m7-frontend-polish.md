# Phase 2 · M7 — Frontend polish: errors, final states, accessibility, browser tests

Decisions: [ADR 0006 update](../adr/0006-frontend-stack.md), [ADR 0013 §11–12](../adr/0013-tamper-evident-audit-and-least-privilege.md).

## 1. Errors are a contract, not a string
The backend returns RFC 9457 Problem Details with a stable `code`. The UI classifies every failure once
(`api/problems.ts`) and shows it with one component (`<ProblemMessage>`):

| Kind | Typical cause | What the user is offered |
|---|---|---|
| `unauthenticated` | session expired (401) | "Sign in again" |
| `forbidden` | role does not allow it (403, no code) | explanation only |
| `not-permitted` | business rule, e.g. `SELF_APPROVAL_FORBIDDEN` | plain-language rule |
| `stale` | `CONCURRENT_MODIFICATION`, `INVALID_STATE_TRANSITION` | data reloaded + "Reload" |
| `protected` | `RECORD_PROTECTED`: the database refused it | data reloaded + support reference |
| `server` / `network` | 5xx, no response | support reference, never server internals |

The **same codes** are what a future AI agent will branch on ("retry", "escalate to a human", "stop"); a UI that
parses messages would break the moment wording changes.

## 2. Defence in depth, explained to the user
A refund decided by someone else a second earlier is refused three times over: by the service (state machine), by
the guard trigger (final state) and by column privileges. The user should not see any of that: they see "Someone
else changed this record… It has been reloaded." The **database** layer reports only `RECORD_PROTECTED` plus a
correlation ID; table and trigger names stay in the server log (`ApiExceptionHandler`), and the refusal is audited.

## 3. Final means final, also on screen
Closed dispute cases show a banner with who closed them and when; decided refunds carry a "Final" badge; buttons come
only from the server's `allowedActions`. Irreversible decisions get a confirmation dialog that states the consequence
("records a simulated refund", "payment charged back") — this is a *human-in-the-loop* checkpoint, the same pattern AI
agent tool calls will need before acting.

## 4. Two kinds of integrity, never merged
The admin panel shows **Database audit chain** (verified in the app, on demand) next to **External checkpoints**
(status *reported* by the checkpoint job via `audit_checkpoint_runs`). A single green "OK" would overstate what the
backend can know: it cannot read the ledger by design. Labelling *reported* vs *verified* evidence is the same
discipline AI outputs need (model claim vs. checked fact).

## 5. Accessibility as a default
Skip link; heading focus after navigation; `role="status"` loading; `role="alert"` problems focused once; dialogs that
trap focus, close with Escape and return focus; visible focus outlines; reduced motion. Automated checks: `vitest-axe`
in component tests, axe-core in Chromium in the browser suite (contrast included). Automated checks find roughly a
third of issues; keyboard and screen-reader walkthroughs are still needed.

## 6. Browser tests that assert security, not just clicks
`frontend/e2e` (Playwright) logs in through the real Keycloak and checks, besides the workflows:
no tokens in `localStorage`/`sessionStorage`/page, HttpOnly session cookie, CSRF required, server 403 behind every
hidden page. They run on reserved synthetic payments and never record traces (a trace would contain the typed
password). Run: `npm run e2e` with the stack up.
