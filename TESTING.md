# Reliability checks

Run `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug` with the Android Studio JDK. JVM tests cover a full 65-minute PCM stream, preserved chunk boundaries, sparse checkpoints, incomplete provider responses, cancellation, bounded HTTP fallback/quota handling, persisted model selection, and update version/checksum validation.

The device suite intentionally requires a prepared disposable emulator. Install the v1.1.0 APK, create a legacy completed job in `files/transcriptions/upgrade-test/job.json` with `fileName` set to `Upgrade persistence test` and `transcript` set to `saved transcript before update`, then install the new APK with `adb install -r`. Do not uninstall between versions. This checks preservation of saved jobs.

Generate the audio fixture with FFmpeg:

```text
ffmpeg -f lavfi -i sine=frequency=440:sample_rate=44100:duration=3900 -ac 2 -codec:a libmp3lame -b:a 32k test-65min.mp3
```

Push it to `/sdcard/Android/data/com.naylinhtike.smarttranscriber/files/transcriber-65min.mp3`. Allow at least 160 MB of free device storage for the decoded WAVs and fixture. Install `app-debug-androidTest.apk` and run:

```text
adb shell am instrument -w -r com.naylinhtike.smarttranscriber.test/androidx.test.runner.AndroidJUnitRunner
```

The three device tests exercise actual MediaCodec stereo MP3 decoding and Sonic resampling over 65 minutes, an interrupted AtomicFile write, and the legacy-job upgrade. These checks do not establish Burmese/Pali transcription accuracy; that requires an actual sermon and a working Gemini account.
