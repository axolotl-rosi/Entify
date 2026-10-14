# Repository Guidelines

## Project Structure & Module Organization

This repository is a single-module Android app in `app/`. Kotlin sources live under `app/src/main/java/com/example/entify_again/` and are split by responsibility: `ui/` for Compose screens and view models, `data/` for Room entities/DAO/repository, `service/` for notification and foreground services, `util/` for preferences and helpers, and `handler/`, `classifier/`, `webview/` for feature-specific logic. Assets used by WebView scraping are in `app/src/main/assets/`. Android resources are in `app/src/main/res/`. Unit tests live in `app/src/test/`; instrumented tests live in `app/src/androidTest/`.

## Build, Test, and Development Commands

- `./gradlew assembleDebug` - build a debug APK.
- `./gradlew installDebug` - install the debug build on a connected device/emulator.
- `./gradlew testDebugUnitTest` - run JVM unit tests in `app/src/test`.
- `./gradlew connectedDebugAndroidTest` - run instrumented tests on a device.
- `./gradlew lintDebug` - run Android lint checks before opening a PR.

Run commands from the repository root. On Windows, use `gradlew.bat` instead.

## Coding Style & Naming Conventions

Use Kotlin with 4-space indentation and standard Android/Kotlin naming. Classes, composables, and view models use `UpperCamelCase` (`SettingsScreen`, `EntryPollingForegroundService`). Functions and properties use `lowerCamelCase`. Keep resource names snake_case (`ic_stat_like.xml`, `activity_main.xml`). Prefer small, focused functions over large inline blocks; move reusable UI into `ui/component/`. Follow existing patterns in `MainActivity.kt` only when a feature is tightly coupled to notification polling.

## Testing Guidelines

Use JUnit tests for pure logic and Android instrumented tests for UI, database, or framework integration. Name test files after the target class, e.g. `NotificationClassifierTest`. Use method names that describe behavior, such as `classify_returnsCommentType_forTemplate1`. Add or update tests when changing classification, persistence, or polling behavior.

## Commit & Pull Request Guidelines

This repository currently has no commit history, so use clear imperative commit messages such as `Add notification memory settings` or `Fix CSRF token overwrite during polling`. Keep commits scoped to one concern. PRs should include: a short problem statement, summary of the fix, test steps or command output, and screenshots for UI changes. Link related issues and mention any runtime or permission-sensitive behavior.

## Security & Configuration Tips

Do not commit real Entry credentials, cookies, or token dumps. Treat `logcat.txt` as local debugging data and sanitize it before sharing. When editing WebView or polling code, verify behavior with notification permission, login state, and foreground-service constraints.
