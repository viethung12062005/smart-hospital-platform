# ADR-0018: One account manages multiple profiles, self-claims existing records, and a shared OTP service

## Status
**Accepted (2026-11-01)**: SA decision after surveying how hospitals and booking apps in Vietnam work. Supplements [ADR-0017](0017-keycloak-identity-everywhere.md) and [ADR-0003](0003-fhir-native-store.md). Replaces the rule "one account is linked to only one patient profile" (detailed design 15 §8, C7).

## Context
A public survey (Medpro app, Ho Chi Minh City Heart Institute, Bach Mai, Thu Duc, Tam Anh, and news articles; thin sources, not verified in real apps) showed three things the previous design did not cover:

1. **Users often book on behalf of others.** Parents book for children, children book for elderly parents who do not use smartphones; a family may share one phone number. Many apps let one account add "relative profiles". The old design (AC-03.6) only allowed booking for **oneself**.
2. **Existing patients already have records at the counter.** Tam Anh cross-checks information then sends an OTP to **link the account to an existing record**. The old design only created a new record or said "suspected duplicate, go to the counter", easily creating duplicates.
3. **OTP only via SMS.** SMS costs more than Zalo verification messages and has SIM-swap risk. Zalo is a popular messaging app in Vietnam.

## Decision

### 1. Account–profile link model
- Keep `identity_links`: each account has **one** "self" profile (at most one "self" account per profile). All existing libraries (SHR-21, `fhirUser`) unchanged.
- Add `profile_links`: other profiles the account manages. Two types:
  - **Guardian** of a person **under 16** (per the 2016 Children's Law): father, mother, or a guardian who certifies, full authority, expires when the person turns 16.
  - **Authorized representative** for adults: **only when that person agrees**, by reading an OTP sent to their phone number in the record, or at the counter with original documents (suitable for elderly people who do not use smartphones).
- **Scope:** `booking` (view, book, cancel, reschedule appointments and administrative profile; default) or `records` (add encounter results and issued documents, only when the OTP message states the scope so the record owner knows in advance). The authorized representative cannot view unpublished notes, cannot edit name, date of birth, gender, and cannot enable AI processing on behalf of the record owner.
- **Limits:** at most 10 profiles per account, valid for 12 months and renewed with a new OTP, revocable at any time with effect ≤ 60 seconds (same as the permission cache TTL).
- **Authorization:** add `linked`, `linked-records`, `guardian` conditions to the matrix (`own` unchanged). The 3-future-appointment limit (BR-03) is counted per profile. Audit every access through a link with `onBehalfOf`.

### 2. Self-claim existing records
The patient enters full name, date of birth, and phone number registered at the hospital. If exactly **one** record matches, the system sends an OTP to the number **stored in the record** (not the number just entered if different). If the code is correct and the record is not linked to any "self" account, link it, then notify the number in the record so the owner can detect impersonation.
- Responses are **always identical** whether or not there is a match, and no data is revealed before the OTP is correct, so no one can probe who is a patient of the hospital.
- No match, multiple candidates (shared phone number), or a record without a phone number → go to the counter for a nurse to link after document verification.
- Anti-abuse limits: 5 attempts per 24 hours per account, 3 codes per 24 hours per number.

### 3. Shared OTP service, Zalo first, SMS after
`notification` provides internal APIs **N8** (send code) and **N9** (verify code); Keycloak (registration, number change) and clinical (record claim, relative linking) both call them. Channel for phone numbers: **Zalo first**, if Zalo fails, the number does not use Zalo, or delivery takes over 30 seconds, then **SMS with the same code**; email uses the email channel. 6-digit code, 5-minute expiry, single use, stored only as a hash, 5 wrong attempts locks for 15 minutes, limits by phone number and IP. Each channel is an adapter behind the `OtpChannel` interface (with a fake for dev and test). OTP is **not** a staff second factor (still TOTP or WebAuthn).

## Alternatives considered
| Option | Conclusion |
|---|---|
| One account per person, no relative profiles | Rejected: does not fit Vietnamese family reality; elderly people and children cannot use it themselves |
| Relatives book on behalf only by entering information, without record owner consent | Rejected: exposes others' health data, violates the consent principle |
| Merge all family records into one shared record | Rejected: wrong medical data model, one record per person |
| Claim records only at the counter | Kept as a fallback, but add self-service via OTP to reduce counter load and duplicates |
| Each place sends OTP itself (Keycloak one place, clinical another) | Rejected: duplicate provider configuration, limits, and budget; choose one shared service |
| SMS only | Rejected: more expensive and SIM-swap risk; keep SMS as fallback |

## Consequences
**Positive**
- Matches how Vietnamese people use services: book for children and parents, reclaim old records without going to the counter.
- Fewer duplicate records, less counter work, cheaper OTP, and management in one place.

**Risks and mitigation**
| Risk | Mitigation |
|---|---|
| Exposure of relatives' health data through fake links | OTP goes to the relative's own number; default scope is appointments only; fast revocation; notify the record owner; audit `onBehalfOf` |
| Probing records by trying names, dates of birth, phone numbers | Neutral responses, limits per account and number, temporary lockout, anomaly alerts |
| Phone number reassigned (code sent to the wrong person) | Link only with the correct code; notify the number in the record; records with unknown numbers go to the counter |
| Users aged 16 to 17: boundary between autonomy and guardian | At 16 the guardianship ends, offer to transfer rights; confirm with the Personal Data Protection Law regarding children before real operation |
| Zalo requires a business account and verification message template; SMS requires a sender brand | Do after the fake version; provider and current regulations settled at real deployment |

## Work to do
Add user stories US-076 to US-079 and tasks CLN-33 to CLN-38, NTF-16 to NTF-18, SHR-30, WEB-35, WEB-36, update IAM-04 (call `notification`). The authorization matrix adds three conditions and rules for linked profiles. SRS v0.96 (UC claim-record, add-dependent, link-relative, revoke-link; AC-RG.3, AC-RG.4, AC-03.7).
