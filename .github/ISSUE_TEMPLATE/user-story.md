---
name: User Story
about: Parent issue of a US; tasks are sub-issues (docs/00-workflow.md §2)
title: "US-xxx: <story name>"
labels: user-story
---
**Story:** As a <role>, I want <want> so that <benefit>.
**Priority:** <!-- Must / Should / Could; add the matching priority:* label -->
**Use cases, APIs, related requirements:** <!-- e.g. UC-07; S10; FR-07 -->

**Acceptance criteria (AC):**
- [ ] <!-- AC-xx.y - test case in docs/06-testing/catalog/<file>.yaml -->

**Feature flag:** <!-- SHP_FEATURE_<NAME> if a task merges before the US is done; write "none" if not needed -->

**Close when:** every sub-issue is closed, every AC has an automated test green on main, and the flow has been run on a cluster with fake data (record the result in a comment).

> Public repo: fake data only. Do not paste real patient data, tokens, passwords or logs containing PII.
