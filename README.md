# chipster-web-server

Chipster web app backend.

## Code formatting

The Java code is formatted with the Eclipse formatter, using the settings in
`config/eclipse-formatter.xml`:

```
./gradlew spotlessApply   # format
./gradlew spotlessCheck   # check, run by "./gradlew check" and "./gradlew build" too
```

The "Java format" workflow in `.github/workflows/java-format.yml` runs
`spotlessCheck` on pull requests and on master, when the Java code, the
formatter settings or the Gradle build changes.

The settings are the "Eclipse [built-in]" profile, except that the indentation
uses 4 spaces instead of tabs, comments are not formatted, and lines that are
wrapped already are not joined.

To format on save in VS Code, install the "Language Support for Java" extension
of Red Hat (`redhat.java`), which uses the same formatter, and add these to
`.vscode/settings.json`. The Java extension of Oracle uses a different
formatter, which doesn't read this file.

```json
"java.format.settings.url": "config/eclipse-formatter.xml",
"java.format.settings.profile": "chipster",
"[java]": {
  "editor.defaultFormatter": "redhat.java",
  "editor.formatOnSave": true,
  "editor.insertSpaces": true,
  "editor.tabSize": 4
}
```

These settings are read from the `.vscode` directory of the folder that is
opened in VS Code. When you open the parent directory of chipster-web-server
instead, put them in the `.vscode/settings.json` of that directory, with
`"java.format.settings.url": "chipster-web-server/config/eclipse-formatter.xml"`.
A relative path is resolved against the opened folder.

In Eclipse, import the same file as the formatter profile. IntelliJ can import
it as a code style too, but it formats with its own formatter, so the result
can differ a little from `spotlessCheck`.

### Pre-commit hook

A pre-commit hook checks the staged Java files with Spotless, and the staged
files of `js/type-service` with Prettier and ESLint, and refuses the commit if
any are unformatted or have lint errors. It checks the staged content, not the
working tree, and skips the checks whose files the commit doesn't touch. The
Java check runs Gradle, so it needs a JDK, also when committing from a GUI
client that doesn't get the `JAVA_HOME` or `PATH` of your shell.

The Gradle build (any task that compiles the Java code) and `npm install` in
`js/type-service` both activate the hook by pointing `core.hooksPath` at
`.githooks`, with `.githooks/install`. It does nothing when `core.hooksPath`
is set already, or when the default hooks directory `.git/hooks` has hooks of
your own, because git would stop running them. It's a shell script, so on
Windows the build doesn't run it, and `npm install` only when `sh` is on the
`PATH`. In those cases, turn the
hook on yourself with `git config core.hooksPath .githooks`, or run
`.githooks/pre-commit` from a pre-commit hook of your own.

Skip the hook for one commit with `git commit --no-verify`, or turn it off in
your clone with:

```
git config --unset core.hooksPath
git config chipster.installHooks false
```

## Tests

The Java tests are split in two with the JUnit tag `@Tag("integration")`:

```
./gradlew test               # all tests
./gradlew unitTest           # unit tests, need nothing else
./gradlew integrationTest    # integration tests
```

The integration tests need something outside this repository: the ones that
use `TestServerLauncher` need a running backend, and `ToolboxLoadTest` needs
the chipster-tools repository checked out next to this one. Untagged tests
are unit tests, so tag a new test that needs either of those. `./gradlew
check` and `./gradlew build` run `test`, so they need the backend too.

The "Unit test" workflow in `.github/workflows/unit-test.yml` runs `unitTest`
on pull requests and on master, when the Java code, its resources, the Gradle
build or `security/users` changes. The integration tests are not run in
GitHub Actions.
