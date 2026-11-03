# Flawd BPMN Editor

Source: https://github.com/srozen/flawd

An IntelliJ Platform plugin for viewing and editing BPMN 2.0 diagrams (`.bpmn`, `.bpmn2`, `*.bpmn20.xml`).
The diagram is the [bpmn-js](https://bpmn.io) modeler running in the IDE's embedded Chromium (JCEF).

- **Diagram / Split / Text** views (toolbar toggle at the top right of the editor), like the Markdown editor
- **Two-way sync**: diagram edits are written into the XML document, and text edits re-render the diagram
- **Properties panel** for names, IDs, conditions, documentation and so on (toggle in the editor toolbar)
- **IDE undo/redo** covers diagram edits. Each diagram change is one undoable command, and only the changed range of the XML is replaced.
- In split view, selecting an element moves the text caret to its XML
- Follows the IDE's light/dark theme; **Export as SVG** from the editor toolbar
- **File | New | BPMN Diagram** creates a starter process

## Project layout

```
build.gradle.kts           IntelliJ Platform Gradle Plugin 2.x build; also runs the web build
web/                       bpmn-js editor page (esbuild bundles it into one self-contained index.html)
  src/main.js              modeler setup + the JS side of the IDE bridge
  src/editor.css           layout and the dark theme (overrides bpmn-io's --bio-* tokens)
src/main/kotlin/dev/flawd/bpmn/
  BpmnEditorProvider.kt    file editor provider, split editor, toolbar actions, settings
  BpmnDiagramEditor.kt     JCEF browser, document <-> diagram sync, theme, SVG export
  BpmnFiles.kt             which files are BPMN, file icon
  NewBpmnFileAction.kt     File | New | BPMN Diagram
src/main/resources/META-INF/plugin.xml
samples/order-fulfillment.bpmn
CHANGELOG.md               release notes; the current version's section becomes the Marketplace "What's New"
```

## Building and running

Requirements: JDK 25 (the JBR bundled with IntelliJ 2026.2 works) and Node.js/npm (for the web bundle).

```bash
./gradlew runIde          # start a sandbox IDE with the plugin installed
./gradlew buildPlugin     # build/distributions/bpmn-editor-<version>.zip, installable via Settings | Plugins | Install from Disk
./gradlew test
```

By default Gradle downloads IntelliJ IDEA `platformVersion` (see `gradle.properties`). To build against an
installed IDE instead, which is much faster, add this to `~/.gradle/gradle.properties`:

```properties
platformLocalPath=/Users/you/Applications/IntelliJ IDEA.app
```

If Gradle can't find `npm` (e.g. when it runs from inside the IDE without your shell's PATH), set
`npmCommand=/absolute/path/to/npm` the same way.

### Working on the web editor

`./gradlew buildWeb` writes `build/generated/web/bpmn-editor/index.html`. The page works in a normal browser too:
serve that folder and call `bpmnEditor.init({ xml })` from the console. Messages that would go to the IDE are logged
to the console instead. When it runs inside the IDE, you can open DevTools from the browser's context menu if
`ide.browser.jcef.contextMenu.devTools.enabled` is turned on in the Registry.

## Publishing to JetBrains Marketplace

### Before the first release

1. **Name and ID:** the plugin is "Flawd BPMN Editor" with ID `io.github.srozen.flawd` (`<id>` in `plugin.xml`).
   The ID can never change once published. The Kotlin package `dev.flawd.bpmn` is independent of it.
2. **Vendor details:** `pluginVendorName`, `pluginVendorEmail` and `pluginVendorUrl` in `gradle.properties` must
   match your vendor profile on plugins.jetbrains.com. They're shown publicly, and `publishPlugin` refuses to run
   without an email or URL.
3. **Signing certificate:** create it once and keep it safe. Every release must be signed with the same key.

   ```bash
   openssl genpkey -aes-256-cbc -algorithm RSA -out private_encrypted.pem -pkeyopt rsa_keygen_bits:4096
   openssl rsa -in private_encrypted.pem -out private.pem
   openssl req -key private.pem -new -x509 -days 3650 -out chain.crt
   ```

   Never commit these files.
4. **First upload, done by hand:** sign in at plugins.jetbrains.com, then build a signed zip:

   ```bash
   export CERTIFICATE_CHAIN="$(cat chain.crt)" PRIVATE_KEY="$(cat private_encrypted.pem)" PRIVATE_KEY_PASSWORD=...
   ./gradlew signPlugin
   ```

   Upload `build/distributions/bpmn-editor-<version>-signed.zip` with **Upload plugin**, choose the MIT license,
   and add screenshots. JetBrains reviews new plugins by hand before they're listed.
5. **Publishing token:** create one under your Marketplace profile's **My Tokens** for the automated releases below.

### Every later release

1. Move the `[Unreleased]` notes in `CHANGELOG.md` under a new version heading and bump `pluginVersion`.
   A pre-release version such as `0.2.0-beta.1` publishes to the `beta` channel instead of the default one.
2. Run the checks. `verifyPlugin` runs JetBrains' Plugin Verifier against the recommended IDE versions, which
   downloads several IDEs; add `-PverifyLocalIde=/path/to/IDE.app` to check against one installed IDE instead.

   ```bash
   ./gradlew test verifyPlugin
   ```
3. Publish. This runs the vendor check, builds, signs and uploads:

   ```bash
   export PUBLISH_TOKEN=... CERTIFICATE_CHAIN="$(cat chain.crt)" PRIVATE_KEY="$(cat private_encrypted.pem)" PRIVATE_KEY_PASSWORD=...
   ./gradlew publishPlugin
   ```

### Licensing

The plugin is MIT licensed (`LICENSE`, also bundled as `META-INF/LICENSE`). The web build generates
`THIRD-PARTY-NOTICES.txt` from every npm package that ends up in the bundle and ships it inside the plugin.
bpmn-js's license additionally requires the **bpmn.io watermark** on the canvas to stay fully visible and never
overlapped, so keep any new overlay below its `z-index` of 100 (see the note in `web/src/editor.css`).

## How the sync works

The XML document is the source of truth.

| Direction | Mechanism |
|---|---|
| IDE → diagram | A document listener debounces for 250 ms, then calls `bpmnEditor.importXml(xml)`. The viewport is preserved. |
| diagram → IDE | `commandStack.changed` is debounced for 120 ms, then `saveXML` runs and the result goes through `JBCefJSQuery` (`changed\n<xml>`). The IDE applies it in a `WriteCommandAction` that replaces only the differing range. |
| undo/redo | bpmn-js's undo/redo actions are rerouted to the IDE's `UndoManager`, which reverts the document; the diagram then re-imports it. |
| invalid XML | The diagram shows the parse error and is locked, so it can't overwrite your text, until the XML parses again. |

## Known limitations

- The first diagram edit re-serializes the whole file the way bpmn-js formats it (attribute order, indentation),
  so files written by other tools get a one-time formatting diff.
- Vendor extensions (Camunda, Flowable, Zeebe) round-trip as unknown XML. Their dedicated properties aren't shown
  in the panel. Adding `camunda-bpmn-moddle` or `zeebe-bpmn-moddle` plus the matching properties providers in
  `web/src/main.js` would add them.
- It needs an IDE runtime with JCEF; without JCEF only the text view is available.
