# EV Remote

[![CI](https://github.com/g-bon/ev-remote/actions/workflows/ci.yml/badge.svg)](https://github.com/g-bon/ev-remote/actions/workflows/ci.yml)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)
[![Ko-fi](https://img.shields.io/badge/Ko--fi-support-FF5E5B?logo=ko-fi&logoColor=white)](https://ko-fi.com/gabibon)
[![Buy Me a Coffee](https://img.shields.io/badge/Buy%20Me%20a%20Coffee-support-FFDD00?logo=buymeacoffee&logoColor=black)](https://buymeacoffee.com/gabibon)

Unofficial Android app to control Stellantis electric cars (Opel, Peugeot, Citroën, DS, Vauxhall) with the e-Remote / Connect services: climate preconditioning, charging, charge limit, status and live data for [ABRP](https://abetterrouteplanner.com).

[Italiano](#italiano) · [Protocol documentation](docs/protocol.md)

> **Disclaimer.** EV Remote is an independent project, not affiliated with, endorsed or sponsored by Stellantis or its brands. It uses the same private back end as the official apps, which can change or block it at any time. Use it at your own risk.

## Features

- Login on the official login page of the brand, in a clean WebView, and one-time activation of remote commands with SMS and PIN.
- **Climate on and off**, with wake-up, confirmation from the car's live events, and automatic retries on communication errors.
- Charging start and stop, and a **charge limit** (default 80%) that stops charging even with the app closed.
- Status: battery, range, charging, cable, climate, outside temperature, locks and open doors, position, odometer, 12 V battery, battery health.
- **ABRP live data**: telemetry on every refresh, and a trip mode that sends data every minute.
- Exportable logs with VIN, customer id and tokens masked, for troubleshooting.
- Tokens and the OTP state are encrypted with a key in the Android Keystore. No servers of its own: the app talks only to Stellantis and, if enabled, to ABRP.

## Limits

- Built for Opel Italy by default. Other brands and countries are a build option (see [Build](#build)) and are untested.
- Each activation of remote commands registers a new OTP "device" on your account. Stellantis limits them (error `NOK:MAXNBTOOLS`) and accepts about 6 OTP codes per day. Avoid reinstalling and activating again without a reason.
- The climate starts only with the car **locked and switched off**, and with at least 20% battery or the car charging.
- On some cars the GPS position and the speed are never updated by Stellantis.

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
./gradlew assembleGithubDebug                                    # Opel Italy
./gradlew assembleGithubDebug -Pevremote.brand=MyPeugeot -Pevremote.country=FR
```

The OAuth client of the official app is **not** in this repository. The build downloads `configs.json` from [homeassistant-stellantis-vehicles](https://github.com/andreadegiovine/homeassistant-stellantis-vehicles) at a pinned commit and checks its SHA-256. You can override any value with environment variables or a git-ignored `android/app/stellantis.properties`.

ABRP needs an API key from Iternio, set as `ABRP_API_KEY`. Without it the ABRP section is disabled.

Flavors:
- `github` may show donation links;
- `play` never does, as required by Google Play.

## Support the project

If EV Remote is useful to you, you can support it on [Ko-fi](https://ko-fi.com/gabibon) or [Buy Me a Coffee](https://buymeacoffee.com/gabibon), also from the **Sponsor** button of the repository or from the app (*Informazioni e licenze*).

## License

[GPL-3.0](LICENSE). Credits and third-party licenses are in [NOTICE](NOTICE): the OTP client is a port of [psa_car_controller](https://github.com/flobz/psa_car_controller), and much of the protocol knowledge comes from [homeassistant-stellantis-vehicles](https://github.com/andreadegiovine/homeassistant-stellantis-vehicles).

---

## Italiano

App Android non ufficiale per le auto elettriche Stellantis (Opel, Peugeot, Citroën, DS, Vauxhall) con i servizi e-Remote / Connect. Gestisce:
- clima a distanza;
- ricarica e limite di ricarica;
- stato dell'auto;
- dati in tempo reale per ABRP.

> **Avvertenza.** EV Remote è un progetto indipendente, non affiliato né approvato da Stellantis o dai suoi marchi. Usa lo stesso back end privato delle app ufficiali, che può cambiare o bloccarlo in qualsiasi momento. Usala a tuo rischio.

**Funzioni:**
- login sulla pagina ufficiale del marchio, e attivazione dei comandi remoti con SMS e PIN, una volta sola;
- **clima** acceso e spento, con sveglia dell'auto, conferma dagli eventi in tempo reale e nuovi tentativi automatici;
- avvio e stop della ricarica, e **limite di ricarica** (80% predefinito) attivo anche ad app chiusa;
- stato completo dell'auto;
- dati in tempo reale per **ABRP**, con modalità viaggio;
- log esportabili con i dati sensibili mascherati.

Token e stato OTP sono cifrati sul telefono, e l'app non ha server propri.

**Da sapere:**
- Ogni attivazione dei comandi remoti registra un "dispositivo" sull'account. Stellantis ne limita il numero e accetta circa 6 codici OTP al giorno.
- Il clima parte solo con l'auto **chiusa e spenta**.
- La build predefinita è per Opel Italia.

**Installazione:** scarica l'APK da [Releases](https://github.com/g-bon/ev-remote/releases), verificalo come descritto sopra e aprilo sul telefono.

**Sostieni il progetto** su [Ko-fi](https://ko-fi.com/gabibon) o [Buy Me a Coffee](https://buymeacoffee.com/gabibon).

Licenza [GPL-3.0](LICENSE), crediti in [NOTICE](NOTICE).
