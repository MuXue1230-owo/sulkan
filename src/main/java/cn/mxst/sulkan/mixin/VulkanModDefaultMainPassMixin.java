package cn.mxst.sulkan.mixin;

import cn.mxst.sulkan.Sulkan;
import cn.mxst.sulkan.shaderpack.ShaderpackComputeDispatcher;
import net.vulkanmod.vulkan.pass.DefaultMainPass;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DefaultMainPass.class)
public class VulkanModDefaultMainPassMixin {
	@Inject(
		method = "end",
		at = @At(
			value = "INVOKE",
			target = "Lnet/vulkanmod/vulkan/Renderer;endRenderPass(Lorg/lwjgl/vulkan/VkCommandBuffer;)V",
			shift = At.Shift.AFTER
		)
	)
	private void sulkan$dispatchCompute(VkCommandBuffer commandBuffer, CallbackInfo ci) {
		try {
			ShaderpackComputeDispatcher.dispatch(commandBuffer);
		} catch (Exception e) {
			Sulkan.LOGGER.warn("Compute dispatch failed: {}", e.getMessage());
		}
	}
}
