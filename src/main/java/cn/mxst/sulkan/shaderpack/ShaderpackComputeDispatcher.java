package cn.mxst.sulkan.shaderpack;

import cn.mxst.sulkan.Sulkan;
import cn.mxst.sulkan.config.SulkanConfig;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.framebuffer.SwapChain;
import net.vulkanmod.vulkan.shader.SPIRVUtils;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.lwjgl.vulkan.VkCommandBuffer;

public final class ShaderpackComputeDispatcher {
	private static final ConcurrentMap<String, ComputePipeline> PIPELINES = new ConcurrentHashMap<>();
	private static final Set<String> WARNED_SEGMENTS = ConcurrentHashMap.newKeySet();
	private static final Set<String> WARNED_PIPELINES = ConcurrentHashMap.newKeySet();
	private static final ImagePool IMAGE_POOL = new ImagePool();
	private static final int MAX_PIPELINES = 64;

	private ShaderpackComputeDispatcher() {
	}

	public static synchronized void invalidateCaches() {
		for (ComputePipeline pipeline : PIPELINES.values()) {
			pipeline.free();
		}
		PIPELINES.clear();
		WARNED_SEGMENTS.clear();
		WARNED_PIPELINES.clear();
		IMAGE_POOL.clear();
	}

	public static void dispatch(VkCommandBuffer commandBuffer) {
		if (commandBuffer == null) {
			return;
		}
		SulkanConfig config = SulkanConfig.get();
		if (config == null || !config.enableShaderpack) {
			return;
		}
		List<ShaderpackPipelineProgram> programs = ShaderpackManager.resolveActivePipelinePrograms();
		if (programs.isEmpty()) {
			return;
		}
		List<ShaderpackPipelineProgram> computePrograms = new ArrayList<>();
		for (ShaderpackPipelineProgram program : programs) {
			if (program.compute() != null && !program.compute().isBlank()) {
				computePrograms.add(program);
			}
		}
		if (computePrograms.isEmpty()) {
			return;
		}

		SwapChain swapChain = net.vulkanmod.vulkan.Renderer.getInstance().getSwapChain();
		if (swapChain == null || swapChain.getWidth() <= 0 || swapChain.getHeight() <= 0) {
			return;
		}
		Map<String, Boolean> altStateByImage = new LinkedHashMap<>();

		for (ShaderpackPipelineProgram program : computePrograms) {
			try {
				dispatchProgram(program, commandBuffer, swapChain, altStateByImage);
			} catch (Exception e) {
				String key = program.stage() + ":" + program.segmentName() + "#" + program.index();
				if (WARNED_SEGMENTS.add(key)) {
					Sulkan.LOGGER.warn("Failed to dispatch compute segment '{}': {}", key, e.getMessage());
				}
			}
		}
	}

	private static void dispatchProgram(
		ShaderpackPipelineProgram program,
		VkCommandBuffer commandBuffer,
		SwapChain swapChain,
		Map<String, Boolean> altStateByImage
	) {
		List<ImageBindingSpec> bindings = collectBindings(program);
		if (bindings.isEmpty()) {
			return;
		}
		ShaderpackShaderApplier.LoadedSource loaded = ShaderpackShaderApplier.loadPipelineSource(program, program.compute(), "compute");
		if (loaded == null || loaded.source() == null || loaded.source().isBlank()) {
			return;
		}

		String cacheKey = buildPipelineCacheKey(program, loaded.source(), bindings);
		ComputePipeline pipeline = PIPELINES.get(cacheKey);
		if (pipeline == null) {
			ComputePipeline created = createPipeline(cacheKey, loaded.path(), loaded.source(), bindings.size());
			if (created == null) {
				return;
			}
			if (PIPELINES.size() >= MAX_PIPELINES) {
				invalidateCaches();
			}
			ComputePipeline existing = PIPELINES.putIfAbsent(cacheKey, created);
			pipeline = existing == null ? created : existing;
			if (existing != null) {
				created.free();
			}
		}

		boolean defaultAlt = "alt".equalsIgnoreCase(program.pingPong());
		List<VulkanImage> images = new ArrayList<>(bindings.size());
		for (ImageBindingSpec binding : bindings) {
			String name = normalizeImageBinding(binding.binding());
			boolean useAlt = altStateByImage.getOrDefault(name, defaultAlt);
			ImageExtent extent = resolveImageExtent(program, name, swapChain.getWidth(), swapChain.getHeight());
			if (extent.width() <= 0 || extent.height() <= 0) {
				images.add(null);
				continue;
			}
			images.add(IMAGE_POOL.resolve(name, useAlt, extent.width(), extent.height(), VulkanImage.DefaultFormat));
		}
		if (images.stream().anyMatch(image -> image == null)) {
			return;
		}

		DispatchSize dispatch = resolveDispatchSize(program, swapChain.getWidth(), swapChain.getHeight());
		if (dispatch.x() <= 0 || dispatch.y() <= 0 || dispatch.z() <= 0) {
			return;
		}

		try (MemoryStack stack = MemoryStack.stackPush()) {
			for (VulkanImage image : images) {
				transitionToGeneral(image, stack, commandBuffer);
			}
			pipeline.updateDescriptors(images, stack);
			VK10.vkCmdBindPipeline(commandBuffer, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.pipeline());
			VK10.vkCmdBindDescriptorSets(
				commandBuffer,
				VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
				pipeline.layout(),
				0,
				stack.longs(pipeline.descriptorSet()),
				null
			);
			VK10.vkCmdDispatch(commandBuffer, dispatch.x(), dispatch.y(), dispatch.z());
			insertComputeMemoryBarrier(commandBuffer, stack);
		}

		for (Map.Entry<String, Boolean> flip : program.flips().entrySet()) {
			if (!Boolean.TRUE.equals(flip.getValue())) {
				continue;
			}
			String key = normalizeImageBinding(mapFlipTargetToImageBinding(flip.getKey()));
			boolean current = altStateByImage.getOrDefault(key, defaultAlt);
			altStateByImage.put(key, !current);
		}
	}

	private static void transitionToGeneral(VulkanImage image, MemoryStack stack, VkCommandBuffer commandBuffer) {
		if (image == null) {
			return;
		}
		int oldLayout = image.getCurrentLayout();
		if (oldLayout == VK10.VK_IMAGE_LAYOUT_GENERAL) {
			return;
		}
		VulkanImage.transitionLayout(
			stack,
			commandBuffer,
			image,
			oldLayout,
			VK10.VK_IMAGE_LAYOUT_GENERAL,
			VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
			VK10.VK_ACCESS_MEMORY_READ_BIT | VK10.VK_ACCESS_MEMORY_WRITE_BIT,
			VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
			VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT
		);
		image.setCurrentLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
	}

	private static void insertComputeMemoryBarrier(VkCommandBuffer commandBuffer, MemoryStack stack) {
		VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
		barrier.get(0)
			.sType$Default()
			.srcAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT)
			.dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT);
		VK10.vkCmdPipelineBarrier(
			commandBuffer,
			VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
			VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
			0,
			barrier,
			null,
			null
		);
	}

	private static DispatchSize resolveDispatchSize(ShaderpackPipelineProgram program, int width, int height) {
		List<Integer> workGroups = program.workGroups();
		if (workGroups != null && workGroups.size() == 3) {
			return new DispatchSize(workGroups.get(0), workGroups.get(1), workGroups.get(2));
		}
		List<Integer> workGroupsRender = program.workGroupsRender();
		if (workGroupsRender != null && workGroupsRender.size() == 3) {
			int gx = Math.max(1, ceilDiv(width, Math.max(1, workGroupsRender.get(0))));
			int gy = Math.max(1, ceilDiv(height, Math.max(1, workGroupsRender.get(1))));
			int gz = Math.max(1, workGroupsRender.get(2));
			return new DispatchSize(gx, gy, gz);
		}
		return new DispatchSize(1, 1, 1);
	}

	private static int ceilDiv(int value, int divisor) {
		if (divisor <= 0) {
			return 1;
		}
		return (value + divisor - 1) / divisor;
	}

	private static List<ImageBindingSpec> collectBindings(ShaderpackPipelineProgram program) {
		LinkedHashMap<String, Boolean> byName = new LinkedHashMap<>();
		for (String name : program.imagesRead()) {
			if (name == null || name.isBlank()) {
				continue;
			}
			byName.putIfAbsent(normalizeImageBinding(name), false);
		}
		for (String name : program.imagesWrite()) {
			if (name == null || name.isBlank()) {
				continue;
			}
			byName.put(normalizeImageBinding(name), true);
		}
		List<ImageBindingSpec> bindings = new ArrayList<>(byName.size());
		for (Map.Entry<String, Boolean> entry : byName.entrySet()) {
			bindings.add(new ImageBindingSpec(entry.getKey(), entry.getValue()));
		}
		return bindings;
	}

	private static String normalizeImageBinding(String binding) {
		return binding == null ? "" : binding.trim().toLowerCase(Locale.ROOT);
	}

	private static String mapFlipTargetToImageBinding(String target) {
		String normalized = normalizeImageBinding(target);
		if (normalized.matches("^colortex([0-9]|1[0-5])$")) {
			String suffix = normalized.substring("colortex".length());
			return "colorimg" + suffix;
		}
		return normalized;
	}

	private static ImageExtent resolveImageExtent(
		ShaderpackPipelineProgram program,
		String imageBinding,
		int swapWidth,
		int swapHeight
	) {
		int width = Math.max(1, swapWidth);
		int height = Math.max(1, swapHeight);
		String renderTarget = mapImageBindingToRenderTarget(imageBinding);
		Map<String, List<Integer>> sizes = program.bufferSizes() == null ? Map.of() : program.bufferSizes();
		List<Integer> explicitSize = sizes.get(renderTarget);
		if (explicitSize != null && explicitSize.size() == 2) {
			width = Math.max(1, explicitSize.get(0));
			height = Math.max(1, explicitSize.get(1));
			return new ImageExtent(width, height);
		}
		Map<String, List<Double>> scales = program.bufferScales() == null ? Map.of() : program.bufferScales();
		List<Double> scale = scales.get(renderTarget);
		if (scale != null && scale.size() == 2) {
			double sx = Math.max(0.01D, scale.get(0));
			double sy = Math.max(0.01D, scale.get(1));
			width = Math.max(1, (int) Math.round(swapWidth * sx));
			height = Math.max(1, (int) Math.round(swapHeight * sy));
		}
		return new ImageExtent(width, height);
	}

	private static String mapImageBindingToRenderTarget(String imageBinding) {
		String normalized = normalizeImageBinding(imageBinding);
		if (normalized.matches("^colorimg([0-9]|1[0-5])$")) {
			return "colortex" + normalized.substring("colorimg".length());
		}
		if (normalized.matches("^shadowcolorimg([0-9]|1[0-5])$")) {
			return "shadowcolor" + normalized.substring("shadowcolorimg".length());
		}
		return normalized;
	}

	private static String buildPipelineCacheKey(
		ShaderpackPipelineProgram program,
		String source,
		List<ImageBindingSpec> bindings
	) {
		StringBuilder builder = new StringBuilder(256);
		builder
			.append(program.worldId()).append('|')
			.append(program.stage()).append('|')
			.append(program.segmentName()).append('|')
			.append(program.index()).append('|')
			.append(Integer.toHexString(source.hashCode()));
		for (ImageBindingSpec binding : bindings) {
			builder
				.append('|')
				.append(binding.binding())
				.append(':')
				.append(binding.writable() ? 'w' : 'r');
		}
		return builder.toString();
	}

	private static ComputePipeline createPipeline(String key, String sourcePath, String source, int bindingCount) {
		SPIRVUtils.SPIRV spirv = SPIRVUtils.compileShader(
			sourcePath == null ? key : sourcePath,
			source,
			SPIRVUtils.ShaderKind.COMPUTE_SHADER
		);
		if (spirv == null) {
			if (WARNED_PIPELINES.add(key)) {
				Sulkan.LOGGER.warn("Failed to compile compute shader '{}'.", sourcePath);
			}
			return null;
		}
		try {
			return ComputePipeline.create(spirv, bindingCount);
		} catch (RuntimeException e) {
			if (WARNED_PIPELINES.add(key)) {
				Sulkan.LOGGER.warn("Failed to create compute pipeline '{}': {}", sourcePath, e.getMessage());
			}
			return null;
		} finally {
			spirv.free();
		}
	}

	private record ImageBindingSpec(String binding, boolean writable) {
	}

	private record ImageExtent(int width, int height) {
	}

	private record DispatchSize(int x, int y, int z) {
	}

	private static final class ComputePipeline {
		private final long descriptorSetLayout;
		private final long descriptorPool;
		private final long descriptorSet;
		private final long layout;
		private final long shaderModule;
		private final long pipeline;
		private final int bindingCount;

		private ComputePipeline(
			long descriptorSetLayout,
			long descriptorPool,
			long descriptorSet,
			long layout,
			long shaderModule,
			long pipeline,
			int bindingCount
		) {
			this.descriptorSetLayout = descriptorSetLayout;
			this.descriptorPool = descriptorPool;
			this.descriptorSet = descriptorSet;
			this.layout = layout;
			this.shaderModule = shaderModule;
			this.pipeline = pipeline;
			this.bindingCount = bindingCount;
		}

		private static ComputePipeline create(SPIRVUtils.SPIRV spirv, int bindingCount) {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				long shaderModule = createShaderModule(spirv.bytecode(), stack);
				long descriptorSetLayout = createDescriptorSetLayout(bindingCount, stack);
				long layout = createPipelineLayout(descriptorSetLayout, stack);
				long descriptorPool = createDescriptorPool(bindingCount, stack);
				long descriptorSet = allocateDescriptorSet(descriptorPool, descriptorSetLayout, stack);
				long pipeline = createComputePipeline(shaderModule, layout, stack);
				return new ComputePipeline(
					descriptorSetLayout,
					descriptorPool,
					descriptorSet,
					layout,
					shaderModule,
					pipeline,
					bindingCount
				);
			}
		}

		private static long createShaderModule(java.nio.ByteBuffer bytecode, MemoryStack stack) {
			VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack);
			info.sType$Default();
			info.pCode(bytecode);
			LongBuffer pShaderModule = stack.mallocLong(1);
			int result = VK10.vkCreateShaderModule(Vulkan.getVkDevice(), info, null, pShaderModule);
			if (result != VK10.VK_SUCCESS) {
				throw new RuntimeException("vkCreateShaderModule failed: " + result);
			}
			return pShaderModule.get(0);
		}

		private static long createDescriptorSetLayout(int bindingCount, MemoryStack stack) {
			VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(bindingCount, stack);
			for (int i = 0; i < bindingCount; i++) {
				bindings.get(i)
					.binding(i)
					.descriptorCount(1)
					.descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
					.stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
			}
			VkDescriptorSetLayoutCreateInfo info = VkDescriptorSetLayoutCreateInfo.calloc(stack);
			info.sType$Default();
			info.pBindings(bindings);
			LongBuffer pLayout = stack.mallocLong(1);
			int result = VK10.vkCreateDescriptorSetLayout(Vulkan.getVkDevice(), info, null, pLayout);
			if (result != VK10.VK_SUCCESS) {
				throw new RuntimeException("vkCreateDescriptorSetLayout failed: " + result);
			}
			return pLayout.get(0);
		}

		private static long createPipelineLayout(long descriptorSetLayout, MemoryStack stack) {
			VkPipelineLayoutCreateInfo info = VkPipelineLayoutCreateInfo.calloc(stack);
			info.sType$Default();
			info.pSetLayouts(stack.longs(descriptorSetLayout));
			LongBuffer pLayout = stack.mallocLong(1);
			int result = VK10.vkCreatePipelineLayout(Vulkan.getVkDevice(), info, null, pLayout);
			if (result != VK10.VK_SUCCESS) {
				throw new RuntimeException("vkCreatePipelineLayout failed: " + result);
			}
			return pLayout.get(0);
		}

		private static long createDescriptorPool(int bindingCount, MemoryStack stack) {
			VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack);
			poolSizes.get(0)
				.type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
				.descriptorCount(bindingCount);

			VkDescriptorPoolCreateInfo info = VkDescriptorPoolCreateInfo.calloc(stack);
			info.sType$Default();
			info.maxSets(1);
			info.pPoolSizes(poolSizes);

			LongBuffer pPool = stack.mallocLong(1);
			int result = VK10.vkCreateDescriptorPool(Vulkan.getVkDevice(), info, null, pPool);
			if (result != VK10.VK_SUCCESS) {
				throw new RuntimeException("vkCreateDescriptorPool failed: " + result);
			}
			return pPool.get(0);
		}

		private static long allocateDescriptorSet(long descriptorPool, long descriptorSetLayout, MemoryStack stack) {
			VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack);
			allocInfo.sType$Default();
			allocInfo.descriptorPool(descriptorPool);
			allocInfo.pSetLayouts(stack.longs(descriptorSetLayout));
			LongBuffer pSet = stack.mallocLong(1);
			int result = VK10.vkAllocateDescriptorSets(Vulkan.getVkDevice(), allocInfo, pSet);
			if (result != VK10.VK_SUCCESS) {
				throw new RuntimeException("vkAllocateDescriptorSets failed: " + result);
			}
			return pSet.get(0);
		}

		private static long createComputePipeline(long shaderModule, long layout, MemoryStack stack) {
			VkPipelineShaderStageCreateInfo shaderStage = VkPipelineShaderStageCreateInfo.calloc(stack);
			shaderStage.sType$Default();
			shaderStage.stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
			shaderStage.module(shaderModule);
			shaderStage.pName(stack.UTF8("main"));

			VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack);
			pipelineInfo.get(0)
				.sType$Default()
				.stage(shaderStage)
				.layout(layout);

			LongBuffer pPipeline = stack.mallocLong(1);
			int result = VK10.vkCreateComputePipelines(Vulkan.getVkDevice(), 0L, pipelineInfo, null, pPipeline);
			if (result != VK10.VK_SUCCESS) {
				throw new RuntimeException("vkCreateComputePipelines failed: " + result);
			}
			return pPipeline.get(0);
		}

		private void updateDescriptors(List<VulkanImage> images, MemoryStack stack) {
			VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(bindingCount, stack);
			for (int i = 0; i < bindingCount; i++) {
				VulkanImage image = images.get(i);
				VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack);
				imageInfo.get(0)
					.imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
					.imageView(image.getImageView())
					.sampler(0L);
				writes.get(i)
					.sType$Default()
					.dstSet(descriptorSet)
					.dstBinding(i)
					.dstArrayElement(0)
					.descriptorCount(1)
					.descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
					.pImageInfo(imageInfo);
			}
			VK10.vkUpdateDescriptorSets(Vulkan.getVkDevice(), writes, null);
		}

		private long descriptorSet() {
			return descriptorSet;
		}

		private long layout() {
			return layout;
		}

		private long pipeline() {
			return pipeline;
		}

		private void free() {
			VK10.vkDestroyPipeline(Vulkan.getVkDevice(), pipeline, null);
			VK10.vkDestroyShaderModule(Vulkan.getVkDevice(), shaderModule, null);
			VK10.vkDestroyPipelineLayout(Vulkan.getVkDevice(), layout, null);
			VK10.vkDestroyDescriptorSetLayout(Vulkan.getVkDevice(), descriptorSetLayout, null);
			VK10.vkDestroyDescriptorPool(Vulkan.getVkDevice(), descriptorPool, null);
		}
	}

	private static final class ImagePool {
		private final Map<String, ImagePair> pairs = new LinkedHashMap<>();

		private VulkanImage resolve(String name, boolean alt, int width, int height, int format) {
			if (width <= 0 || height <= 0 || format <= 0) {
				return null;
			}
			ImagePair pair = pairs.get(name);
			if (pair == null || pair.width() != width || pair.height() != height || pair.format() != format) {
				if (pair != null) {
					pair.main().free();
					pair.alt().free();
				}
				pair = createPair(name, width, height, format);
				if (pair != null) {
					pairs.put(name, pair);
				}
			}
			if (pair == null) {
				return null;
			}
			return alt ? pair.alt() : pair.main();
		}

		private ImagePair createPair(String name, int width, int height, int format) {
			VulkanImage main = createStorageImage(name + "_main", width, height, format);
			VulkanImage alt = createStorageImage(name + "_alt", width, height, format);
			if (main == null || alt == null) {
				if (main != null) {
					main.free();
				}
				if (alt != null) {
					alt.free();
				}
				return null;
			}
			return new ImagePair(main, alt, width, height, format);
		}

		private VulkanImage createStorageImage(String name, int width, int height, int format) {
			int usage = VK10.VK_IMAGE_USAGE_STORAGE_BIT
				| VK10.VK_IMAGE_USAGE_SAMPLED_BIT
				| VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT
				| VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
			return VulkanImage.builder(width, height)
				.setName("sulkan_" + name)
				.setFormat(format)
				.setUsage(usage)
				.setLinearFiltering(false)
				.setClamp(true)
				.createVulkanImage();
		}

		private void clear() {
			for (ImagePair pair : pairs.values()) {
				pair.main().free();
				pair.alt().free();
			}
			pairs.clear();
		}
	}

	private record ImagePair(VulkanImage main, VulkanImage alt, int width, int height, int format) {
	}
}
