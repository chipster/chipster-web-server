# chipster-web-server

Chipster web app backend.

## Code formatting

The Java code is formatted with the Eclipse formatter, using the settings in
`config/eclipse-formatter.xml`:

```
./gradlew spotlessApply   # format
./gradlew spotlessCheck   # check, run by "./gradlew check" and "./gradlew build" too
```

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
