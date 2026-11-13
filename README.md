# Medsy

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![CI](https://github.com/FoxxoOwO/medsy/actions/workflows/ci.yml/badge.svg)](https://github.com/FoxxoOwO/medsy/actions/workflows/ci.yml)

Offline-first Android app for tracking medication, HRT and bloodwork — no account, no cloud,
no telemetry. Everything (medications, schedules, dose log, labs, inventory) lives in a
SQLCipher-encrypted Room database on the device; the only network traffic is the optional
SUKL drug-database sync and the UV index lookup.

The UI is Czech-first with full English and Czech string sets.

## Features

- **Schedules & dose log** — fixed times, intervals, and PRN ("as needed") schedules with
  per-day ceilings; absorbances, patch sites and injection sites are recorded per dose.
- **Reminders** — exact alarms with an optional lead time, per-action notifications
  (take / snooze / skip), and an optional full-screen alert over the lock screen while the
  phone is locked.
- **Inventory** — stock, beyond-use dates, low-stock and expiry warnings, package scanning.
- **Labs** — blood panel entry, PDF/OCR import, and range evaluation against reference
  intervals with an HRT profile selector.
- **Reports** — a shareable PDF summary for appointments.
- **Privacy** — database encryption, optional biometric app lock, discreet mode (neutral app
  icon and name, no medication names on the lock screen), and an encrypted export/import
  format for device migration.
- **Health Connect** — optional write-only export of blood pressure and heart rate.

## Stack

Kotlin 2.3 · Jetpack Compose (Material 3 Expressive) · Hilt · Room + SQLCipher ·
DataStore · WorkManager · AGP 9.1, `minSdk` 26, `targetSdk` 36, `compileSdk` 37.

## Build

```bash
./gradlew :app:assembleDebug      # debug APK
./gradlew :app:testDebugUnitTest  # unit + Robolectric tests
```

Requires JDK 21 and Android SDK 37. `local.properties` must point at your SDK
(`sdk.dir=...`); it is not committed.

## Layout

```
app/src/main/java/cz/medsy/
├── data/        # Room, repositories, settings, backup, UV, health, work
├── domain/      # pure logic: scheduling, PRN limits, labs, alerts, discreet display
├── notification/# alarm receivers, notifier, lock-screen alert
└── ui/          # Compose screens, theme, widgets
```

`AGENT.md` documents the architecture and the conventions this codebase follows; `NOTES.md`
records implementation traps hit along the way.

## Disclaimer

Medsy is a personal record-keeping tool, not a medical device. It does not give dosing advice
and must not replace a prescription or a doctor's instructions.

## License

Medsy is free software: you can redistribute it and/or modify it under the terms of the GNU
General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version.

Medsy is distributed in the hope that it will be useful, but **without any warranty**; without
even the implied warranty of merchantability or fitness for a particular purpose. See the GNU
General Public License for more details.

You should have received a copy of the GNU General Public License along with Medsy; it is in
[`LICENSE`](LICENSE). If not, see <https://www.gnu.org/licenses/>.

Copyright (C) 2026 TeresaPC

---

Created by [FoxxoOwO](https://github.com/FoxxoOwO) ❤️
