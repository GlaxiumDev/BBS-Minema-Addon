# BBS Minema (starter)

A companion Fabric mod that records a linearized depth pass alongside BBS mod's
own video export -- the one thing Minema had that BBS mod's already-ported
fixed-timestep/PBO/ffmpeg pipeline doesn't.

## How it works

- `BBSMinema` hooks `WorldRenderEvents.LAST`, watches BBS mod's own
  `VideoRecorder.isRecording()` + `BBSRendering.canRender`, and starts/stops
  in lockstep with it -- no separate UI, no separate keybind, it just rides
  along with BBS mod's existing record button.
- `MinemaRecorder` mirrors `VideoRecorder`'s double-buffered PBO + raw
  ffmpeg pipe approach almost line for line, except it reads
  `GL_DEPTH_COMPONENT`/`GL_FLOAT` instead of `GL_BGR`/`GL_UNSIGNED_BYTE`, and
  linearizes each sample on the CPU before writing it out (raw depth samples
  are non-linear -- without this step you get a white blob, not a depth pass).
- Output lands in the same movies folder as BBS mod's own export, named
  `<timestamp>_depth.mp4`.

## Before this actually works

1. **Get BBS mod building/running locally first** (`./gradlew runClient` in
   the bbs-mod-master source you already have) so you have a jar to link
   against.
2. Point `bbs_mod_version` in `gradle.properties` at whatever's actually on
   Modrinth's maven right now, or switch `build.gradle` to the
   `files('libs/...')` option and drop a locally built jar in `./libs`.
3. **Always build with `./gradlew`, not a bare `gradle` command.** This
   project pins Gradle 8.6 (same as BBS mod itself) via
   `gradle/wrapper/gradle-wrapper.properties`, because Fabric Loom
   `1.6-SNAPSHOT` was built against Gradle 8.6's internal `Problems` API and
   breaks with a `Could not create an instance of type
   LoomProblemReporter` error on other Gradle versions -- including newer
   ones. If you run a system-installed `gradle` instead of `./gradlew`,
   you'll almost certainly hit that error.
   - Linux/macOS: `./gradlew build`
   - Windows: `gradlew.bat build`

## Known rough edges (things to tighten up next)

- **Far plane is guessed** (`far = 512F` default via `setPlanes`). For
  accurate linearization you want the real near/far Minecraft's
  `GameRenderer` is using that frame -- worth pulling via a small mixin if
  you need the depth pass to be metrically correct rather than just
  visually reasonable.
- **CPU-side linearization loop** is a plain Java `for` loop over every
  pixel, every frame. Fine at 1080p30, but at higher res/framerate you'll
  want to move this into a GLSL compute/fragment shader and skip the CPU
  round-trip entirely -- BBS mod already has a shader pipeline you could
  hook into for this.
- **No independent toggle.** Right now depth recording is 1:1 with BBS
  mod's own recording state. If you want to export depth only sometimes,
  add a keybind/setting and gate `recordingNow` on that too.
- Only tested for architecture soundness against BBS mod 1.7.7 (MC 1.20.4,
  Yarn 1.20.4+build.1) -- if you're on Iris/Sodium with shaders enabled,
  double check the depth attachment is what you expect (Iris sometimes
  swaps framebuffers).
