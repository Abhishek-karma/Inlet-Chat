# Development Rules

## Product

Build a small, reliable, production-quality Android AI chat application.

The product provides:

- text conversation
- streaming responses
- conversation history
- configurable OpenAI-compatible providers
- existing voice input/output
- attachments where already supported
- clean, modern, minimal UI

This is a normal AI chat application.

It is NOT an AI agent platform.

---

## Non-Negotiable Rules

1. Do not build an autonomous agent.
2. Do not add agent loops, planners, routers, orchestrators, or multi-agent systems.
3. Do not add local LLM inference in V1.
4. Do not add wake-word or background listening.
5. Do not add semantic memory or vector databases.
6. Do not build a large tool system.
7. Do not create abstractions without a current requirement.
8. Do not create modules for theoretical separation.
9. Do not create repositories that only forward calls.
10. Do not create managers for single responsibilities.
11. Do not add dependencies unless they solve a real requirement.
12. Do not copy another application's UI.
13. Do not expose internal AI/engineering terminology in the UI.
14. Do not leave dead code, fake data, placeholders, or unused screens.
15. Do not preserve obsolete architecture simply because it already exists.
16. Do not solve hypothetical future requirements.
17. Do not hide failures behind infinite loading.
18. Never store API keys in source code.
19. Do not send user data anywhere unless required for the configured request.
20. Do not mark work complete until it has been tested.

---

## Engineering Principle

Prefer:

simple code
→ clear state
→ direct data flow
→ tested behavior

Avoid:

abstraction
→ abstraction
→ manager
→ engine
→ router
→ framework

When a simpler implementation satisfies the requirement, use it.

---

## Source of Truth

Use the following priority:

1. Current source code and actual application behavior
2. `design.md` for current UI/UX direction
3. Approved product requirements
4. Other documentation

Documentation may become outdated.

Do not blindly follow old documentation.

If documentation conflicts with the current approved design or actual product direction:

- understand the existing implementation
- identify the conflict
- follow the current approved requirement/design
- remove obsolete implementation
- update documentation when necessary

`design.md` is the current visual and interaction specification.

---

## Design Rules

The UI must be created as a cohesive product.

Do not copy Claude, Kimi, ChatGPT, or any other application's UI.

Use external applications only as general quality references.

The design should be:

- minimal
- modern
- calm
- content-first
- Android-native
- accessible
- production-quality

Avoid:

- excessive cards
- excessive pills
- gradients
- neon
- glow
- glassmorphism
- futuristic HUDs
- decorative AI graphics
- unnecessary animations
- excessive icons
- visual clutter

The conversation is the primary product surface.

The UI should get out of the user's way.

Follow `design.md` for the current design system, screens, components, states, typography, spacing, and interaction principles.

---

## Stitch / Visual References

Stitch designs, screenshots, wireframes, generated images, and external visual references are references only.

Do not copy their implementation blindly.

Do not introduce features merely because they appear in a reference.

Use the approved `design.md` and actual product requirements to determine what belongs in the application.

Translate visual intent into clean, maintainable Jetpack Compose code.

---

## Change Rule

Every feature or meaningful behavior change must have:

- a requirement
- an implementation
- error handling where applicable
- tests
- a manual verification path

Do not add speculative features.

For UI changes, verify:

- light mode
- dark mode
- different screen sizes
- keyboard open/closed
- loading/streaming
- error states
- large text
- accessibility

---

## Code Quality

Prefer deletion and simplification over adding layers.

Do not create:

- generic UI frameworks
- unnecessary managers
- unnecessary coordinators
- unnecessary UseCases
- unnecessary repositories
- unnecessary state abstractions

Reuse existing components when they genuinely fit.

Create a new component when it improves clarity or reuse.

---

## Comments

Do not add comments where the code is already self-explanatory.

Comments should be:

- short
- necessary
- factual

Remove obsolete or unnecessary comments.

Do not use comments to explain obvious code.

---

## Commit Rule

Commit messages should be short and descriptive.

Example:

`fix chat composer layout`

`refine settings UI`

`fix markdown table rendering`

---

## Release Rule

A release is complete only when:

- debug build succeeds
- release build succeeds
- tests pass
- core flows work on a real Android device
- provider configuration works
- streaming works
- cancellation works
- conversation persistence works
- voice does not break text chat
- attachments work where supported
- Markdown rendering works
- failures recover cleanly
- light/dark themes work
- no known release-blocking issue remains

Never claim completion based only on compilation.

---

## Final Review

Before declaring a task complete, review the result as:

1. Senior Android engineer
2. Product designer
3. UX reviewer
4. QA engineer

Ask:

- Is the implementation simpler than before?
- Is the UI coherent?
- Is the conversation the visual priority?
- Are there unnecessary components?
- Are there unnecessary controls?
- Does the UI work in real conversations?
- Are all important states handled?
- Does it feel production-ready?

Fix issues found during this review before finishing.