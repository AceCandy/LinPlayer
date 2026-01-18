# Anime4K shaders (subset)

This directory contains a subset of Anime4K GLSL shaders from the Anime4K
project, used to provide Anime4K preset options inside LinPlayer (libmpv).

- Source: https://github.com/bloc97/Anime4K
- Upstream commit: 7684e9586f8dcc738af08a1cdceb024cc184f426
- License: MIT (see `LICENSE`)

Notes:
- Some shader files are duplicated with a different name (e.g. `_2`) so that a
  single shader pipeline never uses the exact same shader file twice, as
  recommended by Anime4K's GLSL/MPV instructions.
