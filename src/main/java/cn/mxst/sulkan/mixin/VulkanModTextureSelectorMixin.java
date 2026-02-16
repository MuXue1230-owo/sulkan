package cn.mxst.sulkan.mixin;

import cn.mxst.sulkan.shaderpack.ShaderpackTextureBinder;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VTextureSelector.class)
public class VulkanModTextureSelectorMixin {
	@Inject(method = "bindShaderTextures", at = @At("HEAD"))
	private static void sulkan$applyShaderpackTextureBindings(Pipeline pipeline, CallbackInfo ci) {
		ShaderpackTextureBinder.applyBindings(pipeline);
	}
}
