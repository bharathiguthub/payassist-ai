# ADR 0004 — Role-based authorization

**Status:** Accepted (Phase 1)

## Context
Three personas: care executive, supervisor, platform administrator.

## Decision
- Keycloak **realm roles** `CARE_AGENT`, `CARE_SUPERVISOR`, `ADMIN`, emitted in a flat `roles` claim
  (ID token, access token, userinfo) by a protocol mapper on the `payassist-bff` client.
- `KeycloakRoleMapper` maps them to `ROLE_*` authorities using an **allow-list**. Keycloak defaults
  such as `offline_access` never become application authorities.
- **Defence in depth**: coarse URL rules in `SecurityConfig` plus `@PreAuthorize` on every controller
  method, written with the shared constants in `com.payassist.identity.Roles`.
- **No implicit role hierarchy.** Each rule lists the roles it allows, so a rule can be read on its own.
- The SPA hides navigation the user cannot use; this is UX only, and the backend is authoritative.
- An authenticated user with no PayAssist role can call `/api/v1/me` (so the UI can explain the
  problem) and gets 403 everywhere else.

| Capability | CARE_AGENT | CARE_SUPERVISOR | ADMIN |
|---|:-:|:-:|:-:|
| Search / view payments | ✔ | ✔ | ✔ |
| Payment statistics | | ✔ | ✔ |
| Audit log | | | ✔ |
| Payment timeline / investigation summary (Phase 2) | ✔ | ✔ | ✔ (explicit, read-only) |
| Refund eligibility check (Phase 2, advisory, read-only) | ✔ | ✔ | ✔ (explicit, read-only) |
| Read / list refund requests (Phase 2) | ✔ | ✔ | ✔ (explicit, read-only) |
| Create refund requests (Phase 2) | ✔ | ✔ | |
| Approve / reject refund requests (Phase 2; never own, within limit) | | ✔ | |

## Consequences
- Mapped roles live on the `Authentication`, not on the `OidcUser` principal. Code must read
  authorities from `Authentication` (regression test: `MeControllerTest.readsRolesFromAuthenticationNotFromPrincipal`).
- Later phases (for example "refund approval above a threshold") may need attribute-based rules.
  These can be added as `@PreAuthorize` beans without changing the role model.
