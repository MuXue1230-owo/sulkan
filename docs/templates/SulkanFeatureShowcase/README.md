# Sulkan Feature Showcase

This is the official showcase shaderpack for the current Sulkan format (`spec_version = "1.0.0"`).

Goals:
- demonstrate the current Sulkan authoring model end-to-end
- act as a regression pack for parser and loader features
- stay version-aligned with Sulkan updates

Coverage in this pack:
- metadata/stages/resources/extensions/features
- runtime/global controls (`[runtime]`, `[global]`)
- custom texture bindings (`[textures]`, stage + sampler)
- nested options (`page`, `bool`, `enum`, `int`, `float`, `string`)
- lightweight option auto extraction (`@sulkan_option`)
- marker replacement (`@SULKAN:<key>@`) and file replacement (`key = "[use_file]"`)
- stage segments (`[[segments]]`) and `index` ordering
- stage and segment params (`@SULKAN_PARAM:<key>@`)
- include system (`#include` relative + absolute, nested include, `#pragma once`)
- world-specific pipeline directories (`pipelines/world-1`, `pipelines/world1`, fallback `pipelines/any`)
- Phase B declaration fields (`drawbuffers`, `rendertargets`, `ping_pong`, `flip`, `compute`, `work_groups`, `images_*`)
- Phase C declaration fields (`program.*.enabled`, `alpha_test`, `blend`, `size.buffer.*`, `scale.*`)
- UI orchestration (`ui.screen`, `ui.sliders`, `ui.profile`, columns)
- ID/layer mappings (`ids.*`, `layer`)
- hot reload and shader export debug switches (`runtime.hot_reload`, `runtime.debug.save_shaders`)

Visible default behavior:
- `gbuffer` stage is enabled and applies a strong terrain tint (`debug_white`) for quick visual confirmation in-world.

Maintenance policy:
- keep this pack valid against the current Sulkan parser and loader
- when a new shaderpack feature is added to Sulkan, add or update one small example in this pack
- bump `shaderpack.version` whenever behavior or format examples are updated
