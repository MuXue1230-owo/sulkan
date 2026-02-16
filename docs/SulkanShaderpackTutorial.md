# Sulkan 光影包编写教程（分段体系）

本文对应 Sulkan 当前实现（`spec_version = "1.0.0"`），只讲 Sulkan 自有体系，不涉及 OptiFine 兼容。

## 1. 核心设计

1. 固定 6 个渲染阶段，且阶段文件名固定：
   - `shadow.toml`
   - `gbuffer.toml`
   - `lighting.toml`
   - `translucent.toml`
   - `postprocess.toml`
   - `final.toml`
2. 每个阶段文件使用 `[[segments]]` 定义“渲染阶段分段”。
3. 分段按 `index` 升序执行，`index` 越小越靠前。
4. 第一个分段输入固定，最后一个分段输出固定；中间分段通过 `inputs/outputs` 传参，类型和名称必须一致。
5. 旧格式 `[[programs]]` / `shader_type` 已废弃，不再解析。

## 2. 目录结构

```text
MyFirstSulkanPack/
  shaderpack.toml
  pipelines/
    any/
      shadow.toml
      gbuffer.toml
      lighting.toml
      translucent.toml
      postprocess.toml
      final.toml
  shaders/
    mypack/
      stub.vsh
      stub.fsh
      post_tint.fsh
```

`any` 是兜底 world，建议必须保留。

## 3. `shaderpack.toml` 最小示例

```toml
[shaderpack]
name = "My First Sulkan Pack"
version = "0.1.0"
spec_version = "1.0.0"

[stages]
shadow = false
gbuffer = false
lighting = false
translucent = false
postprocess = true
final = false

[features]
sulkan_config_options = false
```

## 3.1 `shaderpack.toml` 扩展示例（当前实现）

完整示例见：`docs/templates/SulkanFeatureShowcase/shaderpack.toml`。  
该示例补齐了以下接口：

1. `[runtime]` / `[global]`：全局运行时控制（例如 `hot_reload`、`debug.save_shaders`）。
2. `[textures]`：按 `stage + sampler` 绑定自定义纹理源（`resource:` / `dynamic:` / `shaderpack:`）。
3. `features.auto_extract_options = true`：启用轻量自动选项提取（`@sulkan_option`）。
4. `[ui]`：`screen` / `screen_columns` / `sliders` / `profile` 编排。
5. `[ids]` 与 `[layer]`：ID 映射和渲染层映射。

`@sulkan_option` 最小示例（写在 shader 注释中）：

```glsl
// @sulkan_option path=debug.auto_extracted type=bool default=true key=debug.auto_extract target=shaders/mypack/post.fsh
```

## 4. `[[segments]]` 语法

```toml
[[segments]]
name = "blit"
index = 0
inputs = ["Texture2D:scene_color", "DepthTexture:scene_depth"]
outputs = ["Texture2D:post_color", "DepthTexture:post_depth"]
fragment = "shaders/mypack/post_tint.fsh"

[segments.params]
TINT = "1.05"
```

字段说明：

1. `name`：分段名（光影包自定义，仅用于标识与调试，不参与请求匹配）。
2. `index`：执行顺序（非负整数，且同文件内唯一）。
3. `inputs/outputs`：`Type:name` 数组，表示分段参数接口。
4. `vertex/fragment/geometry/compute/config`：至少提供一项。
5. `[segments.params]`：可选，用于 `@SULKAN_PARAM:<key>@` 替换。
6. `drawbuffers` 或 `rendertargets`：可选，声明颜色输出目标（`colortex0..15` 或 Sulkan 自定义 target 名，不支持 `gcolor/gdepth/...` 别名）。
7. `ping_pong`：可选，`main` 或 `alt`。
8. `[segments.flip]`：可选，声明翻转目标（例如 `colortex0 = true`）。
9. `work_groups` / `work_groups_render`：可选，计算分发尺寸（需要 `compute`）。
10. `images_read` / `images_write`：可选，声明 image load/store 绑定（`colorimg0..5`、`shadowcolorimg0..1`）。
11. `enabled`：可选，程序启停表达式（也支持阶段级 `[program.<segment_name>].enabled`）。
12. `alpha_test` / `blend`：可选，程序级渲染状态声明（当前主要以 `SULKAN_*` 接口暴露）。
13. `[segments.size.buffer]`：可选，程序级附件固定尺寸（`[width, height]`）。
14. `[segments.scale]`：可选，程序级附件缩放（`[sx, sy]`）。

`#include` 规则（当前实现）：

1. 支持 `#include "relative/path.glsl"` 与 `#include <absolute/path.glsl>`。
2. 支持尾部 `//` 注释（例如 `#include "a.glsl" // note`）。
3. 路径会做规范化，支持 `./`、`../`。
4. 支持 `#pragma once`（同一文件重复 include 会被跳过）。
5. 递归深度上限为 16，超限会记录警告。

Phase B 接口规则（当前实现）：

1. `drawbuffers` 可用 `"046"`、`"0,4,6"` 或数组；`rendertargets` 可用字符串/字符串数组，目标支持 `colortex0..15` 与自定义名称。
2. 如果同时声明 `drawbuffers` 与 `rendertargets`，两者解析结果必须一致。
3. `compute` 不允许用于 `gbuffer` 阶段。
4. `work_groups` / `work_groups_render` 必须是 3 个正整数，且要求同分段定义了 `compute`。
5. `images_read` / `images_write` 仅接受 `colorimg0..5` 与 `shadowcolorimg0..1`。
6. `compute` 分段会在每帧末端按分段顺序 `dispatch`，并把 `images_*` 绑定到 Sulkan 内部 storage image。

Phase C 接口规则（当前实现）：

1. `enabled` 支持 `true/false`、标识符、`!`、`&&`、`||`、`==`、`!=`、括号表达式。
2. 允许阶段级声明 `[program.<segment_name>].enabled = "<expr>"`，分段内 `enabled` 优先级更高。
3. `alpha_test` 支持 `off`、`<ref>`、`<func> <ref>`（例如 `greater 0.1`）。
4. `blend` 支持 `off`、`src dst`、`src dst srcA dstA`。
5. `size/scale` 声明会影响 compute image 分配尺寸，键名使用 render target（如 `colortex1`）。
6. Shader 会自动注入 `SULKAN_*` 标准宏（含 `SULKAN_RENDER_STAGE_*`、最小 attribute/uniform 宏、alpha/blend/size/scale 宏）。

匹配规则：

1. 不再按 `selectors` 或 `[segments.files]` 匹配单个文件。
2. 只要该阶段被声明并启用，即由 shaderpack 对该阶段进行整阶段重写。

当前默认目标：

1. `shadow` -> `shaders/basic/terrain_earlyZ/*`
2. `gbuffer` -> `shaders/basic/terrain/*`
3. `lighting` -> `shaders/basic/clouds/*`
4. `translucent` -> `shaders/core/rendertype_item_entity_translucent_cull/*`
5. `postprocess` -> `shaders/basic/blit/*`
6. `final` -> `shaders/core/screenquad/*`、`shaders/core/animate_sprite/*`、`shaders/core/animate_sprite_blit*`

注意：

- `name` 只是分段标识，建议按语义命名，便于调试。

## 5. 固定阶段接口表

下表是当前实现中每个阶段的固定首尾接口。

| 阶段 | 第一个分段固定输入 (`inputs`) | 最后一个分段固定输出 (`outputs`) |
| --- | --- | --- |
| `shadow` | `Buffer:scene_geometry`, `Buffer:light_params` | `DepthTexture:shadow_map` |
| `gbuffer` | `Buffer:scene_geometry`, `Buffer:material_params`, `Buffer:camera_matrices` | `Texture2D:gbuffer_albedo`, `Texture2D:gbuffer_normal`, `DepthTexture:gbuffer_depth` |
| `lighting` | `Texture2D:gbuffer_albedo`, `Texture2D:gbuffer_normal`, `DepthTexture:gbuffer_depth`, `DepthTexture:shadow_map` | `Texture2D:lit_color` |
| `translucent` | `Texture2D:lit_color`, `DepthTexture:gbuffer_depth`, `Buffer:translucent_geometry` | `Texture2D:scene_color` |
| `postprocess` | `Texture2D:scene_color`, `DepthTexture:scene_depth` | `Texture2D:post_color`, `DepthTexture:post_depth` |
| `final` | `Texture2D:post_color`, `DepthTexture:post_depth`, `MotionTexture:motion_vectors` | `Texture2D:final_color`, `DepthTexture:final_depth`, `MotionTexture:final_motion` |

## 6. 校验规则（当前实现）

1. 每个阶段文件至少有一个 `[[segments]]`。
2. `index` 必须唯一且 `>= 0`。
3. 第一个分段 `inputs` 必须与该阶段固定输入完全一致。
4. 最后一个分段 `outputs` 必须与该阶段固定输出完全一致。
5. 从第二个分段开始，`inputs` 必须由前面分段 `outputs` 提供，且 `Type:name` 一致。
6. 同名资源在后续分段不能改变类型。
7. 资源类型必须在支持集合内：`Texture2D`、`Texture3D`、`TextureCube`、`Buffer`、`DepthTexture`、`MotionTexture`。

## 7. 推荐使用方法

1. 先做单分段版本：每个阶段只有一个 `index=0` 分段，先跑通校验。
2. 优先在 `postprocess` 做可见效果（例如暖色调），方便确认“有生效”。
3. 需要多段时再拆分：`index=0/10/20...`，并显式声明中间段 `inputs/outputs`。
4. 打开 F3 观察 Sulkan 调试项：
   - `Pipeline lookup`
   - `Pipeline resolve (segment hits)`
   - `Pipeline world chain`

## 8. 排错清单

1. 是否还在使用旧的 `[[programs]]` / `shader_type`。
2. 已启用阶段是否存在对应的 `pipelines/any/<stage>.toml` 文件。
3. 每个阶段文件是否至少有一个 `[[segments]]`。
4. 首段输入 / 末段输出是否严格等于固定接口表。
5. 中间段输入是否能在前序分段输出中找到同名同类型。
6. 分段 `index` 是否唯一且顺序符合预期。

## 9. 可复制模板

- `docs/templates/MinimalSulkanPack`
- `docs/templates/MinimalSulkanPackOptions`
- `docs/templates/SulkanFeatureShowcase`（官方示范与回归测试包）

进阶骨架：`docs/SulkanStageScaffold.md`
