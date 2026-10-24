# Releasing

Releases go to Maven Central through the Central Portal, from GitHub Actions. Pushing a `v*` tag runs [`.github/workflows/release.yml`](.github/workflows/release.yml), which signs every artifact, uploads the bundle and waits for the Portal to validate it. Publishing stays a manual click (`autoPublish=false`); after it, a version can never be changed or removed.

## One-time setup

1. The `io.github.hozdemir` namespace is verified on [central.sonatype.com](https://central.sonatype.com). It covers every group id below it.
2. The GPG key's public part is on `keyserver.ubuntu.com`, where Central looks it up:
   ```bash
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
   ```
3. Repository secrets (Settings > Secrets and variables > Actions). Run these in a real terminal, not through a tool without a TTY, or `gh` stores an empty value:
   ```bash
   gh secret set CENTRAL_USERNAME   # Portal > View Account > Generate User Token
   gh secret set CENTRAL_PASSWORD
   gh secret set GPG_PASSPHRASE
   gpg --armor --export-secret-keys <KEY_ID> | gh secret set GPG_PRIVATE_KEY
   ```

If `gpg` fails with `Inappropriate ioctl for device`, it cannot reach a terminal to ask for the passphrase:

```bash
export GPG_TTY=$(tty)
gpg-connect-agent updatestartuptty /bye
```

## Before a release

Run the workflow by hand (Actions > Release > Run workflow). This dry run imports the key, signs and bundles everything, and checks a signature against the key on the keyserver, but uploads nothing. A wrong `GPG_PRIVATE_KEY` or `GPG_PASSPHRASE` fails here. The Central token is only exercised by a real release.

## Release

1. Make sure `main` is green and the README's version matches the one you are about to release.
2. Tag and push; the tag decides the version, the POMs on `main` keep their `-SNAPSHOT`:
   ```bash
   git tag -a v0.2.0 -m "v0.2.0"
   git push origin v0.2.0
   ```
3. When the workflow reports `Deployment ... has been validated`, open [Publishing > Deployments](https://central.sonatype.com/publishing/deployments), check the files and press **Publish**. **Drop** discards it instead.
4. Artifacts reach `repo1.maven.org` within minutes; search indexes take hours.

## After a release

1. Create the GitHub release from the tag with short notes.
2. Move `main` to the next snapshot:
   ```bash
   ./mvnw versions:set -DnewVersion=0.3.0-SNAPSHOT -DgenerateBackupPoms=false -DprocessAllModules=true
   ```
3. Update the version in the README's installation snippets.
