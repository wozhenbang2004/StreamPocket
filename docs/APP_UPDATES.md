# Application updates · 0.2.7

## User interaction

Settings → **应用更新** is separate from the yt-dlp/download-engine updater. When enabled (default), foreground app opening checks at most once per 24 hours; a new compatible stable version produces an in-app prompt once per version. It is **not OS push**, a background schedule or silent installation. The update page shows version, release notes and download size; tapping Download opens an explicit traffic/download confirmation.

Android DownloadManager owns the transfer and its connectivity/reboot retry behavior. An owned download ID + offer survive process recreation. Reopening the update page or the main app reconciles system status; a finished download is copied to private staging, verified, then offered for system-confirmed installation. Waiting for network, failure, cancellation and rechecking are distinct states. Cancel removes only this update job and its staging/ready files, never video/download-library/account data.

Unverified downloads use `.download` and generic binary MIME, with in-progress-only system notifications, not an APK completion notification that could open the unverified file in an installer. Only the internally verified `app-updates/ready/` directory is exposed by a non-exported FileProvider subclass, with temporary read grants. No media/account/files-root provider path is configured.

On Android26+, the user must permit this source via system settings if needed. Returning from that setting does **not** auto-install: tap Install again. The app rechecks the file and identity, then launches the standard installer, whose signature verification and final user confirmation remain authoritative. Cancelling/declining installation retains the current app and data. No uninstall, downgrade or identity change is requested.

## Publisher/client contract

Repository: `wozhenbang2004/StreamPocket`. Discovery is `GET https://api.github.com/repos/wozhenbang2004/StreamPocket/releases/latest`, anonymous, API version `2026-03-10`. Only a non-draft, non-prerelease with BOTH assets is usable:

- `StreamPocket-arm64-v8a.apk`
- `update-manifest.json`

Tag: `v<version>`. `SHA256SUMS` may also be published for humans; the client uses the manifest and APK identity. JSON fields (all required):

```
schemaVersion: 1
versionCode: Android integer versionCode
versionName: exact APK versionName
packageName: exact installed applicationId (current testing lineage: dev.streampocket.localtest)
abi: arm64-v8a
minSdk: exact APK minimum SDK (currently 24)
apkAssetName: StreamPocket-arm64-v8a.apk
apkSize: exact APK bytes
apkSha256: 64-character SHA-256 hex
signingCertificateSha256: 64-character public certificate SHA-256 hex
```

No usable public APK Release is currently provided. Binary publication remains pending the native dependency corresponding-source and notice requirements. The client does not resolve those obligations.

## Acceptance/security boundaries

- Metadata response maximum 256 KiB; manifest 64 KiB; explicit timeouts/5 redirect ceiling. Metadata redirects remain HTTPS and pinned GitHub/CDN hosts; API/release asset paths are constrained to the intended repository and tag. No auth token, credentials or cookies are supplied. APK transport is Android DownloadManager; its redirects/retries are platform-owned, not claimed to use our metadata redirect policy.
- Manifest size maximum300 MiB; compare it to GitHub asset size and digest when GitHub supplies one. Reject unsupported schema/ABI/minSDK and wrong source URL.
- Before download, compare manifest package and public certificate to the **currently installed app**, and require strictly increasing versionCode. Before installation, independently parse downloaded APK package/version/name/minSDK/certificate and compare to the approved manifest/current app. Multiple/unknown signers are rejected; key rotation is not silently accepted.
- SHA-256 and exact size are checked while copying the downloaded descriptor into private storage. Oversized, truncated, wrong-hash and cancelled copies delete the incomplete staged file. Rehash again immediately before installer launch; only read grants are supplied.
- Android PackageManager archive identity parsing is **not presented as a replacement for full APK cryptographic verification**. The final system installer must verify APK signatures and enforce upgrade rules. A manifest/hash is transport integrity, not independent proof of publisher authorization; matching installed signer + system verifier is the trust boundary.
- Generation guards make cancelled/old async operations unable to publish READY or open the installer after a new user action. Serial worker owns staging. Download IDs use `-1` as absent, since zero must not be assumed invalid. No settings/record schema migration, private-key upload, remote resolver or custom update server.


## Limits

No end-to-end public-release download/install, real-phone unknown-source permission, reboot transfer or system installation is claimed. The system installer must perform its own signature verification and obtain final user confirmation. Older builds without this updater require a manual same-signature upgrade first.
