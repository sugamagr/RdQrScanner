# Cloud Sync + Portal — Ship-Ready Status

**Branch:** `feat/cloud-sync` (67 commits ahead of `main` @ `a24249b`)
**Built:** 2026-06-20 overnight
**Live portal:** https://rd-scanner-portal.pages.dev
**Final acceptance oracle:** **SHIP IT** (bg_ec44ec7f)

## What's done

| Phase | Status | Commits |
|---|---|---|
| 1. Schema v6 + auth shell | ✅ PASS | 18 |
| 2. Push sync + notifications | ✅ PASS | 12 |
| 3. Pull sync + realtime + 5-min poll | ✅ PASS | 4 core + F1-F3 fixes |
| 4. Portal v1 (live on Cloudflare Pages) | ✅ PASS | 9 |
| 5. Hardening + observability | ✅ PASS | 13 (T5.1–T5.13) |

## §17 spec invariants — all 4 hold

1. Phone's Room DB = source of truth for that phone ✓
2. Cloud Postgres = source of truth across devices ✓
3. Active sessions never sync ✓
4. No server-side application logic in v1 ✓

## User explicit constraints — all met

- "Industry standard code" — PASS (Supabase RLS, WorkManager, Ktor, proper mutex serialization, TS strict)
- "Dribbble-A-or-above design + current color format" — PASS (`tailwind.config.ts` mirrors `Color.kt`: PrimaryOrange #FF9F43, AccentMint #4ECDC4, AccentCoral #FF6B6B)
- "Free-tier forever" — PASS (Supabase free, Cloudflare Pages free, no paid features in schema)
- "Notifications minimal + good-looking" — PASS (3 channels: sync_success, sync_error, remote_edit; banner on Home; Channel C for remote edits per §15.5.2)

## 12 boundary-review findings: 9 resolved, 3 deferred

| ID | Description | Status | Fix |
|---|---|---|---|
| F1 | sync_events never written | ✅ FIXED | eaac227 + a6a99d5 |
| F2 | bulk delete unbatched | ✅ FIXED | bd1c487 |
| F3 | runPush/Pull race | ✅ FIXED | 9da92af |
| F4 | silent overwrite no WARN log | ✅ FIXED | 41b50ee |
| F5 | LWW tie-breaker (<=) | ✅ FIXED | 9801035 |
| F6 | drain loop missing | ✅ FIXED | df480bb + ead5ec7 |
| F7 | mid-edit tombstone | ✅ FIXED | f4ea378 + 181bb49 |
| F8 | schema-missing silent error | ✅ FIXED | 286d68f + 181bb49 |
| F9 | portal vs phone attribution | ✅ FIXED | 14f7ee2 |
| F10 | modal focus trap | 🟡 BACKLOG | UX polish, non-blocking |
| F11 | mobile slider keyboard avoidance | 🟡 BACKLOG | edge case, non-blocking |
| F12 | month grid chronological hint | 🟡 BACKLOG | minor polish, non-blocking |

## What you need to do before phones actually sync

The code is ready. Only the cloud setup is left:

1. **Apply the schema** (5 min)
   - Open Supabase Studio → SQL Editor → New Query
   - Paste the contents of `cloud/schema.sql` (267 lines)
   - Run
   - Idempotent; safe to re-run if you applied an earlier version

2. **Enable Realtime** (1 min)
   - Supabase Studio → Database → Replication
   - Enable Realtime publication for: `devices`, `scan_sessions`, `scan_lots`, `rd_numbers`

3. **Create the owner user** (1 min)
   - Supabase Studio → Authentication → Users → Add user
   - Email + password
   - Use these credentials on phones AND portal

## What's already deployed

- Supabase URL + anon key already in `local.properties` (phones) and `portal/.env.local` (portal dev)
- Cloudflare Pages project `rd-scanner-portal` created, deployed, env vars set
- Live URL https://rd-scanner-portal.pages.dev serves the production bundle
- 5 redeploys throughout the night, each verified with curl probes

## How to verify end-to-end (after schema applied)

1. Install fresh APK from the `feat/cloud-sync` branch on any test phone
2. Sign in → run first-run setup (operator + device name) → finalize a test session
3. Within ~5s the session should appear at https://rd-scanner-portal.pages.dev
4. Open the portal in two browser tabs side-by-side
5. Edit a defaulter month in tab A → tab B should refresh within ~1s via Realtime
6. Edit the same defaulter from the portal → the phone should show a banner + Channel C notification within seconds
7. Sign in on a second phone → both phones should see each other's sessions on next pull

## Ready to merge

```bash
git checkout main
git merge feat/cloud-sync
git push origin main
```

Or open a PR for review first — `gh pr create` from the branch.

## Spec amendments shipped

- §11 LWW tie-breaker: strict `<` instead of `<=` (T5.7)
- §15.5.5 origin attribution: `last_editor_device_id` column drives classification (T5.6)
- §15.5.7 SCHEMA_MISSING pill state (T5.1)
- §15.5.8 drain-until-empty pull loop (T5.4)
- §15.5.9 mid-edit tombstone guard (T5.5)
- §15.5.10 serialized push + pull mutex (T5.2)
