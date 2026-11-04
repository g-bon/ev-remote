# EV Remote

[![CI](https://github.com/g-bon/ev-remote/actions/workflows/ci.yml/badge.svg)](https://github.com/g-bon/ev-remote/actions/workflows/ci.yml)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)
[![Ko-fi](https://img.shields.io/badge/Ko--fi-support-FF5E5B?logo=ko-fi&logoColor=white)](https://ko-fi.com/gabibon)
[![Buy Me a Coffee](https://img.shields.io/badge/Buy%20Me%20a%20Coffee-support-FFDD00?logo=buymeacoffee&logoColor=black)](https://buymeacoffee.com/gabibon)

Unofficial Android app to control Stellantis electric cars with the e-Remote / Connect services: climate preconditioning, charging, charge limit, status and live data for [ABRP](https://abetterrouteplanner.com).

> **Currently supported: Opel Corsa-e (Italy) only.** Everything else is in development, see [Supported vehicles](#supported-vehicles).

> **Disclaimer.** EV Remote is an independent project, not affiliated with, endorsed or sponsored by Stellantis or its brands. It uses the same private back end as the official apps, which can change or block it at any time. Use it at your own risk.

## Built on the work of

EV Remote would not exist without two open source projects that reverse engineered the Stellantis connected-car services. All the credit for understanding the protocol goes to them. Please **star them and support their authors**:

| Project | Author | What EV Remote owes it | Support |
|---|---|---|---|
| [**psa_car_controller**](https://github.com/flobz/psa_car_controller) [![Stars](https://img.shields.io/github/stars/flobz/psa_car_controller?style=social)](https://github.com/flobz/psa_car_controller) | [Florian Bezannier (@flobz)](https://github.com/flobz) | The inWebo OTP client, the hardest part of the protocol. EV Remote's [Otp.java](android/app/src/main/java/it/gbon/evremote/Otp.java) is a direct port of its `otp.py`, checked request by request by [otp-test](android/otp-test). Also the original reverse engineering of the PSA remote-control API. | [GitHub Sponsors](https://github.com/sponsors/flobz) · [PayPal](https://www.paypal.com/donate?hosted_button_id=SM652WPXFNCXS) |
| [**homeassistant-stellantis-vehicles**](https://github.com/andreadegiovine/homeassistant-stellantis-vehicles) [![Stars](https://img.shields.io/github/stars/andreadegiovine/homeassistant-stellantis-vehicles?style=social)](https://github.com/andreadegiovine/homeassistant-stellantis-vehicles) | [Andrea De Giovine (@andreadegiovine)](https://github.com/andreadegiovine) | Up-to-date OAuth, MQTT and command payloads, the per-brand app configuration (`configs.json`, downloaded at build time), the charge-limit logic, the ABRP telemetry mapping and the knowledge of error codes and live events. | [Ko-fi](https://ko-fi.com/andreatito) · [Buy Me a Coffee](https://buymeacoffee.com/andreatito) |

If you use Home Assistant, [homeassistant-stellantis-vehicles](https://github.com/andreadegiovine/homeassistant-stellantis-vehicles) is the more complete solution and supports many more vehicles. EV Remote is for people who want a standalone phone app.

## Supported vehicles

| Vehicle | Country / app | Status |
|---|---|---|
| **Opel Corsa-e** (e-Remote Control) | **Italy, MyOpel** | **Supported**: tested on a real car |
| Other Opel electric models (Mokka-e, Astra Electric, …) | Italy, MyOpel | In development: same back end, untested |
| Opel in other countries | MyOpel, other countries | In development: build option, untested |
| Peugeot, Citroën, DS, Vauxhall electric models | MyPeugeot, MyCitroën, MyDS, MyVauxhall | In development: build option, untested |
| Plug-in hybrids and combustion cars | any | Not planned |

Features on the supported vehicle (Opel Corsa-e, Italy):

| Feature | Status |
|---|---|
| Login and remote-commands activation (SMS + PIN) | Supported |
| Status: battery, range, charging, cable, climate, locks, odometer, 12 V battery, battery health | Supported |
| Climate on and off, with wake-up, retries and live confirmation | Supported |
| ABRP live data and trip mode | Supported |
| Charging start and stop | In development: implemented, being tested |
| Charge limit with background checks (default 80%) | In development: implemented, being tested |
| GPS position and speed | Depends on the car: on the test car Stellantis never updates them |
| Doors lock, horn, lights | Not available with the e-Remote service |

Tried EV Remote with another car or country? Please [open an issue](https://github.com/g-bon/ev-remote/issues/new/choose) with the result, it helps move it to "Supported".

## Features

- Login on the official login page of the brand, in a clean WebView, and one-time activation of remote commands with SMS and PIN.
- **Climate on and off**, with wake-up, confirmation from the car's live events, and automatic retries on communication errors.
- Charging start and stop, and a **charge limit** that stops charging even with the app closed.
- Status: battery, range, charging, cable, climate, outside temperature, locks and open doors, position, odometer, 12 V battery, battery health.
- **ABRP live data**: telemetry on every refresh, and a trip mode that sends data every minute.
- Exportable logs with VIN, customer id, vehicle id, tokens and GPS coordinates masked, for troubleshooting.
- Tokens and the OTP state are encrypted with a key in the Android Keystore. No servers of its own: the app talks only to Stellantis and, if enabled, to ABRP.

## Limits

- Each activation of remote commands registers a new OTP "device" on your account. Stellantis limits them (error `NOK:MAXNBTOOLS`) and accepts about 6 OTP codes per day. Avoid reinstalling and activating again without a reason.
- The climate starts only with the car **locked and switched off**, and with at least 20% battery or the car charging.
- The app user interface is in Italian for now.

## Install

1. Download `ev-remote-<version>.apk` from [Releases](https://github.com/g-bon/ev-remote/releases).
2. Optionally, check it:
   - SHA-256 of the file: compare with `SHA256SUMS` in the release;
   - build provenance: `gh attestation verify ev-remote-<version>.apk --repo g-bon/ev-remote`;
   - signing certificate: `apksigner verify --print-certs ev-remote-<version>.apk`, SHA-256 fingerprint `B2:DA:41:FE:CC:27:DD:7E:D0:C9:BF:72:3E:76:E7:74:EA:73:6D:D1:B0:59:0A:29:BB:D8:BA:EF:8E:B9:5D:0D`.
3. Open the APK on the phone and allow installs from that source.

[Obtainium](https://github.com/ImranR98/Obtainium) can install updates automatically from the Releases page.

## Build

Requirements: JDK 17 or newer and the Android SDK (platform 35).

```bash
cd android
./gradlew assembleGithubDebug                                    # Opel Italy (supported)
./gradlew assembleGithubDebug -Pevremote.brand=MyPeugeot -Pevremote.country=FR   # in development
```

The OAuth client of the official app is **not** in this repository. The build downloads `configs.json` from [homeassistant-stellantis-vehicles](https://github.com/andreadegiovine/homeassistant-stellantis-vehicles) at a pinned commit and checks its SHA-256. You can override any value with environment variables or a git-ignored `android/app/stellantis.properties`.

ABRP needs an API key from Iternio, set as `ABRP_API_KEY`. Without it the ABRP section is disabled.

Flavors:
- `github` may show donation links;
- `play` never does, as required by Google Play.

The protocol is documented in [docs/protocol.md](docs/protocol.md).

## Support the project

If EV Remote is useful to you, you can support it on [Ko-fi](https://ko-fi.com/gabibon) or [Buy Me a Coffee](https://buymeacoffee.com/gabibon), also from the **Sponsor** button of the repository or from the app (*Informazioni e licenze*). Please consider supporting the [two projects it is built on](#built-on-the-work-of) too.

## License

[GPL-3.0](LICENSE). Credits and third-party licenses are in [NOTICE](NOTICE).
