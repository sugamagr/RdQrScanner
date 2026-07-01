# Release recipe

RD Scanner is distributed via GitHub Releases. Every install of v2.0.0
or later runs a force-update gate on cold launch: it hits the GitHub
API, extracts the versionCode from the release tag, and blocks all app
UI until the operator installs the newest version. If GitHub is
unreachable the check silently defers so a flaky WiFi day never bricks
the app.

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

# 6. Cut the GitHub release with the APK attached.
gh release create v2.0.1+21 \
    app/build/outputs/apk/release/app-release.apk \
    --title "v2.0.1" \
    --notes "One-line description of what changed"

# 7. Done. On next cold-launch of every phone running v2.0.0 or newer,
#    UpdateChecker sees v2.0.1+21 > VERSION_CODE 20, and the gate
#    forces the download-and-install flow. Colleagues can't skip it.
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
