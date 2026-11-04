# Contributing

Thanks for helping! Issues and pull requests are welcome, in English or Italian.

## Before you start

- Remote commands use real cars and limited resources: OTP codes are about 6 per day, and each activation registers a device on the account. Test on your own car, and do not repeat activations needlessly.
- Never commit credentials. `stellantis.properties`, keystores and `.env` files are git-ignored, and CI runs gitleaks on every push.

## Development

```bash
cd android
./gradlew assembleGithubDebug lint
./otp-test/run.sh          # checks the Java OTP port against the Python reference
```

- Code style: plain Java 17 without AndroidX, like the existing code. Keep dependencies to a minimum: every new dependency needs a license compatible with GPL-3.0, listed in `NOTICE`.
- Changes to `Otp.java` or `OtpCrypto.java` must keep `otp-test` passing.
- Describe user-visible changes in `CHANGELOG.md`.

## Pull requests

- One topic per pull request, with a short description of what changed and how you tested it.
- CI must pass: build, lint, OTP test, gitleaks, CodeQL.
- By contributing you agree that your contribution is licensed under GPL-3.0.
