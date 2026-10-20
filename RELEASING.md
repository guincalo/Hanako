# Releasing Hanako

Hanako 2.x is built from the `hanako-classic` branch by
[`.github/workflows/hanako-classic.yml`](.github/workflows/hanako-classic.yml). Tags follow
[LRS version numbering](https://git.coom.tech/drummyfish/less_retarded_wiki/src/branch/master/version_numbering.md):
`vMAJOR.MINOR`, with a `d` suffix for development builds, which are published as pre-releases.

1. Make sure the last CI build of `hanako-classic` is green.
2. In [CHANGELOG.md](CHANGELOG.md), move the `## [Unreleased]` notes into `## [X.Y] - YYYY-MM-DD`
   (or `[X.Yd]`) and push.
3. Tag that commit and push only the tag:
   ```bash
   git tag -a vX.Y -m "Hanako vX.Y"
   git push origin vX.Y
   ```
   CI builds the arm64-v8a APK for the tag, signed with the private Hanako key (secrets
   `HANAKO_KEYSTORE_*`; a tag build fails without them) and uploads it as the `hanako-classic-arm64`
   workflow artifact, named `Hanako-vX.Y-arm64-v8a.apk` with its `.sha256`.
4. Create the GitHub release from that artifact:
   ```bash
   gh run download <run-id> -n hanako-classic-arm64 -D dist
   gh release create vX.Y dist/* --title "Hanako vX.Y" --notes-file notes.md   # add --prerelease for d builds
   ```
5. Check the release page and install the APK once.

Hanako 1.x (Mercurygram based) was released from the `hanako` branch by `hanako.yml`; that branch is
no longer developed.
