# Contributing to Bubble Night

Thanks for your interest in improving Bubble Night. Bug reports, translations, accessibility feedback and pull requests are all welcome.

## Reporting bugs

Please open an issue with:

- what you did, what you expected and what happened instead;
- the game version (shown at the end of **Settings**), your device model and Android version;
- for crashes, the file from **Settings → Export diagnostics**. It holds only the stack trace and basic device information; read it before attaching.

## Development setup

1. Install a JDK (17 or newer; the project is built with JDK 21) and the Android SDK with platform `android-37.2`.
2. Point Gradle at them:

   ```bash
   export JAVA_HOME=/path/to/jdk
   export ANDROID_HOME=/path/to/android-sdk
   ```

3. Build and test:

   ```bash
   ./gradlew :core:test :leveltool:test :app:testDebugUnitTest
   ./gradlew :app:assembleDebug
   ```

   Or run everything the CI runs, including lint and certificate replay:

   ```bash
   bash tools/verify.sh
   ```

No emulator is needed. Game rules belong in `:core` and are tested on the JVM; the UI is checked on a real device.

## Pull requests

- Create a branch from `main` and keep each pull request focused on one change.
- Make sure `bash tools/verify.sh` passes and say in the description how you tested the change, including which device you used for UI changes.
- Add or update unit tests when you change rules, persistence or content tooling.
- Keep commits small, with messages in the imperative mood ("Add left-handed layout").

## Coding conventions

- Everything in the code is in English: identifiers, comments, log and error messages.
- Every text the player sees goes in `app/src/main/res/values/strings.xml` (English), with a translation in each `values-<lang>/strings.xml`. Do not hardcode visible text in Kotlin.
- Match the style of the surrounding code. Comments explain *why*, not *what*.
- `:core` must stay free of Android, clock and platform-random dependencies. Use the versioned `SplitMix64` and canonical state hashing so results are reproducible.
- The game, the solver and the certificate checker share `:core`. Never copy engine logic into another module or language.
- Any change to the engine's observable behavior needs a new `engineVersion` and regenerated certificates.

## Changing level content

Levels in `content/` are certified, and the build replays every certificate. When you edit a level:

1. Edit the source JSON (or the template plus `tools/author_orbit01.py` for orbit 1), then raise the level's `revision`.
2. Run `solve` for the affected levels, then `verify-certificates` and `validate --strict`.
3. Commit the level, the updated `content/catalog.json` hash and the new certificates together.

[docs/CONTENT.md](docs/CONTENT.md) describes the format and the full pipeline.

## Translations

To add a language, copy `app/src/main/res/values/strings.xml` to `values-<code>/strings.xml`, translate every `<string>`, `<plurals>` and `<string-array>`, and leave `app_name` out. Include the two orbit arrays (`orbit_names` and `orbit_one_level_titles`) and check the result on a device, because some labels are tight on small screens.

## License

By contributing, you agree that your contributions are licensed under the [MIT License](LICENSE).
