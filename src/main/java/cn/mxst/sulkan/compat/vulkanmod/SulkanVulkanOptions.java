package cn.mxst.sulkan.compat.vulkanmod;

import cn.mxst.sulkan.Sulkan;
import cn.mxst.sulkan.client.gui.SulkanShaderpackScreen;
import cn.mxst.sulkan.config.SulkanConfig;
import cn.mxst.sulkan.shaderpack.ShaderpackLoadResult;
import cn.mxst.sulkan.shaderpack.ShaderpackManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.vulkanmod.config.gui.OptionBlock;
import net.vulkanmod.config.option.Option;
import net.vulkanmod.config.option.SulkanActionOption;
import net.vulkanmod.config.option.SwitchOption;

public final class SulkanVulkanOptions {
	private SulkanVulkanOptions() {
	}

	public static OptionBlock[] createOptionBlocks() {
		SulkanConfig config = SulkanConfig.get();

		SwitchOption enableShaderpack = new SwitchOption(
			Text.translatable("sulkan.options.enable_shaderpacks"),
			value -> {
				if (config.enableShaderpack == value) {
					return;
				}
				config.enableShaderpack = value;
				if (value) {
					activateSelectedShaderpack(config);
				} else {
					ShaderpackManager.clearActiveShaderpack();
				}
				MinecraftClient client = MinecraftClient.getInstance();
				SulkanVulkanPipelineReloader.reloadResourcesAndPipelines(client);
			},
			() -> config.enableShaderpack
		);
		enableShaderpack.setTooltip(Text.translatable("sulkan.options.enable_shaderpacks.tooltip"));

		SulkanActionOption openShaderpacks = new SulkanActionOption(
			Text.translatable("sulkan.options.shaderpack_list"),
			Text.translatable("sulkan.options.shaderpack_list.open"),
			() -> MinecraftClient.getInstance().setScreen(new SulkanShaderpackScreen(MinecraftClient.getInstance().currentScreen))
		);
		openShaderpacks.setTooltip(Text.translatable("sulkan.options.shaderpack_list.tooltip"));

		return new OptionBlock[]{
			new OptionBlock("", new Option<?>[]{
				enableShaderpack,
				openShaderpacks
			})
		};
	}

	private static void activateSelectedShaderpack(SulkanConfig config) {
		ShaderpackLoadResult result = ShaderpackManager.applySelectedShaderpack(config.selectedShaderpack);
		if (!result.isValid() && config.selectedShaderpack != null && !config.selectedShaderpack.isBlank()) {
			Sulkan.LOGGER.warn("Failed to activate selected shaderpack '{}':", config.selectedShaderpack);
			for (String error : result.errors()) {
				Sulkan.LOGGER.warn("  {}", error);
			}
		}
	}

}
