# Medsy — build notes

Offline-first Android medication / HRT / bloodwork tracker. Package `cz.medsy`.

## Status (verified, not assumed)

| Check | Result |
|---|---|
| `:app:assembleDebug` | **BUILD SUCCESSFUL**, `app/build/outputs/apk/debug/app-debug.apk` (~112 MB, debug) |
| `:app:testDebugUnitTest` | **BUILD SUCCESSFUL**, **541 tests, 0 failed, 0 skipped** |
| SQLCipher natives | `lib/{arm64-v8a,armeabi-v7a,x86,x86_64}/libsqlcipher.so` are packaged |
| Room schema export | `app/schemas/` written via KSP |

## Toolchain (actual, resolved against Maven)

| Item | Value |
|---|---|
| JDK | Temurin 17.0.21 |
| Gradle | 9.9.1 (wrapper) |
| AGP | 9.1.0 (built-in Kotlin — `kotlin-android` plugin **removed**) |
| Kotlin | 2.3.21 |
| KSP | 2.3.13 |
| Hilt | 2.60.1 |
| compileSdk / targetSdk / minSdk | 37 / 36 / 26 |
| build-tools | 37.0.0 (installed via `sdkmanager`) |
| Compose BOM | 2026.09.00 |
| material3 | **1.5.0-rc01** (pinned, overrides BOM) |
| material-icons-core / -extended | **pinned 1.7.8** |
| Android SDK | `D:\Android\SDK` |

### Version traps (each one cost a build)

1. **Hilt 2.59+ hard-requires AGP 9.0+**, and **the whole modern AndroidX line
   requires AGP 9.1 + compileSdk 37** (`aar-metadata.properties` says
   `minAndroidGradlePluginVersion=9.1.0`, `minCompileSdk=37`). So AGP 8.x is not
   viable here — migrated to AGP 9.1.0 + Gradle 9.9.1 + Hilt 2.60.1.
2. **AGP 9 rejects the `org.jetbrains.kotlin.android` plugin** ("no longer required
   for Kotlin support since AGP 9.0"). Removed; `kotlin { compilerOptions { … } }`
   still works.
3. **`android.defaults.buildfeatures.buildconfig` was removed in AGP 9** — setting it
   fails configuration. Removed from `gradle.properties`.
4. **material3 1.4.0 (the BOM's pin) has no Expressive APIs.** Verified against the
   AAR: `MaterialExpressiveTheme`, `MotionScheme`, `ButtonGroup`,
   `ShortNavigationBar`, `LoadingIndicator` only exist from 1.5.0-alpha. Pinned
   `1.5.0-rc01`.
5. **`material-icons-core` 1.12.x is empty** (moved to a separate artifact), so
   `Icons.Rounded.Check` disappeared. Pinned icons-core/-extended to 1.7.8; even so,
   the icons are *extension properties* and need an explicit import.
6. **KSP ≥ 2.3.x is decoupled from the Kotlin version** (no `2.3.21-x.y.z` marker
   exists). `2.3.13` is the latest plugin release.
7. SÚKL moved `www.sukl.cz` → `sukl.gov.cz`; open data → `opendata.sukl.gov.cz`.
   The catalogue is JS-rendered, so URLs live in `SuklConfig` (injectable) rather
   than being hard-coded deep links.

### Verified API signatures used

```
MaterialExpressiveTheme(colorScheme, motionScheme, shapes, typography, content)
MotionScheme.Companion.expressive()
Shapes(extraSmall, small, medium, large, extraLarge,
       largeIncreased, extraLargeIncreased, extraExtraLarge)
Typography(... displayLargeEmphasized … labelSmallEmphasized)   // real properties
RoundedPolygon.star(numVerticesPerRadius, radius, innerRadius, rounding, innerRounding)
RoundedPolygon.pill() / .circle() / .rectangle(w,h,rounding) / RoundedPolygon(numVertices,…)
Morph(start, end).toPath(progress: Float, path: android.graphics.Path)
net.zetetic.database.sqlcipher.SupportOpenHelperFactory(passphrase: ByteArray)
```

## Bugs found by the tests (worth remembering)

- **Circular enum initialisation**: `ApplicationSite` referenced `SiteGroup`, whose
  constructor listed `ApplicationSite` values. Kotlin initialised `SiteGroup` first,
  so every `ApplicationSite` entry was still `null` → NPE on `RecommendedSite`.
  Fix: `ApplicationSite` no longer references `SiteGroup` in its constructor;
  `group` is a computed property and `SiteGroup` became a plain enum.
- **`BigDecimal.setScale(0)` scales *and* rounds** — `3.5 * 3600` becomes
  `12600.000…1` at 20-digit precision, which rounds to **12601 s**. Interval dosing
  must use bare multiplication + `setScale(0, HALF_UP)`.
- **GS1 AI matching**: trying 4-digit AIs first made `0108…` parse as AI `0108`; trying
  2-digit first made `0100…` parse as AI `02`. Fixed-length AIs must be matched
  longest-first, and a candidate is rejected unless its fixed-length value fits and
  is numeric. Variable AIs only run to FNC1 or end-of-payload.
- **eRecept**: `Regex.find` returned the *first* 12-char token (usually part of a
  timestamp), not the prescription code. Now every candidate is checked with
  `findAll`, and structured JSON payloads expose an explicit code field.

## Implemented

- **Design system**: `Color.kt` (Blossom / Nonbinary / Mint / Sand / OLED palettes,
  dynamic color), `Type.kt` (Expressive emphasised styles), `Shapes.kt`,
  `MorphingShapes.kt` (`PolygonShape`, `MorphShape`, per-form polygons),
  `Theme.kt` (`MaterialExpressiveTheme` + discreet-mode CompositionLocal).
- **Data**: 11 Room entities, `Converters`, 9 DAOs, SQLCipher DB key wrapped by an
  Android-Keystore AES/GCM key, DataStore settings, Hilt modules.
- **Domain**: unit converter (MW-derived factors), reference-range engine, vial /
  dead-space / BUD math, site rotation, schedule engine (fixed/interval/cyclical/
  taper/PRN, DST-safe), trough calculator, interaction engine, inventory + travel
  planner, adherence.
- **Scanning**: GS1 DataMatrix, EAN-13 (check digit), eRecept; SÚKL CSV parser +
  repository with chunked ingest.
- **Background**: exact `AlarmManager` alarms, notification quick actions
  (take/skip/snooze), boot/timezone receiver, WorkManager (SÚKL sync, inventory,
  bloodwork), Glance widget.
- **Labs**: PdfBox-Android text extraction, Czech-lab parser, staging pipeline.
- **UI**: Bento dashboard, morphing dose button, Biomarker Canvas chart with target
  band + milestone pins, medications, inventory/travel, settings, scanner (CameraX +
  ML Kit), bloodwork. EN + CS strings.

## Known limitations / not done yet

- ~~Biometric unlock is a stub~~ — now implemented with `BiometricPrompt`
  (`BIOMETRIC_STRONG` + device-credential fallback) in `security/BiometricGate.kt`.
  Robolectric cannot exercise it, so it is **compile-verified only, not runtime-tested**.
- **Minification (release) not verified** — only `assembleDebug` was run.
- Milestone pins are rendered by the chart but no UI creates milestones yet.
- Health Connect is a dependency only: no `HealthConnectClient` calls are written.
- Symptom ↔ dose correlation is not computed; symptoms are logged and listed only.
- SÚKL CSV URL is a placeholder path; verify against the live catalogue before trusting
  a sync.
- Compose UI has `@Preview`s but no instrumented tests (no emulator in this environment).

## Additional bugs found by the DAO tests (Robolectric + real SQLite)

- **Dispensing order was inverted**: `ORDER BY firstPuncturedDateEpochDay IS NULL DESC`
  put *unopened* vials first, so a freshly opened vial would be used before an
  already-punctured one and the open vial would silently pass its BUD. Correct order is
  punctured-first (`IS NULL ASC`), then earliest expiry, then undated batches.
- **Robolectric under Java 17 rejects SDK 36** ("requires Java 21"); pinned
  `sdk=35` in `app/src/test/resources/robolectric.properties`.
- **Robolectric auto-instantiates `@HiltAndroidApp`**, whose `onCreate` opens the
  Keystore/SQLCipher DB and fails with `KeyStoreException: AndroidKeyStore not found`.
  Fixed with `application=android.app.Application` in the same properties file.

## Reminder audit (fixed 2026-11-11)

Audit of "reminders fire and their settings work" found the feature was **not
delivering reminders at all** in normal use:

| # | Defect | Impact | Fix |
|---|---|---|---|
| R1 | `rescheduleAll` was only called from `BootCompletedReceiver`. `saveSchedule` had **no callers**. | A user could not create a schedule, and any schedule that existed produced no alarm until the next reboot. | Added `ReminderCoordinator.start()` in `Application.onCreate`, plus a settings-driven collector that re-arms on change. |
| R2 | Injectable dose confirmed from a notification logged no `volumeMl`, and `applyInventory` fell back to the **mg dose as mL**. | A 5 mg dose at 50 mg/mL deducted **5 mL instead of 0.15 mL** — the vial was emptied in one tap. | `DoseRepository.log` now derives the volume from concentration when none is supplied, and persists it on the log. Covered by `DoseLoggingTest` (written first, it failed with the vial already empty). |
| R3 | One `PendingIntent` per *occurrence*, request code from a hash of schedule+minute. | A paused/deleted schedule kept its armed alarm; `cancel()` was unreachable and the notification's Take action used `System.currentTimeMillis()` so it logged against the wrong slot. | One alarm per *schedule* (stable request code) + separate snooze code; `sync()` cancels paused, deleted and orphaned schedules and is idempotent. |
| R4 | No staleness guard. | After days without opening the app, a long-past alarm announced "due now". | Occurrences older than 6 h are skipped and the next one is armed. |
| R5 | No `POST_NOTIFICATIONS` request anywhere; `notify()` failed silently on API 33+. | Reminders invisible with no explanation. | Runtime prompt, plus a banner when notifications or exact alarms are unavailable, re-checked on resume (`LifecycleResumeEffect`). |
| R6 | No reminder settings existed (sound, vibration, lead time, snooze, on/off). | Spec'd settings were impossible. | Added to `AppSettings`/DataStore and the Settings screen (localized EN + CS). |
| R7 | `reminderVibration` was saved but never read. | A dead toggle. | Four dose channels (sound×vibration) since channel properties are immutable at runtime. |
| R8 | `leadMinutes` was lost on re-arm inside the receiver. | "Alert before" applied only to the first alarm after a sync. | `armFollowing` now forwards `settings.reminderLeadMinutes`. |

Verification: `DoseAlarmSchedulerTest` (13), `DoseLoggingTest` (6),
`DoseAlarmDecisionTest` (11). Mutation check performed — stubbing `cancel()` to a
no-op makes 4 of the scheduler tests fail, so they genuinely guard the behaviour.

**Still not covered:** the `BroadcastReceiver` classes themselves are not
instantiated in tests (their `@Inject` fields need the Hilt singleton graph, which
Robolectric does not build). Scheduled-alarm state is asserted through
`ShadowAlarmManager`, which is real for `AlarmManager` but not a device test.

## Interaction audit (fixed 2026-11-11)

The engine had rules but was **not clinically working** on real data:

| # | Defect | Impact | Fix |
|---|---|---|---|
| I1 | `DashboardViewModel` built substances with `mapNotNull { med.activeSubstance?.let { … } }`. | Any medication with **no substance name was silently excluded** — including every SÚKL row that carries only an ATC code. Warfarin+NSAID etc. never fired. | `InteractionEngine.ActiveSubstance` now carries name *or* ATC; the engine filters only when **both** are blank. |
| I2 | Drug rules matched **only** on ATC (`matches` ignored the name). | Name-only medications (SÚKL rows without ATC) were inert for drug–drug checks. | Rules match on either identifier. |
| I3 | Lifestyle/absorption keywords were English/Czech-lowercase only, compared against raw SÚKL names. | `Magnesii oxidum`, `Calcii carbonas`, `Ferri sulfas` (the actual SÚKL spellings) matched **nothing**; `Železo` failed case-folding. | Names are normalised (NFD, diacritics stripped, lowercased) and keyword lists include Latin stems (`magnesii`, `calcii`, `ferri`, `magnesio`). |
| I4 | `absorptionConflicts()` was public but **had no callers**. | Iron+calcium / levothyroxine+mineral collisions were computed nowhere and shown to nobody. | Conflicts are emitted as `InteractionAlert`s with the required gap, carrying medication ids for future scheduler offsetting. |
| I5 | Dashboard rendered `interactionAlerts.first()` inside a single card. | A **MINOR** alert could hide a **SEVERE** one; the rest were invisible. | All alerts are listed, sorted by severity, with a `Severe/Moderate/Minor` prefix and an error-container colour when any is severe. |
| I6 | Alerts existed only on the dashboard. | While adding a medication the user saw no conflict. | `MedicationsViewModel` groups alerts by medication id and each list row shows its own alert. |

Verified regimen (estradiol + spironolactone) yields exactly: potassium warning
(MODERATE), oestradiol+spironolactone (MINOR), and two oestrogen lifestyle notes —
no duplicates.

`InteractionEngineTest` (22 tests). Mutation check performed: suppressing the
absorption alerts fails the dedicated test, and restoring the old `mapNotNull`
filter would drop ATC-only medications.

**Known noise:** every oestrogen user gets "St John's Wort" and "smoking" notes
purely from ATC `G03CA`. That is intentional information, not a detected conflict,
but it means the dashboard banner appears for most HRT users. Consider marking
these as *advice* rather than *interactions* if the banner feels alarmist.

## Barcode / box scanning audit (fixed 2026-11-11)

| # | Defect | Impact | Fix |
|---|---|---|---|
| S1 | `SuklRepository.findByGtin` **dropped 3 digits** of the GTIN-14 and treated the rest as a SÚKL code. | GTIN and SÚKL code are unrelated (manufacturer GS1 vs Czech registry). This fabricated a lookup that could resolve to a **completely different medication**. | GTIN is now a real column on `sukl_medications`, ingested from the CSV (`GTIN/EAN/CAROVY_KOD` aliases). Lookup matches the stored GTIN only, tolerating the packaging indicator (0–8). |
| S2 | `ScannerViewModel.save()` ignored `ScannedPackage.expirationDate` / `batchNumber`; `InventoryRepository` was not even injected. | Scanning an EU-FMD box showed expiry on screen and **threw it away** — no inventory batch, so expiry and BUD alerts could never fire. | Extracted `PackageToInventory.batchFrom` (pure, tested) and wired it into `save()`; added a quantity field (falls back to GS1 AI 30, then 1 unit). |
| S3 | `isHrt` returned true for ATC `H02` — **corticosteroids**, not HRT. | Every prednisone prescription was flagged as HRT and polluted the trough/interval analysis. | HRT is `G03` (sex hormones) and `G02` (gynaecological hormones) only. |
| S4 | `searchSukl` auto-applied the first hit on every keystroke. | Typing "Estrofem" was impossible — the field was overwritten after 3 characters. | Search now only fills `suklSuggestions`; the user taps one to apply it (`pickSukl`). |
| S5 | Unparseable DataMatrix / bad EAN check digit returned silently. | Looked like a broken camera with no feedback. | Explicit status messages for both failure modes. |
| S6 | Dashboard computed the injectable volume itself while the repository also derives it. | Two code paths for one clinical number. | Dashboard passes no volume; the repository is the single source. |

Tests: `SuklGtinLookupTest` (4), `PackageToInventoryTest` (10), `ScannerSearchTest` (6).
Mutation check: restoring the old `substring(3)` lookup fails 2 GTIN tests.

**Still unverified:** the CameraX analyzer and ML Kit decoding are not exercised by
any test (no camera under Robolectric). `ScannerSaveTest` was attempted and removed —
`viewModelScope` + Room + Robolectric did not run deterministically, which is why the
batch mapping was extracted into a pure function instead. Camera→ML Kit→`onBarcode`
needs a device test.

## Inventory calculator audit (fixed 2026-11-11)

| # | Defect | Impact | Fix |
|---|---|---|---|
| V1 | `dailyFor` treated a schedule as **one dose per day** regardless of `timesOfDay`. | A twice-daily tablet showed **half** the real consumption, so "days left" was roughly doubled and low-stock alerts never fired. | Doses per day = `timesOfDay.size`; interval-based schedules deliberately ignore it. |
| V2 | Tapering always used `taperSteps.first()`. | A patient on a 4→1 mg taper was costed at 4 mg/day forever, **understating** supply by 4×. | `currentTaperStep(schedule, today)` walks the steps by elapsed days; travel plan uses the step in force at trip start. |
| V3 | "Days left" was computed from **all** stock, and expired/past-BUD batches were still counted. | App promised supply the patient cannot legally take; a fully expired medication read as "N days left". | New `usableRemaining` (excludes expired and past-BUD), `daysRemaining` derives from it. `remaining` still reports the physical shelf count. |
| V4 | `expiringBatches` filtered only `!expiry.isAfter(cutoff)`, so **already-expired** batches appeared as "expires soon". | Expired stock was labelled as a future expiry instead of "discard". | Separate `expiredBatches` and `expiringBatches` (future only); inventory UI and the stock worker report them differently. |
| V5 | `beyondUsePassed` included **empty** vials. | "Discard" nags for a vial with nothing left. | Only batches with `remainingQuantity > 0`. |
| V6 | Travel plan ignored expiry/BUD and never received the trip date. | A plan could look sufficient using stock that expires before departure; tapering was costed at today's step rather than the trip's. | Availability excludes batches that expire or pass BUD before `tripStart`; `tripStart` is passed through. |
| V7 | `ceilToUnit` had a branch that returned the same value either way. | Dead code implying a rounding rule that did not exist. | Plain `setScale(0, CEILING)` with a comment on why whole units matter. |

Tests: `InventoryCalculationTest` (35). Mutation check: reverting all four behaviours
fails 11 of those tests.

**Known limitation:** `usableRemaining` treats a past-BUD batch as fully unusable, when
in practice only that batch is unusable — the arithmetic is correct for "what can I take
today" but does not model partial vials separately in the UI.

## Bloodwork analysis audit (fixed 2026-11-11)

| # | Defect | Impact | Fix |
|---|---|---|---|
| B1 | Printed lab bounds were stored in the lab's unit (`pg/mL`) while the value was normalised to SI (`pmol/L`). | A 610 pmol/L result was compared against a "734" ceiling that was really 200 pg/mL — **every result looked normal**. | `LabRangeConverter.toSi` converts both bounds using the row's own unit; unrecognised units keep the original numbers rather than dropping them. |
| B2 | `LAB_PRINTED` had no ranges at all and was filtered out of the UI chips. | The one profile that reflects the actual report was unreachable. | `ReferenceRanges.printedRange` builds a profile from the newest session's own bounds; the chip is offered. |
| B3 | `BloodworkViewModel` hardcoded `ProfileType.HRT_FEM`. | The `defaultRangeProfile` setting was ignored, so a masculinising patient saw feminising targets. | The profile is loaded from `SettingsRepository` and re-applied on change; an unknown stored value falls back to `HRT_FEM`. |
| B4 | `assessTrough` had **no caller** — the UI never invoked it. | Trough/peak context, a headline feature, never appeared. | Derived automatically from the newest session, and re-assessed at the *draw* time on import. |
| B5 | `saveImport` stamped `isTrough` from the **current** state. | A report entered days later got today's timing instead of the venipuncture's. | Assess at the supplied draw timestamp. |
| B6 | `lastTakenForSchedule` fetched the medication's newest dose then discarded it if it belonged to another schedule. | With morning+evening schedules, "no previous dose" was reported and the trough was never computed. | New `lastTakenForScheduleId` query scoped by `scheduleId` in SQL; `LATE` doses count. |

Tests: `LabRangeNormalizationTest` (7), `LabImportPipelineTest` (4),
`LastDosePerScheduleTest` (4), `BloodworkAnalysisTest` (6).

Mutation check: reverting the bounds conversion **does not** fail the unit tests —
it fails only `LabImportPipelineTest`, which was added precisely because the first
mutation run passed. Worth remembering: testing a converter is not testing the path
that calls it.

**Known limitation:** `BloodworkAnalysisTest` needed `Dispatchers.setMain`, and the
PDF/OCR extraction itself (PdfBox, ML Kit) is still only compile-verified.

## Export & biometric audit (fixed 2026-11-11)

| # | Defect | Impact | Fix |
|---|---|---|---|
| E1 | Biometric `lock()` existed but was **never called**; `unlocked` was only ever set to true. | Once opened, the app stayed unlocked for the whole process lifetime — including after backgrounding. A privacy lock that never re-locks. | `LifecycleEventEffect(ON_STOP)` calls `viewModel.lock()`. |
| E2 | Enabling the lock mid-session did not take effect until the next cold start. | The toggle looked broken, and the session stayed open. | `AppViewModel` tracks the previous value and closes the lock when it turns on. |
| E3 | The export PDF was written to `cacheDir` and the UI said "Share it with your doctor" — but there was **no FileProvider and no share action**. | The generated file was unreachable; the promise in the UI was false. | Added `FileProvider` + `res/xml/file_paths.xml` and a Share button. Authority uses `${applicationId}`, so it also works with the `.debug` suffix (verified in the packaged manifest). |
| E4 | `exportReport()` called `generate()` without the profile. | The PDF always used `HRT_FEM` targets, even for a masculinising patient. | The profile is read from settings and passed through. |
| E5 | PDF had **no deltas and no chart**, though the spec asks for both. | A doctor could not see whether a value was rising or falling. | `ReportContent` computes signed deltas against the previous draw; a vector trend line with a shaded target band is drawn with `Path`. |
| E6 | Only 30-day adherence was printed. | Spec asked for 30 **and** 90 days. | Both windows are rendered. |
| E7 | No milestones in the report. | A dose change looked like unexplained lab noise. | `MEDICATION CHANGES` section, newest first. |
| E8 | `ReportContent.build` shift: all content is now computed off-Canvas. | — | `PdfDocument` throws `document is closed!` under Robolectric, so the renderer cannot be tested; content can. |

Tests: `AppLockStateTest` (6), `ReportContentTest` (13).

Mutation check: reverting the mid-session re-lock fails `AppLockStateTest`.

**Known limitation:** the Canvas drawing itself is still compile-verified only — every
`drawText`/`drawPath` call is exercised by reading the content model, not by
inspecting a rendered PDF. `BiometricPrompt` likewise cannot be driven under
Robolectric.

## Health Connect audit (2026-11-11)

Health Connect was **not integrated at all** — it was a dependency and a DataStore
flag with nothing behind either:

| # | Finding | Evidence |
|---|---|---|
| H1 | No Health Connect code existed. | `grep -rn "HealthConnectClient" app/src/main/java` → zero hits. |
| H2 | The `healthConnectEnabled` setting was written but never read by anything. | Only definitions in `SettingsRepository`; no consumer. |
| H3 | No `health.READ_*` / `health.WRITE_*` permission was declared. | `AndroidManifest.xml` had none. |
| H4 | The toggle was absent from the UI. | No match in `ui/`. |
| H5 | The dependency was still packaged into the APK. | `:app:dependencies` resolves `connect-client:1.2.0-alpha06` + proto. |

The spec asks for "Health Connect API (syncing weight, vitals)". Implemented to match:

- `HealthPermissions` — only the vitals the app records (BP, heart rate).
  No steps/sleep, and **no weight**: the app has no weight log, so requesting
  `READ_/WRITE_WEIGHT` would be access without a purpose (removed on request).
- `HealthConnectAvailability` — never throws; distinguishes unavailable from
  "provider needs an update", and the settings screen offers the right action.
- `HealthRecordMapper` — rejects implausible values (HR outside 20–300, diastolic
  above systolic) instead of writing junk into a record clinicians may read.
  Records get a stable `clientRecordId`, so a repeated sync cannot duplicate.
- `HealthConnectSyncer` + `PlatformHealthConnectWriter` — writes through the real
  client with `Metadata.manualEntry()`; returns a result, never throws.
- Vitals are pushed right after `SymptomsViewModel.save()`; a sync failure cannot
  lose the entry the user just typed.
- Settings card with the real `PermissionController` contract; the switch only turns
  on once the write permissions are granted.
- `<queries>` for `com.google.android.apps.healthdata` (required on Android 13 and
  below where Health Connect is a separate APK).

Verified in the packaged APK: the four remaining permissions are present, and the
`<queries>` package entry is emitted.

Tests: `HealthConnectTest` (13). Mutation check: removing the `systolic > diastolic`
guard fails the dedicated test.

**Known limitation:** the writer itself is not exercised against a live provider —
Robolectric has no Health Connect, so `PlatformHealthConnectWriter` is
compile-verified and `HealthConnectAvailability` is asserted to report
`UNAVAILABLE` there.

Weight was deliberately dropped (2026-11-11): the permission was declared but no
weight log exists, so `HealthConnectTest` now asserts that no permission mentioning
`WEIGHT` is requested. Adding a weight log later means re-adding both the permission
and the record mapping together.

## Test environment limits (verified, not guessed)

- Robolectric's `android.graphics.pdf.PdfDocument` is a native stub under the sandbox:
  `startPage` throws `document is closed!`. A smoke test for `DoctorReportGenerator`
  was attempted and **removed** — the PDF path is compile-verified only.
- `BiometricPrompt` cannot be driven under Robolectric either; `BiometricGate` is
  compile-verified only.
- Anything touching `AndroidKeyStore` fails under Robolectric, which is why
  `robolectric.properties` forces a plain `Application`.


## Spec compliance audit vs AGENT.md (2026-11-11)

Legend: **DONE** = implemented and tested · **PARTIAL** = logic exists but unreachable
or incomplete · **MISSING** = absent.

### §2 Tech stack
| Item | State | Note |
|---|---|---|
| Kotlin 2.0+, Gradle KTS, version catalog, KSP | **DONE** | Kotlin 2.3.21, AGP 9.1 |
| Compose + Material 3 Expressive | **DONE** | `MaterialExpressiveTheme` from 1.5.0-rc01 |
| `graphics-shapes` morphing | **DONE** | `PolygonShape`, `MorphShape` |
| Clean Architecture + StateFlow | **DONE** | 3 layers, MVI-ish VMs |
| Hilt | **DONE** | |
| Room + SQLCipher + DataStore + biometric | **DONE** | biometric now re-locks |
| AlarmManager exact + WorkManager + receivers | **DONE** | fixed in reminder audit |
| CameraX + ML Kit barcode + Text Recognition v2 | **DONE** | barcode + OCR both wired (F2) |
| PdfBox-Android | **DONE** | |
| Compose Canvas charts | **DONE** | Bézier + target band |
| Glance widget | **DONE** | |
| Health Connect | **PARTIAL** | writes BP/HR only; weight removed on request |

### §3 UI/UX
| Item | State | Note |
|---|---|---|
| Shape per medication form | **DONE** | pill/hexagon/squircle/droplet |
| Dose button morph with bouncy spring | **DONE** | `MorphingDoseButton` |
| Bento grid with varied containers | **DONE** | |
| Display/Headline typography | **DONE** | |
| Floating pill bottom nav + FAB | **DONE** | `ShortNavigationBar` |
| Dynamic color / OLED / pastel / trans palettes | **DONE** | 5 palettes |
| Discreet: mask notifications | **DONE** | |
| Discreet: swap launcher icon + name | **DONE** | `activity-alias` |
| Discreet: hide HRT badges | **DONE** | see Phase 1.4 |

### §4A SÚKL & scanner
| Item | State | Note |
|---|---|---|
| SÚKL open data parse/query | **DONE** | CSV + repository |
| PIL/SPC links from 7-digit code | **DONE** | |
| EAN-13 + GS1 DataMatrix, AI 01/17/10 | **DONE** | fixed GTIN mapping |
| (17) auto-populates inventory expiry | **DONE** | |
| eRecept scan + 14-day validity | **DONE** | queue, persistence, expiry warning, see F7 below |

### §4B Scheduling & HRT forms
| Item | State | Note |
|---|---|---|
| Fixed/interval/cyclical/tapering/PRN | **DONE** | engine **and** editor |
| PRN max daily dose limit | **DONE** | enforced before the write, see Phase 3.1 |
| Fasting constraint (`30 min before meal`) | **DONE** | `MealRelation` + `offsetMinutes` |
| Auto-offset alarm on absorption collision | **DONE** | applied once, never over a user offset — see Phase 3.2 |
| Injection mg→mL | **DONE** | |
| Patch state machine (Applied→worn→replace) | **DONE** | see Phase 2.1 |
| Gel/spray actuation counts | **DONE** | see Phase 2.2 |
| Sublingual countdown timer | **DONE** | see Phase 2.3 |
| Site rotation algorithm | **DONE** | `SiteRotation.recommend` + site picker |
| Interactive 2D body map | **DONE** | `BodyMapCanvas` + `BodyMapPicker`, see F1 below |

### §4C Inventory
| Item | State | Note |
|---|---|---|
| Dead space + BUD | **DONE** | fixed calculation |
| Consumables auto-deduct on injection | **DONE** | |
| Travel calculator | **DONE** | |
| Travel checklist of vials/needles/syringes (not just medication units) | **DONE** | `TravelPlan.consumables`, see F4 below |
| Create inventory batches from UI | **DONE** | `BatchEditorDialog` + `BatchDraftValidator`, see F5 below |

### §4D Interactions
| Item | State | Note |
|---|---|---|
| Drug–drug ATC engine | **DONE** | fixed ATC-only matching |
| Food/supplement/lifestyle alerts | **DONE** | |
| UV index warning | **DONE** | `UvRepository` + `Photosensitivity`, see F6 below |

### §4E Bloodwork
| Item | State | Note |
|---|---|---|
| HRT_FEM / HRT_MASC / CIS_F / CIS_M ranges | **DONE** | |
| LAB_PRINTED range | **DONE** | fixed unit conversion |
| **CUSTOM doctor target (user entry)** | **MISSING** | profile exists but there is **no UI to enter the window** |
| **Overlay several ranges at once** | **MISSING** | one profile selectable at a time |
| Bidirectional unit converter | **DONE** | all listed biomarkers |
| Trough/peak calculation | **DONE** | now automatic |
| Pre-test morning-dose warning | **DONE** | |
| PDF text lab parser + staging screen | **DONE** | |
| OCR of photographed reports | **DONE** | ML Kit Text Recognition v2, see F2 below |
| Correlation graph with Δ and target band | **DONE** | |
| **Medication milestone pins in the chart** | **PARTIAL** | chart supports them; Bloodwork screen passes `emptyList()` |
| **Create milestones** | **MISSING** | no UI ever calls `milestoneRepository.add` |

### §4F Symptoms & report
| Item | State | Note |
|---|---|---|
| Log 1–5 severity, mood, BP/HR etc. | **DONE** | |
| Correlate symptom spikes with dose times | **DONE** | `SymptomCorrelationCalculator`, see F3 below |
| One-page PDF: meds, adherence 30/90, lab table with deltas, chart | **DONE** | |

### §5 Database schema
All 10 specified entities exist with the specified columns. `ReferenceRangeProfileEntity.profileType` is stored as a string that also admits `LAB_PRINTED`, which is a superset of the spec. Deviation: `SuklMedicationEntity` adds `gtin` (needed for the corrected GTIN lookup).

### §6 Step plan
Steps 1–6 all have implementations; see the gaps above for what is only partial.

### §7 Standards
| Item | State | Note |
|---|---|---|
| `@Preview` for screens | **DONE** | 12 screens (incl. scanner and lab capture), 29 previews total |
| Light/Dark previews | **DONE** | 14 dark variants; every screen has light+dark, guaranteed by `ScreenPreviewTest` |
| DST / BigDecimal / missing bounds | **DONE** | covered by tests |
| **EN + CS strings** | **DONE** | every screen label goes through `stringResource`, `values-cs` at parity (250 keys), guarded by `HardcodedStringTest` + `StringsParityTest` |

### Summary
- **Fully done:** stack, theme, scheduling engine, vial math, inventory math, interactions,
  bloodwork analytics, PDF export, reminders, lock, Health Connect (BP/HR).
- **Unreachable logic (highest impact):** custom doctor target, milestone creation + pins,
  prescription queue, PRN daily cap, absorption-driven alarm offset, patch/gel/sublingual
  handling, OCR, symptom correlation, discreet HRT badge, Czech localization of 42 strings.
- ~~No schedule editor~~ — **added 2026-11-11**, see below.

## Schedule editor (added 2026-11-11)

Closes the largest gap in the audit: `saveSchedule` previously had no caller, so no
regimen could be created at all.

- `ScheduleValidator` — pure validation + draft conversion. Rejects the failure modes
  that would produce a silently dead regimen: interval schedules without an interval,
  daily schedules with no time, taper with no steps, zero/negative dose, absurd
  intervals (a typo of "3500" days), PRN max not above a single dose.
- `ScheduleEditorViewModel` — draft state, defaults per medication form (an injection
  starts as a 3.5-day interval with site rotation and a `mg` unit), validation
  feedback, and save/delete that **re-arm the alarm immediately**. Without that call a
  new schedule would have no reminder until the next cold start — exactly the bug the
  reminder audit found.
- `ScheduleEditorScreen` — type chips, interval field, `TimePicker` for multiple daily
  times, dose/unit, taper step editor, PRN daily cap, food relation + offset, site
  rotation, active toggle, note. Reachable from each medication card, which now shows
  its schedules or a red "No schedule — no reminders" hint.
- Navigation route `schedule/{medicationId}/{scheduleId}`; `MedicationsViewModel`
  groups schedules by medication via `combine` + `flatMapLatest`.

Tests: `ScheduleValidatorTest` (25), `ScheduleEditorIntegrationTest` (9 = 34 in the
package). The integration test asserts the **database and the armed alarm**, not just
the returned id. Mutation check: removing the `refreshSchedule` call fails it.

## Honest bottom line
The app is **not** a complete implementation of AGENT.md. Core domain logic and the
offline data pipeline are solid and tested (293 tests), but roughly a third of the
user-facing capabilities are either absent or exist as logic with no way to reach them.

## Phase 0.1 done — Room migrations (2026-11-11)

The database was `version = 1` with only `fallbackToDestructiveMigrationOnDowngrade`.
Every column added by the remaining plan would have wiped patient data on update.

- `MedsyMigrations` (`data/local/Migrations.kt`) — `MIGRATION_1_2` plus a
  `statements1to2()` mirror so tests can assert on the SQL without running Room.
  Rules documented in the file: new columns nullable or defaulted, never drop a table
  holding patient records.
- Schema **v2** adds: `dose_logs.patchState`, `dose_logs.wornUntil`,
  `schedules.actuationsPerDose`, `schedules.holdMinutes`, and the prescription queue
  (`prescriptions`, `prescription_items` with `ON DELETE CASCADE` and a unique code
  index).
- `DatabaseModule` now registers `addMigrations(...)`; the destructive fallback is
  gone. `MedsyDatabase.CURRENT_VERSION = 2`.
- Domain models, entities and mappers carry the new fields end to end.

Tests: `MigrationRegistryTest` (9), `MigrationImportTest` (8), `RoomUpgradeTest` (4).
The strongest guarantee is `RoomUpgradeTest`: it builds a **real v1 SQLite file from
the generated DDL in `schemas/1.json`**, seeds rows, then opens it through Room with
the migrations. Room validates every column and index against the entities, so a
mismatch throws instead of serving a broken database.

Two behaviours learned while testing (both now encoded):
- Raw SQLite has `foreign_keys` **off** by default; the cascade only fires after
  `PRAGMA foreign_keys = ON`. Room sets this per connection.
- `ALTER TABLE ADD COLUMN` is **not** re-runnable, so a migration is single-use;
  the `CREATE TABLE IF NOT EXISTS` statements are guarded so a partially applied
  upgrade can be retried.

Mutation check: unregistering the migration fails 7 tests.

Next per the plan: Phase 1.1 (milestones).

## Phase 1.1 done — medication milestones (2026-11-11)

The chart and the PDF could already draw milestone pins, but nothing ever created one,
so a dose change looked like unexplained lab noise.

- `MilestoneDiff` (`domain/milestone/`) — pure decision logic. Records `STARTED` on
  create, `DOSE_CHANGED` (with both values) on a dose or unit change,
  `ROUTE_CHANGED` on a frequency/type/time change, `STOPPED`/`STARTED` on pause and
  resume, `STOPPED` on delete. **Deliberately ignores note edits and equal-but-rescaled
  doses** (`5` vs `5.0`), and collapses a simultaneous dose+frequency change into one
  pin so two markers cannot overlap on the chart.
- `MedicationRepository.saveSchedule` / `deleteSchedule` / `setScheduleActive` record
  milestones themselves, so every caller gets them and none can forget. The
  repository now takes a `MilestoneDao`.
- `BloodworkViewModel` loads milestones and passes them to `BiomarkerChart` (was
  `emptyList()`), refreshing on profile/selection change.
- `BiomarkerChart` now renders the milestone **label**, alternating rows so nearby
  pins do not collide, with ellipsis truncation.
- The PDF report already read `milestoneRepository.all()`, so it picks these up.

Tests: `MilestoneDiffTest` (22), `MilestoneRecordingTest` (7). Mutation check:
skipping the write fails 4 recording tests.

Two test-only fixes were needed as a side effect: `Room`'s suspend DAO calls are not
visible synchronously on the test thread, so the schedule-editor integration test now
polls for the expected state instead of asserting immediately.

Next per the plan: Phase 1.2 (custom doctor target).

## Phase 1.2 done — custom doctor target (2026-11-11)

`ProfileType.CUSTOM` existed as a label with no way to enter a window.

- `CustomTargetValidator` — validates user input and, crucially, **detects unit
  mix-ups**: a number that is implausible as SI but plausible once read in the
  conventional unit (100 pmol/L vs 100 pg/mL) produces a warning rather than being
  silently saved. The envelope comes from the built-in *warning* bounds only, because
  target windows legitimately differ per patient group.
- `CustomTargetViewModel` — converts the typed value from the chosen unit into SI
  **before** validating and saving, since every stored value is SI.
- `CustomTargetScreen` — min/max fields, SI/conventional unit chips, inline errors and
  warnings, remove action; reachable from the bloodwork screen when `CUSTOM` is
  selected, which now shows the current window.
- `BloodTestRepository.deleteRange` added so a target can be removed.
- `BloodworkViewModel` already re-read custom ranges, so the chart picks the saved
  window up without further wiring.

Tests: `CustomTargetValidatorTest` (19).

**Two design bugs found while writing the tests** (both fixed):
- the first envelope used target bounds too, so a legitimate 300–1000 ng/dL entry was
  not flagged *and* a plausible masculinising window was; warning bounds are the right
  yardstick.
- the unit check converted in the wrong direction: the typed number is already in the
  conventional unit and must be converted *to* SI before comparing, not the other way.

Next per the plan: Phase 1.3 (overlaying ranges).

## Phase 1.3 done — overlaying reference ranges (2026-11-11)

The spec asks to "allow switching and **overlaying** ranges per biomarker"; only one
profile could be active at a time.

- `RangeBands` (`domain/calc/`) — pure builder: primary band first, then overlays,
  capped at `MAX_BANDS = 3` with the **primary guaranteed to survive truncation**
  (it decides the status colour). A profile whose range is unknown, or `CUSTOM` with
  nothing stored, is skipped rather than drawn as an empty band.
- `BiomarkerChart` takes `bands: List<TargetBand>`; the value axis accounts for every
  band, overlays are drawn first so the primary stays visible through the translucency.
- `BloodworkViewModel` keeps `profile` (primary, drives evaluation) plus
  `overlayProfiles: Set<ProfileType>`; `toggleOverlay` ignores the primary.
- `BiomarkerSeries.bands` carries the composed bands; the screen renders them plus a
  colour legend when more than one is shown.
- Profile chips now act as primary/overlay toggles, labelled "(primary)" for the anchor.

Tests: `RangeBandsTest` (13), plus 5 overlay tests in `BloodworkAnalysisTest`.
Mutation check: drawing overlays ahead of the primary fails 3 band tests.

**Test-infrastructure notes** (cost real time, worth recording):
- Gradle reuses a filtered test result for the whole task. `--tests Class` after a
  single-method run silently reported "1 test". `:app:cleanTestDebugUnitTest` forces a
  true run — the class then executed 11 tests in 5.5 s, so nothing was hanging.
- `--rerun-tasks` breaks `processDebugResources`, and on Windows
  `bundleDebugClassesToRuntimeJar` fails with a file lock while another daemon holds
  the jar. `./gradlew --stop` clears it.
- ViewModel background collectors leaked between tests and made
  `Dispatchers.resetMain()` throw; the test now cancels `viewModelScope` per instance.

Next per the plan: Phase 1.4 (discreet mode hides HRT badges).

## Phase 1.4 done — discreet mode hides HRT badges (2026-11-11)

The spec asks that discreet mode hide HRT badges. The audit found the badge was always
shown, and the wider leak was bigger than the badge itself.

- `DiscreetDisplay` (`domain/discreet/`) — pure decision for one medication row. In
  discreet mode it hides the **badge, the active substance name and the ATC code**.
  That matters because `G03CA` names an oestrogen just as plainly as the word "HRT";
  fixing only the badge would have left the diagnosis readable.
- Strength and form stay visible — needed to pick the right box, and not
  diagnosis-specific. If nothing safe remains, the detail line is omitted rather than
  shown empty.
- Wired into `MedicationsScreen` (masked name styled as muted), and — because the same
  leak existed elsewhere — into the dashboard stock alerts, the inventory list, the
  travel checklist and the schedule editor.

Tests: `DiscreetDisplayTest` (12). Mutation check: leaving the substance and ATC
visible fails 2 tests.

Next per the plan: Phase 3.1 (PRN daily limit).

## Phase 3.1 done — PRN daily limit (2026-11-11)

`maxDailyDose` was stored and shown in the editor but never enforced, so a "limit"
could be exceeded freely.

- `PrnLimit` (`domain/calc/`) — pure counting rules. Only `TAKEN`/`LATE` doses count
  (a skipped or snoozed dose has not entered the body), only the same schedule, only
  the local calendar day. Fractional doses accumulate.
- `DoseRepository.log` checks the ceiling **before** writing and throws
  `PrnLimitExceeded`; `allowOverLimit` exists only for an explicit user confirmation.
  This is the boundary the dashboard and the notification action both cross, so the
  limit cannot be bypassed by a lock-screen button.
- New DAO query `forScheduleBetween` returns the day's doses for one schedule.
- Dashboard: a confirmation dialog ("Over the daily limit … Take anyway / Cancel")
  instead of a silent failure.
- Notification quick-take: if the limit blocks it, the dose is **not** written and the
  user gets a "Dose not recorded" notification telling them to confirm in the app.

Tests: `PrnLimitTest` (15), `PrnLimitRepositoryTest` (8). Mutation check: removing the
guard fails 2 repository tests.

Next per the plan: Phase 3.2 (automatic alarm offset on absorption conflict).

## Phase 3.2 done — automatic alarm offset on absorption conflict (2026-11-11)

`absorptionConflicts` computed the required gap but nothing acted on it.

- `AbsorptionOffsetPlanner` (`domain/schedule/`) — pure decision logic with two hard
  rules: **never override an offset the user set** (that is a clinical decision; the
  conflict is reported as unresolved advice instead), and **move the later dose**, so
  the anchor medication (levothyroxine taken fasting on waking) stays where the patient
  expects it. The gap comes from the conflict, not a constant.
- `ReminderCoordinator.refresh` applies the plan by persisting `offsetMinutes` — not
  only shifting the alarm — so the schedule list shows the real due time. It then
  re-reads the schedules, so the caller arms the shifted times.
- The shift is **idempotent**: once `offsetMinutes` is set, the planner treats it as
  configured and leaves it alone. Without this every refresh would push the dose
  further out.
- Offsets are surfaced once in a "Reminders adjusted" banner on the medication list and
  are consumed on read, so they do not nag.

Tests: `AbsorptionOffsetPlannerTest` (10), `AbsorptionOffsetIntegrationTest` (10).
Mutation check: ignoring the existing-offset guard fails 2 tests — the first attempt
mutated the wrong file and silently passed, which is why the check was repeated.

Next per the plan: Phase 2.1 (patch state machine).

## Phase 2.1 done — patch state machine (2026-11-11)

`PatchState` was a declared enum, and the v2 columns existed, but nothing ever set them.

- `PatchLifecycle` (`domain/patch/`) — pure state machine: `APPLIED` on logging a patch,
  `WORN` before the wear window closes, `REMOVAL_DUE` at and after it, `REMOVED` on
  confirmation. The window comes from the schedule interval (default 84 h), with an
  absurd value falling back to the default rather than producing a year-long patch.
- `DoseRepository.log` records `patchState` + `wornUntil` **only** for a `PATCH` form
  that was actually taken; a skipped patch never starts the wear clock, and a tablet has
  no wear window at all.
- `markPatchRemoved` / `currentPatch` added, backed by a `latestPatch` query.
- Dashboard shows an "active patch" card with the remaining wear time and a **Removed**
  button when removal is due. A patch forgotten more than 12 h past its window stops
  asking — the patient has certainly dealt with it.

Tests: `PatchLifecycleTest` (17), `PatchPersistenceTest` (9). Mutation check: not
persisting the state fails 4 tests.

Next per the plan: Phase 2.2 (gel/spray actuation counts).

## Phase 2.2 done — gel/spray actuation counts (2026-11-11)

The `actuationsPerDose` column existed from migration v2 but nothing read or wrote it,
so a two-pump dose deducted **one** unit from the bottle.

- `MeteredDose` (`domain/calc/`) — pump arithmetic. `pumpsPerDose` defaults to 1 and
  ignores zero/negative values; `deductForDose` gives the amount to remove;
  `Meter` turns a batch into remaining pumps plus **whole** doses left (a partial pump
  dose cannot be dispensed) and a used fraction for the progress bar.
- A batch counted in `mL` returns **no** meter: millilitres cannot answer "how many
  pumps are left", and a wrong number here would be worse than none. Czech and English
  unit spellings (`dávka`, `pump`, `spray`, `actuation`, `dose`) are recognised.
- `DoseRepository.applyInventory` now deducts the configured pump count for `GEL`/`SPRAY`
  and receives the schedule for that purpose.
- Schedule editor gained a "Pumps (sprays) per dose" field (shown only for metered-dose
  forms), carried through `ScheduleDraft` and validated (whole number > 0).
- Inventory list shows "44 of 60 pumps · 22 doses left" when a meter applies.

Tests: `MeteredDoseTest` (18), `MeteredDoseDeductionTest` (6), plus 4 validation tests.
Mutation check: ignoring the pump count fails 4 deduction tests.

Next per the plan: Phase 2.3 (sublingual countdown).

## Phase 2.3 done — sublingual hold timer (2026-11-11)

The `holdMinutes` column existed from migration v2 but nothing read it.

- `HoldTimer` (`domain/calc/`) — the remaining time is **derived from the start instant**
  rather than decremented, which is what keeps it correct after the screen was off or
  the process died. Default 10 min; a zero or absurd configured value falls back rather
  than producing a 83-hour hold.
- Dashboard: logging a `SUBLINGUAL` dose starts a card with a progress ring and a
  "Keep it under your tongue" countdown that ticks once a second, then flips to
  "You can swallow now". The tick recomputes from the start instant, so a backgrounded
  app resumes with the right value instead of restarting.
- Advisory only: the dose is saved **before** the timer starts, so the timer can never
  block logging.
- Schedule editor gained "Hold under tongue (minutes)", shown only for sublingual
  forms, validated as 1–60 minutes.

Tests: `HoldTimerTest` (20), plus 4 validation tests.
Mutation checks: detaching the countdown from the end instant fails 1 test; ignoring
the configured hold fails 4. The first mutation was too narrow to be convincing, so it
was replaced with one that exercises the real behaviour.

Next per the plan: F1 (2D body map), F2 (OCR of photographed lab reports), F3
(symptom ↔ dose correlation), F4 (consumables in the travel plan), F5 (manual batch entry)
and F6 (UV index) are **done**, see below. F7 (eRecept queue) is done as well. Next is
nothing — F1–F9 are done.

## F1 — Interactive 2D body map (done)

- `domain/bodymap/BodyMapHitTest.kt` — tap → site in normalised coordinates, tolerance
  `0.08`. Kept out of the composable because the body faces the viewer: the patient's
  left is on the right of the screen, and a mirrored hit test would record the side just
  used.
- `ui/components/BodyMapCanvas.kt` — schematic torso/arms/legs, dots coloured by
  `Freshness`, ring on the suggestion, thicker ring on the selection. Taps go through
  the hit test; `contentDescription` summarises selected + suggested for TalkBack.
- `ui/components/BodyMapPicker.kt` — map plus freshness legend, a sentence naming the
  chosen site (“used 12 days ago” / “never used”) and a chip row with **every** site, so
  the map is operable with a screen reader or switch device. Both routes call one
  `onSelect`.
- `DashboardViewModel` — `SiteChoices` per rotation-tracked medication
  (`siteChoicesFor(medicationId)`), built from `doseRepository.recentSiteHistory`.
  Previously one global `recommendedSite` was sent for whichever dose was tapped.
- `DashboardScreen` — tapping “take” on a dose whose schedule has `requireSiteRotation`
  opens `DoseSiteDialog` (map, pre-selected suggestion, Override + confirm). Doses
  without a site log immediately, as before. Discreet mode still masks the drug name.
- `DoseSiteDialog` is a `Dialog` with a scrollable column rather than an `AlertDialog`:
  the map needs the vertical room.

Tests: `SiteMapInteractionTest` (7), `SiteHistoryTest` (5 — suggestion and map colouring
agree, other forms ignored), `SiteSelectionLoggedTest` (3, Robolectric: stored
`applicationSite`, newest-first history, null site stays null). Suite: 574 tests, 0
failures. Mutation check: dropping the mirrored-x guard makes the tap tests fail.

## F2 — OCR of photographed lab reports (done)

- `data/lab/OcrTextRecognizer.kt` — `OcrDocument(blocks: List<List<String>>)` plus the
  `OcrRecognizer` interface and `MlKitOcrRecognizer` (`TextRecognition` with
  `TextRecognizerOptions.DEFAULT_OPTIONS`, one client per call, closed in `finally`).
  Blocks are kept instead of flattened: the join rule is what needs a test.
- `OcrDocument.text()` joins **lines**, never blocks, with `\n`. Czech reports print the
  analyte and value on one line ("Estradiol 610 pmol/L"); a space join would splice the
  value of one column onto the next column's name at any table break.
- `data/lab/OcrLabExtractor.kt` — `recognizer.recognize(uri)` → `pipeline.importOcrText`.
  Deliberately never throws: an unreadable or unopenable photo yields an empty staging
  result, the single most likely input must not crash the screen.
- `di/OcrModule.kt` — `@Binds MlKitOcrRecognizer → OcrRecognizer` (the seam exists so the
  tests can pass a fake; ML Kit cannot run in a JVM test).
- `ui/bloodwork/LabScanScreen.kt` — full-screen capture: CameraX `Preview` +
  `ImageCapture` (`CAPTURE_MODE_MINIMIZE_LATENCY`) writing `lab-report-<ts>.jpg` into
  `cacheDir` (already served by the existing FileProvider, so no new permission), shutter
  disabled while a shot is in flight, plus a `PickVisualMedia` gallery fallback for denied
  camera permission or existing photos. It only produces a `Uri`.
- `BloodworkViewModel.importPhoto(uri)` + `isReadingPhoto` in the state; the screen shows
  "Reading the photo on this device…" while it runs and guards against a double import.
- `BloodworkScreen` — "Scan report" (primary) + "PDF" (outlined) in one row; the capture
  step is screen-local state rather than a nav route, because the staging result lives in
  this view model and a second route would need its own hand-off. An empty staging result
  now says "No values recognised…" and offers **Retake** instead of an unusable Save.

Tests: `OcrLineJoinTest` (4 — same-line pairs stay whole, blocks are newline-joined, blank
lines dropped, empty document), `OcrLabExtractorTest` (4, Robolectric with the real
pipeline and in-memory Room: "Estradiol 610 pmol/L" → one valid `E2` row in `pmol/L`,
unreadable photo → empty rows, recogniser exception → empty rows and empty `rawText`,
raw text kept for review). Suite: 582 tests, 0 failures.

Mutation checks: joining with a space fails `OcrLineJoinTest`; dropping the `runCatching`
around the recogniser fails the exception test.

## F3 — Symptom patterns near a dose (done)

- `domain/calc/SymptomCorrelation.kt` — `SymptomPoint`, `DoseEvent`,
  `SymptomCorrelation` (`ratio`, `isWorseNearDose` at ≥ 1.15×, `isMilderNearDose` at
  ≤ 0.87×, `isReportable`), `takenDoseEvents(logs)` and
  `SymptomCorrelationCalculator` (`nearestDose` inclusive ±2 h, `analyze`).
  Severity is the only per-symptom signal the app has, so this is a mean-of-means
  comparison, never a causal claim; the card says so in its own footer.
- `takenDoseEvents` drops SKIPPED/SNOOZED — a dose that never entered the body cannot
  explain a symptom — and times a taken dose by `actualTimestamp ?: scheduledTimestamp`.
- `SymptomsViewModel.patterns` combines 90 days of symptoms with doses
  (`SharingStarted.WhileSubscribed`) and keeps only `isReportable` entries, so a single
  entry can never grow a “Patterns” card.
- `SymptomsScreen` — Patterns card above “Last 30 days”: one row per symptom with a
  sentence (“Headache · 2.8× worse within 2 h of a dose”, or “no clear difference near a
  dose”), a 5-dot severity strip for both groups and the counts (`5×` / `11×`). Counts are
  printed on purpose: a pattern over four entries should not read like a law.

Tests: `SymptomCorrelationTest` (8 — higher severity in window, empty data, single record
not reported, two-per-side still too few, no doses at all, ordering by strength, milder
symptoms, taken-only filtering and the scheduled-time fallback), `SymptomDoseWindowTest`
(5 — inclusive boundary, backwards look, nearest of two, custom window, zone independence).
Suite: 596 tests, 0 failures. Mutation checks: `< limit` instead of `<=` fails the boundary
test; `MIN_SAMPLES = 2` fails “two entries on each side are still too few”.

Also fixed a latent flake in `BloodworkAnalysisTest.tearDown`: it cancelled
`viewModelScope` and immediately called `Dispatchers.resetMain()`, which could throw
`Dispatchers.Main is used concurrently with setting it` (or hang the worker) when a
collection was still resuming. It now `cancelAndJoin`s each scope first.

## F4 — Consumables in the travel plan (done)

- `InventoryCalculator.TravelPlan` gained `consumables: List<TravelConsumable>` and
  `injectionCount`; `hasShortfall` now covers supplies too.
- Injection count is **occurrence-based**, not a daily average: `injectionCount` asks
  `ScheduleEngine.occurrences` for every schedule of an `isInjectable` medication inside
  `[tripStart, tripStart + tripDays + bufferDays)`. A 5-day trip with a twice-daily
  injection therefore yields 10 syringes, 10 draw needles, 10 injection needles and
  20 swabs. PRN schedules are excluded — they are not “necessary doses”, and guessing
  them would inflate the packing list.
- Multipliers come from `consumablesPerInjection()`; the same map drives deduction after a
  logged dose, so a plan cannot disagree with what the app will actually consume.
- Stock is aggregated **per kind**, because supply rows are free text: `consumableKind(name)`
  classifies “29G insulin syringe” and “1 ml syringe” as one `SYRINGE` supply and sums
  them; unknown names (gauze, gloves) are ignored rather than guessed. `DoseRepository`
  now uses the same classifier instead of its own `when`, so “draw needle” can never be
  counted as an injection needle.
- Missing stock counts as **zero**, never as “probably fine”: the travel card says so and
  shows the shortfall in error colour.
- Patches are deliberately *not* in this list. For a patch user the patch **is** the dose
  and is already counted in the medication quantities; adding it again would double-count
  it. `TravelConsumablesTest` has a case pinning that.
- `InventoryScreen.TravelPlanner` renders a second section, “Consumables for N injections”,
  with `Need X · have Y` per row and a red `−Z` shortfall.

Tests: `TravelConsumablesTest` (10 — ten injections → 10/10/10/20, buffer days add
injections, tablets produce no supplies at all, a patch is not a syringe, PRN excluded,
injections after the trip ignored, shortfall from low stock, unrecorded stock = zero,
name classification incl. draw-before-needle, labels) and
`TravelPlanRepositoryTest` (2, Robolectric — same-kind rows summed and unmatched rows
ignored, missing stock reports the full requirement).
Suite: 608 tests, 0 failures.

Mutation checks: swapping the classifier's draw/needle branches fails
“consumable names are classified…”; dropping the `isInjectable` guard fails
“a trip without injections lists no consumables” and “a patch is the medication”.

## F5 — Manual batch entry (done)

- `domain/inventory/BatchDraft.kt` — `BatchDraft`, `BatchValidation(errors, warnings)` and
  `BatchDraftValidator`. Quantity, unit, medication, negative thresholds and a
  non-positive beyond-use limit are **errors** (a batch of "0" is a typo, not stock). An
  expiry date in the past or today is only a **warning**: recording old stock is
  legitimate, and blocking it would push people to enter a fake date. A unit that does not
  match the form (`tbl` for an injection) also only warns — the app should not decide that
  a user's box is labelled wrong.
- `BatchDraft.toBatch()` sets `initialQuantity = remainingQuantity = quantity` and leaves
  `firstPuncturedDate` null, so the new batch is dispensed by the normal order (earliest
  expiry) and only becomes "open" when a dose punctures it.
- `InventoryRepository.addBatch(draft, form, today)` validates first and **never writes an
  invalid draft**; `observeMedications()` feeds the picker.
- `InventoryViewModel.validateBatch` is pure (dialog shows errors before anything is
  written); `addBatch` writes.
- `ui/inventory/BatchEditorDialog.kt` — medication chips, quantity + unit (prefilled from
  the form: `ml` / `patch` / `dose` / `tbl`), optional expiry as `yyyy-MM-dd`, batch number,
  low-stock days, beyond-use days, and dead space only for injectables. Errors appear on
  save, warnings appear with them; the dialog closes only when the draft is valid.
- `InventoryScreen` — "Add batch" in the header row (only when medications exist), dialog
  hosted by screen-local state. No FAB: the screen lives inside the app scaffold, and a
  FAB there would collide with the bottom bar.

Tests: `BatchDraftValidatorTest` (11 — valid draft, zero/negative quantity, past and
same-day expiry as warnings, blank unit, unit/form mismatch warning, negative threshold,
zero beyond-use, negative dead space vs dead space on tablets, draft→batch mapping) and
`BatchInsertTest` (4, Robolectric — batch appears in `stockLines` and `dispensibleBatches`,
invalid draft never written, a stored batch is the one deducted by `InventoryDao.deduct`,
expired batch stored but reported unusable).
Suite: 623 tests, 0 failures.

Mutation checks: `< 0` instead of `<= 0` in the quantity rule fails "zero quantity is an
error"; dropping the `if (validation.isValid)` guard fails "an invalid draft is never
written".

## F6 — UV index and photosensitivity (done)

- `domain/calc/Photosensitivity.kt` — `UvWarning` + `Photosensitivity.uvWarning(uv, meds)`.
  Two conditions must hold: UV above 6.0 (WHO "high") **and** at least one active
  medication whose ATC code starts with a photosensitising prefix (J01A tetracyclines,
  J01E sulfonamides, J01M fluoroquinolones, D10BA isotretinoin, D10AD topical retinoids,
  N05AA/N05AC phenothiazines, C01BD amiodarone, C03A thiazides, M01A NSAIDs). Matching on
  ATC rather than on the name is what makes it work for SÚKL rows that carry only a code.
  A null UV index returns null — silence, not a guess.
- `data/uv/OpenMeteoUvSource.kt` — `UvSource` (geocode + current UV), Open-Meteo
  implementation over `HttpURLConnection` with 5 s timeouts (no new dependency; Retrofit is
  not in the project), and `OpenMeteoParser` split out so payloads are tested without a
  network. Any non-2xx, timeout or malformed JSON becomes null, never an exception.
- `UvRepository` — cache-or-network with `MAX_AGE = 3 h`. A fresh reading is served without
  a request; an expired one is refetched; a failed refetch returns **null, never the stale
  value** (showing yesterday's UV as today's would be a silent lie). No city → no request.
- Cache lives in DataStore through the new `uv_value` / `uv_fetched_at` / `uv_place` keys;
  `SettingsRepository` implements `UvCache` directly, and changing the city clears the
  reading because the number belonged to the old place.
- `DashboardViewModel` fetches in its own coroutine, so a dead network can never delay the
  dose list; the card simply doesn't appear. Discreet mode replaces medication names with
  "one of your medications".
- Settings card "Sun & UV": toggle plus a city field with Save/Clear. No location
  permission is requested — the city is typed, and it never leaves the device except as
  coordinates sent to Open-Meteo.
- No `UvRefreshWorker`: the plan allowed dashboard-triggered refresh, and the 3 h cache
  already bounds the traffic to at most one geocode + one forecast per refresh window.

Tests: `PhotosensitivityTest` (10), `UvCacheTest` (6), `OpenMeteoParsingTest` (5).
Suite: 644 tests, 0 failures.

Mutation checks: `<` instead of `<=` in the UV threshold fails "UV right at the threshold
raises nothing"; serving the stale cache on a failed refetch fails "an expired cache with a
dead network returns null".

## F7 — eRecept queue (done)

- `PrescriptionDao` (`data/local/dao/Daos.kt`) + `PrescriptionWithItems` (`@Embedded` +
  `@Relation`): the header and its items come back in one transaction.
  **Uniqueness is a lookup, not a unique index** — `insertPrescription` returns null when
  the code already exists. Adding a unique index to `prescriptions` would have forced a
  schema v3 migration for a check the transaction can do itself.
- `PrescriptionRepository` — `save()` returns `SAVED` / `ALREADY_SAVED` so the scanner can
  say "already saved" instead of pretending it wrote something; `active()` drops expired
  and dispensed rows; `observeActive()` is the flow the UI uses.
- `domain/prescription/PrescriptionQueue.kt` — `QueuedPrescription.daysLeft` counts
  **calendar days in the user's zone**, so "valid until 23:59 today" is 0 days, not 0.9.
  `EReceptParser.daysRemaining` clamps negatives to 0 and would hide "expired", which is why
  the queue has its own counter. `WARNING_DAYS = 2`, `badgeCount`, `describe`, and
  `reminderMessage` (names only the nearest one).
- `ScannerViewModel.handleQr` now persists the parsed eRecept and reports whether it was
  new; a QR that is not an eRecept still stores nothing.
- `ui/prescriptions/PrescriptionsScreen.kt` + `PrescriptionsViewModel` — list with
  "Expires today / tomorrow / in N days", an "Expiring" chip and error-coloured card,
  Mark dispensed / Create medication / Delete. "Create medication" navigates to the scanner
  with the item name prefilled (`scanner?prefill=…`).
- Dashboard card appears only for prescriptions inside the 2-day window.
- `ReminderCoordinator.refresh()` posts one notification for the nearest expiring eRecept
  (channel INVENTORY, id 9001) instead of adding another alarm — the plan allowed a daily
  check on app start.
- No schema change: tables already existed in v2; only a DAO was added.

Tests: `PrescriptionExpiryTest` (13), `PrescriptionDaoTest` (8), `ScannerPrescriptionTest` (6).
Suite: 671 tests, 0 failures.

Mutation checks: removing the duplicate-code guard fails both "the same code twice is
reported, not duplicated" and "the raw dao refuses a duplicate code"; widening the warning
window to three days fails "two days left warns, three days does not".

Known gaps: no PDF import of eRecept (only QR/text payloads that the parser understands);
the notification fires on app start, so a prescription that becomes expiring while the app
stays closed for two days is only reported on the next launch.

## F8 — Localisation (done)

- 103 literals from `Text("…")` and 4 `contentDescription` literals across 13 screens moved
  to `stringResource`. Keys follow the existing `<area>_<thing>` convention
  (`dashboard_log_dose`, `batch_expiry_label`, `scan_detail_gtin`, …); repetitions reuse
  `common_cancel` / `common_save` / `common_delete` instead of creating synonyms.
- Formatted texts use arguments, not concatenation: `labs_save_results` (`Save %1$d results`),
  `target_minimum`/`target_maximum` (`Minimum (%1$s)`), `scan_detail_*` (`GTIN: %1$s`),
  `settings_snooze_minutes` (`%1$d min`), `schedule_remove_time` (`Remove %1$s`).
- Five keys collided with existing ones (`inventory_consumables`, `inventory_travel_start`,
  `inventory_travel_end`, `settings_palette`, `settings_sukl_sync`) and were dropped from the
  new block; the existing texts stay ("Trip start" / "Trip end" instead of "Start" / "End").
- `values-cs` now has the same 201 keys. Czech copy avoids loanwords where a Czech word is
  normal (Upravit, Uložit, Odebrat, TF for heart rate) and keeps drug names untouched.
- Deliberately **not** localised: `@Preview` sample data (not UI copy), medication names,
  user data.
- Second pass: the first pass only matched `Text("…")`, but this codebase writes most copy as
  `text = "…"`. Another 52 whole labels moved (46 `text =`, 6 inside `if/else` pairs such as
  `"Edit schedule"`/`"New schedule"`), and `Schedule.summaryLine()` became `@Composable` so its
  fragments (`"every %1$s d"`, `"as needed"`, `" (paused)"`) can use `stringResource`.
- Where a new key would have duplicated an existing text, the existing key is reused:
  `channel_bloodwork`, `bloodwork_empty`, `bloodwork_true_trough`, `bloodwork_not_trough`,
  `range_custom`, `batch_unit`, `dashboard_greeting`, `dashboard_nothing_scheduled`,
  `dashboard_adherence`, `nav_bloodwork`, `medications_title`, `nav_settings`, `meds_scan`.
  `values-cs` now has 250 keys.

Tests: `StringsParityTest` (5) — key parity, placeholders identical in both languages,
extracted keys really exist, every `<plurals>` translated with the same placeholders and an
`other` form; `HardcodedStringTest` (6) — scans `ui/**/*.kt` outside `@Preview` for
`Text("…")`, `text = "…"`, `else "…"` **and** `contentDescription = "…"`, flags templates that
spell words out, plus self-checks that the scanner flags a literal and a word-bearing template
and ignores previews/`stringResource`/pure formatting.

Mutation checks: turning one `stringResource` back into `Text("Unlock")` fails
"no hardcoded labels outside previews"; deleting one Czech key fails
"every English key has a Czech translation"; turning `text = stringResource(…)` back into
`text = "Travel plan"` and into `else "Beyond-use date passed — discard"` both fail the
scanner; `"Severity ${draft.severity}/5"` fails it too (template rule); changing one Czech
`<item quantity="few">%1$d` to `%1$s` fails "every plural is translated with the same
placeholders".

### Templates (second half of F8)

- The 28 interpolated templates now take arguments: `labs_result_count`, `labs_row_line` +
  `labs_unit_unknown`, `target_storage_note`, `dashboard_rx_line`, `inventory_expires`,
  `inventory_usable`, `inventory_pumps_line`, `inventory_need_have`,
  `inventory_need_have_units`, `schedule_shift_reminder`, `settings_saved`,
  `symptoms_severity`, `symptoms_log_line`, `symptoms_pattern_line`, `symptoms_caption_near`,
  `symptoms_caption_other`, `symptoms_worse`, `symptoms_milder`, `symptoms_no_difference`.
- Counts use `<plurals>` (6 keys, one/few/`other` in Czech): `labs_result_count`,
  `dashboard_doses_taken`, `inventory_days_left`, `inventory_for_days`,
  `inventory_consumables_for`, `rx_state_in_days`, read with `pluralStringResource`.
- `PrescriptionQueue.describe` stays English for the notification and its tests; the UI branch
  is repeated in `ui/prescriptions/PrescriptionText.kt` as `@Composable describePrescription`,
  which owns `rx_state_dispensed|no_expiry|expired|today|tomorrow` + `rx_state_in_days`.
- `SymptomsScreen.correlationSentence` became `@Composable` so the three sentences come from
  resources; the ratio is still formatted with `Locale.ROOT` and passed as `%1$s`.
- Left in Kotlin on purpose: pure formatting with no words — `"• $warning"`,
  `"${formatValue(value)} ${selected.unit}"`, `"$percent%"`, `"${consumable.remainingCount}"`,
  `"−${item.shortfall}"`, `"${formatMean(mean)}/5 $caption ($count×)"` (the caption itself is
  a resource). The scanner allows them because the literal part holds fewer than two letters.
- `values`/`values-cs` now hold 317 strings and 11 plurals each, including the 40 `msg_*`
  strings and 5 `msg_*` plurals used by the message layer.

### Messages from ViewModels and the domain (third part of F8)

- The domain cannot reach resources, so sentences travel as data:
  `cz.medsy.domain.message.Message(key: MessageKey, args: List<MessageArg>, count: Int?)`,
  with `MessageArg.Text` and `MessageArg.Nested` for "eRecept … — expires tomorrow" style
  composition. Builders `message(key, vararg args)` and `messagePlural(key, count, vararg args)`
  keep producers short; `MessageCodec` (kotlinx.serialization) turns a message into JSON.
- `ui/message/MessageText.kt` owns the mapping: `MessageKey.res()` is an exhaustive `when`
  with **no `else`**, so a new key is a compile error, and `Message.asText()` (composable) /
  `Message.asText(context)` (notifications) render it with nested arguments resolved first.
- Converted producers: `ScannerViewModel.statusMessage`, `PrnLimit.describe`,
  `PatchLifecycle.describe`, `MilestoneDiff.Change.message` + `describe`,
  `PrescriptionQueue.describe` + `reminderMessage`, `DashboardViewModel`'s
  `PrnLimitWarning.message` and `ActivePatch.description`, the four `Workers` notifications,
  `DoseNotifier.notifyLimitBlocked` and `ReminderCoordinator`. `PrescriptionText.kt` shrank to
  a one-line delegation, so the UI and the notification can no longer disagree.
- Milestones are persisted, so the sentence must survive a language switch: `version = 3`
  with `MIGRATION_2_3` (`ALTER TABLE medication_milestones ADD COLUMN message TEXT`). New rows
  store the encoded message and leave the legacy `description` empty; `MilestonePin` carries
  `label: Message?` + `legacyLabel: String` and resolves the text before the `Canvas` (a text
  measurer cannot call `stringResource`).
- `PatchLifecycle.formatRemaining` now takes `now` explicitly instead of calling `Instant.now()`
  — a reminder rendered on a stale instant could otherwise say "remove in 2 h" forever.
- Tests assert keys, not English: `PrnLimitTest`, `PatchLifecycleTest`, `PrescriptionExpiryTest`,
  `MilestoneDiffTest`, `MilestoneRecordingTest`, `ScannerPrescriptionTest`, `ScannerSearchTest`.
  This makes them independent of the phone language.
- Guard: `MessageLocalisationTest` (4) — every key resolves to a resource, plural keys map to
  `<plurals>` and the rest to `<string>`, the resolver has no `else`, and no
  `statusMessage =`/`reminderMessage =` is assigned a literal. Mutations: adding `else ->` fails
  the resolver check; dropping the `MessageCodec::encode` in `Mappers` fails
  `MilestoneRecordingTest`; assigning an English literal to `statusMessage` does not compile at
  all, which is the strongest guard of the three.

### Sentences behind a fallback, a worker and a settings card (fourth part of F8)

- `HealthConnectSyncer` no longer invents `"write failed"`: `SyncResult.reason: String?` became
  `message: Message?`, with `HEALTH_SYNC_DISABLED`, `HEALTH_SYNC_NOTHING` and `HEALTH_SYNC_FAILED`.
  The failing provider still cannot lose the entry — the test asserts the key, not the sentence.
- Two screens report failures the validator cannot describe (a failed write). They gained
  `messageErrors: List<Message>` next to the validator's `errors: List<String>`, and the card
  renders `(state.errors + state.messageErrors.map { it.asText() })`. One card, two sources: a
  second error card would have made a save failure look like a different problem.
  Keys: `SAVE_TARGET_FAILED`, `SAVE_SCHEDULE_FAILED`, `TARGET_MIN_UNREADABLE`,
  `TARGET_MAX_UNREADABLE`, `SCHEDULE_MEDICATION_MISSING`.
- `UvWarning.message` became a `Message`: the generic sentence when no medication name is known
  (`UV_EXPOSURE`), `UV_EXPOSURE_ONE`/`_TWO` naming one or two drugs, and the `UV_EXPOSURE_MANY`
  plural above that. The UV number is formatted with `Locale.ROOT` so a Czech phone still shows
  `7.5`. Discreet mode keeps the generic sentence — a trade name on the unlock screen leaks the
  diagnosis.
- The guard found more than it was asked for. `MessageLocalisationTest` now also scans for
  `error.message ?: "<English>"` and for sentences that begin a continuation line (invisible to
  the `Text("…"` scan, because the literal starts the line). Fixing what it flagged localised the
  sublingual hold card, the stock-alert lines, the UV card, the body-map chips and their
  `contentDescription`, the lab window/scan hints (`labs_review_results` plural), the Health
  Connect card in Settings (title, description, switch and both buttons) and the schedule
  interval labels — 26 strings plus 1 plural in both languages.
- A semantics lambda is not composable, so the body-map chip now resolves its label and its
  spoken description *before* `Modifier.semantics { contentDescription = … }`.
- `MessageLocalisationTest` is 6 checks. Mutations: re-adding `error.message ?: "Could not save
  the schedule"` fails the fallback scan; adding a `+ "for this dose"` continuation fails the
  line scan; mapping `UV_EXPOSURE_MANY` to `R.string` instead of `R.plurals` fails the resource
  kind check. Suite 691 tests / 0 failures; APK 109 MB.

Known gaps at this point: sentences passed as composable *parameters* (`title = "How often"`,
`subtitle = "Inactive schedules produce no alarms"`, the whole lock screen in `MedsyApp.kt`) were
still English — the scanners look for literals that start a line or follow `text =`, and these
sit in named arguments. Domain calculators also built their own prose
(`InteractionEngine` 20 strings, `BloodworkCalculator` 8). 49 such literals in total; the next
subsection closes them.

### Sentences in named parameters, and the prose of two calculators (fifth part of F8)

- The scanners only saw literals that start a line or follow `text =`. A sentence passed as
  `title = "…"` / `subtitle = "…"` / `label = "…"` was invisible, and 49 of them were left. They
  are now resources: eight `SettingsCard` titles and their subtitles (`settings_privacy`,
  `settings_sun`, `settings_appearance`, `settings_data`, `settings_uv_warning…`), the schedule
  editor sections (`schedule_section_how_often`, `_times`, `_taper`, `_food`, `_application`,
  `_reminder`), the site-rotation and active switches, and the lock screen in `MedsyApp.kt`
  (`lock_unlock_prompt`, reusing `lock_confirm` for the subtitle).
- The lock prompt cannot use `stringResource` where it is passed: `LockedScreen(onUnlock = { … })`
  runs from a click handler, so `unlockTitle`/`unlockSubtitle` are resolved in the composable
  *before* the call. A `stringResource` inside that lambda would compile only inside a composable
  and fail at the call site.
- `InteractionEngine` rules now carry `MessageKey` pairs instead of sentences: 22 rules ×
  title/detail = 62 strings in both languages (`interaction_<slug>_title|_detail`), plus
  `severity_severe/moderate/minor` and `severity_prefix` (`"%1$s · "`), so a Czech dashboard
  reads "Závažná · Warfarin + aspirin". Alerts are sorted by `title.key.name` — the key survives
  a language switch, a sentence does not.
- The absorption conflict keeps its `pair` as a name (`"iron/calcium-magnesium"`) because it
  seeds unify keys, but `detail` is a `MessageKey` and the planner returns `Message`:
  `Unresolved.reason`/`detail`, `Plan.explanation` (`ABSORPTION_EXPLANATION` = minutes + nested
  detail). `ReminderCoordinator.OffsetNotice.message` and
  `MedicationsViewModel.offsetNotices: StateFlow<List<Message>>` follow it to the screen.
- `BloodworkCalculator`: `TroughAssessment.label` and `PreTestWarning.title/detail` are
  `Message`s; `BloodworkViewModel` wraps each warning in `BLOODWORK_WARNING_SENTENCE`
  with the title and detail as nested args, so the bullet "• " and the sentence come from one
  resource. The draw window is still described as a duration (`formatHours`) because "4 h 20 min"
  reads the same in both languages.
- Two resource traps: `bloodwork_true_trough` already existed as a label, so the templated
  version needed its own name (`bloodwork_trough_after_dose`); and `St John's Wort` breaks aapt
  ("Invalid unicode escape sequence") because the apostrophe must be escaped — the text now uses
  a typographic `’`, which needs no escaping and types better.

`MessageLocalisationTest` is 7 checks: the new one flags
`(title|subtitle|label|headline|placeholder|description|summary)\s*=\s*"[^"]*\s[^"]*"` in `ui/`
and `work/`. The continuation-line check was also corrected — it counted letters in the whole
line, so `" — " + warnings.first().detail.asText()` looked like a sentence; it now judges only
the leading literal. Mutations: `SettingsCard(title = "English sentence injected")` fails the
named-parameter check, and swapping a rule's title key fails
`InteractionEngineTest.statin gets grapefruit advice`. Suite 692 tests / 0 failures; APK 109 MB.

### Short labels from the domain (sixth part of F8)

- The four gaps above are closed: 32 strings and one plural in both languages
  (`profile_*`, `consumable_*`, `batch_*`, `form_*`, `vial_*`, `inventory_prn_manual`,
  `inventory_get_more`, `<plurals name="inventory_enough_days">`).
- The domain types now carry `Message`: `RangeBands.Band.label`,
  `InventoryCalculator.TravelItem.suggestion`, `TravelConsumable.label`,
  `ConsumableKind.label()`, `BatchValidation.errors`/`warnings` and
  `RecommendedSite.reason`. `BatchValidation.message` (a joined `String`) was deleted — the
  dialog renders one `Text` per entry, which also drops the string split.
- The form names became keys instead of a `when` returning sentences
  (`BATCH_FORM_TABLET` … `BATCH_FORM_DROPS`), so `MedicationForm` no longer owns any prose.
- A label cannot be resolved inside a non-composable lambda: `selected.bands.mapIndexed { … }`
  builds the chart arguments, so `BloodworkScreen` computes
  `val bandLabels = selected.bands.map { it.label.asText() }` first and indexes into it.
- `MessageLocalisationTest` gained an eighth check, `no domain object carries an English
  phrase`, scanning `domain/` and `data/` for
  `(label|reason|detail|suggestion|message|errors|warnings|title|subtitle|description)\s*(\+=|=)\s*"[^"]*\s[^"]*"`
  and for a `when` branch returning a sentence (`-> "A tablet"`). The second regex caught a
  deliberately re-added branch; putting a literal into `List<Message>` is a compile error
  instead. Domain classes cannot reach resources, so a phrase stored in a field is English
  forever — this is the check that keeps the layer from eroding.
- Tests that asserted English text now assert keys: `RangeBandsTest` (and it checks the four
  profiles map to *distinct* keys), `TravelConsumablesTest`, `BatchDraftValidatorTest`,
  `VialCalculatorTest.reason.key`, `InventoryCalculationTest` (PRN suggestion),
  `TravelPlanRepositoryTest` (matches consumables by `kind`, not by label). Suite 693 tests /
  0 failures.

## F9 — Light/dark previews (done)

- Every screen now carries a named light and dark pair:
  `@Preview(name = "Dashboard light")` + `@Preview(name = "Dashboard dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)`
  on one `@Composable` function, so sample data is written once.
- Screens added: `ScannerScreen` (fake `ScannerUiState` with a GS1 package and
  `hasCameraPermission = false`, so no camera is touched), `LabScanScreen`, `LockedScreen`
  (+ a third variant with `unlockUnavailable = true`).
- `LabScanScreen` was split: `LabScanContent(onCancel, onPickGallery, camera = { … })` holds
  the layout and takes the camera as a slot, because `rememberLauncherForActivityResult`
  cannot run inside a preview. `CameraPermissionPanel` is the slot's fallback, so the preview
  shows the real "camera access is needed" state.
- `androidx.camera.core.Preview` clashes with the Compose `Preview` annotation; both camera
  screens import it as `CameraPreview`.
- While touching those two screens, their remaining English literals moved to `stringResource`
  (`labs_scan_title`, `labs_scan_permission`, `labs_scan_hint`, `lock_title`,
  `lock_title_discreet`, `lock_no_biometric`, `lock_confirm`). `values-cs` now has 208 keys.

Test: `ScreenPreviewTest` (3) — every listed screen has ≥2 `@Preview`, at least one dark, and
all previews are named. Mutation check: deleting the dark annotation from `SettingsScreen.kt`
fails two of the three.

Known gap: previews are verified at source level only — rendering them needs Android Studio,
so a preview that compiles but crashes at render (e.g. a `CompositionLocal` without a default)
would not be caught here.

## Remaining work

`IMPLEMENTATION_PLAN.md` now lists F1–F9 as done, ordered by dependency rather than by spec order. Key points:

- **Phase 0.1 is a blocker**: the database is still `version = 1` with only a
  destructive downgrade fallback. Any new column (patch state, gel actuation,
  sublingual hold, prescription queue) would wipe user data on upgrade. Migrations
  plus `MigrationTestHelper` tests must land first.
- ~~Phase 1.1 milestones~~ — **done**. Remaining in Phase 1: custom doctor
  target, range overlaying, discreet HRT badge — highest clinical value, no schema
  change except milestones.
- **Phase 4.1 (OCR) is cheap**: `LabImportPipeline.importOcrText` and the ML Kit
  text-recognition dependency with its native libs are already present; only the
  camera → `TextRecognition` step and a staging screen are missing.

## Bugfix pass 2 — the app crashed on launch

Reported as "aplikace padá při spuštění". Crash buffer:

```
java.lang.RuntimeException: Unable to start activity ComponentInfo{cz.medsy.debug/cz.medsy.MainActivity}
  Caused by: java.lang.IllegalStateException: You need to use a Theme.AppCompat theme (or descendant) with this activity.
      at androidx.appcompat.app.AppCompatDelegateImpl.createSubDecor
      at androidx.core.splashscreen.SplashScreen$Impl31.setKeepOnScreenCondition
      at cz.medsy.MainActivity.onCreate(MainActivity.kt:18)
```

`MainActivity` extends `AppCompatActivity` while `values/themes.xml` inherited from
`android:Theme.Material.Light.NoActionBar` — a platform theme that AppCompat refuses to run under.
Nothing in the build could see it: both classes exist, both compile, and no unit test starts an
activity. It only fails on a device.

Fixed by switching the theme parents (not the activity): `Theme.AppCompat.Light.NoActionBar` for
`values/`, `Theme.AppCompat.NoActionBar` for `values-night/`. The light/dark split stays explicit
rather than `DayNight`, matching what the resource files already expressed.

### What the first correct screenshot showed

Three more defects, all invisible to unit tests because they only exist at render time:

| Symptom | Cause | Fix |
| --- | --- | --- |
| Banner sat under the clock | the banner is drawn above the `Scaffold`, which only insets its own content | `Modifier.statusBarsPadding()` on `PermissionBanner` |
| "Good evening", "No results yet" in English | a `when` branch and an elvis fallback in Kotlin | `DASHBOARD_GREETING_*`, `BLOODWORK_NO_RESULTS` keys |
| Bottom bar "Home / Meds / Labs" in English | `TopLevelDestination.label` was a `String` | `@StringRes val label: Int` + `R.string.nav_*` (new `nav_symptoms`) |

The same review found English `when` labels for `ProfileType`, `ScheduleType` and `MealRelation`
(`friendlyName()`, `label()`), now returning `Message` with `PROFILE_*`, `SCHEDULE_TYPE_*` and
`MEAL_RELATION_*` keys.

### Why the scanners missed them

`HardcodedStringTest` looks for literals in argument position (`Text("…")`, `text = "…"`).
`else -> "Good evening"` and `value ?: "No results yet"` are neither. Two new guards close that:

* `no screen branches or falls back to an English sentence` — scans `src/main/java/cz/medsy/ui`
  with `PROSE_BRANCH` (`X -> "Two words"`) and `ELVIS_SENTENCE` (`?: "Two words"`).
* `navigation labels come from resources` — the enum must carry `val label: Int`, and every
  `R.string.nav_*` needs a Czech entry.

Both were written after the bug, and each was verified by reintroducing the literal.

Suite **698 tests / 0 failures**, lint 0 errors, `assembleDebug` green, verified on the device:
launch, Czech greeting, Czech bottom bar, banner below the status bar.

## Contrast pass — OLED dark surfaces

The OLED palette kept a pure-black background but its container ramp was almost black as well
(`surfaceContainerLow = 0x0A0A0C`, `surfaceContainer = 0x101013`, `surfaceContainerHigh = 0x16161A`).
Cards, the stock list and the bottom bar therefore merged into one field and the bar looked
transparent. In `ui/theme/Color.kt` (`oledDark()`) the ramp is now `0x15151B / 0x1B1B22 / 0x222229 /
0x2A2A32`, with `surfaceVariant = 0x23232A` and `outlineVariant = 0x3C3C44` so dividers and card
edges are visible.

The bottom bar in `ui/MedsyApp.kt` additionally asks for `surfaceContainerHigh` explicitly and
carries a 1 dp `outlineVariant` border in the pill shape, so it reads as a bar over the list instead
of floating text.

In `ui/inventory/InventoryScreen.kt` a low-stock row paints its card with `errorContainer` while the
"usable", "expired batches" and "beyond-use" lines were coloured `error` — red text on dark red in
dark mode. They now use `onErrorContainer` when the row is low, and the muted lines use the same
container-aware colour instead of `onSurfaceVariant`.

## Scrolling in Settings — work on the frame cost

Reported as "scrollování v nastavení se kouše". The phone dropped off wireless adb while this was
being fixed, so nothing here is measured on device — these are the two real costs found by reading
the code, plus two cheap reductions.

1. `ui/theme/Theme.kt` built the `ColorScheme` **during composition**: `dynamicDarkColorScheme(context)`,
   `dynamicLightColorScheme(context)` and `medsy*Scheme(palette)` each return a new instance. Every
   recomposition of `MedsyTheme` therefore handed MaterialTheme a different `ColorScheme` identity and
   invalidated every `MaterialTheme` read below it. It is now wrapped in
   `remember(config.dynamicColor, config.mode, config.palette, dark)`.
2. `ui/settings/SettingsScreen.kt` called `viewModel.healthConnectStatus()` inside
   `remember { … }`. That function reaches `HealthConnectAvailability.status(context)`, which does a
   cross-process provider lookup — a main-thread binder call during the first composition of the
   screen. `SettingsViewModel` now resolves it on `Dispatchers.IO` into a `StateFlow` that the route
   collects.
3. `SettingsCard` upper-cased its title on every recomposition (`title.uppercase()` — locale-aware,
   allocates); it is `remember(title)` now.
4. The seven `item { }` blocks carry keys, so a `settings` emission no longer forces a positional
   re-creation of the whole list content.

Not changed: the settings body is a plain `LazyColumn` of seven cards — no nested scrolling, no
per-frame animation — so if the stutter survives these fixes it needs a trace rather than more
guesswork (`adb shell am trace-application -a cz.medsy.debug` / Perfetto, with the release build,
since debug Compose is several times slower).

## Health Connect toggle did nothing

Symptom: the "Write vitals to Health Connect" switch in Settings stayed off no matter how often it
was tapped. Three separate defects sat on that one path.

1. **No rationale activity.** Health Connect refuses the permission sheet unless the calling app
   declares an exported activity answering `androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE`; on
   Android 14 the request then resolves to an empty set instead of throwing, which is exactly why
   the switch looked dead and nothing appeared in logcat. Added
   `app/src/main/java/cz/medsy/health/HealthRationaleActivity.kt` (Compose, explains what is
   written, `common_close`) and its manifest entry.
2. **The result was trusted as-is.** The callback did
   `if (granted.containsAll(HealthPermissions.writePermissions)) setHealthConnect(true)`.
   `HealthPermissions.allowsWrites(granted)` now expresses that rule, and
   `SettingsViewModel.onHealthPermissionsResult` re-reads
   `HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()`, falling
   back to the contract result only when the query itself fails. An empty set therefore means "not
   granted" rather than "granted nothing, hope for the best".
3. **A revoked grant left the switch on.** `SettingsViewModel.init` now calls
   `syncHealthConnectWithGrants()`: when Health Connect says the write permissions are gone, the
   stored flag is turned off instead of silently failing every later write.

Also: the availability status is `Status?` now and the card shows "Checking whether Health Connect is
available…" until the IO lookup answers — previously the initial `UNAVAILABLE` value made the card
claim Health Connect was not installed for one frame.

`HealthPermissionsTest` locks down both halves: `allowsWrites` across the empty/half/read-only cases,
and the manifest entry (action, class name, `exported="true"`).

## SÚKL sync had no feedback

The card in Settings offered "Sync SÚKL database" and then said nothing: the button queued
`SuklSyncWorker` and forgot about it, so a failed download looked identical to a successful one and
the user had no way to tell whether the local dataset was fresh.

- `app/src/main/java/cz/medsy/data/settings/SuklSyncStore.kt` keeps the outcome in its own DataStore
  (`medsy_sukl_sync`: `finished_at`, `count`, `failed`). A separate store rather than a new key in
  `AppSettings` because the worker writes it, not the user, and `AppSettings` is the user's document.
- `SuklSyncWorker` records `recordSuccess(repository.localCount())` after a completed download and
  `recordFailure()` only when it stops retrying — a retry is silence, otherwise the card would flash
  "failed" while WorkManager is still working on it.
- `SettingsViewModel.suklSync` combines that store with
  `workManager.getWorkInfosForUniqueWorkFlow(WorkScheduler.SUKL_SYNC_NOW)`; `suklSyncRunning(states)`
  counts ENQUEUED/BLOCKED as in progress too, since a queued sync waiting for network is not "done".
- The card shows: spinner + disabled button while running, "Last sync failed…" in `error` colour,
  or "<count> medications in the local SÚKL database · updated <relative time>". The relative time
  comes from `DateUtils.getRelativeTimeSpanString`, which is localised by the platform, so no new
  `Message` key was needed for it.

The weekly periodic sync writes the same record, so the card also reflects runs that happened while
the app was closed.

## Dosage forms and symptom kinds came from the enum itself

Two enums carried their own English display text, so these words never passed through a resource and
every scanner (which looks for `Text("…")` and named parameters) walked straight past them.

- `MedicationForm.shortLabel()` in `app/src/main/java/cz/medsy/ui/scanner/ScannerScreen.kt` returned
  "Tablet", "Capsule", "IM", "SubQ", "Patch", "Gel", "Spray", "SL", "Drops" for the scan-result
  chips. It is now `internal val MedicationForm.shortLabelRes: Int` over `R.string.form_short_*`, and
  the chip renders `stringResource(form.shortLabelRes)`. Keeping the map in the scanner screen is
  deliberate: it is the only place that needs the short form, while the long names already come from
  `MessageKey.BATCH_FORM_*` in the batch editor.
- `SymptomKind` in `app/src/main/java/cz/medsy/ui/symptoms/SymptomsViewModel.kt` held
  `val label: String` ("Mood", "Hot flashes", …) which the picker, the log rows and the correlation
  cards printed verbatim. It is now `@param:StringRes val label: Int`; the `code` field is the value
  persisted in `symptom_logs` and must never change with the language, so the two are separate fields
  rather than one label that doubles as an id.
- `SymptomsScreen` has to resolve the label where it draws: the log rows and `SymptomPatternRow` fall
  back to the raw `symptomCode` when a stored code no longer maps to a kind, which keeps unknown rows
  readable instead of dropping them.

Czech short forms are the abbreviations a patient actually sees on a box (Tableta, Kapsle, IM, s.c.,
Náplast, Gel, Sprej, SL, Kapky) rather than translated words for the injection routes.

## Useful commands

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:testDebugUnitTest --tests "cz.medsy.domain.schedule.*"
```

## Bugfix pass

Four defects, found by `lintDebug` plus a read of the scheduler editor and the workers.

1. **Lint failed the build** (so `assembleRelease` could never pass). Three groups:
   - `MissingQuantity` ×14: every Czech `<plurals>` lacked the `many` quantity, which Android
     uses for fractional counts ("1,5 dne", not "1,5 dní"). Added with genitive-singular wording.
   - `UnsafeOptInUsageError`: `ScannerScreen.kt` reads `proxy.image`; the analyser is now
     annotated `@OptIn(ExperimentalGetImage::class)`.
   - `MissingPermission` ×4 in `DoseNotifier`: a false positive — each call is guarded by
     `canPostNotifications()` *and* wrapped in `runCatching`, because the permission can be
     revoked between the check and the call. Lint cannot see through either, so the four posting
     helpers carry `@SuppressLint("MissingPermission")` and one comment explains why.
2. **The schedule editor spoke English on a Czech phone.** `ScheduleValidator` returned
   `List<String>` with 27 sentences, rendered verbatim in the error card. Both scanners missed
   it: the screen scan looks for `text = "…"` and the domain scan only walks `domain/`+`data/`,
   while this class lives in `ui/`. Errors are now 15 `Message` keys (`SCHEDULE_*`), arg-carrying
   where needed ("Step 2: …", "max 365 days"), and `ScheduleEditorUiState` has a single
   `errors: List<Message>` instead of one string list plus one message list.
   `MessageLocalisationTest` gained `no validator builds an English error`, matching
   `errors += "…"` / `errors.add("…")` / `errors = listOf("…")` under `ui/`.
3. **A `when` branch that returned a sentence would have been invisible too** — covered by the
   same regex as the domain check (`-> "A tablet"`).
4. **Stale notifications never disappeared.** `InventoryRecalcWorker` builds ids as
   `4000 + rowIndex` and only ever calls `notify()`: a medication that was restocked kept the
   id of a line that is no longer flagged, so "Málo zásoby" stayed on the shade. The worker now
   cancels `NOTIFICATION_BASE … +64` before posting. `BloodworkReminderWorker` had the same hole
   in its early return and now cancels `5000` when there is nothing to warn about.
   `StaleNotificationTest` (2 tests) pins both, since the workers themselves need a device.

Two resource traps, both hit again: `schedule_no_time` and `schedule_add_time` already existed
(the validation string is therefore `schedule_time_required`), and appending a `when` branch by
"last closing brace" landed inside `Message.asText(context)` — the resolver branch block must be
inserted at the end of `res()`.

Lint is now clean of errors (warnings remain: 54 `UnusedResources` — dead strings such as
`nav_medications`, `dashboard_next_dose`, `notification_take` with zero Kotlin references — plus
`PluralsCandidate`, `UseKtx`, `NewerVersionAvailable`). Suite **696 tests / 0 failures**.

## Backup export and import

The backup is a single JSON file. Two things drove the design: it must still open after a restore
into a fresh install (so it carries no Room ids), and it must never be able to destroy data it did
not read successfully (so the file is parsed and validated before anything is deleted).

`data/backup/BackupFormat.kt` lists the twelve tables in parents-first order with the keys that
identify a row for merging (`medications` by name/form/strength, `prescriptions` by `code`,
`dose_logs` by medication + scheduled timestamp + status, …). The SÚKL catalogue is deliberately not
in the list: it is re-downloadable, it is the largest table by far, and restoring it would undo a
sync that is already newer than the file.

`BackupRepository.export()` snapshots every table inside one transaction, so rows written while the
user was choosing a destination cannot appear half-copied. Ids are never written into the children:
each row is inserted without its id and the SQLite-assigned one is remembered in a map, which is how
`schedules.medicationId`, `dose_logs.scheduleId` and `biomarker_values.sessionId` are rewritten on the
way back in. A child whose mandatory parent was not mapped is skipped rather than inserted dangling.

Merging uses the same map, seeded with the rows already in the database, and compares keys after
normalising numbers — `1` and `1.0` from JSON must count as the same strength, otherwise every merge
would duplicate the medication. Replacing wipes the tables in children-first order first, and only
after the file has been decoded, the format checked and the version compared.

`BackupCrypto` wraps the JSON in an envelope with the parameters it needs: PBKDF2-HMAC-SHA256
(120 000 iterations, fresh 16-byte salt) derives the key, AES-GCM with a fresh 12-byte IV encrypts
it, and both travel in the file, so a backup made today still opens in a year. A wrong password is
reported as such (`WrongPassword`), which is only safe because GCM authenticates the tag — a corrupt
file and a wrong password would otherwise be indistinguishable.

In the settings screen the export button builds the file in memory first and then opens the save
dialog; the import button reads the file, and only then asks for the password (when the file is
encrypted) and for replace-or-merge. `ActivityResultContracts.CreateDocument`/`OpenDocument` keep the
storage permission out of the app. Everything the repository can fail with is a `BackupException`
carrying a `MessageKey`, so the screen shows a localised line and no English sentence leaks from the
data layer.

Tests: `BackupCryptoTest` (round trip, wrong password, truncated envelope, fresh parameters per
export, no plaintext in the envelope) and `BackupRoundTripTest` (in-memory Room, 10 cases including
links being rewritten, merging twice adding nothing, merging keeping a medication the file lacks,
replacing dropping what the file lacks, and a foreign file leaving the database untouched).
Suite **724 tests / 0 failures**, APK 108.96 MB.

## Application sites, body map and dose units

Three English leftovers had one thing in common: they were produced by something other than a
`Text("…")` — an enum, a `when` branch behind a comparison, or a string template — so every previous
scan walked past them.

`ApplicationSite` no longer carries a sentence. It carries a `MessageKey` (`SITE_LEFT_DELTOID`, …)
and `label` is a `Message`, which keeps the persisted value untouched: Room stores `name`, so a
restore from an old backup still maps back to the same site. The 20 labels exist in both languages,
including the awkward ones — "Břicho vlevo nahoře" for "Upper left abdomen", because a Czech
"Levý horní abdomen" reads like a translated form.

The picker's status line is now assembled from messages rather than interpolated: the site label and
the age are both messages, and the age is a plural (`bodymap_age_days`) so Czech gets "před 1 dnem",
"před 3 dny" and "před 5 dny" from one key. `describeAge` therefore returns a `Message`; the legend
("Never used", "Under 7 d", …) and the canvas's accessibility summary are resources too — the canvas
says "Mapa těla: vybráno …, doporučeno …" instead of "Body map. Selected: …".

Dose units are free text that the user edits and that is stored (`schedules.doseUnit`,
`inventory_batches.unit`), so they cannot be canonical tokens without leaking English into the field.
`ui/message/DoseUnits.kt` maps a form to a resource, the batch dialog and the schedule editor both
pre-fill from it (`náplast`, `kapsle`, `kapka`, `dávka`, `tbl`, `ml`, `mg`), and the injected
`@ApplicationContext` in `ScheduleEditorViewModel` resolves it outside composition. Rows written
before this change keep their old text — nothing rewrites stored data.

Three real leaks surfaced while checking the result: `LabScanScreen` built "Camera unavailable: …"
and "Could not take the photo: …" as templates (`LAB_SCAN_CAMERA_UNAVAILABLE`,
`LAB_SCAN_CAPTURE_FAILED`), and `SymptomsScreen` glued "…/5 … (…×)" together (`symptom_pattern_mean`).

Guards: `PROSE_BRANCH` no longer requires a bare identifier before `->`, so `chosen == null -> "…"`
is visible; a new check flags a string template in `ui/` that still holds two English words after the
placeholders are removed — that is exactly the `"Camera unavailable: $message"` shape. Both were
verified by mutation: the widened branch check flags the old picker line, and the template check
fails when a probe function is dropped into `ui/`.

Tests: `BodyMapLabelsTest` (distinct keys per site, no sentence inside the enum, plural counts
survive, six units for nine forms). Suite **729 tests / 0 failures**, APK 108,98 MB.
