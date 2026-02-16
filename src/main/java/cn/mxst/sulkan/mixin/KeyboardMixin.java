package cn.mxst.sulkan.mixin;

import cn.mxst.sulkan.Sulkan;
import cn.mxst.sulkan.client.gui.SulkanShaderpackScreen;
import cn.mxst.sulkan.client.input.SulkanDebugHotkeys;
import cn.mxst.sulkan.compat.vulkanmod.SulkanVulkanPipelineReloader;
import cn.mxst.sulkan.config.SulkanConfig;
import cn.mxst.sulkan.shaderpack.ShaderpackLoadResult;
import cn.mxst.sulkan.shaderpack.ShaderpackManager;
import net.minecraft.client.Keyboard;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Keyboard.class)
public abstract class KeyboardMixin {
	@Shadow
	@Final
	private MinecraftClient client;

	@Unique
	private int sulkan$lastHandledDebugKey = Integer.MIN_VALUE;
	@Unique
	private long sulkan$lastHandledDebugKeyAt = Long.MIN_VALUE;

	@Inject(method = "processF3", at = @At("HEAD"), cancellable = true)
	private void sulkan$processSulkanDebugKeys(KeyInput input, CallbackInfoReturnable<Boolean> cir) {
		if (input == null) {
			return;
		}

		if (input.key() == InputUtil.GLFW_KEY_R) {
			if (!this.sulkan$isRepeatedHotkey(input.key())) {
				this.sulkan$reloadShaderpackAndPipelines();
			}
			cir.setReturnValue(true);
			return;
		}

		if (SulkanDebugHotkeys.matchesOpenShaderpackListKey(input)) {
			if (!this.sulkan$isRepeatedHotkey(input.key())) {
				this.sulkan$openShaderpackList();
			}
			cir.setReturnValue(true);
		}
	}

	@Unique
	private boolean sulkan$isRepeatedHotkey(int key) {
		long now = Util.getMeasuringTimeMs();
		boolean repeated = this.sulkan$lastHandledDebugKey == key && now - this.sulkan$lastHandledDebugKeyAt < 200L;
		this.sulkan$lastHandledDebugKey = key;
		this.sulkan$lastHandledDebugKeyAt = now;
		return repeated;
	}

	@Unique
	private void sulkan$reloadShaderpackAndPipelines() {
		SulkanConfig config = SulkanConfig.get();
		if (config.enableShaderpack) {
			ShaderpackLoadResult result = ShaderpackManager.applySelectedShaderpack(config.selectedShaderpack);
			if (!result.isValid() && config.selectedShaderpack != null && !config.selectedShaderpack.isBlank()) {
				Sulkan.LOGGER.warn("Failed to reload selected shaderpack '{}':", config.selectedShaderpack);
				for (String error : result.errors()) {
					Sulkan.LOGGER.warn("  {}", error);
				}
				this.sulkan$showDebugMessage(Text.translatable("sulkan.debug.reload.invalid"), true);
			}
		} else {
			ShaderpackManager.clearActiveShaderpack();
		}

		this.sulkan$showDebugMessage(Text.translatable("sulkan.debug.reload.started"), false);
		SulkanVulkanPipelineReloader.reloadResourcesAndPipelines(this.client).whenComplete((unused, throwable) -> this.client.execute(() -> {
			if (throwable == null) {
				this.sulkan$showDebugMessage(Text.translatable("sulkan.debug.reload.finished"), false);
			} else {
				Sulkan.LOGGER.warn("Failed to reload Sulkan shaderpack resources.", throwable);
				this.sulkan$showDebugMessage(Text.translatable("sulkan.debug.reload.failed"), true);
			}
		}));
	}

	@Unique
	private void sulkan$openShaderpackList() {
		if (this.client.currentScreen instanceof SulkanShaderpackScreen) {
			return;
		}
		this.client.setScreen(new SulkanShaderpackScreen(this.client.currentScreen));
		this.sulkan$showDebugMessage(Text.translatable("sulkan.debug.open_list"), false);
	}

	@Unique
	private void sulkan$showDebugMessage(Text message, boolean error) {
		if (this.client == null || this.client.inGameHud == null) {
			return;
		}
		Text formatted = error
			? Text.literal("[Sulkan] ").append(message).formatted(net.minecraft.util.Formatting.RED)
			: Text.literal("[Sulkan] ").append(message).formatted(net.minecraft.util.Formatting.YELLOW);
		this.client.inGameHud.getChatHud().addMessage(formatted);
		this.client.getNarratorManager().narrateSystemMessage(formatted);
	}
}
