# Release status — 0.2.9 test build

Application source: **0.2.9-test / versionCode 15**. ARM64, Android 7.0+,
application ID dev.streampocket.localtest. The maintainer package retains the
existing testing certificate and is debuggable. It is not a production-stability
or physical-device acceptance claim.

## Versioned assets and source access

The [v0.2.9-test Release](https://github.com/wozhenbang2004/StreamPocket/releases/tag/v0.2.9-test) is the intended common location for:

- [StreamPocket-arm64-v8a.apk](https://github.com/wozhenbang2004/StreamPocket/releases/download/v0.2.9-test/StreamPocket-arm64-v8a.apk)
- update-manifest.json (exact APK identity, size and SHA256)
- [StreamPocket-0.2.9-third-party-sources-arm64-20261031.zip](https://github.com/wozhenbang2004/StreamPocket/releases/download/v0.2.9-test/StreamPocket-0.2.9-third-party-sources-arm64-20261031.zip)

These links become usable only when the assets are actually published. A source
commit or tag alone does not provide an installable update. The GitHub Source code
archive for that tag supplies this sanitized application source; the separate
third-party source archive supplies native dependency source/build/notice material.
Keep both source access directions beside the binary. No signing private key,
personal account data, or private development history is included.

## Native material scope

The APK retains all eight official youtubedl-android 0.18.1 ARM64 entries, matched
to upstream commit d725d5c9a18c3a99a13ee0308bf78275dc310760. It does not contain
the separate experimental replacement builds. The companion archive includes
identified upstream sources, the fixed Termux tree
3f525a6a33d63029ef954cd3eb469a35062994f2, source hashes, patches, wrapper sources,
license notices, captured configuration and build/archive-assembly instructions.
Its map covers 272 ELF occurrences across 74 package families; COVERAGE.json
records no concrete missing identified source archive or package recipe.

The known wrapper-specific Python change to Lib/tempfile.py is included as an
exact patch. Observed binary version/configuration evidence remains separate from
recipe version metadata: a family mapping is not a claim that every library embeds
a complete package revision. No native recompilation or identical-hash rebuild is
claimed. See the archive's README.md, BUILDING.md, SOURCES.json, SOURCE-MAP.json,
PAYLOADS.json, ARCHIVE-MEMBERS.json, licenses/ and SHA256SUMS.

## Updates and verification limits

The updater requires the same package/certificate, a strictly increasing code,
and both fixed assets on a non-draft/non-prerelease Release returned by latest.
This GitHub channel flag is necessary for client discovery; it does not turn a
test APK into a physically validated stable product. Devices on code14 can be
offered code15; a device already on code15 receives no same-code update.

Final APK signature/ZIP, packaged source identity and unchanged native entries
were checked. Focused application checks are not a full regression. Real-phone
upgrade/data retention, MediaCodec performance, actual clip export/audio sync,
and end-to-end public-release download/system installation remain unverified.
See [TESTING](TESTING.md), [APP_UPDATES](APP_UPDATES.md) and
[THIRD_PARTY_NOTICES](../THIRD_PARTY_NOTICES.md).
