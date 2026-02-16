package cn.mxst.sulkan.compat.vulkanmod;

import cn.mxst.sulkan.Sulkan;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.MinecraftClient;
import net.vulkanmod.render.PipelineManager;
import net.vulkanmod.vulkan.Vulkan;

public final class SulkanVulkanPipelineReloader {
	private SulkanVulkanPipelineReloader() {
	}

	public static CompletableFuture<Void> reloadResourcesAndPipelines(MinecraftClient client) {
		if (client == null) {
			return CompletableFuture.completedFuture(null);
		}
		return client.reloadResources().thenRun(() -> client.execute(SulkanVulkanPipelineReloader::rebuildPipelinesSafely));
	}

	public static void rebuildPipelinesSafely() {
		Runnable task = () -> {
			try {
				Vulkan.waitIdle();
				PipelineManager.destroyPipelines();
				PipelineManager.init();
			} catch (Throwable t) {
				Sulkan.LOGGER.warn("Failed to rebuild VulkanMod pipelines.", t);
			}
		};
		if (RenderSystem.isOnRenderThread()) {
			task.run();
		} else {
			RenderSystem.queueFencedTask(task);
		}
	}
}
