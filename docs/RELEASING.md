# Releasing

Releases are built, signed and published by the **Release** GitHub Actions workflow when a version tag is pushed.

The plugin is listed on JetBrains Marketplace as
[Flawd BPMN Editor](https://plugins.jetbrains.com/plugin/35780-flawd-bpmn-editor), plugin ID `io.github.srozen.flawd`.
The ID can never change.

## Publishing a release

1. In [CHANGELOG.md](../CHANGELOG.md), move the notes under `## [Unreleased]` into a new `## [x.y.z]` section, and set
   `pluginVersion` in `gradle.properties` to match.
2. Commit, then tag and push. The tag must be `v` followed by `pluginVersion`:

   ```bash
   git tag v0.2.0
   git push origin main v0.2.0
   ```

The workflow checks that the tag matches `pluginVersion`, runs the tests, and signs the plugin. It then publishes to
the Marketplace and creates a GitHub release with the signed zip and that version's changelog. JetBrains reviews each
update before it goes live.

A pre-release version such as `0.2.0-beta.1` goes to the Marketplace `beta` channel and becomes a GitHub pre-release.

## Secrets

The workflow reads four repository secrets:

| Secret | What it is |
|---|---|
| `CERTIFICATE_CHAIN` | Plugin signing certificate (`chain.crt`) |
| `PRIVATE_KEY` | Its encrypted private key (`private_encrypted.pem`) |
| `PRIVATE_KEY_PASSWORD` | The key's password |
| `PUBLISH_TOKEN` | Marketplace token from **My Tokens** on your Marketplace profile. Without it, the workflow skips publishing and only creates the GitHub release. |

Every release must be signed with the same key, so keep a backup of the certificate, key and password somewhere safe,
such as a password manager. Never commit them. To store them from local files:

```bash
gh secret set CERTIFICATE_CHAIN --repo srozen/flawd < chain.crt
gh secret set PRIVATE_KEY --repo srozen/flawd < private_encrypted.pem
gh secret set PRIVATE_KEY_PASSWORD --repo srozen/flawd < password.txt
gh secret set PUBLISH_TOKEN --repo srozen/flawd    # paste the token when prompted
```

## Publishing from your machine

Export the same four values as environment variables and run:

```bash
./gradlew publishPlugin
```

`publishPlugin` refuses to run until `pluginVendorEmail` or `pluginVendorUrl` is set in `gradle.properties`.

## Checking compatibility

The **Build** workflow runs JetBrains' Plugin Verifier against the recommended IDE versions on every push. To check
against one installed IDE locally:

```bash
./gradlew verifyPlugin -PverifyLocalIde="/Applications/IntelliJ IDEA.app"
```
