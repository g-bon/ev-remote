# Security policy

## Reporting a vulnerability

Please **do not open a public issue** for security problems. Use GitHub's private vulnerability reporting instead: *Security* tab, then *Report a vulnerability*.

You should get an answer within a week. Fixes are released as a new version, with credit to the reporter unless you prefer otherwise.

## Scope

In scope:
- the app;
- its build and release pipeline;
- how it stores and transmits account data: OAuth and MQTT tokens, the OTP state, the PIN, the ABRP token.

Out of scope: the Stellantis, inWebo and ABRP servers. Report problems there to their owners.

## Never share

When you open an issue, never paste:
- your password, PIN or SMS codes;
- OAuth or MQTT tokens;
- your full VIN.

The "Export logs" function already masks the VIN, the customer id and the tokens. Still, read the text before sharing it.
