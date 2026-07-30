# Room exported schemas

Room writes one JSON per database version here, driven by `exportSchema = true`
on `LetterboxDatabase` and the `room.schemaLocation` KSP argument in
`app/build.gradle.kts`. **These files are committed deliberately.**

## Why they must be committed before a schema change

`MigrationTestHelper` creates a database at a starting version by executing the
SQL in that version's JSON. So testing a migration from N to N+1 requires the
JSON for **N**, captured while the code still described version N. Regenerating
after the change produces the JSON for N+1 and the predecessor is gone for good.

The database falls back to `fallbackToDestructiveMigration()`, so a schema change
shipped without a working `Migration` deletes every user's cached email. An
untested migration is therefore a silent-data-loss bug, and the exported schema
is what makes it testable.

## Changing an `@Entity`

1. Confirm the JSON for the current version is already committed here.
2. Bump `version` on `LetterboxDatabase` and write the `Migration`.
3. Add a case to `LetterboxDatabaseMigrationTest` covering it.
4. Build, then commit the newly generated JSON together with the change.

## Regenerating

Generation needs Room's annotation processor, so it requires a real Android SDK:

```sh
./gradlew :app:kspProdDebugKotlin --no-daemon
```

`./gradlew :app:test` and `:app:assembleProdDebug` also produce it as a
side effect.
