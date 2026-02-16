# Minimal Sulkan Pack (Options, segments template)

This template adds Sulkan shaderpack settings on top of the stage-segment template.

Included options:
- `enable_warm`: bool
- `warm_strength`: enum (`low`, `medium`, `high`)

It demonstrates:
- `[features].sulkan_config_options = true`
- marker replacement via `@SULKAN:<key>@`
- `[[segments]]` based pipeline files
