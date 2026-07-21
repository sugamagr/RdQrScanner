# Release recipe

RD Scanner is distributed via GitHub Releases. Every install of v2.0.0
or later runs an update probe on cold launch: it hits the GitHub API,
extracts the versionCode from the release tag, and — depending on
whether the release body contains the literal token `[FORCE]` — either
blocks all app UI (force update) or shows a dismissable orange banner
at the top of the app (optional update, the default since v2.0.5). If
GitHub is unreachable the check silently defers so a flaky WiFi day
never bricks the app.

The probe response is cached in SharedPreferences for 6 hours. This
matters because GitHub throttles unauthenticated API calls at 60/hour
per PUBLIC IP address, and multiple colleagues on one office WiFi
share ONE public IP behind NAT. Without the cache, five phones
cold-launching within an hour after a WhatsApp update announcement can
exhaust the shared bucket and every subsequent phone gets HTTP 403 →
silently misses the update. With the cache, the first launch fetches;
every launch for the next 6 hours reads from local storage.

Operators can also trigger a manual check any time via
**Settings → About & updates → Check for updates**. Manual checks
bypass the 6-hour cache but still populate it on success.

## Force vs optional updates

**Default is optional.** Every release is dismissable unless you
explicitly mark it as mandatory. Marking is done via the release BODY,
not the tag:

```
gh release create v2.1.0+30 RdBookScanner-v2.1.0.apk \
    --title 'v2.1.0' \
    --notes '[FORCE] Cloud schema change - phones on v2.0.x cannot sync until installed.'
```

The `UpdateChecker.parseForceFlag()` function does a case-insensitive
substring match for `[FORCE]` anywhere in the release body. If present,
`isForce` is true → MainActivity renders the blocking `UpdateGateScreen`
that swallows the back gesture. If absent, `isForce` is false → an
orange `UpdateBanner` at the top of the app that the operator can
dismiss with the X button.

When to use `[FORCE]`:

- Cloud schema migration (v13+ Supabase change) that older app versions
  cannot survive.
- Sync-format change in the phone-cloud DTO shape.
- Critical security fix in an auth or crypto path.
- Any change where "phones on older versions producing stale/wrong
  data" is worse than "phones stuck on the update gate for a day".

When to leave it optional (99% of releases):

- New features, UI polish, bug fixes that improve UX but don't break
  the older version.
- Any change where a phone on the previous version can continue to
  sync and function without corrupting cloud data.

The banner dismissal is session-scoped: the operator dismissing it
this morning does NOT hide it forever. Next cold launch shows the
banner again unless the operator has installed the update by then.
This is intentional so an ignored optional update eventually gets
installed without a nag every 10 minutes.

## Tag format (LOAD-BEARING — do not skip)

Every release tag MUST look like:

```
v<major>.<minor>.<patch>+<versionCode>
```

Concrete examples:

- `v2.0.0+20` — first release with force-update gate
- `v2.0.1+21` — patch, one higher versionCode
- `v2.1.0+22` — minor release
- `v3.0.0+30` — major release, skipping some codes is fine

The `+<versionCode>` suffix carries the machine-comparable integer that
`UpdateChecker.kt` reads and compares to `BuildConfig.VERSION_CODE`. If
you tag without the `+N` suffix, the checker treats the tag as
malformed and returns `UpToDate` as a safe default — every phone stays
on the current version until you fix the tag.

Rule of thumb: the versionCode integer must be `>` any previously
released integer. The versionName (`2.0.1`, `2.1.0`, etc.) is free-form
for humans; it does not drive the comparison.

## APK filename convention

Every release APK attached to a GitHub Release MUST be named:

```
RdBookScanner-v<versionName>.apk
```

Concrete examples:

- `RdBookScanner-v2.0.0.apk`
- `RdBookScanner-v2.0.3.apk`
- `RdBookScanner-v2.1.0.apk`

Never upload `app-release.apk` (Gradle's default output name). Always
rename it before `gh release create` or `gh release upload`.

The naming rule is enforced by convention only — `UpdateChecker.kt`
picks any `.apk` asset from the release, so a wrong filename won't
break updates today. It will confuse humans who save the file though.
Keep the format consistent across every release.

## Cutting a release — the whole recipe

```bash
# 1. Bump both fields in app/build.gradle.kts:
#      versionCode = 21
#      versionName = "2.0.1"

# 2. Build the signed release APK.
./gradlew :app:assembleRelease

# 3. Verify locally (optional, catches signing / launch bugs early).
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n com.qrscanner.app/.MainActivity

# 4. Commit + push the versionCode bump.
git add app/build.gradle.kts
git commit -m "release: v2.0.1"
git push origin feat/cloud-sync

# 5. Tag and push the tag.
git tag v2.0.1+21
git push origin v2.0.1+21

# 6. Rename APK to the standard release name BEFORE uploading.
#    Format: RdBookScanner-v<version>.apk (no versionCode suffix).
#    LOAD-BEARING: this filename is what colleagues see when they save
#    the APK to their phone. Keep the format identical across every
#    release so an old file next to a new one sorts and diffs cleanly.
cp app/build/outputs/apk/release/app-release.apk \
   /tmp/RdBookScanner-v2.0.1.apk

# 7. Cut the GitHub release with the renamed APK attached.
gh release create v2.0.1+21 \
    /tmp/RdBookScanner-v2.0.1.apk \
    --title "v2.0.1" \
    --notes "One-line description of what changed"

# 7. Done. On next cold-launch of every phone running v2.0.0 or newer,
#    UpdateChecker sees the new versionCode > VERSION_CODE currently
#    installed. If the release body contains [FORCE] the operator
#    hits the blocking gate; otherwise they see an orange banner they
#    can dismiss and update at their own pace. Cache lives 6 hours, so
#    on flaky WiFi day the check falls back to the cached response
#    before returning UpToDate.
```

## Where the keystore lives

- Signing key file: `keystore.jks` at repo root (git-ignored).
- Signing credentials: `keystore.properties.txt` at repo root
  (git-ignored) — copied to `app/keystore.properties` by convention;
  Gradle reads from `app/keystore.properties`.
- Both files exist ONLY on the shipping machine. Losing them means
  every future release must be signed with a different key, which
  breaks in-place upgrades on every phone — colleagues would have to
  uninstall + reinstall, losing local Room data (cloud data survives).

**BACKUP CHECKLIST (do this if you haven't yet):**

1. Copy `keystore.jks` to iCloud Drive, Google Drive, or WhatsApp
   yourself as a file attachment.
2. Copy `keystore.properties.txt` to the same place.
3. Write down the two passwords (keystore + key) on paper. Store the
   paper with your other important documents.

## First-install for a colleague's phone

Give them the APK URL from the GitHub release page. They tap it in
their browser → Android downloads the APK → they tap the download →
Android asks "Install unknown apps from Chrome? (or whatever browser)"
→ they enable it once → APK installs → they sign in with your shared
account → sync pulls all cloud data down.

The first update after that will go through the in-app gate — they
don't need to visit GitHub again.

## Supabase keep-alive Worker

The app's Supabase project runs on the free tier, which auto-pauses
after 7 days without database activity. When paused:

- DNS returns NXDOMAIN for the project URL
- Every phone sees "not online" on cold launch
- The force-update gate itself keeps working (GitHub is a separate
  dependency) but the app is unusable because the backend is gone

The `supabase-keepalive/` Cloudflare Worker prevents this. It fires
an authenticated REST HEAD request every 3 days at 03:00 UTC (which
is 08:30 IST — safely pre-workday). See `supabase-keepalive/README.md`
for setup, verification, and rotation instructions.

If the Worker itself fails (Cloudflare outage, key rotation drift),
the fallback is manual: visit https://supabase.com/dashboard, click
Resume on the project, done in 60 seconds.

## Rate limit

GitHub unauthenticated API is 60 requests per hour per IP. At 5 phones
launching the app 10 times a day each, that's 50 requests per day
worst-case, absurdly under the cap. No auth token needed on the client.

## Build variants

- `debug` — signed with Android debug key. NOT compatible with any
  release-signed install (signature mismatch on upgrade). Only useful
  for development on your Mac.
- `release` — signed with your keystore, R8/ProGuard disabled per Q2.
  ~77 MB APK. This is the ONLY artifact you distribute.

## What could go wrong

- **Tag missing +N suffix**: gate returns UpToDate, no phones update.
  Fix: re-tag correctly and push a new tag.
- **APK asset not attached to release**: gate returns UpToDate.
  Fix: attach the APK via the GitHub web UI or `gh release upload`.
- **APK asset filename doesn't end in `.apk`**: gate returns UpToDate
  (matches on `endsWith(".apk")`). Fix: rename before upload.
- **VersionCode goes DOWN** by accident: gate compares as integer, so
  a lower number is treated as "already up to date" — no phones
  update. Fix: bump versionCode above the previously-released value.
- **Keystore fingerprint changes** (you lose the .jks and generate a
  new one): every phone's install fails with `INSTALL_FAILED_UPDATE_
  INCOMPATIBLE`. Fix: colleagues uninstall old app + install new APK
  fresh. Local Room data lost; cloud data restored on sign-in.
