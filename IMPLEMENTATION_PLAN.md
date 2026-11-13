# Implementační plán — zbývající funkce z AGENT.md

Audit k **2026-11-12**, ověřený proti kódu (ne jen proti `NOTES.md`, jehož matice
na několika místech zastarala). Zbývá **9 položek**. Fáze 0.1–3.2 a 2.1–2.3 jsou
hotové, proto v tomto plánu nejsou.

## Co už hotové je (nekontrolovat znovu)

Stack, téma, Bento dashboard, morphing tlačítko, plánovací engine + editor režimu,
vial/dead-space matematika, sklad, interakce, PRN limit, offset alarmu při absorpční
kolizi, náplasti, pumpy gelů, sublinguální časovač, milníky (vytváření i piny v grafu
i v PDF), vlastní cíl lékaře, překrytí rozsahů, discreet režim, OCR-free PDF parser,
report, lock, Health Connect (TK/P).

**Schéma:** DB je `version = 2` s exportovaným schématem a `MIGRATION_1_2`.
Žádná z devíti položek níže **nepotřebuje novou migraci** — tabulky pro eRecept
i sloupce pro stav náplastí už existují. To odstraňuje největší riziko tohoto plánu.

## Pořadí práce

| # | Funkce | Odhad | Proč v tomto pořadí |
|---|---|---|---|
| F1 | 2D mapa těla + výběr místa | M | **hotovo** — viz `NOTES.md` |
| F2 | OCR vyfocených labáků | M | **hotovo** — viz `NOTES.md` |
| F3 | Korelace symptomů s dávkami | M | **hotovo** — viz `NOTES.md` |
| F4 | Spotřební materiál v cestovním plánu | S | **hotovo** — viz `NOTES.md` |
| F5 | Ručné zakládání šarží | S | **hotovo** — viz `NOTES.md` |
| F6 | UV index / fotosenzitivita | S | **hotovo** — viz `NOTES.md` |
| F7 | Fronta eReceptů | M | **hotovo** — viz `NOTES.md` |
| F8 | Lokalizace natvrdo zadaných textů | M | **hotovo** — viz `NOTES.md` |
| F9 | `@Preview` světlá/tmavá | S | **hotovo** — viz `NOTES.md` |
| F10 | Export a import zálohy | M | **hotovo** — viz `NOTES.md` |

Legenda: S = jeden soubor / jedna obrazovka, M = více vrstev.

---

## F1 — Interaktivní 2D mapa těla (M) — HOTOVO

**Stav:** hotovo. `BodyMapCanvas`, `BodyMapPicker`, `BodyMapHitTest`, `SiteChoices`
v `DashboardViewModel` a `DoseSiteDialog` v `DashboardScreen`. Testy
`SiteMapInteractionTest`, `SiteHistoryTest`, `SiteSelectionLoggedTest` (15 testů).
Detaily v `NOTES.md` § F1.

**Výchozí stav před F1:** `domain/bodymap/BodyMap.kt` je hotový a otestovaný (`BodyMapTest`):
`placement(site)`, `states(form, history, now)`, `freshness()`, `FRESH_DAYS = 7`,
`AGED_DAYS = 21`, souřadnice normalizované 0..1. Chybí **vykreslení a výběr**;
`DashboardScreen.kt:383` posílá do `DoseRepository.log` vždy `state.recommendedSite`.

**Kroky**

1. `ui/components/BodyMapCanvas.kt` — `Canvas` s obrysem trupu/paží/nohou a klikatelnými
   zónami z `BodyMap.placedSites()`; barva z `freshness` (NEVER = obrys, FRESH =
   `errorContainer`, RECENT = `tertiaryContainer`, AGED = `primaryContainer`).
   Hit-test v normalizovaných souřadnicích, tolerancia ≥ 0.06 (prst, ne pixel).
2. `ui/components/BodyMapPicker.kt` —stavový composable: `selected`, `recommended`,
   `onSelect`. Doporučené místo dostane výrazný prstenec + popisek „Recommended“.
3. `DashboardScreen` — v potvrzovacím dialogu dávky nahradit textové doporučení
   pickerem; `onLogDose` dostane skutečně vybrané místo (parametr už existuje,
   `DashboardScreen.kt:107`). Formuláře bez `requiresSite` picker nezobrazí.
4. `DashboardViewModel` — historie míst z `doseLogDao` pro daný lék (existující dotaz
   na poslední dávky), převod na `List<Pair<ApplicationSite, Instant>>` pro `states()`.
5. Přístupnost: každá zóna má `contentDescription` („Left deltoid, used 12 days ago“),
   aby se mapa dala ovládat TalkBackem.

**Testy:** `SiteMapInteractionTest` (hit-test vrací správné místo včetně zrcadlení —
levá strana těla je vpravo na obrazovce), `SiteHistoryTest` (`states()` ignoruje místa
jiné formy, `NEVER` pro nepoužité), `SiteSelectionLoggedTest` (vybrané místo se uloží
do `DoseLogEntity.applicationSite`).

**Kritérium:** kliknutí na levé stehno uloží `LEFT_VASTUS_LATERALIS`; po uložení se
zóna obarví jako FRESH a doporučení se přesune na druhou stranu.

---

## F2 — OCR vyfocených laboratorních zpráv (M) — HOTOVO

**Stav:** hotovo. `OcrTextRecognizer` (+ `OcrDocument`), `OcrLabExtractor`,
`OcrModule`, `LabScanScreen`, `BloodworkViewModel.importPhoto`. Testy
`OcrLineJoinTest` (4), `OcrLabExtractorTest` (4, Robolectric). Detaily v `NOTES.md` § F2.

Původní zadání (pro historii):

**Výchozí stav před F2:** ML Kit Text Recognition je v závislostech (nativní libs v APK), ale kód volá
jen `BarcodeScanning` (`ScannerScreen.kt:46–49`). `LabImportPipeline.importOcrText`
existuje a nemá volajícího. Staging obrazovka pro PDF text existuje.
**Kroky**

1. `data/lab/OcrTextRecognizer.kt` — tenká abstrakce (`suspend fun recognize(uri): String`)
   s implementací nad `TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)`.
   Abstrakce je kvůli testovatelnosti: ML Kit v unit testech neběží.
2. `data/lab/OcrLabExtractor.kt` — `InputImage.fromFilePath(context, uri)` → blocks →
   spojení textu po řádcích (řádky, ne bloky: české labáky mají název a hodnotu na
   jednom řádku) → `LabImportPipeline.importOcrText`.
3. `ui/bloodwork/LabScanScreen.kt` — CameraX preview + tlačítko spouště + fallback
   `PickVisualMedia` z galerie; po přečtení přechod na existující staging.
4. Navigace z `BloodworkScreen` („Scan report“) a oprávnění `CAMERA` už v manifestu.
5. Staging musí vždy ukázat rozpoznané hodnoty před uložením (riziko záměny 0/8).

**Testy:** `OcrLabExtractorTest` s fake recognizerem (text s „Estradiol 610 pmol/L“
projde do stagingu; nečitelný text vrátí prázdný výsledek, ne pád),
`OcrLineJoinTest` (název a hodnota na jednom řádku se nerozpojí).

**Kritérium:** fotka s „Estradiol 610 pmol/L“ otevře staging s jednou hodnotou;
offline přístroj se chová stejně (OCR je lokální).

---

## F3 — Korelace symptomů s dávkami (M) — HOTOVO

**Stav:** hotovo. `SymptomCorrelation` + `SymptomCorrelationCalculator`,
`takenDoseEvents`, `SymptomsViewModel.patterns`, Patterns karta v `SymptomsScreen`.
Testy `SymptomCorrelationTest` (8), `SymptomDoseWindowTest` (5). Detaily v `NOTES.md` § F3.

Původní zadání (pro historii):

**Výchozí stav před F3:** `SymptomsScreen` jen vypisuje záznamy; žádný výpočet (grep „correlat“ = 0).

**Kroky**

1. `domain/calc/SymptomCorrelation.kt` — pro každý `SymptomLog` najít nejbližší dávku
   do ±X hodin (okno konfigurovatelné, default 2 h) a určit fázi (`BloodworkCalculator`
   už umí „hours since last dose“ / trough).
2. Agregace: průměrná závažnost **v okně** vs **mimo okno** pro každý `SymptomKind`,
   s počtem vzorků; při `n < 3` výsledek nehlásit jako zjištění (jinak vzniká falešná
   korelace z jednoho bodu).
3. `SymptomsViewModel` — načíst `doseLogDao` za 90 dní, spočítat, vystavit
   `List<SymptomCorrelation>`.
4. `SymptomsScreen` — karta „Patterns“: pruh závažnosti s piny dávek (stejný jazyk jako
   `BiomarkerChart`), plus jedna věta typu „Headache is 1.8× worse within 2 h of a dose“.

**Testy:** `SymptomCorrelationTest` (syntetická data: vyšší závažnost v okně než mimo;
prázdná data nevrátí dělení nulou; méně než 3 vzorky se nehlásí),
`SymptomDoseWindowTest` (hranice okna je inkluzivní, časová zóna nemění výsledek).

**Kritérium:** syntetická data ukážou vyšší závažnost v okně; s jediným záznamem se
karta „Patterns“ nezobrazí.

---

## F4 — Spotřební materiál v cestovním plánu (S) — HOTOVO

**Stav:** hotovo. `TravelPlan.consumables` + `injectionCount`, `consumableKind(name)`,
repo agreguje zásobu po druzích, `TravelPlanner` má druhou sekci.
Testy `TravelConsumablesTest` (10), `TravelPlanRepositoryTest` (2). Detaily v `NOTES.md` § F4.

Původní zadání (pro historii):

**Výchozí stav před F4:** `travelPlan` sčítal jen jednotky léků;
`consumablesPerInjection` existuje, ale plán ho nepoužívá.

**Kroky**

1. Rozšířit `TravelItem` o `consumables: List<TravelConsumable>` nebo přidat druhý
   seznam `consumableItems` do `TravelPlan` (volím druhé — lék a spotřební materiál
   mají jiné jednotky a jinou kontrolu zásoby).
2. Počet injekcí za cestu = součet nezbytných dávek forem `isInjectable` za
   `tripDays + bufferDays`; vynásobit `consumablesPerInjection` (1× stříkačka,
   1× nasávací jehla, 1× aplikační jehla, 1–2× tampon).
3. Chybějící počet porovnat s `ConsumableEntity.remainingCount`, nedostatek označit
   stejně jako u léků (`hasShortfall`).
4. `InventoryScreen.TravelPlanner` (`:211`) vykreslit druhou sekci.

**Testy:** `TravelConsumablesTest` (10 injekcí → 10 stříkaček / 10 jehel / 20 tamponů;
žádná injekce → prázdný seznam, ne nuly; nedostatek se hlásí).

**Kritérium:** 10 injekcí na cestě vygeneruje 10 stříkaček a upozornění při menší zásobě.

---

## F5 — Ručné zakládání šarží (S) — HOTOVO

**Stav:** hotovo. `BatchDraft` + `BatchDraftValidator`, `InventoryRepository.addBatch`,
`BatchEditorDialog` napojený z `InventoryScreen`.
Testy `BatchDraftValidatorTest` (11), `BatchInsertTest` (4). Detaily v `NOTES.md` § F5.

Původní zadání (pro historii):

**Výchozí stav před F5:** `InventoryBatch` vzniká jen ze skeneru (`PackageToInventory.kt:34`);
`InventoryScreen` nemá formulář, takže lék koupený bez krabičky nelze naskladnit.

**Kroky**

1. `ui/inventory/BatchEditorScreen.kt` (nebo dialog): lék, počet, jednotka, expirace
   (nullable — u některých přípravků není), číslo šarže, `deadSpaceMlPerDose`,
   `lowStockThresholdDays`.
2. `InventoryViewModel.addBatch(draft)` s validací: množství > 0, expirace nesmí být
   v minulosti (varování, ne blok), jednotka musí odpovídat formě (`mL` pro injekce,
   „dávka“ pro gely).
3. Uložit přes `InventoryDao.insert` (existuje), pro nový lék nejdřív `MedicationDao`.
4. Vstup z `InventoryScreen` FAB „Add batch“ a z detailu léku.

**Testy:** `BatchDraftValidatorTest` (nula/negativní množství, expirace v minulosti),
`BatchInsertTest` (Robolectric + reálné SQLite: šarže se objeví ve `observeAll`,
`dispensibleBatches` ji vydá až po naplnění).

**Kritérium:** ručně založená šarže 10× 2 mg se objeví ve skladu a odečítá se při dávce.

---

## F6 — UV index a fotosenzitivita (S) — HOTOVO

**Stav:** hotovo. `Photosensitivity`, `UvRepository` (cache 3 h), `OpenMeteoUvSource`,
karta na dashboardu, přepínač a město v nastavení.
Testy `PhotosensitivityTest` (10), `UvCacheTest` (6), `OpenMeteoParsingTest` (5).
Detaily v `NOTES.md` § F6.

Původní zadání (pro historii):

**Výchozí stav před F6:** žádný kód; `INTERNET` je v manifestu. Varování je zatím statický text.

**Kroky**

1. `data/uv/UvRepository.kt` — Open-Meteo `current=uv_index`, odpověď uložit do
   DataStore s časem stažení (nové klíče v `SettingsRepository.Keys`).
2. Cache platná 3 h; při chybě sítě se použije poslední hodnota, jinak `null`.
   **Nikdy** nesmí selhat tichým pádem ani zablokovat dashboard.
3. `domain/calc/Photosensitivity.kt` — práh UV > 6 **a** aktivní lék ze seznamu
   fotosenzitivních (tetracykliny, isotretinoin, některá antipsychotika — mapování
   podle ATC, ne podle názvu).
4. `WorkScheduler` — volitelně `UvRefreshWorker` (6 h, `NetworkType.CONNECTED`);
   jinak stačí refresh při otevření dashboardu.
5. UI: karta na dashboardu jen když obě podmínky platí; v nastavení lze vypnout.

**Testy:** `UvWarningTest` (UV 7 + fotosenzitivní lék → varování; UV 5 → nic;
nefotosenzitivní lék → nic), `UvCacheTest` (offline vrátí poslední hodnotu; expirovaná
cache bez sítě vrátí `null`).

**Kritérium:** offline režim nic nespadne a varování se jen neukáže.

---

## F7 — Fronta eReceptů (M) — HOTOVO

**Stav:** hotovo. `PrescriptionDao` + `PrescriptionRepository`, ukládání při skenu,
`PrescriptionsScreen`, karta na dashboardu, notifikace 2 dny před expirací.
Testy `PrescriptionExpiryTest` (13), `PrescriptionDaoTest` (8), `ScannerPrescriptionTest` (6).
Detaily v `NOTES.md` § F7.

Původní zadání (pro historii):

**Výchozí stav před F7:** `PrescriptionEntity` + `PrescriptionItemEntity` a tabulky v migraci v2
existují, ale **není `PrescriptionDao`** a `ScannerViewModel.kt:112` parsovaný předpis
zahodí (drží ho jen v UI stavu).

**Kroky**

1. `PrescriptionDao` v `data/local/dao/Daos.kt`: `observeActive(now)`, `getByCode`,
   `insert` s `@Transaction` pro hlavičku + položky, `delete`.
2. `data/repository/PrescriptionRepository.kt`: uložit při skenu, načíst aktivní,
   `daysRemaining` přes `EReceptParser` (existuje).
3. `ScannerViewModel.handleQr` — po parsování uložit; duplicitní `code` řešit
   `OnConflictStrategy.IGNORE` a uživateli říct „already saved“.
4. `ui/prescriptions/PrescriptionsScreen.kt` — seznam s odpočtem platnosti, varování
   2 dny před expirací, tlačítko „Create medication“ předvyplní editor léku.
5. Navigace + badge na dashboardu, jen když existuje předpis expirující do 3 dnů.
6. Notifikace o blížící se expiraci přes existující `ReminderCoordinator` (bez nového
   alarmu — denní kontrola při otevření appky stačí).

**Testy:** `PrescriptionDaoTest` (insert hlavičky i položek, unikátní `code`),
`PrescriptionExpiryTest` (14 dní, vlastní platnost, expirovaný), `ScannerPrescriptionTest`
(sken uloží právě jeden předpis; opakovaný sken nevytvoří duplikát).

**Kritérium:** sken eReceptu vytvoří záznam, restart appky ho neztratí a 2 dny před
expirací se objeví varování.

---

## F8 — Lokalizace natvrdo zadaných textů (M) — HOTOVO

**Stav:** hotovo. 103 textů z `Text("…")` + 4 `contentDescription` + dalších 52 textů z
`text = "…"` / `else "…"` v 13 obrazovkách přes `stringResource`; 28 šablon s interpolací
převedeno na parametry (`%1$s`, `%1$d`) a 6 počtových textů na `<plurals>` (jednotné/množné
číslo i v češtině). `Schedule.summaryLine()`
a `SymptomsScreen.correlationSentence()` jsou nově `@Composable`, aby mohly použít `stringResource`.
Testy `StringsParityTest` (5) a `HardcodedStringTest` (6, hlídá i šablony se slovy).
Věty z ViewModelů a domény už nejsou anglické stringy: nesou se jako
`cz.medsy.domain.message.Message` (klíč + argumenty, serializovatelný) a překládá je až UI
(`ui/message/MessageText.kt`, exhaustive `when` bez `else`). Týká se to skeneru,
`PrnLimit.describe`, `PatchLifecycle.describe`, `MilestoneDiff`, `PrescriptionQueue`
(včetně notifikační připomínky), dashboardu, workerů a notifikací. Milníky se ukládají jako
zpráva, takže schéma je `version = 3` s `MIGRATION_2_3` (`message TEXT`), a legacy `description`
zůstává pro staré řádky. Testy domény teď porovnávají klíče, ne anglické věty; nový
`MessageLocalisationTest` (6) hlídá, že každý klíč má resource, plurály jdou přes `<plurals>`,
resolver nemá `else`, žádný ViewModel nepoužívá `error.message ?: "<anglicky>"` a žádný
soubor v `ui/`/`work/` nezačíná řádek anglickou větou (to odhalilo 26 dalších textů: karta
sublingválního držení, řádky skladových výstrah, UV karta, štítky body mapy včetně
`contentDescription`, hlášky laboratoře a celá karta Health Connect v nastavení). Doplněny
`HEALTH_SYNC_*`, `SAVE_TARGET_FAILED`, `SAVE_SCHEDULE_FAILED`, `TARGET_*_UNREADABLE`,
`SCHEDULE_MEDICATION_MISSING` a `UV_EXPOSURE*` (s plurálem pro víc léků).

Poslední dvě dávky: texty v pojmenovaných parametrech (`title = "…"`, `subtitle = "…"`, zámek
v `MedsyApp.kt`) a próza kalkulátorů. `InteractionEngine` má 22 pravidel jako dvojice
`MessageKey` (62 stringů `interaction_*`, severity `severity_*`), `AbsorptionOffsetPlanner`
vrací `Message` (`ABSORPTION_*`), `BloodworkCalculator` má `TroughAssessment.label` a
`PreTestWarning.title/detail` jako `Message` (`BLOODWORK_*`). `MessageLocalisationTest` má
7 kontrol a hlídá i `title|subtitle|label|headline|placeholder|description|summary = "…"`.
`values` i `values-cs`: 437 stringů + 13 plurálů.

Zbývající krátké popisky z domény jsou hotové: 32 stringů + plural
`inventory_enough_days` (`profile_*`, `consumable_*`, `batch_*`, `form_*`, `vial_*`,
`inventory_prn_manual|get_more`). `RangeBands.Band.label`, `TravelItem.suggestion`,
`TravelConsumable.label`, `ConsumableKind.label()`, `BatchValidation.errors/warnings`
a `RecommendedSite.reason` nesou `Message`; `BatchValidation.message` zanikl. Osmá kontrola
`MessageLocalisationTest` skenuje `domain/` a `data/` na prózu v polích i na větu v `when`.
Testy 693 / 0 selhání. Detaily v `NOTES.md` § F8.

Původní zadání (pro historii):

**Výchozí stav před F8:** 84 výskytů `Text("…")` mimo `@Preview`. Klíče EN/CS jsou v paritě (133 = 133),
takže chybí jen extrakce a české překlady nových klíčů.

**Kroky**

1. Projít obrazovky po jedné (editor režimu, skener, nastavení, sklad, dashboard,
   bloodwork, symptomy, report) a nahradit literály `stringResource(R.string.…)`.
2. Doplnit české překlady do `values-cs/strings.xml`; číselné/formátované texty přes
   `stringResource(id, args)`, ne konkatenací.
3. Názvy léků a uživatelská data **nezasahovat** — lokalizují se jen UI texty.
4. Do té doby neopravovat `Text("` v `@Preview` (ty zůstávají).

**Testy:** `StringsParityTest` (každý klíč v `values` má protějšek v `values-cs`),
`HardcodedStringTest` (sken `app/src/main/java/**/ui/**` bez `@Preview` bloků nenajde
`Text("` s literálem delším než 1 znak) — tento test je zároveň regresní pojistka.

**Kritérium:** `HardcodedStringTest` projde a česká obrazovka neobsahuje anglické
popisky kromě názvů léčiv.

---

## F9 — `@Preview` světlá/tmavá pro každou obrazovku (S) — HOTOVO

**Stav:** hotovo. 12 obrazovek × světlá/tmavá (29 preview celkem, 14 tmavých), nově
`ScannerScreen`, `LabScanScreen`, `LockedScreen`. Test `ScreenPreviewTest` (3) hlídá
přítomnost obou variant. Detaily v `NOTES.md` § F9.

Původní zadání (pro historii):

**Výchozí stav před F9:** 8 preview, **0** s `uiMode`; bez preview `ScannerScreen` a `LockedScreen`
(`ui/MedsyApp.kt:110`).

**Kroky**

1. Doplnit `@Preview(name = "… light")` a `@Preview(name = "… dark", uiMode = UI_MODE_NIGHT_YES)`
   pro každou obrazovku; u displejových používat statické ukázkové stavy (žádné Hilt VM).
2. Přidat preview i pro nové obrazovky z F1–F3 a F7 (`BodyMapPicker`, `LabScanScreen`,
   `SymptomsScreen` patterns, `PrescriptionsScreen`).
3. U disponibilních obrazovek (`ScannerScreen` s CameraX) preview s fake stavem bez
   kamery, aby se nevyžadovalo zařízení — jinak se preview jen rozbije.

**Kritérium:** každá obrazovka má alespoň jednu světlou a jednu tmavou variantu;
`grep -c "@Preview"` u obrazovek ≥ 2 (mimo jednoduché komponenty).

---

## Společný Definition of Done (platí pro každou položku)

1. Napsat **test první**, který selže (stejně jako u GTIN a objemu injekcí).
2. Ověřit mutační kontrolou, že test chrání právě opravovanou logiku.
3. `./gradlew :app:testDebugUnitTest` i `./gradlew :app:assembleDebug`.
4. Aktualizovat matici v `NOTES.md` z MISSING/PARTIAL na DONE včetně jména testu
   a odstranit zastaralé řádky (custom target, overlay, milníky).
5. Nová data v DataStore: klíč + default + test, že chybějící hodnota nespadne.

## Rizika

| Riziko | Dopad | Opatření |
|---|---|---|
| ML Kit v unit testech neběží | OCR netestovatelné | abstrakce `OcrTextRecognizer` + fake |
| OCR zamění 0/8 | špatná laboratorní hodnota | staging se zobrazí vždy, nikdy nepřímý zápis |
| UV API nedostupné | prázdná karta / pád | cache 3 h, `null` = bez varování, žádná výjimka do UI |
| Mapa těla zrcadlí strany | opakované píchání do stejné strany | `BodyMapTest` už řeší; hit-test test na zrcadlení |
| F8 rozbije funkce z F1–F7 | regrese textů | F8 až po nich + `HardcodedStringTest` |
| eRecept duplicity | dvojí předpis | unikátní index na `code` už v migraci |
| Discreet režim unikne v nových obrazovkách | prozrazení diagnózy | každý nový text s názvem léku projít `DiscreetDisplay` |

## Co plán záměrně neobsahuje

- **Váhu / Health Connect weight** — odstraněno na žádost; vrácení by znamenalo
  oprávnění i mapování současně.
- Synchronizaci mezi zařízeními a odesílání dat lékaři — appka je offline-first.
- Nové databázové migrace — všech 9 položek se vejde do schématu v2.

## F10 — Export a import zálohy (M) — HOTOVO

Záloha je jeden JSON soubor ve složce, kterou si uživatel vybere (`CreateDocument`/`OpenDocument`,
takže aplikace nepotřebuje žádné úložištní oprávnění). Volitelně ji lze zašifrovat heslem
(PBKDF2-HMAC-SHA256, AES-GCM, parametry v souboru), takže záloha zůstane čitelná i po přeinstalování.

Export obsahuje dvanáct tabulek v pořadí rodiče → potomci a **žádná id**: při importu dostane každý
řádek id nové a vazby (`schedules.medicationId`, `dose_logs.scheduleId`, `biomarker_values.sessionId`)
se přepíší přes mapu. Katalog SÚKL v záloze není — je stahovatelný, největší a jeho obnovení by
přepsalo novější synchronizaci.

Import se nejdřív rozhodne o režimu: **Nahradit** (smaže tabulky od potomků k rodičům a naplní je
souborem) nebo **Sloučit** (porovná klíče jako `name/form/strength`, `code` receptu, dávka v čase;
existující řádky nechá být a nové děti přiřadí k nim). Čísla se porovnávají normalizovaně, aby `1`
a `1.0` nebyly dva různé léky, a celý soubor se ověří (formát, verze, dešifrování) **dřív**, než se
cokoli smaže. Chyby jsou `BackupException` s `MessageKey`, takže se zobrazují lokalizovaně.

Testy: `BackupCryptoTest` (6) a `BackupRoundTripTest` (10, skutečné schema v paměti). Celkem
**724 testů / 0 selhání**, APK 108,96 MB.
