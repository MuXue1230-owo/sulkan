# Sulkan 阶段骨架（segments 体系）

这份文档给出六阶段的可复制骨架，全部基于 `[[segments]]`。

## 1. 规则速览

1. 阶段文件固定：`shadow/gbuffer/lighting/translucent/postprocess/final.toml`。
2. 每个阶段至少一个 `[[segments]]`。
3. `index` 越小执行越早，同阶段唯一。
4. 首段 `inputs` 固定，末段 `outputs` 固定。
5. 中间段输入必须由前序分段输出提供，且 `Type:name` 完全一致。
6. `name` 仅用于分段标识与调试，不参与请求匹配；阶段声明后即整阶段重写。
7. 阶段默认目标已覆盖六阶段：`shadow/gbuffer/lighting/translucent/postprocess/final`。

## 2. 单分段骨架（可直接复制）

### `pipelines/any/shadow.toml`

```toml
[[segments]]
name = "shadow_stub"
index = 0
inputs = ["Buffer:scene_geometry", "Buffer:light_params"]
outputs = ["DepthTexture:shadow_map"]
vertex = "shaders/mypack/stub.vsh"
fragment = "shaders/mypack/stub.fsh"
```

### `pipelines/any/gbuffer.toml`

```toml
[[segments]]
name = "terrain"
index = 0
inputs = ["Buffer:scene_geometry", "Buffer:material_params", "Buffer:camera_matrices"]
outputs = ["Texture2D:gbuffer_albedo", "Texture2D:gbuffer_normal", "DepthTexture:gbuffer_depth"]
vertex = "shaders/mypack/stub.vsh"
fragment = "shaders/mypack/stub.fsh"
```

### `pipelines/any/lighting.toml`

```toml
[[segments]]
name = "lighting_stub"
index = 0
inputs = ["Texture2D:gbuffer_albedo", "Texture2D:gbuffer_normal", "DepthTexture:gbuffer_depth", "DepthTexture:shadow_map"]
outputs = ["Texture2D:lit_color"]
fragment = "shaders/mypack/stub.fsh"
```

### `pipelines/any/translucent.toml`

```toml
[[segments]]
name = "translucent_stub"
index = 0
inputs = ["Texture2D:lit_color", "DepthTexture:gbuffer_depth", "Buffer:translucent_geometry"]
outputs = ["Texture2D:scene_color"]
vertex = "shaders/mypack/stub.vsh"
```

### `pipelines/any/postprocess.toml`

```toml
[[segments]]
name = "blit"
index = 0
inputs = ["Texture2D:scene_color", "DepthTexture:scene_depth"]
outputs = ["Texture2D:post_color", "DepthTexture:post_depth"]
fragment = "shaders/mypack/post_tint.fsh"
```

### `pipelines/any/final.toml`

```toml
[[segments]]
name = "screenquad"
index = 0
inputs = ["Texture2D:post_color", "DepthTexture:post_depth", "MotionTexture:motion_vectors"]
outputs = ["Texture2D:final_color", "DepthTexture:final_depth", "MotionTexture:final_motion"]
vertex = "shaders/mypack/stub.vsh"
```

## 3. 多分段示例（以 postprocess 为例）

```toml
[[segments]]
name = "pp_prepare"
index = 0
inputs = ["Texture2D:scene_color", "DepthTexture:scene_depth"]
outputs = ["Texture2D:pp_color", "DepthTexture:pp_depth"]
fragment = "shaders/mypack/pp_prepare.fsh"

[[segments]]
name = "blit"
index = 10
inputs = ["Texture2D:pp_color", "DepthTexture:pp_depth"]
outputs = ["Texture2D:post_color", "DepthTexture:post_depth"]
fragment = "shaders/mypack/post_tint.fsh"
```

要点：

1. 首段吃固定输入，末段吐固定输出。
2. 中间资源 `pp_color/pp_depth` 的类型与名称必须前后完全一致。
3. 分段执行顺序只看 `index`，名称可自由定义。

## 4. Phase B 示例（MRT / Ping-Pong / Compute / Image）

```toml
[[segments]]
name = "post_mrt"
index = 20
inputs = ["Texture2D:pp_color", "DepthTexture:pp_depth"]
outputs = ["Texture2D:post_color", "DepthTexture:post_depth"]
fragment = "shaders/mypack/post_mrt.fsh"
drawbuffers = "013"
ping_pong = "main"

[segments.flip]
colortex1 = true

[[segments]]
name = "post_compute"
index = 30
inputs = ["Texture2D:post_color", "DepthTexture:post_depth"]
outputs = ["Texture2D:post_color", "DepthTexture:post_depth"]
compute = "shaders/mypack/post_histogram.csh"
work_groups = [16, 16, 1]
images_read = ["colorimg0"]
images_write = ["colorimg1", "shadowcolorimg0"]
```

说明：

1. `drawbuffers`/`rendertargets` 支持 `colortex0..15` 与 Sulkan 自定义 target 名（不支持 `gcolor/gdepth` 别名）。
2. `work_groups` 与 `work_groups_render` 需要 `compute`，且不能放在 `gbuffer` 阶段。
3. `images_read/images_write` 只允许 `colorimg0..5` 与 `shadowcolorimg0..1`。

## 5. Phase C 示例（Enabled / Alpha / Blend / Size / Scale）

```toml
[program.post_compute]
enabled = "post.enable && !debug.force_off"

[[segments]]
name = "post_color_grade"
index = 0
inputs = ["Texture2D:scene_color", "DepthTexture:scene_depth"]
outputs = ["Texture2D:post_color", "DepthTexture:post_depth"]
fragment = "shaders/mypack/post_color_grade.fsh"
alpha_test = "greater 0.001"
blend = "src_alpha one_minus_src_alpha one zero"

[segments.size.buffer]
colortex0 = [1920, 1080]

[segments.scale]
colortex1 = [0.5, 0.5]
```

说明：

1. `enabled` 支持布尔与表达式（`!`、`&&`、`||`、`==`、`!=`、括号）。
2. `alpha_test` 建议使用 `off` 或 `<func> <ref>`。
3. `blend` 支持 `off`、`src dst`、`src dst srcA dstA`。
4. `size/scale` 目前会影响 compute image 的实际分配尺寸。
