# Changelog

## Unreleased

- Build: Gradle 9.8.2 and Android Gradle Plugin 9.5.0; Gradle files use the assignment syntax required by Gradle 10.

## 2.0.1

- Exported logs also mask the vehicle id and the GPS coordinates (latitude, longitude, elevation), which revealed where the car is parked.

## 2.0.0 – first public release

- New name and package: **EV Remote** (`it.gbon.evremote`). It installs as a new app: log in again and activate remote commands with a new SMS.
- The OAuth client of the official app is no longer stored with the source code. The build downloads `configs.json` from homeassistant-stellantis-vehicles at a pinned commit, verified with SHA-256.
- Tokens, OTP state and every stored text are encrypted with an Android Keystore key. Cleartext traffic and backups are disabled.
- OTP server responses are parsed without DTDs or external entities (XXE), and the login WebView has no file or content access.
- ABRP is disabled in builds without an Iternio API key.
- Brand and country are build options (`-Pevremote.brand`, `-Pevremote.country`).
- New "About and licenses" screen, with optional donation links (GitHub builds only).
- Signed release builds with R8, SHA-256 checksums and build provenance attestation.

## 1.x – private builds

- Login, OTP activation, climate with retries and live confirmation, charging start and stop.
- Charge limit with background checks.
- ABRP live data and trip mode.
- Exportable logs.
