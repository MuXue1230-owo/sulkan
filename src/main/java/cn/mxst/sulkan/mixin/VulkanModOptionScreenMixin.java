package cn.mxst.sulkan.mixin;

import cn.mxst.sulkan.compat.vulkanmod.SulkanVulkanOptions;
import cn.mxst.sulkan.config.SulkanConfig;
import java.util.List;
import net.minecraft.text.Text;
import net.vulkanmod.config.option.OptionPage;
import net.vulkanmod.config.gui.VOptionScreen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VOptionScreen.class)
public abstract class VulkanModOptionScreenMixin {
	@Shadow
	@Final
	private List<OptionPage> optionPages;

	@Inject(method = "addPages", at = @At("TAIL"))
	private void sulkan$addSulkanPage(CallbackInfo ci) {
		OptionPage page = new OptionPage(
			Text.translatable("sulkan.options.pages.sulkan").getString(),
			SulkanVulkanOptions.createOptionBlocks()
		);
		this.optionPages.add(page);
	}

	@Inject(method = "applyOptions", at = @At("TAIL"))
	private void sulkan$saveConfig(CallbackInfo ci) {
		SulkanConfig.save();
	}
}
