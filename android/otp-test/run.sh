#!/usr/bin/env bash
# Checks the Java OTP client against the original Python implementation (same random numbers, fake inWebo server).
# Requirements: python3 and a JDK (javac/java on PATH or in JAVA_HOME). Exit code 1 if anything differs.
set -euo pipefail
cd "$(dirname "$0")"
export OTP_TEST_OUT="${OTP_TEST_OUT:-$(mktemp -d)}"
mkdir -p "$OTP_TEST_OUT"

PY="${OTP_TEST_PYTHON:-}"
if [ -z "$PY" ]; then
  python3 -m venv "$OTP_TEST_OUT/venv"
  "$OTP_TEST_OUT/venv/bin/pip" -q install "pycryptodome==3.*" "requests<3"
  PY="$OTP_TEST_OUT/venv/bin/python"
fi
JBIN="${JAVA_HOME:+$JAVA_HOME/bin/}"

"$PY" reference.py
"${JBIN}javac" -nowarn -d "$OTP_TEST_OUT/classes" \
  ../app/src/main/java/it/gbon/evremote/OtpCrypto.java ../app/src/main/java/it/gbon/evremote/Otp.java \
  it/gbon/evremote/OtpHarness.java
"${JBIN}java" -cp "$OTP_TEST_OUT/classes" it.gbon.evremote.OtpHarness

"$PY" - <<'PY'
import os, sys
from urllib.parse import urlsplit, parse_qsl
out = os.environ['OTP_TEST_OUT']
p = open(os.path.join(out, 'python_urls.txt')).read().split()
j = open(os.path.join(out, 'java_urls.txt')).read().split()
q = lambda u: dict(parse_qsl(urlsplit(u).query, keep_blank_values=True))
same = len(p) == len(j) and all((a == b) if not a.startswith('http') else q(a) == q(b) for a, b in zip(p, j))
print('OTP port: identical' if same else 'OTP port: DIFFERENT')
sys.exit(0 if same else 1)
PY
