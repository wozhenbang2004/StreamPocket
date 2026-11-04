#!/usr/bin/env bash
# Isolated CI emulator only; these x86_64 APKs are never release artifacts.
set -euo pipefail

qa_serial="emulator-${EMULATOR_PORT:-5554}"
qa_output="app/build/reports/emulator-acceptance"
mkdir -p "$qa_output"

capture_evidence() {
  adb -s "$qa_serial" logcat -d > "$qa_output/logcat.txt" 2>&1 || true
  adb -s "$qa_serial" shell dumpsys audio > "$qa_output/audio-focus.txt" 2>&1 || true
  adb -s "$qa_serial" exec-out run-as dev.streampocket.localtest \
    tar -C files/qa-captures -cf - upgrade050 startup051 > "$qa_output/captures.tar" 2> "$qa_output/capture-errors.txt" || true
  if [ -s "$qa_output/captures.tar" ]; then
    tar -xf "$qa_output/captures.tar" -C "$qa_output" || true
  fi
}
trap capture_evidence EXIT

adb -s "$qa_serial" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$qa_serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# Acknowledge the emulator's one-time system navigation tutorial before UI capture.
adb -s "$qa_serial" shell settings put secure immersive_mode_confirmations confirmed
adb -s "$qa_serial" logcat -c
timeout 240s adb -s "$qa_serial" shell am instrument -w -r -e scenario upgrade050 \
  dev.streampocket.localtest.test/dev.streampocket.UiAcceptanceInstrumentation \
  | tee "$qa_output/instrumentation.txt"

python3 - "$qa_output/instrumentation.txt" <<'PY'
from pathlib import Path
import sys
result = Path(sys.argv[1]).read_text()
if 'PASS: upgrade050;' not in result or 'INSTRUMENTATION_CODE: -1' not in result:
    raise SystemExit('Android media/PiP acceptance did not report success; inspect the uploaded evidence.')
print('Real Android media/PiP acceptance passed.')
PY

# Start the second scenario in a fresh process of this CI emulator's target app.
adb -s "$qa_serial" shell am force-stop dev.streampocket.localtest
timeout 90s adb -s "$qa_serial" shell am instrument -w -r -e scenario startup051 \
  dev.streampocket.localtest.test/dev.streampocket.UiAcceptanceInstrumentation \
  | tee "$qa_output/startup-instrumentation.txt"

python3 - "$qa_output/startup-instrumentation.txt" <<'PY'
from pathlib import Path
import sys
result = Path(sys.argv[1]).read_text()
if 'PASS: startup051;' not in result or 'INSTRUMENTATION_CODE: -1' not in result:
    raise SystemExit('Android startup-update acceptance did not report success; inspect the uploaded evidence.')
print('Real Android startup-update lifecycle and switch acceptance passed; metadata transport was a fixture.')
PY
