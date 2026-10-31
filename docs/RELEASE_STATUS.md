# Release status

Source version: **0.2.7-test / versionCode 13**. Normal builds target ARM64, Android 7.0+; the debug application ID is dev.streampocket.localtest.

## Source-only publication

This repository provides application source, tests, build/use instructions, license notices and permitted test media. It does not distribute an APK, native dependency binaries, signing credentials or private development history.

## Binary distribution

Public APK distribution remains pending. The prebuilt native dependencies fetched by youtubedl-android 0.18.1 include GPL-covered components. Complete exact source versions, downstream patches, build scripts and license/copyright coverage have not been established for those binaries. Official artifact provenance and the application license alone do not close these obligations.

Before publishing an APK, provide the required corresponding source and notices for every covered component, verify them against the final APK, and publish the versioned binary and source materials together with clear access directions. See [THIRD_PARTY_NOTICES](../THIRD_PARTY_NOTICES.md).

## Application updates

The source implements an opt-out foreground check and user-confirmed download/system-installer launch. There is currently no usable public APK Release. The release-to-install path has not been validated end to end. See [APP_UPDATES](APP_UPDATES.md). Do not interpret the implemented client as a promise that online update delivery is available.
