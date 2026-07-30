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
2. Bump `version` on `LetterboxDatabase` and add the `Migration` to
   `data/Migrations.kt`. Push; CI commits the new JSON.
3. Once that JSON has landed, add a case to `LetterboxDatabaseMigrationTest`.

## Regenerating

Normally you do not: the `Export Room Schemas` workflow runs on every push to
`main`, generates the JSON, and commits it back. Because that commit arrives
*after* yours, a migration test for the new version can only be added in a
follow-up change — `MigrationTestHelper` reads these files from the instrumented
APK's assets, so the JSON has to be committed before the test can load it.

To generate locally you need a real Android SDK, since this runs Room's
annotation processor:

```sh
./gradlew :app:kspProdDebugKotlin --no-daemon
```

`./gradlew :app:test` and `:app:assembleProdDebug` also produce it as a side
effect.
