package cn.mxst.sulkan.shaderpack;

import cn.mxst.sulkan.Sulkan;
import cn.mxst.sulkan.config.SulkanConfig;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.util.Identifier;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;

public final class ShaderpackTextureBinder {
	private static final ConcurrentMap<String, Identifier> cachedShaderpackTextures = new ConcurrentHashMap<>();
	private static final Set<Identifier> registeredTextures = ConcurrentHashMap.newKeySet();
	private static final Set<String> warnedBindings = ConcurrentHashMap.newKeySet();

	private ShaderpackTextureBinder() {
	}

	public static synchronized void invalidateCaches() {
		TextureManager textureManager = null;
		try {
			MinecraftClient client = MinecraftClient.getInstance();
			if (client != null) {
				textureManager = client.getTextureManager();
			}
		} catch (Exception ignored) {
		}
		if (textureManager != null) {
			for (Identifier id : registeredTextures) {
				try {
					textureManager.destroyTexture(id);
				} catch (Exception ignored) {
				}
			}
		}
		registeredTextures.clear();
		cachedShaderpackTextures.clear();
		warnedBindings.clear();
	}

	public static void applyBindings(Pipeline pipeline) {
		if (pipeline == null) {
			return;
		}
		SulkanConfig config = SulkanConfig.get();
		if (config == null || !config.enableShaderpack) {
			return;
		}
		ShaderpackMetadata metadata = ShaderpackManager.getActiveShaderpack();
		if (metadata == null || metadata.textureBindings == null || metadata.textureBindings.isEmpty()) {
			return;
		}
		String stage = inferStage(pipeline.name);
		List<ImageDescriptor> descriptors = pipeline.getImageDescriptors();
		if (descriptors == null || descriptors.isEmpty()) {
			return;
		}
		for (ImageDescriptor descriptor : descriptors) {
			if (descriptor == null || descriptor.imageIdx < 0) {
				continue;
			}
			String sampler = descriptor.name;
			String bindingSource = metadata.textureBindings.resolve(stage, sampler);
			if (bindingSource == null || bindingSource.isBlank()) {
				continue;
			}
			Identifier textureId = resolveTextureIdentifier(bindingSource, metadata);
			if (textureId == null) {
				warnBindingOnce(stage, sampler, bindingSource);
				continue;
			}
			TextureManager textureManager = MinecraftClient.getInstance().getTextureManager();
			AbstractTexture texture = textureManager.getTexture(textureId);
			if (texture == null || texture.getGlTextureView() == null) {
				warnBindingOnce(stage, sampler, bindingSource);
				continue;
			}
			VRenderSystem.setShaderTexture(descriptor.imageIdx, texture.getGlTextureView());
		}
	}

	private static Identifier resolveTextureIdentifier(String source, ShaderpackMetadata metadata) {
		String trimmed = source == null ? "" : source.trim();
		if (trimmed.isBlank()) {
			return null;
		}
		if (trimmed.startsWith("resource:")) {
			return parseIdentifier(trimmed.substring("resource:".length()));
		}
		if (trimmed.startsWith("dynamic:")) {
			return parseIdentifier(trimmed.substring("dynamic:".length()));
		}
		if (trimmed.startsWith("shaderpack:")) {
			return loadShaderpackTexture(metadata.sourcePath, trimmed.substring("shaderpack:".length()));
		}
		if (trimmed.startsWith("raw:")) {
			warnBindingOnce("any", "raw", trimmed + " (raw textures are not enabled yet)");
			return null;
		}
		Identifier parsed = parseIdentifier(trimmed);
		if (parsed != null) {
			return parsed;
		}
		return loadShaderpackTexture(metadata.sourcePath, trimmed);
	}

	private static Identifier loadShaderpackTexture(Path shaderpackPath, String relativePath) {
		if (shaderpackPath == null || relativePath == null || relativePath.isBlank()) {
			return null;
		}
		String normalized = ShaderpackPipelineProgram.normalizePath(relativePath);
		if (normalized.isBlank()) {
			return null;
		}
		String cacheKey = shaderpackPath.toAbsolutePath().normalize() + "|" + normalized;
		Identifier cached = cachedShaderpackTextures.get(cacheKey);
		if (cached != null) {
			return cached;
		}
		byte[] bytes = readShaderpackBytes(shaderpackPath, normalized);
		if (bytes == null || bytes.length == 0) {
			return null;
		}
		Identifier textureId = registerDynamicTexture(shaderpackPath, normalized, bytes);
		if (textureId == null) {
			return null;
		}
		cachedShaderpackTextures.putIfAbsent(cacheKey, textureId);
		return textureId;
	}

	private static Identifier registerDynamicTexture(Path shaderpackPath, String relativePath, byte[] bytes) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null) {
			return null;
		}
		TextureManager textureManager = client.getTextureManager();
		if (textureManager == null) {
			return null;
		}
		String packSegment = shaderpackPath.getFileName() == null
			? "shaderpack"
			: sanitizePathSegment(shaderpackPath.getFileName().toString().toLowerCase(Locale.ROOT));
		String textureSegment = sanitizeRelativePath(relativePath);
		String uniqueSuffix = Integer.toHexString((shaderpackPath.toString() + "|" + relativePath).hashCode());
		Identifier textureId = Identifier.of(
			Sulkan.MOD_ID,
			"shaderpack/" + packSegment + "/" + textureSegment + "_" + uniqueSuffix
		);
		try {
			NativeImage image = NativeImage.read(new ByteArrayInputStream(bytes));
			NativeImageBackedTexture texture = new NativeImageBackedTexture(() -> textureId.toString(), image);
			textureManager.registerTexture(textureId, texture);
			registeredTextures.add(textureId);
			return textureId;
		} catch (Exception e) {
			String warningKey = "register:" + relativePath;
			if (warnedBindings.add(warningKey)) {
				Sulkan.LOGGER.warn("Failed to register shaderpack texture '{}': {}", relativePath, e.getMessage());
			}
			return null;
		}
	}

	private static byte[] readShaderpackBytes(Path shaderpackPath, String relativePath) {
		String normalized = ShaderpackPipelineProgram.normalizePath(relativePath);
		if (Files.isDirectory(shaderpackPath)) {
			Path file = shaderpackPath.resolve(normalized);
			if (!Files.exists(file)) {
				return null;
			}
			try {
				return Files.readAllBytes(file);
			} catch (IOException e) {
				return null;
			}
		}
		String fileName = shaderpackPath.getFileName() == null
			? ""
			: shaderpackPath.getFileName().toString().toLowerCase(Locale.ROOT);
		if (!fileName.endsWith(".zip")) {
			return null;
		}
		try (ZipFile zipFile = new ZipFile(shaderpackPath.toFile(), StandardCharsets.UTF_8)) {
			ZipEntry entry = zipFile.getEntry(normalized);
			if (entry == null) {
				return null;
			}
			try (InputStream stream = zipFile.getInputStream(entry)) {
				return stream.readAllBytes();
			}
		} catch (IOException e) {
			return null;
		}
	}

	private static Identifier parseIdentifier(String value) {
		String trimmed = value == null ? "" : value.trim();
		if (trimmed.isBlank()) {
			return null;
		}
		Identifier parsed = Identifier.tryParse(trimmed);
		if (parsed != null) {
			return parsed;
		}
		if (!trimmed.contains(":")) {
			return Identifier.of("minecraft", trimmed);
		}
		return null;
	}

	private static String inferStage(String pipelineName) {
		String lower = pipelineName == null ? "" : pipelineName.trim().toLowerCase(Locale.ROOT);
		if (lower.contains("terrain_earlyz")) {
			return "shadow";
		}
		if ("terrain".equals(lower) || lower.contains("/terrain")) {
			return "gbuffer";
		}
		if (lower.contains("cloud")) {
			return "lighting";
		}
		if (lower.contains("rendertype_item_entity_translucent_cull")) {
			return "translucent";
		}
		if (lower.contains("blit")) {
			return "postprocess";
		}
		if (lower.contains("screenquad") || lower.contains("animate_sprite")) {
			return "final";
		}
		return "any";
	}

	private static void warnBindingOnce(String stage, String sampler, String source) {
		String key = stage + "|" + sampler + "|" + source;
		if (warnedBindings.add(key)) {
			Sulkan.LOGGER.warn(
				"Unable to resolve custom texture binding stage='{}', sampler='{}', source='{}'.",
				stage,
				sampler,
				source
			);
		}
	}

	private static String sanitizePathSegment(String value) {
		if (value == null || value.isBlank()) {
			return "unknown";
		}
		String normalized = value.replace('\\', '/');
		normalized = normalized.replace(':', '_');
		normalized = normalized.replaceAll("[^a-z0-9._/-]", "_");
		normalized = normalized.replace("../", "");
		while (normalized.startsWith("/")) {
			normalized = normalized.substring(1);
		}
		while (normalized.endsWith("/")) {
			normalized = normalized.substring(0, normalized.length() - 1);
		}
		return normalized.isBlank() ? "unknown" : normalized;
	}

	private static String sanitizeRelativePath(String value) {
		String normalized = sanitizePathSegment(value);
		return normalized.isBlank() ? "texture" : normalized;
	}
}
