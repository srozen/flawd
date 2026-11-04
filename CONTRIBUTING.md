# Contributing to Flawd BPMN Editor

Thanks for helping out! Bug reports and feature ideas go in [GitHub issues](https://github.com/srozen/flawd/issues).
For code changes, open a pull request against `main`. The **Build** workflow runs the tests and JetBrains' Plugin
Verifier on every pull request.

## Prerequisites

- **JDK 25.** The JetBrains Runtime bundled with IntelliJ IDEA 2026.2 works:
  `export JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"` on macOS.
- **Node.js 24 and npm** for the web editor bundle. Gradle runs npm for you.

## Building and running

```bash
./gradlew runIde          # start a sandbox IDE with the plugin installed
./gradlew test            # platform tests (headless)
./gradlew buildPlugin     # build/distributions/bpmn-editor-<version>.zip
```

The `samples/` folder has BPMN files to try in the sandbox: `./gradlew runIde --args="$PWD/samples"`.

By default Gradle downloads the IntelliJ IDEA version set as `platformVersion` in `gradle.properties`. To build against
an IDE you already have installed, which is much faster, add it to `~/.gradle/gradle.properties`:

```properties
platformLocalPath=/Applications/IntelliJ IDEA.app
```

If Gradle can't find `npm`, for example when it runs from inside the IDE without your shell's `PATH`, set
`npmCommand=/absolute/path/to/npm` there as well.

## Project layout

```
src/main/kotlin/dev/flawd/bpmn/
  BpmnEditorProvider.kt    split editor and its provider (saves the layout per file), toolbar actions, settings
  BpmnDiagramEditor.kt     JCEF browser, document <-> diagram sync, theme, SVG export
  BpmnFiles.kt             which files are BPMN, file icon, BPMN namespaces for the XML view
  NewBpmnFileAction.kt     File | New | BPMN Diagram
src/main/resources/META-INF/plugin.xml
web/                       the diagram editor page: bpmn-js, bundled by esbuild into one self-contained index.html
  src/main.js              modeler setup and the JavaScript side of the IDE bridge
  src/editor.css           layout and the dark theme (overrides bpmn.io's --bio-* tokens)
  build.mjs                bundling, plus THIRD-PARTY-NOTICES.txt for every bundled npm package
src/test/                  platform tests and their test data
samples/                   BPMN files for trying the plugin
.github/workflows/         Build (tests, Plugin Verifier) and Release (sign, publish, GitHub release)
```

## How the editor works

The plugin opens BPMN files in a `TextEditorWithPreview`: the regular XML text editor next to a JCEF browser running
bpmn-js. The XML document is the source of truth, and the browser talks to the IDE through a `JBCefJSQuery` bridge.

| Direction | Mechanism |
|---|---|
| IDE → diagram | A document listener waits 250 ms after the last change, then calls `bpmnEditor.importXml(xml)`. The viewport is kept. |
| Diagram → IDE | bpmn-js's `commandStack.changed` is debounced by 120 ms, then `saveXML` runs and the result goes over the bridge. The IDE applies it in a `WriteCommandAction` that replaces only the range that differs. |
| Undo and redo | bpmn-js's undo and redo actions are rerouted to the IDE's `UndoManager`. It reverts the document, and the diagram re-imports it. |
| Invalid XML | The diagram shows the parse error and locks until the XML parses again, so it can't overwrite the text. |
| Hidden diagram | In Text view the canvas has no size and bpmn-js can't render. Imports wait until the diagram is shown. |

### Working on the web editor

`./gradlew buildWeb` writes `build/generated/web/bpmn-editor/index.html`. The page also works in a normal browser:
serve that folder and call `bpmnEditor.init({ xml })` from the console. Messages meant for the IDE are logged to the
console instead. Inside the IDE you can open Chrome DevTools from the diagram's context menu after enabling
`ide.browser.jcef.contextMenu.devTools.enabled` in the Registry.

### The bpmn.io watermark

bpmn-js's license requires the bpmn.io watermark in the corner of the canvas to stay fully visible and never be
overlapped. Keep any new overlay below the watermark's `z-index` of 100; see the note in `web/src/editor.css`.

## Changelog

Add a line under `## [Unreleased]` in [CHANGELOG.md](CHANGELOG.md) for any change users will notice. That section
becomes the release notes on the Marketplace and on GitHub.
