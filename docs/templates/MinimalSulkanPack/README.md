# Minimal Sulkan Pack (segments template)

This template uses Sulkan's stage-segment pipeline format (`[[segments]]`).

Goal:
- pass metadata and stage-segment validation
- keep all six stage files present
- apply one visible postprocess tint (`postprocess` stage, `blit` segment)

How to use:
1. Copy this folder into `shaderpacks/`.
2. Select `MinimalSulkanPack` in Sulkan shaderpack list.
3. Press `F3` and check Sulkan debug lines:
   - `Pipeline lookup`
   - `Pipeline resolve (segment hits)`
   - `Pipeline world chain`

If the tint is not visible, check `docs/SulkanShaderpackTutorial.md` section "排错清单".
