# Releasing Hanako

Releases are built and published by CI ([`.github/workflows/hanako.yml`](.github/workflows/hanako.yml))
when a version tag is pushed. Nothing is uploaded by hand.

## Checklist

1. Make sure `hanako` contains everything for the release and its last CI build is green.
2. In [CHANGELOG.md](CHANGELOG.md), rename `## [Unreleased]` to `## [X.Y.Z] - YYYY-MM-DD`, add a
   new empty `## [Unreleased]` above it, and update the compare links at the bottom:
   ```
   [Unreleased]: https://github.com/guincalo/Hanako/compare/vX.Y.Z...hanako
   [X.Y.Z]: https://github.com/guincalo/Hanako/compare/vPREVIOUS...vX.Y.Z
   ```
   Commit and push that to `hanako`.
3. Tag the commit with an annotated tag and push **only the tag**:
   ```bash
   git tag -a vX.Y.Z -m "Hanako vX.Y.Z"
   git push origin vX.Y.Z
   ```
4. CI then:
   - checks that the tag looks like `vMAJOR.MINOR.PATCH` (optionally `-pre`) and that CHANGELOG.md
     has a `## [X.Y.Z]` section, and stops before building if not;
   - builds the signed arm64-v8a APK once;
   - publishes a GitHub Release named `🌸 Hanako vX.Y.Z` with the APK, its SHA-256 file and that
     CHANGELOG section as notes. Tags with a `-suffix` are marked as pre-releases.
5. Check the release page and download the APK once to make sure it installs.

The branch push of an already-tagged commit is skipped by CI, so a release is built only once.
If a tag run fails validation, fix CHANGELOG.md, delete the tag (`git push origin :refs/tags/vX.Y.Z`
and `git tag -d vX.Y.Z`) and tag again.
