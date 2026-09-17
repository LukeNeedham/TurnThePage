# Release and benchmark builds

The app version is defined in `gradle.properties` (`listai.versionName` and
`listai.versionCode`). Release packaging does not change either value.

## Reproducible local commands

From the repository root, with the pinned JDK/Android SDK used by CI:

```sh
./gradlew :app:assembleRelease :app:bundleRelease
./gradlew :app:assembleBenchmark
```

`release` is non-debuggable and is unsigned unless all four external signing
values are provided. `benchmark` is a release-like, non-debuggable,
profileable emulator variant and uses the existing debug signing key; it is
for QA/performance checks and is not a store artifact.

## Optional release signing

Provide a keystore outside the repository using either Gradle properties or
environment variables. Gradle properties take precedence:

| Gradle property | Environment variable |
| --- | --- |
| `listai.release.storeFile` | `LISTAI_RELEASE_STORE_FILE` |
| `listai.release.storePassword` | `LISTAI_RELEASE_STORE_PASSWORD` |
| `listai.release.keyAlias` | `LISTAI_RELEASE_KEY_ALIAS` |
| `listai.release.keyPassword` | `LISTAI_RELEASE_KEY_PASSWORD` |

All four values are required together. For CI, inject them as masked secrets
and write the keystore to a temporary workspace path; do not commit the file,
`keystore.properties`, passwords, or signing keys. Without credentials,
`assembleRelease` and `bundleRelease` remain useful unsigned packaging checks.

Before a store upload, the release owner still needs the production keystore,
alias, passwords, and the service/account credentials required by the chosen
distribution channel.
