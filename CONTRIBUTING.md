# Contributing to the Developer API reference

## Before you start

- Found a mistake in the reference, or an endpoint that behaves differently than documented? Open a
  GitHub issue or a PR directly - both are fine here, unlike the mod repos.
- Questions about using the API: a thread on [sky.melloo.me/community](https://sky.melloo.me/community)
  or GitHub Issues. Prefer to ask privately instead?
  [sky.melloo.me/contact/ask](https://sky.melloo.me/contact/ask).
- Security issues: see [SECURITY.md](https://github.com/SkyMelloo/SkyMelloo/blob/main/SECURITY.md)
  (shared policy across the whole SkyMelloo org) - never a public issue or PR.
- Want to ask me directly? Add me on Discord: **HexedMaya**.

## What belongs here

- [DEVELOPER_API.md](DEVELOPER_API.md) - the reference itself. Keep it in sync with what the live
  API actually does; if you're not sure, open an issue instead of guessing.
- [examples/](examples/) - small, focused, runnable-in-spirit code samples. One concept per example,
  not a full client implementation (that's what [api-client](https://github.com/SkyMelloo/api-client)
  is for).

## Making a change

1. Fork and branch off `main`.
2. Keep additions consistent with the existing doc's structure and tone - see the existing sections
   for style.
3. Open a PR against `main` with a clear description of what changed and why.

## License

By contributing, you agree your changes are licensed under this repo's [MIT license](LICENSE), same
as the rest of the project.
