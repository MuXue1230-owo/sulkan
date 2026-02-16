package cn.mxst.sulkan.mixin;

import cn.mxst.sulkan.shaderpack.ShaderpackShaderApplier;
import java.io.InputStream;
import net.vulkanmod.render.shader.ShaderLoadUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ShaderLoadUtil.class)
public class VulkanModShaderLoadUtilMixin {
	@Inject(method = "getInputStream", at = @At("HEAD"), cancellable = true)
	private static void sulkan$replaceShaderInput(String path, CallbackInfoReturnable<InputStream> cir) {
		InputStream replaced = ShaderpackShaderApplier.tryOpenReplacedStream(path);
		if (replaced != null) {
			cir.setReturnValue(replaced);
		}
	}
}
