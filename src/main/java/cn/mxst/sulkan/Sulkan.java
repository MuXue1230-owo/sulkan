package cn.mxst.sulkan;

import cn.mxst.sulkan.client.input.SulkanDebugHotkeys;
import cn.mxst.sulkan.config.SulkanConfig;
import cn.mxst.sulkan.shaderpack.ShaderpackLoadResult;
import cn.mxst.sulkan.shaderpack.ShaderpackManager;
import net.fabricmc.api.ClientModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Sulkan implements ClientModInitializer {
	public static final String MOD_ID = "sulkan";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitializeClient() {
		SulkanDebugHotkeys.register();
		SulkanConfig.load();
		SulkanConfig config = SulkanConfig.get();
		if (config.enableShaderpack && config.selectedShaderpack != null && !config.selectedShaderpack.isBlank()) {
			ShaderpackLoadResult result = ShaderpackManager.applySelectedShaderpack(config.selectedShaderpack);
			if (!result.isValid()) {
				LOGGER.warn("Failed to activate selected shaderpack '{}':", config.selectedShaderpack);
				for (String error : result.errors()) {
					LOGGER.warn("  {}", error);
				}
			}
		}
		LOGGER.info("Sulkan client initialized");
	}
}
