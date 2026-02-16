# Minecraft Vulkan Shaderpack Specification Version 1.0.0
*A community-driven rendering pipeline configuration standard for Minecraft Vulkan rendering engines*

---

## Table of Contents

1. [Introduction](#1-introduction)
2. [Conventions and Terminology](#2-conventions-and-terminology)
3. [Architecture Overview](#3-architecture-overview)
4. [Execution Model](#4-execution-model)
5. [Pipeline Stages](#5-pipeline-stages)
6. [Resource Model](#6-resource-model)
7. [Metadata and Configuration](#7-metadata-and-configuration)
8. [Security and Isolation](#8-security-and-isolation)
9. [Conformance Requirements](#9-conformance-requirements)
10. [Extension Mechanism](#10-extension-mechanism)

---

# 1. Introduction

## 1.1 Purpose

This document defines the **Minecraft Vulkan Shaderpack Standard**.

The purpose of this specification is to establish a consistent and vendor-neutral interface between:

* A Vulkan-based Minecraft renderer (the **host**), and
* External shaderpacks implementing custom rendering logic.

This specification defines:

* A fixed rendering stage topology
* A resource binding abstraction model
* A shaderpack metadata format
* A resolution reconstruction subsystem
* Conformance and extension mechanisms

This document is **normative**.
All requirements stated using normative language are mandatory for conformant implementations.

---

## 1.2 Scope

This specification defines:

* The structure and behavior of shaderpacks
* The interaction between shaderpacks and the host renderer
* The requirements for temporal and spatial resolution reconstruction
* The rules for resource binding and stage execution

This specification does **not** define:

* The internal implementation of the host renderer
* Vendor-specific upscaling technologies
* The behavior of non-conformant shaderpacks
* The internal structure of Minecraft’s rendering engine

The host implementation remains free to choose:

* Internal Vulkan resource layouts
* Descriptor management strategies
* Memory allocation strategies
* Vendor-specific technologies

Provided that all externally observable behavior conforms to this specification.

---

## 1.3 Design Goals

The following design goals guided this specification:

1. **Vendor Neutrality**
   Shaderpacks must not depend on GPU vendor-specific behavior.

2. **Deterministic Stage Topology**
   The rendering pipeline must have a fixed and predictable structure.

3. **Abstract Resource Model**
   Shaderpacks reference logical resources rather than physical descriptors.

4. **Resolution Independence**
   Rendering stages operate at an internal resolution independent of the display resolution.

5. **Forward Compatibility**
   The specification must support future rendering technologies through extensions.

---

## 1.4 Conformance

Two types of entities may claim conformance to this specification:

### 1.4.1 Host Conformance

A renderer implementation is conformant if it:

* Implements all required pipeline stages
* Enforces abstract binding validation
* Implements the resolution reconstruction subsystem
* Follows all normative requirements in this document

### 1.4.2 Shaderpack Conformance

A shaderpack is conformant if it:

* Provides valid metadata
* Uses only defined abstract bindings
* Follows stage output requirements
* Does not rely on vendor-specific behavior

Non-conformant shaderpacks or hosts produce **undefined behavior**.

---

## 1.5 Versioning

This specification uses a semantic versioning scheme:

```
MAJOR.MINOR.PATCH
```

Where:

* **MAJOR** changes introduce breaking changes
* **MINOR** changes add backward-compatible features
* **PATCH** changes fix errors or clarify wording

Shaderpacks and hosts **MUST** declare the specification version they target.

---

# 2. Conventions and Terminology

## 2.1 Normative Language

The key words **MUST**, **MUST NOT**, **REQUIRED**, **SHALL**, **SHALL NOT**, **SHOULD**, **SHOULD NOT**, and **MAY** in this document are to be interpreted as described in RFC 2119.

Unless explicitly stated otherwise:

* **MUST / SHALL** indicate an absolute requirement.
* **MUST NOT / SHALL NOT** indicate an absolute prohibition.
* **SHOULD / SHOULD NOT** indicate a recommendation.
* **MAY** indicates an optional feature.

Statements not using normative keywords are descriptive.

---

## 2.2 Defined Terms

The following terms are used throughout this specification:

### Host

The Vulkan-based Minecraft renderer implementation that loads and executes shaderpacks.

The host is responsible for:

* Resource allocation
* Descriptor binding
* Stage scheduling
* Resolution reconstruction
* Validation and fallback behavior

---

### Shaderpack

A package of shaders and metadata conforming to this specification.

A shaderpack defines:

* Rendering logic for fixed stages
* Resource usage via abstract bindings
* Metadata describing capabilities and requirements

---

### Stage

A fixed rendering phase in the rendering pipeline topology.

Stages are:

* Ordered
* Deterministic
* Non-reorderable by shaderpacks

Shaderpacks **SHALL NOT** modify stage topology.

---

### Abstract Binding

A logical identifier used by shaderpacks to reference resources.

Abstract bindings:

* Do not expose Vulkan descriptors
* Are mapped by the host to concrete Vulkan resources
* Must be declared in metadata or defined by this specification

Use of undefined abstract bindings results in **undefined behavior**.

---

### Internal Resolution

The resolution at which rendering stages execute.

Internal resolution:

* May differ from output resolution
* Is defined by the host
* Is subject to dynamic scaling

Shaderpacks **MUST NOT** assume internal resolution equals display resolution.

---

### Output Resolution

The resolution of the final presented image (swapchain resolution).

---

### Resolution Reconstruction

A subsystem that transforms an internal-resolution image into an output-resolution image.

Resolution reconstruction may include:

* Temporal upscaling
* Spatial upscaling
* Frame generation

Vendor-specific technologies are implementation-defined.

---

### Temporal Mode

A reconstruction mode requiring historical frame data, including motion vectors.

Temporal mode **REQUIRES** motion vectors.

---

### Spatial Mode

A reconstruction mode that does not depend on historical frame data.

Spatial mode does not require motion vectors.

---

## 2.3 Behavior Classifications

### Undefined Behavior

Undefined behavior occurs when a shaderpack or host violates a normative requirement.

The result of undefined behavior is not specified by this document.

Examples include:

* Writing to undefined abstract bindings
* Omitting required buffers when enabling temporal reconstruction
* Declaring invalid metadata

---

### Implementation-Defined Behavior

Implementation-defined behavior refers to behavior that:

* Is not mandated by this specification
* Must be documented by the host implementation

Examples include:

* Descriptor allocation strategy
* Internal Vulkan pipeline layouts
* Choice of upscaling vendor SDK

---

### Valid Usage

Valid usage rules describe conditions that must be satisfied for defined behavior.

If a valid usage rule is violated, behavior is undefined.

---

## 2.4 Notation

Code blocks in this specification are illustrative unless explicitly marked as normative.

Tables describing buffer requirements are normative unless otherwise stated.

---

# 3. Architecture Overview

## 3.1 Rendering Model

This specification defines a **fixed-stage rendering architecture**.

The rendering pipeline consists of a predefined sequence of stages executed in a strict order. Shaderpacks **SHALL NOT** reorder, remove, or insert stages into the core topology.

The host implementation is responsible for:

* Creating Vulkan pipelines for each stage
* Managing synchronization between stages
* Resolving resource dependencies
* Executing the Resolution Reconstruction Subsystem

---

## 3.2 Stage Topology

The normative stage topology is defined as follows:

```
Shadow
  ↓
GBuffer
  ↓
Lighting / Composite
  ↓
Translucent
  ↓
Post-Process
  ↓
Final (Internal Resolution Output)
  ↓
Resolution Reconstruction
  ↓
Swapchain Presentation
```

The exact shader content of each stage is defined by the shaderpack.
The existence and ordering of these stages is defined by this specification.

The host **MUST** execute stages in the order defined above.

---

## 3.3 Stage Responsibilities

### 3.3.1 Shadow Stage

Purpose:

* Generate shadow maps or shadow-related data.

Outputs:

* Shadow textures
* Optional variance or moment maps

Shadow outputs are consumed by later stages.

---

### 3.3.2 GBuffer Stage

Purpose:

* Capture scene geometry information.

Typical outputs:

* Albedo
* Normals
* Material parameters
* Depth

The depth buffer produced here **MUST** represent the same geometry used to generate the color output.

---

### 3.3.3 Lighting / Composite Stage

Purpose:

* Perform lighting calculations.
* Combine geometry buffers into a shaded scene.

This stage may consume:

* GBuffer outputs
* Shadow maps
* Environment maps

---

### 3.3.4 Translucent Stage

Purpose:

* Render transparent or semi-transparent geometry.

This stage executes after opaque lighting.

---

### 3.3.5 Post-Process Stage

Purpose:

* Apply screen-space effects.
* Apply color grading.
* Apply temporal jitter.

Post-process effects operate at internal resolution.

---

### 3.3.6 Final Stage

Purpose:

* Produce the final internal-resolution color buffer.

This buffer is the required input to the Resolution Reconstruction Subsystem.

The Final Stage **MUST** output:

* Color buffer (required)
* Depth buffer (required for temporal reconstruction)
* Motion vector buffer (required for temporal reconstruction)

---

## 3.4 Resolution Flow

Rendering stages operate at **Internal Resolution**.

The output of the Final Stage is passed to the Resolution Reconstruction Subsystem.

The Resolution Reconstruction Subsystem:

* Converts internal-resolution images to output-resolution images.
* Performs temporal or spatial upscaling.
* Optionally performs frame generation.

The result is written to the swapchain image.

---

## 3.5 Resolution Independence

Shaderpacks **MUST NOT** assume that:

* Internal resolution equals output resolution.
* Internal resolution is constant across frames.

The host **MAY** dynamically adjust internal resolution.

If internal resolution changes between frames, temporal reconstruction remains valid only if motion vectors remain correct.

---

## 3.6 Prohibited Topology Modifications

Shaderpacks **SHALL NOT**:

* Introduce additional core stages
* Bypass the Resolution Reconstruction Subsystem
* Write directly to swapchain images
* Modify stage ordering

Any attempt to bypass defined topology results in undefined behavior.

---

# 4. Execution Model

## 4.1 Overview

This chapter defines:

* Shaderpack loading behavior
* Validation requirements
* Per-frame execution sequence
* Synchronization guarantees

The host implementation **MUST** follow the execution model defined in this chapter.

---

## 4.2 Shaderpack Loading

When loading a shaderpack, the host **MUST** perform the following steps in order:

1. Parse metadata.
2. Validate specification version compatibility.
3. Validate required stages.
4. Validate declared abstract bindings.
5. Validate declared features.
6. Create Vulkan pipelines for all required stages.

If any validation step fails, the host **MUST** reject the shaderpack.

Partially loaded shaderpacks are not permitted.

---

## 4.3 Metadata Validation

The host **MUST** verify:

* All required metadata fields are present.
* Version requirements are satisfied.
* No undefined abstract bindings are declared.
* No forbidden features are requested.

If metadata is invalid, behavior is defined as:

```
Shaderpack Load Failure
```

The host **MUST NOT** attempt to execute invalid shaderpacks.

---

## 4.4 Frame Execution Sequence

For each rendered frame, the host **MUST** execute the following sequence:

```
1. Update scene state
2. Apply camera jitter (if temporal mode enabled)
3. Execute Shadow Stage
4. Execute GBuffer Stage
5. Execute Lighting / Composite Stage
6. Execute Translucent Stage
7. Execute Post-Process Stage
8. Execute Final Stage
9. Execute Resolution Reconstruction
10. Present swapchain image
```

No stage may be skipped unless explicitly disabled by metadata.

---

## 4.5 Temporal Jitter

If temporal reconstruction is enabled:

* The host **MUST** apply subpixel jitter to the projection matrix.
* The same jitter offset **MUST** be used when generating motion vectors.
* Jitter history **MUST** be consistent across frames.

Failure to apply consistent jitter results in undefined behavior.

---

## 4.6 Motion Vector Timing

Motion vectors **MUST** represent:

```
Previous Frame Screen Position − Current Frame Screen Position
```

Motion vectors:

* MUST be computed before post-process distortion effects.
* MUST correspond to the depth buffer used for reconstruction.
* MUST be valid for all opaque geometry.

If temporal mode is enabled and motion vectors are missing, the host **MUST** fall back to spatial mode.

---

## 4.7 Resolution Reconstruction Invocation

The Resolution Reconstruction Subsystem is invoked after the Final Stage completes.

The host **MUST** provide:

* Current frame internal-resolution color
* Current frame depth
* Motion vectors (if temporal mode)
* Historical frame data (if temporal mode)

The subsystem returns an output-resolution image suitable for presentation.

---

## 4.8 Synchronization Requirements

The host **MUST** guarantee:

* All writes from a stage are visible to subsequent stages.
* All resources used for reconstruction are fully resolved.
* No resource hazards exist across stage boundaries.

The synchronization mechanism is implementation-defined but must preserve correctness.

---

## 4.9 Frame Generation (Optional)

If frame generation is enabled:

* Frame generation **MUST NOT** modify internal stage execution.
* Generated frames **MUST** use valid historical data.
* Shaderpacks **MUST NOT** assume that every presented frame corresponds to a fully executed stage sequence.

Frame generation behavior is implementation-defined beyond these guarantees.

---

## 4.10 Dynamic Resolution Scaling

The host **MAY** modify internal resolution at runtime.

If internal resolution changes:

* Motion vectors **MUST** remain valid.
* Historical buffers **MUST** be adjusted or invalidated.
* Reconstruction quality must not violate valid usage rules.

If resolution changes invalidate temporal history, the host **MUST** reset reconstruction state.

---

# 5. Pipeline Stages

## 5.1 General Rules

All stages defined in this chapter are **normative**.

The host **MUST** execute stages in the order defined in Chapter 3.

Each stage:

* Consumes a defined set of inputs
* Produces a defined set of outputs
* Operates at internal resolution unless otherwise specified

Shaderpacks **MUST** implement all required outputs for each stage.

If a required output is missing, behavior is undefined.

---

## 5.2 Shadow Stage

### 5.2.1 Purpose

The Shadow Stage generates shadow-related data for use in later stages.

---

### 5.2.2 Inputs

The Shadow Stage may consume:

| Input            | Required | Description                       |
| ---------------- | -------- | --------------------------------- |
| Scene geometry   | YES      | World geometry for shadow casting |
| Light parameters | YES      | Directional or point light data   |

Exact input resources are implementation-defined.

---

### 5.2.3 Outputs

The Shadow Stage **MUST** produce:

| Output     | Required | Description                |
| ---------- | -------- | -------------------------- |
| Shadow map | YES      | Depth-based shadow texture |

Additional shadow-related outputs **MAY** be produced.

---

### 5.2.4 Required Behavior

The shadow map:

* MUST represent occlusion from shadow-casting geometry.
* MUST be consistent with geometry rendered in the GBuffer Stage.

---

### 5.2.5 Valid Usage

* Shadow map resolution is implementation-defined.
* Multiple shadow maps are permitted.

---

### 5.2.6 Undefined Behavior

Undefined behavior occurs if:

* The shadow map does not correspond to the scene geometry.
* Required outputs are omitted.

---

## 5.3 GBuffer Stage

### 5.3.1 Purpose

The GBuffer Stage captures geometry information required for lighting and reconstruction.

---

### 5.3.2 Inputs

| Input               | Required | Description              |
| ------------------- | -------- | ------------------------ |
| Scene geometry      | YES      | Opaque geometry          |
| Material parameters | YES      | Surface properties       |
| Camera matrices     | YES      | View and projection data |

---

### 5.3.3 Outputs

The GBuffer Stage **MUST** produce:

| Output          | Required | Description                |
| --------------- | -------- | -------------------------- |
| Color (albedo)  | YES      | Base color buffer          |
| Normal buffer   | YES      | Surface normals            |
| Depth buffer    | YES      | Scene depth                |
| Material buffer | OPTIONAL | Roughness, metalness, etc. |

---

### 5.3.4 Required Behavior

The depth buffer:

* MUST correspond exactly to the geometry used to generate color and normals.
* MUST be suitable for motion vector generation and reconstruction.

---

### 5.3.5 Valid Usage

* GBuffer format and packing are implementation-defined.
* Additional buffers MAY be produced.

---

### 5.3.6 Undefined Behavior

Undefined behavior occurs if:

* Depth does not match geometry.
* Required outputs are missing.

---

## 5.4 Lighting / Composite Stage

### 5.4.1 Purpose

This stage computes lighting and produces the shaded opaque scene.

---

### 5.4.2 Inputs

| Input            | Required | Description             |
| ---------------- | -------- | ----------------------- |
| GBuffer outputs  | YES      | Geometry information    |
| Shadow maps      | YES      | Shadow data             |
| Environment data | OPTIONAL | Sky or ambient lighting |

---

### 5.4.3 Outputs

| Output           | Required | Description         |
| ---------------- | -------- | ------------------- |
| Lit color buffer | YES      | Shaded opaque scene |

---

### 5.4.4 Required Behavior

The lighting result:

* MUST be consistent with GBuffer geometry.
* MUST respect shadow data.

---

### 5.4.5 Undefined Behavior

Undefined behavior occurs if:

* Required GBuffer inputs are missing.
* Output color buffer is not produced.

---

## 5.5 Translucent Stage

### 5.5.1 Purpose

This stage renders transparent or semi-transparent geometry.

---

### 5.5.2 Inputs

| Input            | Required | Description                |
| ---------------- | -------- | -------------------------- |
| Lit opaque color | YES      | Output from lighting stage |
| Scene geometry   | YES      | Translucent objects        |
| Depth buffer     | YES      | Depth testing              |

---

### 5.5.3 Outputs

| Output       | Required | Description                |
| ------------ | -------- | -------------------------- |
| Color buffer | YES      | Opaque + translucent scene |

---

### 5.5.4 Required Behavior

* Translucent objects MUST be composited over the opaque scene.
* Depth testing MUST be respected.

---

### 5.5.5 Undefined Behavior

Undefined behavior occurs if:

* Depth buffer is ignored.
* Required outputs are missing.

---

## 5.6 Post-Process Stage

### 5.6.1 Purpose

This stage applies screen-space effects at internal resolution.

Typical effects include:

* Color grading
* Bloom
* Tone mapping
* Temporal jitter application

---

### 5.6.2 Inputs

| Input        | Required | Description                   |
| ------------ | -------- | ----------------------------- |
| Scene color  | YES      | Output from Translucent Stage |
| Depth buffer | OPTIONAL | For screen-space effects      |

---

### 5.6.3 Outputs

| Output                 | Required | Description          |
| ---------------------- | -------- | -------------------- |
| Processed color buffer | YES      | Post-processed scene |

---

### 5.6.4 Valid Usage

Post-process effects:

* MUST operate at internal resolution.
* MUST NOT assume fixed resolution.

---

## 5.7 Final Stage

### 5.7.1 Purpose

The Final Stage prepares the required outputs for resolution reconstruction.

---

### 5.7.2 Inputs

| Input                | Required                   | Description      |
| -------------------- | -------------------------- | ---------------- |
| Post-processed color | YES                        | Scene color      |
| Depth buffer         | YES                        | Scene depth      |
| Motion vectors       | REQUIRED for temporal mode | Per-pixel motion |

---

### 5.7.3 Outputs

The Final Stage **MUST** produce:

| Output         | Required                   | Description               |
| -------------- | -------------------------- | ------------------------- |
| Final color    | YES                        | Internal-resolution color |
| Depth buffer   | YES                        | Depth for reconstruction  |
| Motion vectors | REQUIRED for temporal mode | Motion data               |

---

### 5.7.4 Required Behavior

* Color, depth, and motion vectors MUST correspond to the same scene state.
* Motion vectors MUST follow the convention defined in Chapter 4.

---

### 5.7.5 Undefined Behavior

Undefined behavior occurs if:

* Motion vectors are missing in temporal mode.
* Depth does not match the final color buffer.
* Required outputs are omitted.

---

# 6. Resource Model

## 6.1 Overview

This chapter defines the **abstract resource binding model** used by shaderpacks.

Shaderpacks **SHALL NOT** reference Vulkan descriptors directly.
All resources are accessed through **abstract bindings** defined by this specification or declared in shaderpack metadata.

The host implementation is responsible for:

* Mapping abstract bindings to Vulkan descriptors
* Allocating resources
* Managing synchronization
* Ensuring resource validity

---

## 6.2 Abstract Binding Contract

An **abstract binding** is a logical identifier representing a resource.

Abstract bindings:

* Are referenced by name or numeric identifier
* Do not correspond directly to Vulkan descriptor sets
* Are resolved by the host at runtime

The host **MUST** provide a valid resource for every abstract binding referenced by a shader.

If a shader references an undefined abstract binding, behavior is undefined.

---

## 6.3 Resource Types

The following abstract resource types are defined:

| Type          | Description                       |
| ------------- | --------------------------------- |
| Texture2D     | 2D image resource                 |
| Texture3D     | 3D image resource                 |
| TextureCube   | Cubemap resource                  |
| Buffer        | Generic storage or uniform buffer |
| DepthTexture  | Depth image resource              |
| MotionTexture | Motion vector buffer              |

Additional types **MAY** be defined by extensions.

---

## 6.4 Binding Categories

Abstract bindings are divided into three categories:

### 6.4.1 Stage Outputs

Resources produced by earlier stages.

Examples:

* GBuffer textures
* Shadow maps
* Depth buffers

These bindings are defined by the specification.

---

### 6.4.2 Host-Provided Resources

Resources supplied by the host.

Examples:

* Camera data
* Frame constants
* Environment textures
* Time values

These bindings are implementation-defined but must follow this specification.

---

### 6.4.3 Shaderpack-Declared Resources

Resources declared in shaderpack metadata.

Examples:

* Custom render targets
* Custom buffers
* Auxiliary textures

The host **MUST** allocate these resources according to metadata declarations.

---

## 6.5 Binding Lifetime

Abstract bindings have one of the following lifetimes:

| Lifetime   | Description            |
| ---------- | ---------------------- |
| Per-frame  | Recreated each frame   |
| Persistent | Survives across frames |
| Temporal   | Stores historical data |

The host **MUST** preserve temporal resources when temporal reconstruction is enabled.

---

## 6.6 Resolution Rules

For resources with dimensions:

* Resources marked as **internal-resolution** MUST match the internal rendering resolution.
* Resources marked as **output-resolution** MUST match the swapchain resolution.
* Resources marked as **fixed-resolution** MUST use the specified dimensions.

If internal resolution changes:

* Internal-resolution resources MUST be resized.
* Temporal resources MUST be preserved or invalidated according to Chapter 4.

---

## 6.7 Format Requirements

The host **MUST** ensure that:

* All required stage outputs use formats compatible with reconstruction.
* Depth buffers are usable for motion reconstruction.
* Motion vector buffers use two-component floating-point formats.

The exact Vulkan formats are implementation-defined.

---

## 6.8 Access Rules

Shaderpacks:

* MAY read from any declared abstract binding.
* MAY write only to bindings declared as writable.

Shaderpacks **MUST NOT**:

* Write to host-provided read-only bindings.
* Read from undefined bindings.
* Assume physical memory layout.

Violation of access rules results in undefined behavior.

---

## 6.9 Synchronization Model

The host **MUST** guarantee:

* Writes to a resource in one stage are visible to subsequent stages.
* No read-after-write hazards occur.
* Temporal resources are synchronized across frames.

The synchronization mechanism is implementation-defined.

---

## 6.10 Undefined Behavior

Undefined behavior occurs if:

* A shader reads from an undefined abstract binding.
* A shader writes to a read-only binding.
* A resource is used with an incompatible format.
* A resource lifetime rule is violated.

---

# 7. Metadata and Configuration

## 7.1 Overview

A shaderpack **MUST** provide a metadata file.

The metadata file:

* Declares specification compatibility
* Declares required stages
* Declares pipeline program mappings
* Declares abstract resource usage
* Declares reconstruction requirements
* Declares optional features

If metadata is missing, the shaderpack **MUST NOT** be loaded.

---

## 7.2 File Format

The metadata file **MUST** use the TOML format.

The filename **MUST** be:

```
shaderpack.toml
```

The file **MUST** be located at the root of the shaderpack package.

If the file cannot be parsed as valid TOML, loading **MUST** fail.

Shaderpacks **MUST** also provide stage pipeline mapping descriptors under:

```
pipelines/[world_id]/[stage_name].toml
```

Where:

* `[world_id]` identifies a world/profile scope.
* `[stage_name]` **MUST** be one of: `shadow`, `gbuffer`, `lighting`, `translucent`, `postprocess`, `final`.

The special value `any` for `[world_id]` is a wildcard scope and **MAY** be used as a fallback.

---

## 7.3 Required Fields

The following fields are mandatory:

```toml
[shaderpack]
name = "Example Shaderpack"
version = "1.0.0"
spec_version = "1.0.0"
```

### Field Definitions

| Field        | Required | Description                  |
| ------------ | -------- | ---------------------------- |
| name         | YES      | Human-readable name          |
| version      | YES      | Shaderpack version           |
| spec_version | YES      | Target specification version |

---

### Validation Rules

* `spec_version` **MUST** match a supported specification version.
* Version format **MUST** follow semantic versioning.
* Missing required fields cause load failure.

---

## 7.4 Stage Declaration

Shaderpacks **MUST** declare which stages are implemented.

Example:

```toml
[stages]
shadow = true
gbuffer = true
lighting = true
translucent = true
postprocess = true
final = true
```

### Rules

* All core stages defined in Chapter 3 **MUST** be declared.
* A stage may be disabled only if explicitly permitted by this specification.
* If a required stage is disabled, load **MUST** fail.

---

## 7.5 Stage Pipeline Mapping Files

Shaderpacks **MUST** declare program source mapping through stage files:

```
pipelines/[world_id]/[stage_name].toml
```

Each stage file may define one or more shader programs, executed in declaration order.

Example:

```toml
# pipelines/any/gbuffer.toml
[params]
shared_scale = "1.0"

[[programs]]
shader_type = "terrain"
vertex = "shaders/basic/terrain/terrain.vsh"
fragment = "shaders/basic/terrain/terrain.fsh"
config = "shaders/basic/terrain/terrain.json"

[programs.params]
shared_scale = "${shared_scale}"
gbuffer_pass = "opaque"

[[programs]]
shader_type = "terrain_earlyZ"
vertex = "shaders/basic/terrain/terrain.vsh"
fragment = "shaders/basic/terrain_earlyZ/terrain_earlyZ.fsh"
config = "shaders/basic/terrain_earlyZ/terrain_earlyZ.json"

[programs.params]
gbuffer_pass = "${gbuffer_pass}"
depth_prepass = "1"
```

### Top-Level Fields

| Field    | Required | Description |
| -------- | -------- | ----------- |
| params   | Optional | Stage-level parameter map (string to string), available to all programs in this file |
| programs | YES      | Ordered array of program mappings (`[[programs]]`) |

### `[[programs]]` Fields

| Field       | Required | Description |
| ----------- | -------- | ----------- |
| shader_type | YES      | Host shader program identifier to replace |
| vertex      | Conditional | Vertex shader source path (relative to shaderpack root) |
| fragment    | Conditional | Fragment shader source path (relative to shaderpack root) |
| config      | Optional | Additional pipeline config file path (for example JSON) |
| files       | Optional | Extra per-file mapping table from host shader asset path to shaderpack path |
| params      | Optional | Program-local parameter map; merged into stage context and passed to following programs |

Rules:

* Stage filename **MUST** be one of the stage names defined in Chapter 5.
* Every stage file **MUST** define at least one `[[programs]]` entry.
* In each `[[programs]]`, at least one of `vertex`, `fragment`, `config`, or `files` **MUST** be present.
* `files` entries **MUST** map string keys to string paths.
* Program order in `[[programs]]` **MUST** be preserved by hosts.
* Program `params` **MAY** reference earlier parameters using `${param_name}`.
* After a program is resolved, its `params` become available to subsequent programs in the same stage file.
* If `[stages].<stage_name> = true`, `pipelines/any/<stage_name>.toml` **MUST** exist and contain at least one valid program mapping.
* Hosts **SHOULD** select an exact `[world_id]` match first, and **SHOULD** fall back to `pipelines/any/` when no exact match exists.
* If multiple mappings define the same `(world_id, shader_type)`, validation **MUST** fail.
* If required mapping files are missing or invalid, loading **MUST** fail.
* Unknown extra fields **MAY** be ignored by hosts.

---

## 7.6 Resource Declarations

Shaderpacks **MAY** declare additional abstract bindings.

Example:

```toml
[[resources]]
name = "custom_lut"
type = "Texture2D"
resolution = "internal"
format = "rgba16f"
lifetime = "persistent"
```

---

### Resource Fields

| Field      | Required | Description                       |
| ---------- | -------- | --------------------------------- |
| name       | YES      | Abstract binding identifier       |
| type       | YES      | Resource type                     |
| resolution | YES      | internal / output / fixed         |
| format     | YES      | Requested format                  |
| lifetime   | YES      | per-frame / persistent / temporal |

---

### Validation Rules

* Resource names **MUST** be unique.
* Types **MUST** match defined resource types.
* Unsupported formats **MUST** cause load failure.
* Unknown lifetime types result in load failure.

---

## 7.7 Resolution Reconstruction Declaration

Shaderpacks **MAY** declare reconstruction requirements.

Example:

```toml
[reconstruction]
mode = "temporal"
requires_motion_vectors = true
requires_depth = true
```

---

### Reconstruction Fields

| Field                   | Required | Description                 |
| ----------------------- | -------- | --------------------------- |
| mode                    | YES      | native / spatial / temporal |
| requires_motion_vectors | OPTIONAL | Boolean                     |
| requires_depth          | OPTIONAL | Boolean                     |

---

### Rules

If `mode = "temporal"`:

* Motion vectors **MUST** be provided.
* Depth buffer **MUST** be valid.
* Temporal jitter **MUST** be supported.

If `mode = "spatial"`:

* Motion vectors **MUST NOT** be required.

If `mode = "native"`:

* Resolution reconstruction is disabled.

---

## 7.8 Feature Declarations

Optional features may be declared:

```toml
[features]
frame_generation = false
dynamic_resolution = true
```

---

### Rules

* Unknown features **MUST** be ignored unless marked required.
* Required unsupported features cause load failure.

---

## 7.9 Extension Declaration

Shaderpacks **MAY** declare required extensions.

```toml
[extensions]
required = ["MVK_shaderpack_raytracing"]
optional = ["MVK_shaderpack_pathtracing"]
```

If a required extension is unsupported, load **MUST** fail.

---

## 7.10 Error Handling

If metadata validation fails:

* The shaderpack **MUST NOT** execute.
* The host **SHOULD** provide diagnostic information.
* Partial loading is prohibited.

---

## 7.11 Undefined Behavior

Undefined behavior occurs if:

* A shader references undeclared resources.
* Reconstruction mode conflicts with stage outputs.
* Metadata contradicts actual shader behavior.

---

# 8. Security and Isolation

## 8.1 Overview

This chapter defines the security and isolation requirements between:

* The Host implementation, and
* The Shaderpack

The host **MUST** enforce isolation boundaries defined in this chapter.

Failure to enforce isolation may result in undefined behavior or system instability.

---

## 8.2 Execution Isolation

Shaderpacks:

* Execute only within the GPU shader pipeline.
* Have no direct access to host memory.
* Have no direct access to system APIs.

Shaderpacks **SHALL NOT**:

* Access host descriptor sets directly.
* Modify host-managed synchronization primitives.
* Access swapchain images directly.

All access must occur through abstract bindings.

---

## 8.3 Resource Isolation

The host **MUST** ensure:

* Shaderpacks cannot access resources not declared in metadata.
* Shaderpacks cannot write to read-only resources.
* Shaderpacks cannot alias host-internal resources.

If a shader references an undeclared resource, behavior is undefined.

---

## 8.4 Swapchain Protection

The swapchain image:

* Is owned by the host.
* Is written only after resolution reconstruction.
* Must not be exposed as a writable abstract binding.

Shaderpacks **SHALL NOT**:

* Render directly to swapchain images.
* Assume swapchain format.
* Depend on swapchain resolution.

---

## 8.5 Descriptor Abstraction

The mapping between abstract bindings and Vulkan descriptor sets:

* Is implementation-defined.
* Must not be observable by shaderpacks.

Shaderpacks **MUST NOT**:

* Depend on descriptor set index.
* Depend on binding index.
* Assume descriptor layout stability.

---

## 8.6 Frame History Isolation

Temporal reconstruction requires frame history buffers.

The host **MUST**:

* Protect historical frame data from unintended modification.
* Ensure history buffers are synchronized.
* Invalidate history when resolution or topology changes.

Shaderpacks **MUST NOT**:

* Modify historical buffers directly.
* Assume persistence beyond defined lifetime.

---

## 8.7 Validation Enforcement

The host **MUST** perform runtime validation for:

* Resource access violations
* Missing required stage outputs
* Invalid reconstruction inputs
* Metadata contract violations

The host **SHOULD** provide diagnostic information when violations occur.

---

## 8.8 Undefined and Host-Defined Behavior

The host is permitted to define additional validation rules.

However, the host **MUST NOT**:

* Silently ignore required validation failures.
* Permit undefined behavior to corrupt host state.

If a violation is detected, the host **MUST**:

* Abort shaderpack execution for that frame, or
* Disable the shaderpack entirely.

---

## 8.9 Denial-of-Service Prevention

The host **MAY** impose limits on:

* Maximum resource allocations
* Maximum render target count
* Maximum buffer sizes
* Maximum temporal history size

If a shaderpack exceeds implementation limits:

* The host **MUST** fail loading.
* The host **SHOULD** report diagnostic information.

---

# 9. Conformance Requirements

## 9.1 Overview

This chapter defines the requirements for claiming conformance to this specification.

Two entities may claim conformance:

* A Host implementation
* A Shaderpack

Conformance claims **MUST** satisfy all applicable normative requirements in this specification.

---

# 9.2 Host Conformance

A host implementation is conformant if and only if it satisfies all requirements defined in this section.

---

## 9.2.1 Required Capabilities

A conformant host **MUST**:

1. Implement the fixed stage topology defined in Chapter 3.
2. Enforce the execution model defined in Chapter 4.
3. Implement the abstract resource model defined in Chapter 6.
4. Parse and validate metadata as defined in Chapter 7.
5. Enforce security and isolation rules defined in Chapter 8.

Failure to implement any required capability invalidates conformance.

---

## 9.2.2 Reconstruction Support

A conformant host **MUST** support:

* Native mode
* At least one spatial or temporal reconstruction method

If temporal reconstruction is supported, the host **MUST**:

* Accept motion vector buffers
* Accept depth buffers
* Maintain temporal history correctly

The specific vendor technologies used are implementation-defined.

---

## 9.2.3 Validation Requirements

A conformant host **MUST**:

* Reject invalid metadata.
* Reject undefined abstract bindings.
* Enforce required stage outputs.
* Enforce reconstruction input requirements.
* Enforce policy flags (`allow`, `forbid`, `require`).

A conformant host **MUST NOT**:

* Silently ignore normative violations.
* Allow undefined behavior to propagate into host state.

---

## 9.2.4 Fallback Compliance

If reconstruction is requested but unavailable:

* The host **MUST** follow fallback rules defined in Chapter 8.
* The host **MUST NOT** substitute an incompatible reconstruction mode.

If a shaderpack declares `require = true` and no compatible mode exists:

* The host **MUST** fail loading.

---

## 9.2.5 Version Compliance

The host **MUST**:

* Validate `spec_version`.
* Reject shaderpacks targeting unsupported MAJOR versions.
* Support backward-compatible MINOR versions where feasible.

---

# 9.3 Shaderpack Conformance

A shaderpack is conformant if and only if it satisfies all requirements defined in this section.

---

## 9.3.1 Metadata Validity

A conformant shaderpack **MUST**:

* Provide valid TOML metadata.
* Declare required stages.
* Declare required resources.
* Declare compatible `spec_version`.

---

## 9.3.2 Stage Output Compliance

A conformant shaderpack **MUST**:

* Produce all required outputs for each stage.
* Provide motion vectors when temporal mode is declared.
* Provide depth consistent with color output.

---

## 9.3.3 Resource Usage Compliance

A conformant shaderpack **MUST NOT**:

* Reference undefined abstract bindings.
* Write to read-only bindings.
* Depend on physical descriptor layout.
* Depend on specific GPU vendors.

---

## 9.3.4 Reconstruction Policy Compliance

A conformant shaderpack:

* MAY allow reconstruction.
* MAY forbid reconstruction.
* MAY require reconstruction.

If `require = true` is declared, the shaderpack **MUST** provide all required reconstruction inputs.

---

## 9.3.5 Prohibited Behavior

A conformant shaderpack **MUST NOT**:

* Attempt to bypass resolution reconstruction.
* Attempt to render directly to swapchain.
* Depend on frame generation timing.
* Invoke vendor-specific SDK logic.

---

# 9.4 Conformance Claims

An implementation or shaderpack claiming conformance:

* **SHALL** state the specification version.
* **SHALL** not claim partial conformance.
* **SHALL** not claim conformance if normative requirements are violated.

---

# 9.5 Non-Conformant Behavior

If either host or shaderpack violates normative requirements:

* Behavior is undefined.
* Conformance claim is invalid.

---

# 10. Extension Mechanism

## 10.1 Overview

This specification supports extensibility through a formal extension mechanism.

Extensions allow:

* Introduction of new features
* Introduction of new resource types
* Introduction of new stage capabilities
* Introduction of optional subsystems

Extensions **MUST NOT** modify or invalidate core behavior defined in this specification.

Core behavior remains authoritative.

---

## 10.2 Extension Naming

All extensions **MUST** follow this naming convention:

```
MVK_shaderpack_<extension_name>
```

Where:

* `MVK` identifies the Minecraft Vulkan Shaderpack specification.
* `<extension_name>` is a lowercase identifier using underscores.

Example extension names:

```
MVK_shaderpack_ray_tracing
MVK_shaderpack_path_tracing
MVK_shaderpack_mesh_shader
MVK_shaderpack_compute_lighting
```

---

## 10.3 Extension Declaration in Metadata

Shaderpacks declare extensions in metadata:

```toml
[extensions]
required = ["MVK_shaderpack_ray_tracing"]
optional = ["MVK_shaderpack_mesh_shader"]
```

---

### Rules

* If a required extension is unsupported, loading **MUST** fail.
* Optional extensions **MAY** be ignored.
* Unknown extension identifiers **MUST** cause load failure if marked required.

---

## 10.4 Extension Scope Rules

Extensions **MAY**:

* Introduce new abstract resource types.
* Introduce new metadata fields.
* Introduce optional stage capabilities.
* Introduce new reconstruction parameters.

Extensions **MUST NOT**:

* Modify stage ordering.
* Remove required stage outputs.
* Bypass resolution reconstruction.
* Break compatibility with core valid usage rules.

---

## 10.5 Extension Dependencies

An extension **MAY** depend on another extension.

If an extension declares dependencies:

* All dependencies **MUST** be supported.
* Dependency cycles are prohibited.

---

## 10.6 Backward Compatibility

Minor and patch versions of this specification:

* MUST NOT remove existing core features.
* MUST NOT invalidate conformant shaderpacks.

Major versions:

* MAY introduce breaking changes.
* MUST increment the MAJOR version number.

---

## 10.7 Extension Versioning

Extensions **MAY** define their own version numbers.

Example:

```toml
[extensions]
required = ["MVK_shaderpack_ray_tracing@1.0"]
```

If versioned extensions are declared:

* The host **MUST** validate version compatibility.
* Incompatible versions **MUST** cause load failure.

---

## 10.8 Experimental Extensions

Hosts **MAY** implement experimental extensions.

Experimental extensions:

* MUST use the prefix:

```
MVK_shaderpack_experimental_<name>
```

* MUST NOT be claimed as stable.
* MAY change without backward compatibility guarantees.

Shaderpacks using experimental extensions **SHALL NOT** claim full conformance.

---

## 10.9 Reserved Namespaces

The following namespaces are reserved and **MUST NOT** be used by third parties:

```
MVK_shaderpack_core_*
MVK_shaderpack_internal_*
```

These namespaces are reserved for future specification-defined extensions.
