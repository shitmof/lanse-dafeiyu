# Blue Fat Fish Android

Use this repository as the authority for the Android pet. Do not modify the neighboring 手机APP project.

- Keep the full 106-animation catalog. Preserve upstream code's MIT attribution and the separate noncommercial/attribution terms for assets documented in THIRD_PARTY_NOTICES.md. Pin upstream downloads by commit and verify source hashes.
- Do not add credentials, signing files, APK binaries, generated animations, `.local`, or research downloads to Git.
- Build with JDK 17 / SDK 35. Run Android lint and relevant device acceptance tests for lifecycle, network, or animation changes.
- Test only on a dedicated emulator; instrumentation resets the app's test configuration and chat history.
- Preserve the existing personal release signing key. Do not replace it to bypass a signing failure.
- Report platform restrictions and untested real-account connections accurately. Offline replies are explicitly labeled fixed responses.
- Use Chinese for user-facing documentation and interface text.
