# Contributing to Hanako

Hanako is a small, **fully vibecoded** fork of [Mercurygram](https://github.com/Mercurygram/Mercurygram):
its own changes are written by AI coding agents and mostly not tested by a human before release.
Contributions are welcome, but please keep that in mind when you read the code.

## Before you start

- **Open an issue first** for anything bigger than a typo, so we can agree on the change before you
  spend time on it. Use the [issue forms](https://github.com/guincalo/Hanako/issues/new/choose).
- Only Hanako's own features belong here. Bugs that also happen in upstream Mercurygram or in
  official Telegram should go to [Mercurygram](https://github.com/Mercurygram/Mercurygram/issues)
  or [Telegram](https://github.com/DrKLO/Telegram/issues).

## Pull requests

- Branch off `hanako` and open the PR against `hanako`.
- Keep a PR to one change, and say how you checked it (built it, ran it on a device, or not at all).
- Hanako's features live mostly in `TMessagesProj/src/main/java/it/belloworld/mercurygram/`
  (`Plus*.java`) with their strings in `TMessagesProj/src/main/res/values/plus_*_strings.xml`.
  Prefer new files there over editing upstream Telegram files.
- User-visible changes get a line under `## [Unreleased]` in [CHANGELOG.md](CHANGELOG.md).

## Building

See [Building](README.md#building) in the README. CI builds every push to `hanako`; releases are
described in [RELEASING.md](RELEASING.md).

## License

Hanako is licensed under the [GNU General Public License v2.0](LICENSE). By contributing you agree
that your contribution is released under the same license.
