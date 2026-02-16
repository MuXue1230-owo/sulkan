package cn.mxst.sulkan.mixin;

import cn.mxst.sulkan.client.input.SulkanDebugHotkeys;
import cn.mxst.sulkan.config.SulkanConfig;
import cn.mxst.sulkan.shaderpack.ShaderpackManager;
import cn.mxst.sulkan.shaderpack.ShaderpackMetadata;
import cn.mxst.sulkan.shaderpack.ShaderpackShaderApplier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.DebugHud;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(DebugHud.class)
public abstract class DebugHudMixin {
	@Shadow
	@Final
	private MinecraftClient client;

	@ModifyArg(
		method = "render",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/hud/DebugHud;drawText(Lnet/minecraft/client/gui/DrawContext;Ljava/util/List;Z)V",
			ordinal = 1
		),
		index = 1
	)
	private List<String> sulkan$appendSulkanInfo(List<String> rightText) {
		if (this.client == null || this.client.options == null || !this.client.debugHudEntryList.isF3Enabled()) {
			return rightText;
		}

		List<String> lines = new ArrayList<>(rightText);
		if (!lines.isEmpty() && !lines.get(lines.size() - 1).isEmpty()) {
			lines.add("");
		}

		SulkanConfig config = SulkanConfig.get();
		ShaderpackMetadata activeShaderpack = ShaderpackManager.getActiveShaderpack();
		String noneLabel = Text.translatable("sulkan.debug.info.none").getString();

		String selectedPack = config.selectedShaderpack == null || config.selectedShaderpack.isBlank() ? noneLabel : config.selectedShaderpack;
		String activePack = activeShaderpack == null ? noneLabel : activeShaderpack.name + " v" + activeShaderpack.version;
		Path activePath = ShaderpackManager.getActivePath();
		String sourcePack = activePath == null ? noneLabel : activePath.getFileName().toString();
		ShaderpackShaderApplier.CacheStats shaderCacheStats = ShaderpackShaderApplier.getCacheStats();
		ShaderpackManager.CacheStats worldCacheStats = ShaderpackManager.getWorldCandidateCacheStats();

		KeyBinding openListKey = SulkanDebugHotkeys.getOpenShaderpackListKey();
		String modifierKey = this.client.options.debugModifierKey.getBoundKeyLocalizedText().getString();
		String openListKeyName = openListKey == null ? "O" : openListKey.getBoundKeyLocalizedText().getString();

		lines.add(Text.translatable("sulkan.debug.info.header").getString());
		lines.add(
			Text.translatable(
				"sulkan.debug.info.state",
				Text.translatable(config.enableShaderpack ? "sulkan.debug.info.state.enabled" : "sulkan.debug.info.state.disabled")
			).getString()
		);
		lines.add(Text.translatable("sulkan.debug.info.selected", selectedPack).getString());
		lines.add(Text.translatable("sulkan.debug.info.active", activePack).getString());
		lines.add(Text.translatable("sulkan.debug.info.source", sourcePack).getString());
		lines.add(Text.translatable("sulkan.debug.info.cache.header").getString());
		lines.add(
			Text.translatable(
				"sulkan.debug.info.cache.target_lookup",
				shaderCacheStats.targetHits(),
				shaderCacheStats.targetLookups(),
				Math.max(0L, shaderCacheStats.targetLookups() - shaderCacheStats.targetHits()),
				sulkan$formatHitRate(shaderCacheStats.targetHits(), shaderCacheStats.targetLookups())
			).getString()
		);
		lines.add(
			Text.translatable(
				"sulkan.debug.info.cache.shaderpack_file",
				shaderCacheStats.shaderpackHits(),
				shaderCacheStats.shaderpackReads(),
				Math.max(0L, shaderCacheStats.shaderpackReads() - shaderCacheStats.shaderpackHits()),
				sulkan$formatHitRate(shaderCacheStats.shaderpackHits(), shaderCacheStats.shaderpackReads())
			).getString()
		);
		lines.add(
			Text.translatable(
				"sulkan.debug.info.cache.uri_source",
				shaderCacheStats.uriHits(),
				shaderCacheStats.uriReads(),
				Math.max(0L, shaderCacheStats.uriReads() - shaderCacheStats.uriHits()),
				sulkan$formatHitRate(shaderCacheStats.uriHits(), shaderCacheStats.uriReads())
			).getString()
		);
		lines.add(
			Text.translatable(
				"sulkan.debug.info.cache.world_candidates",
				worldCacheStats.hits(),
				worldCacheStats.requests(),
				Math.max(0L, worldCacheStats.requests() - worldCacheStats.hits()),
				sulkan$formatHitRate(worldCacheStats.hits(), worldCacheStats.requests())
			).getString()
		);
		lines.add(Text.translatable("sulkan.debug.info.hotkeys", modifierKey + "+R", modifierKey + "+" + openListKeyName).getString());
		return lines;
	}

	@Unique
	private static String sulkan$formatHitRate(long hits, long requests) {
		if (requests <= 0L) {
			return "0.0%";
		}
		long tenths = Math.round((hits * 1000.0) / requests);
		long whole = tenths / 10L;
		long fractional = Math.abs(tenths % 10L);
		return whole + "." + fractional + "%";
	}
}
