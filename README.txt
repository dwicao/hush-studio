VOX STUDIO — Android prototype, version 0.1.12

Offline English narration and reference-conditioned voice cloning using
OpenBMB VoxCPM-0.5B and the community VoxCPM.cpp runtime.

INSTALL / FIRST USE
1. Install VoxStudio-0.1.12-auto-arm64-debug.apk on phones supporting ARM dot-product
   and FP16 instructions (verified on Moto G71). Use the compat APK for older
   ARM64 phones. The fast build checks CPU support before loading inference.
   Android may ask you to allow installation from your chosen file manager.
2. Open the Settings tab. Download the model (499,832,224 bytes),
   or import the exact voxcpm-0.5b-q4_k.gguf file listed below.
   Allow at least 750 MB of free space for installation and speech-model setup,
   plus space for projects. The APK now includes a ~41 MB English ASR archive
   (Vosk small English 0.15); first use extracts ~71 MB into private storage.
3. In Voices, tap New voice, then record or import a clean 3–5 second sample (up to 20 seconds).
   Use the single Import audio button for MP3, M4A/AAC, WAV and other audio
   supported by the phone's Android codecs. Format is detected from content and
   the selected clip is converted locally to mono 16 kHz PCM.
   Duration is read before decoding; the full recording is no longer converted.
   Sources up to 15 minutes / 100 MiB can be sampled: choose a range or use
   Split into 20s clips. Unreadable/protected/unsupported files are rejected.
   Only the selected clip becomes the draft reference.
4. Give the voice a name. Extract its transcript offline and review the words.
   Imported clips and successful foreground recordings can auto-extract words.
   The draft saves automatically, but is separate from your saved voices.
5. Tap the fixed bottom Clone voice button. This saves a reusable named voice
   profile (reference audio plus transcript) locally. No per-voice model training
   occurs; profiles reuse the shared VoxCPM model when generating speech.
   Existing saved voices are independent of subsequent draft changes/imports.
6. Your voices lists saved profiles with sample duration and selection state.
   Tap Listen to preview, Use voice to open Create, or the overflow menu to
   rename/delete. An unfinished draft can be resumed when adding another voice. Use
   Create's voice chooser to switch between profiles and the default Model voice.
7. Choose the voice, enter your script, select quality, and tap Generate narration.
   A project name is optional; a blank name uses the first script words.
   Quality changes update in place without rebuilding the editor. Readiness
   hints explain disabled primary actions. Script drafts still save automatically.
   Fast preview uses 4 steps; Balanced 6 (default); Best quality 10.
   Progress shows stages, elapsed time and provisional seconds generated.
8. Completed projects appear in Library with duration, voice, creation date and
   a script preview. Search titles, voice names or script words; the overflow
   menu offers Rename, Export WAV and confirmed deletion. Tap Listen to use the full-screen
   player: real waveform, seek slider, elapsed/total time, play/pause, ten-second
   skips, playback speed (0.75–2x), and full/section switching. Tap a script
   section to listen or regenerate it (confirmation). Export WAV is pinned at
   the bottom and uses Android's file picker. Each project keeps its own reference snapshot,
   so deleting a voice does not remove or alter previous narrations.

Named voices stay in app-private storage on this phone. This is not a separate
trained model file or a voice export package. Uninstalling deletes saved voices
and narration projects unless you export the audio. Multiple voices are supported;
voice names must be unique (case-insensitive). Existing 0.1.4 reference/transcript
are retained as an unsaved draft until you tap Clone voice.

DEVICE TARGET
Android 9+ (API 28), ARM64 only. Initial tests include a 6 GB-class phone. Limited checks on a Moto G71 5G (Android 12, SM6375) are documented in
VERIFICATION.txt. Broader device compatibility, thermal throttling, battery use
and voice quality remain unverified. There is no
cloud inference fallback. This is a debug-signed prototype, not a Play Store
release or a claim of production readiness.

IMPLEMENTED
- Four consistent tabs: Create, Voices, Library and Settings.
- Compact Create form, guided voice setup, named-voice management, searchable
  library with timestamps/durations/previews, and separate everyday/advanced
  settings. Scroll position is retained per tab; supported keyboard insets hide
  the bottom tab bar while typing. New tasks bring progress into view.
- Session task errors stay visible with Dismiss until cleared or a new task starts.
  Settings separates one-time setup, defaults, privacy and advanced CPU controls.
  Model re-download and deletions require explicit confirmation in the app.
- Fixed-height multiline text fields with internal vertical scrolling. Swipes
  inside long text scroll the editor; the outer page takes over at its edges.
  Script/transcript drafts persist across scrolling and app updates.
- A reference/target text boundary guard prevents the final reference word
  from concatenating with the first script word in the pinned runtime.
- A real JNI bridge to the upstream C++ CPU inference pipeline; no stock
  Android TTS substitute, mock audio, or server-side inference.
- One-time HTTPS model download and exact SHA-256 verification; local import.
- Multiple named saved voices with immutable reference/transcript snapshots.
- A separate autosaved preparation draft; explicit Clone voice saves a profile.
- Saved-voice chooser in Create and Use this voice / sample playback / deletion
  from Your voices. Completed projects retain their own copies.
- Offline English speech recognition from the selected WAV/reference. Vosk
  Android 0.3.75 + JNA 5.18.1; vosk-model-small-en-us-0.15 bundled and
  SHA-256 verified before first-use extraction. Recognition progress and
  cancellation between chunks. Model is released before TTS starts.
- Auto-extract on audio import when enabled and no transcript was typed;
  extract after successful foreground recording; editable result and retry.
  Failed recognition keeps the audio and any existing transcript.
- Script drafts persist automatically. Scripts are limited to 6,000 characters
  and split at sentences / 220-character bounds to limit each request.
- Single-worker generation with a foreground notification and partial wakelock.
- Each completed project keeps its own voice/reference snapshot for regeneration.
- Dedicated narration/sample player with waveform, scrubbing, elapsed/total
  time, play/pause, +/-10 seconds, playback speed, and section navigation.
- Section playback/regeneration, 250 ms gaps, native 16 kHz mono PCM WAV export.
- Playback pauses on app backgrounding, audio-focus loss, or headset unplug.
  Player closes on activity recreation; background audio service/lock-screen
  controls are not implemented. Playback speed changes listening only, not WAV
  export or synthesis settings. Waveform reads PCM in bounded chunks.
- Fast preview uses 4 diffusion steps; Balanced 6; Best quality 10. Audition output.
- Accelerated ARM CPU kernels and selectable 2/4 inference threads in Setup.
  Two threads are the default for the fast APK based on Moto G71 measurements.
- Bulk PCM I/O reduces WAV conversion/export overhead.
- Background work finishes while the app is not visible. Recording stops when
  the activity leaves the foreground. Stop requests take effect at native
  stage/decode-frame boundaries after the current operation finishes. A large
  reference preparation operation may still take time before it can stop.
- Cancelling a new project discards its incomplete sections. Existing completed
  library projects remain available. Process death interrupts the current task;
  a new launch removes incomplete projects. There is no crash-resume queue.

PRIVACY
Scripts, recordings, and audio are processed locally and kept in app-private
storage unless exported. Speech recognition also runs on-device using the bundled English model.
The only app network request is the one-time TTS model download from Hugging Face and its storage redirects. Backups and device transfer
are excluded. Uninstalling the app deletes its library and voice reference.
Export important projects before uninstalling. No telemetry or account login.

MODEL / REPRODUCIBILITY
Runtime: https://github.com/bluryar/VoxCPM.cpp
Pinned commit: e5b89534e379c79b923c9969b79c3858d140bedb
Model source: https://huggingface.co/bluryar/VoxCPM-GGUF
Pinned revision: 7a16c13bad65c5505e38df153d69e02f96eb3960
Filename: voxcpm-0.5b-q4_k.gguf
SHA-256: 32fa909cc3c127bfa1003b0863f7b7587bb44e3ae26c6c1cbf224f58d70a9fb3
URL:
https://huggingface.co/bluryar/VoxCPM-GGUF/resolve/7a16c13bad65c5505e38df153d69e02f96eb3960/voxcpm-0.5b-q4_k.gguf?download=true
The model is downloaded separately; it is not bundled inside the APK/source ZIP.
Only this exact Q4 model is accepted in this first prototype. More model/quality
options should be gated by measured device performance.

BUILD
Open this folder in Android Studio, or use the Gradle wrapper:
  ./gradlew assembleDebug lintDebug testDebugUnitTest
For the accelerated APK, add -PmobileFast=true to the same build command.
Default builds produce the compatibility APK. Both variants share app id and
version code; install one variant at a time.
Requires JDK 17+ (build verified with JDK 22), Android SDK platform 36,
NDK 28.2.13676358, CMake 3.22.1, and network for Gradle dependencies.
Set sdk.dir in your own local.properties or use ANDROID_HOME.
The C++ runtime and JSON header are vendored; inference sources are unchanged.
Our CMake adapter builds the CPU-only library using baseline ARMv8 by default, or
ARMv8.2 dot-product + FP16 with -PmobileFast=true, without
OpenMP/CUDA/Vulkan/Metal. C and C++ kernels use -O3 even in the debug build. Native library uses 16 KB page alignment.
A thin JNI adapter reuses the upstream CLI's generation pipeline in-process,
serializes requests, and turns its errors into app messages. The JNI adapter reports loading, reference encoding, prefill, completed decode
frames and waveform decoding. The app keeps elapsed time ticking during long
operations and reports provisional generated speech duration. It does not
estimate percentage of time remaining or ETA. The model reloads
for each section in this first version. Persistent model loading/reference
feature caching are opportunities for later performance work.

DEVICE TEST
After installing/importing the model in the app:
  ./gradlew connectedDebugAndroidTest
NativeSmokeTest checks JNI generation, short synthetic reference cloning,
repeatable narration benchmarking and cancellation at a decoder boundary.
Benchmark arguments: benchThreads and benchSteps; seed 4242 is test-only.
This test requires a connected ARM64 Android device/emulator; host unit tests
cover WAV parsing, reference conversion, invalid inputs, section assembly and
progress timing/milestones.
See VERIFICATION.txt for checks actually completed in this environment.

LIMITATIONS / NEXT WORK
- Broader device tests, accessibility review, sustained performance and peak-RAM measurement.
- Reduce long-reference encoding/prefill cost: a 20-second reference was too slow
  in the on-phone check. Prefer a short, accurately transcribed reference.
- Listening tests comparing Q4 vs Q8 and cloning similarity to the reference.
- In-process native abort/OOM can terminate the app; consider a separate worker
  process and stronger memory admission checks before wider distribution.
- English-only app; write numbers/abbreviations as spoken words. No automatic
  text normalization, pronunciation dictionary or emotion control. ASR can
  miss words and technical names; review it before cloning.
- No timeline editing or video muxing. Saved voices use reference conditioning;
  each is a reusable profile, not separately fine-tuned model weights.
- Native 16 kHz output: resampling cannot add missing audio detail.
- A reference-conditioned clone is not a separately trained professional voice.
- Release signing, distribution, broad compatibility and accessibility review
  remain before a public launch. No app has been published.

CREDITS / LICENSES
Original model: https://github.com/OpenBMB/VoxCPM (Apache-2.0).
Community runtime: vendor/voxcpm/LICENSE and vendor/voxcpm/NOTICE.
GGML: vendor/voxcpm/third_party/ggml/LICENSE (MIT).
miniaudio: license text at the end of vendor/voxcpm/third_party/miniaudio/miniaudio.h.
nlohmann/json v3.11.3: vendor/JSON-LICENSE.txt (MIT).
Additional upstream licenses/notices remain in the vendored tree.
Compiled-app license notices are in Settings -> About Vox Studio -> Licenses.

SPEECH MODEL REPRODUCIBILITY
URL: https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip
Bytes: 41205931
SHA-256: 30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498
Asset: app/src/main/assets/speech-model.zip (bundled in APK/source ZIP).
Vosk and the English model are Apache-2.0. JNA is used under Apache-2.0.
Notices are in app assets and vendor/VOSK-LICENSE.txt / JNA-LICENSE.txt.
NativeSmokeTest checks TTS; SpeechRecognitionTest checks the saved English
reference and a 5-second recognized reference through the cloning pipeline.

Speed update: identical narration sections reuse a bounded 32 MiB private audio
cache keyed by text, transcript, reference contents, model/build and settings.
Explicit section Regenerate always synthesizes a fresh take. New text still
uses the existing CPU inference engine; no GPU speedup is claimed.

0.1.12 includes automatic GPU attempt and protected CPU fallback. The Moto G71
currently fails full GPU qualification and runs CPU after its first failed
attempt. See GPU-EXPERIMENT.txt for exact limits and two-engine build steps.
