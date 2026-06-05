# The Team — ERP Development Agents

A software-development company, modelled as nine specialised agents. They build a clean-build
ERP on **Java (Spring Boot) + Angular + PostgreSQL**. Shared ground rules live in
[../../PROJECT-CONVENTIONS.md](../../PROJECT-CONVENTIONS.md) — every agent reads the slice
relevant to its work.

## Roster

| Agent | Role | Model | Builds / owns |
|---|---|---|---|
| **system-analyst** | Requirements lead — *asks you the questions* | opus | `docs/requirements/`, `USER-STORIES.md`, glossary |
| **solutions-architect** | System design, data model, ADRs | opus | `ARCHITECTURE.md`, `DATA-MODEL.md`, `docs/decisions/` |
| **project-manager** | Scope, sequencing, planning, risk | opus | `docs/` plans, status, risk register |
| **backend-engineer** | Spring Boot / Java / Flyway / Postgres | sonnet | the API codebase |
| **frontend-engineer** | Angular standalone components | sonnet | the web codebase |
| **qa-engineer** | Test strategy + tests (Testcontainers, Playwright+axe) | sonnet | all test code, `docs/qa/` |
| **security-engineer** | JWT/RBAC, multi-tenant & branch isolation, CVEs | opus | `docs/security/`, security infra |
| **devops-engineer** | Docker, Compose, Postgres, CI, deploy | sonnet | infra, `.github/`, `docs/ops/` |
| **end-user** | Role-plays a real user for UX feedback | sonnet | UX findings (read-only) |

## The workflow (requirements first — by the owner's instruction)

```
            ┌──────────────────────────────────────────────────────────┐
            │  1. DISCOVERY                                             │
   YOU ◄────►  system-analyst  ── asks you questions, writes specs ────┤
            │       │ docs/requirements/ + USER-STORIES.md             │
            └───────┼──────────────────────────────────────────────────┘
                    ▼
            ┌──────────────────────────────────────────────────────────┐
            │  2. DESIGN                                                │
            │  solutions-architect ── ARCHITECTURE.md + DATA-MODEL.md   │
            │                          + ADRs                           │
            │  project-manager     ── sequences the work into slices    │
            └───────┼──────────────────────────────────────────────────┘
                    ▼
            ┌──────────────────────────────────────────────────────────┐
            │  3. BUILD (per slice, full-stack)                         │
            │  backend-engineer ─► frontend-engineer                    │
            │  devops-engineer  ── stands up Postgres / CI / deploy     │
            └───────┼──────────────────────────────────────────────────┘
                    ▼
            ┌──────────────────────────────────────────────────────────┐
            │  4. VERIFY                                                │
            │  qa-engineer       ── tests + release gate                │
            │  security-engineer ── auth / tenant / branch review       │
            │  end-user          ── UX walkthrough                      │
            └──────────────────────────────────────────────────────────┘
```

**Nothing is built before it's understood.** The system-analyst runs discovery first and the
engineering agents wait for ratified specs. Discovery and build are sequenced by dependency:
IAM (org → company → branch → user → role → permission → **branch assignment / default branch**)
comes before any module scoped by company/branch.

## How to invoke an agent

- Mention the agent by name and it gets routed (e.g. *"have the system-analyst start requirements
  gathering for IAM"*, *"ask the solutions-architect to design the user_branch model"*).
- Or let the main assistant pick based on the task — each agent's `description` says when to use it
  and when not to.

## Where to start

> "system-analyst, begin requirements discovery — start with IAM and the branch model."

The analyst will ask you a focused round of questions, write the answers into
`docs/requirements/`, and only then hand the design to the architect. Engineering does not begin
until you've gathered enough to build the right thing.
