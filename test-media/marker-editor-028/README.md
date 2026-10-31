# Licensed timestamp-bound test frames

(c) copyright 2008, Blender Foundation / www.bigbuckbunny.org — CC BY 3.0.
Official source: https://download.blender.org/peach/bigbuckbunny_movies/BigBuckBunny_320x180.mp4.zip
License: https://peach.blender.org/about/ and https://creativecommons.org/licenses/by/3.0/.

These PNG derivatives were decoded from the [licensed 20-second excerpt](../README.md).
`ref20-N.png` names the millisecond position N in that excerpt. Extraction command:
`ffmpeg -ss <N/1000> -i bbb-licensed-20s.mp4 -frames:v 1 ref20-N.png`.

They are injected reference stills for automated native-View layout tests only, not
Android decoder, real-phone playback, audio synchronization or export evidence.
They are not application assets or production-user library records.
