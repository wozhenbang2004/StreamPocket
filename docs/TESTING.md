# Testing

## Android checks

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

JUnit 4.13.2, JSON-java 20240303 and Robolectric 4.16.1 are test-only dependencies. Robolectric/native View rendering is not proof of physical-device decoding, account login, installation or complete functional acceptance. No final all-green full regression or end-to-end public-update installation is claimed for this snapshot.

Focused update checks:

```bash
./gradlew testDebugUnitTest \
  --tests dev.streampocket.AppUpdatesTest \
  --tests dev.streampocket.UpdateActivityTest
```

## Python plugins

Use a dedicated virtual environment:

```bash
python3 -m venv .venv
# Activate .venv for your platform, then:
python -m pip install yt-dlp==2026.10.26
python tools/test_douyin_plugin.py
python tools/test_transfer_plugin.py
python tools/test_bili_transfer_plugin.py
python tools/test_metadata_cli.py
```

These tests use synthetic metadata and mocked network methods. Passing them does not prove that a platform will accept a particular account or URL.

## Pure Java rules

Compile app/src/main/java/dev/streampocket/CoreRules.java and app/src/test/java/dev/streampocket/CoreRulesSelfTest.java with javac, then run dev.streampocket.CoreRulesSelfTest. This check does not require the Android SDK.

## Optional isolated device runner

app/src/androidTest/java/dev/streampocket/UiAcceptanceInstrumentation.java is an optional real-media runner. tools/qa-x86.init.gradle targets an x86_64 emulator and adds licensed test-media assets to the test APK only.

**Use only a disposable emulator or dedicated test profile without personal data.** The runner intentionally writes synthetic library/task/preferences records into dev.streampocket.localtest. Never run it on an existing personal library. Its presence is not a claim that device scenarios passed. The isolated emulator build is not an upgrade APK.
