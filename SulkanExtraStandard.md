# Sulkan Shaderpack Extensions Standard (Draft)
*Supplemental metadata rules for Sulkan shaderpack configuration options.*

---

## 1. Purpose

This document defines additional metadata rules used by Sulkan for shaderpack configuration.
It extends the **Minecraft Vulkan Shaderpack Specification v1.0.0** without modifying its core requirements.

---

## 2. Feature Flags

Shaderpacks **MAY** declare Sulkan-specific feature flags under `[features]`:

```toml
[features]
sulkan_config_options = true
```

### Rules

* If `sulkan_config_options = true`, the shaderpack **MUST** provide an `[options]` table.
* If `sulkan_config_options = false` or is **absent**, the host **MUST** treat configuration options as unsupported.
* Unknown feature keys **MUST** be ignored.

---

## 3. Options Table

When `sulkan_config_options = true`, the shaderpack **MUST** define one or more option entries.

```toml
[options]

[options.quality]
type = "enum"
values = ["low", "medium", "high"]
default = "medium"

[options.enable_reflections]
type = "bool"
default = true
```

### 3.1 Option Entry Format

Each option entry is a table under `[options.<id>]` with the following fields:

| Field   | Required | Description |
| ------- | -------- | ----------- |
| type    | YES      | Option type |
| target  | Conditional | Target shader file for replacement (non-`page`) |
| key     | Conditional | Marker key used in shader files (non-`page`) |
| render_values | Conditional | Replacement values list (non-`page`, required for `enum`) |
| default | YES      | Default value |
| values  | Conditional | Required for `enum` |
| min     | Optional | Minimum value (numeric) |
| max     | Optional | Maximum value (numeric) |
| step    | Optional | Step value (numeric) |
| label_key | Optional | Localization key for display name |
| description_key | Optional | Localization key for description |
| label | Optional | Fallback display name (non-localized) |
| description | Optional | Fallback description (non-localized) |
| options | Conditional | Required for `page` (nested options) |

### 3.2 Supported Types

The following option types are defined:

* `bool`   — Boolean option (`true` / `false`)
* `int`    — Integer option
* `float`  — Floating-point option
* `string` — String option
* `enum`   — Enumerated string option
* `page`   — Nested options page (container, no value)

### 3.3 Replacement Binding

Non-`page` options **MUST** define `target` and `key`. For `enum` options, they **MUST**
also define `render_values` so the host can replace shader files or values dynamically.

* `target` is the shader file path relative to shaderpack root (example: `shaders/gbuffer.glsl`).
* `key` is the marker name to be placed in shader files.
* `render_values` is a string array aligned with `values`. Each entry provides the
  actual replacement value or shader snippet for the corresponding `values` entry.

Special mode: **Direct file replacement**

If `key = "[use_file]"`, the host **MUST** treat each `render_values` entry as a shader
file path (relative to the shaderpack root) and replace the entire `target` file with
the selected file contents. In this mode, marker replacement is **NOT** used.

Marker syntax (in shader files):

```
@SULKAN:<key>@
```

Example usage:

```toml
[options.water_reflection_quality]
type = "enum"
values = ["low", "medium", "high"]
render_values = ["0", "1", "2"]
default = "medium"
target = "shaders/gbuffer.glsl"
key = "water_reflection.quality"
```

```glsl
const int WATER_QUALITY = @SULKAN:water_reflection.quality@;
```

### 3.4 Nested Option Pages

Options can be grouped into nested pages to create hierarchical settings trees.
Pages are declared with `type = "page"` and **MUST** include a child table at `options`.

Example:

```toml
[options]

[options.lighting]
type = "page"
label_key = "sulkan.option.lighting"

[options.lighting.options.water_reflection]
type = "page"
label_key = "sulkan.option.water_reflection"

[options.lighting.options.water_reflection.options.quality]
type = "enum"
values = ["low", "medium", "high"]
render_values = ["0", "1", "2"]
default = "medium"
target = "shaders/gbuffer.glsl"
key = "water_reflection.quality"
label_key = "sulkan.option.water_reflection.quality"
```

Direct file replacement example:

```toml
[options.shadow_quality]
type = "enum"
values = ["low", "high"]
render_values = ["shaders/shadow_low.glsl", "shaders/shadow_high.glsl"]
default = "low"
target = "shaders/shadow.glsl"
key = "[use_file]"
```

Rules:

* `page` entries **MUST** contain `options` with at least one child entry.
* `page` entries **MUST NOT** define `default`, `values`, `min`, `max`, or `step`.
* `page` entries **MUST NOT** define `target`, `key`, or `render_values`.
* Child entries are validated using the same rules as top-level options.
* Nesting depth is unlimited but implementations **SHOULD** avoid overly deep trees.

---

## 4. Validation Rules

If `sulkan_config_options = true`, the host **MUST** validate:

1. `[options]` table exists and contains at least one option entry.
2. Each option entry is a table with:
   * `type` present and supported.
   * `default` present and matching the declared `type`.
3. `enum` options:
   * `values` is a non-empty string array.
   * `default` is one of the declared `values`.
4. `min`, `max`, `step` (if provided) are numeric and type-compatible:
   * `int` requires integer values.
   * `float` allows integer or floating-point values.
5. Non-`page` options:
   * Must include `target` and `key`.
   * `enum` options must include `render_values` with the same length as `values`.
   * Non-`enum` options must not include `render_values`.
6. `page` options:
   * Must include `options` with at least one child entry.
   * Must not include `default`, `values`, `min`, `max`, or `step`.
   * Must not include `target`, `key`, or `render_values`.
   * Children are validated recursively.

If any validation fails, shaderpack loading **MUST** fail.

### 4.1 Localization Warnings

If an option defines `label_key` or `description_key`, the host **SHOULD** attempt to resolve these
keys from the shaderpack `lang/` directory (see Section 5). Missing keys **MAY** produce warnings
but **MUST NOT** fail loading.

---

## 5. Localization (lang Directory)

Shaderpacks **MAY** provide localized strings under a `lang/` directory at the root of the shaderpack:

```
lang/en_us.json
lang/zh_cn.json
```

Each file is a JSON object mapping keys to strings:

```json
{
  "sulkan.option.quality": "Quality",
  "sulkan.option.quality.desc": "Controls overall shader quality."
}
```

### Rules

* If `label_key` or `description_key` is provided, the host **SHOULD** look up the active client locale
  and **SHOULD** fall back to `en_us` if the locale file is missing.
* If no translation is found, the host **MAY** fall back to `label` / `description` if present.
* The `lang/` directory is **optional**. Its absence **MUST NOT** invalidate the shaderpack.

---

## 6. Host UI Behavior

If `sulkan_config_options` is **false** or **absent**, the host **MUST** disable the “Shaderpack Settings” entry in the UI.

---

## 7. Runtime Shader Parameters

Sulkan host **MAY** inject runtime parameters into shader sources using:

```
@SULKAN_PARAM:<key>@
```

The host **MAY** define implementation-specific runtime keys.

Rules:

* Undefined `@SULKAN_PARAM:*@` markers **MAY** remain unchanged.
* Hosts **MAY** add additional keys in future versions.
* Shaderpacks **SHOULD NOT** assume unknown keys are always present.
