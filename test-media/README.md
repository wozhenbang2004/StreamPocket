# Licensed test media (not production APK content)

Official source: https://download.blender.org/peach/bigbuckbunny_movies/BigBuckBunny_320x180.mp4.zip
Official download page: https://peach.blender.org/download/
License: https://peach.blender.org/about/ — Creative Commons Attribution 3.0 (https://creativecommons.org/licenses/by/3.0/).
Attribution: (c) copyright 2008, Blender Foundation / www.bigbuckbunny.org

`bbb-licensed-20s.mp4` is a stream-copy QA excerpt starting around 60 s, requested duration 20 s, from the official 320×180 H.264/AAC video. `bbb-frame-5s.png` is a frame decoded by ffmpeg from the excerpt at 5 s. These are real licensed media, not UI placeholders. This material must remain test-only, not inserted into production users' libraries.

SHA-256:
- bbb-licensed-20s.mp4 (2,216,913 bytes): e6fa30bb74073423c83b4cd9b88bd6630a31e69cb19ed6dbb9f8ff50328f3a28
- bbb-frame-5s.png (130,446 bytes): 51fe6afa903f3a64714a025506c44fe927e0db2cf997410845b6cea07bcb0cc6

The full movie and original download archive are deliberately not duplicated here.

`bbb-portrait-crop-20s.mp4` is a declared portrait derivative of the same licensed excerpt, center-cropped to 102×180 then scaled to 180×320, H.264/AAC. It is not represented as footage originally shot vertically; it supplies an actual decodable portrait-aspect media surface for fit/seek tests. Generated using ffmpeg `crop=102:180:109:0,scale=180:320`, libx264 CRF22. Instrumentation-only assets; not bundled in the application APK.

The separately released music score has different licensing terms; do not extract and redistribute that separate release under this movie attribution.
