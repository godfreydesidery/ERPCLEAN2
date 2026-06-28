# Simulation Program — Backlog / Queue

> Deferred ideas for the business-operations simulation (the persona team that *uses* the ERP and the
> technical team that *fixes* it). **Queued, not built** — picked up later upon owner approval. Newest
> first. See [README.md](README.md) for the running loop.

---

## SIM-BL-002 — Multi-device / responsive coverage (desktop, laptop, tablet, mobile)

**Status:** OPEN — owner-raised 2026-06-28. Harness primitive landed; systematic coverage queued.

Real users hit the ERP on **desktops, laptops, tablets and phones**, so the simulation should exercise
each persona at those viewports — a screen that works at 1440px can be unusable at 390px.

**Already shipped (the primitive):** the harness now takes a `DEVICE` env (`desktop|laptop|tablet|mobile`)
— `sim-lib.js` sets the viewport (+ touch/mobile UA for tablet/mobile), and `operate.js` captures a
screenshot per screen when `DEVICE` is set. First mobile pass (Sabina @ 390px) showed the POS screen is
**genuinely responsive** (header collapses to a hamburger, content stacks, empty/info states intact) —
a good baseline, not a desktop page crammed onto a phone.

**Queued (the coverage):**
- Run **all personas across all four device profiles**, capturing per-screen screenshots.
- Have the **end-user agent review the screenshots** for responsive/usability breakage (overflow, off-screen
  actions, cramped forms, tap targets) — automation drives clicks regardless of layout, so *visual* review
  is the gate, not just functional pass/fail.
- ~~Run the **axe a11y gate at mobile/tablet viewports**~~ — **DONE 2026-06-28**: harness `runAxe` (AXE=1)
  injects axe-core at the DEVICE viewport (WCAG 2.2 incl. `target-size`); **0 serious/critical across 16
  screens × mobile+tablet**. a11y holds at small screens.
- Flag any screen that is functionally reachable but visually broken on a small screen as a UX UPR
  (frontend-engineer), distinct from a defect.
- Consider a couple of representative real devices (a low-end Android phone, an iPad) via Playwright device
  descriptors, not just raw viewports.

**Open questions:** which screens are mobile-critical (POS/counter sales and route-agent capture are the
obvious phone/tablet ones; period-close/GL are desktop)? Do we set a minimum supported width? Should the
nav/branch-switcher get specific mobile review (it's the most-used control)?

---

## SIM-BL-001 — Skill-development plan + bidirectional learning loop (personas ⇄ technical team)

**Status:** DEFERRED — owner-requested 2026-06-28. **Keep in queue; consider later. Do not build yet.**

**The idea (owner's words, paraphrased).** The team should have a **skill-development plan** so the
team develops its skills **periodically, upon approval** — a cadence, not a one-off. Two directions of
learning, plus a preferences channel:

1. **Personas level up.** A persona can *grow new abilities over time*, gated by approval. Example: a
   persona learns to **peek at the browser logs** (console errors + failed network calls) and **attach
   them to its User Problem Report**. Today the harness already *captures* console/API evidence
   (`e2e/sim/sim-lib.js watchProblems`), but the persona files it in pure business voice
   (by design — "users don't speak in stack traces"). The skill-development plan would let a *more
   advanced* / approved persona optionally attach the raw technical log to help the technical team
   reproduce faster — without abandoning the plain-language report. Think tiers: a brand-new user just
   describes what happened; a seasoned user can grab a screenshot and the console log.

2. **Technical team learns from users.** The technical team should *learn from the users* — patterns
   across UPRs (recurring friction, the same screen tripping many roles) feed back into design/UX, not
   just one-off fixes. A periodic retro: what did this run teach us about how people actually work?

3. **Preferences, not just defects.** Sometimes **users prefer things their own way to ease their
   activities** — a layout, a default, a shortcut, a sequence. These are *preferences / UX requests*,
   distinct from bugs (which the UPR/triage loop already handles). The plan should give preferences a
   first-class channel: captured, weighed, and either adopted (it genuinely eases the work) or
   declined with a reason — separate from the defect pipeline, and **approval-gated** so the product
   doesn't drift to one user's taste.

**Why it's valuable (for when we revisit).**
- Richer evidence → faster, more accurate triage (the optional log attachment closes the gap between
  business-voice reports and reproducible Issues).
- The learning loop turns a one-shot test run into a *continuous improvement* practice.
- A preferences channel surfaces real ergonomic wins the defect lens misses, without letting any single
  user's preference quietly become a requirement.

**Rough shape (not a commitment — for later scoping).**
- A persona "skill ladder" in the persona agent-defs (e.g. a `skills:`/`tier:` notion) that unlocks
  abilities (attach-logs, attach-screenshot, propose-preference) **on owner approval**.
- A periodic cadence (per run / per sprint) where (a) personas may gain a skill, (b) the technical team
  runs a learning retro over the run's UPRs, (c) preferences are reviewed.
- A **Preferences register** (sibling of UPR-REGISTER / ISSUES-REGISTER) — preference, requester,
  rationale, decision (adopt / decline + why), approval.
- Approval gates throughout (no skill unlock, no preference adoption, without sign-off).

**Open questions for when we pick this up.** Who approves a skill unlock and on what cadence? Do log
attachments risk leaking sensitive data into reports (redaction policy)? How do we keep preferences from
fragmenting the UX across roles? Does "technical team learns from users" become a standing retro doc?

---

*Add new deferred simulation ideas above this line as `SIM-BL-NNN`.*
