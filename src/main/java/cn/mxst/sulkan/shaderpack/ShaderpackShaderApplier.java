package cn.mxst.sulkan.shaderpack;

import cn.mxst.sulkan.Sulkan;
import cn.mxst.sulkan.config.SulkanConfig;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ShaderpackShaderApplier {
	private static final String ASSETS_ROOT = "/assets/vulkanmod/";
	private static final String JAR_ASSETS_ROOT = "!/assets/vulkanmod/";
	private static final String SHADERS_PREFIX = "shaders/";
	private static final String MARKER_PREFIX = "@SULKAN:";
	private static final String PARAM_MARKER_PREFIX = "@SULKAN_PARAM:";
	private static final String MARKER_SUFFIX = "@";
	private static final String USE_FILE_KEY = "[use_file]";
	private static final Pattern NON_OPAQUE_UNIFORM_PATTERN = Pattern.compile(
		"(?m)^\\s*uniform\\s+(?!(sampler|image|subpassInput|accelerationStructure|atomic_uint)\\b)[A-Za-z_][A-Za-z0-9_]*\\s+[A-Za-z_][A-Za-z0-9_]*(\\s*\\[[^\\]]*\\])?\\s*;"
	);
	private static volatile ApplierCache cache = ApplierCache.EMPTY;

	private ShaderpackShaderApplier() {
	}

	public static void invalidateCaches() {
		cache = ApplierCache.EMPTY;
	}

	public static CacheStats getCacheStats() {
		return cache.snapshotStats();
	}

	public static InputStream tryOpenReplacedStream(String uriString) {
		String relativePath = extractRelativePath(uriString);
		if (relativePath == null || !relativePath.startsWith(SHADERS_PREFIX)) {
			return null;
		}
		SulkanConfig config = SulkanConfig.get();
		if (!config.enableShaderpack) {
			return null;
		}
		ShaderpackMetadata metadata = ShaderpackManager.getActiveShaderpack();
		ShaderpackConfig shaderConfig = ShaderpackManager.getActiveConfig();
		if (metadata == null || shaderConfig == null) {
			return null;
		}
		ApplierCache context = ensureCache(metadata);
		String normalizedTarget = normalizePath(relativePath);
		List<String> targetCandidates = buildTargetCandidates(normalizedTarget);
		ShaderpackPipelineProgram pipelineProgram = ShaderpackManager.resolveActivePipelineProgram(normalizedTarget);
		String mappedPath = null;
		if (pipelineProgram != null) {
			mappedPath = pipelineProgram.resolveSourcePath(normalizedTarget);
			if (mappedPath == null) {
				for (String candidate : targetCandidates) {
					mappedPath = pipelineProgram.resolveSourcePath(candidate);
					if (mappedPath != null) {
						break;
					}
				}
			}
		}
		if (mappedPath != null) {
			targetCandidates = withExtraCandidate(targetCandidates, mappedPath);
		}
		List<ShaderpackOptionDecl> matchedOptions = collectTargetOptions(context, targetCandidates);
		ShaderpackOptionDecl fileReplacement = null;
		List<ShaderpackOptionDecl> targetOptions = new ArrayList<>();
		for (ShaderpackOptionDecl option : matchedOptions) {
			if (USE_FILE_KEY.equals(option.key())) {
				if (fileReplacement == null) {
					fileReplacement = option;
				}
			} else {
				targetOptions.add(option);
			}
		}

		String source = null;
		if (fileReplacement != null) {
			String replacementPath = resolveEnumRenderValue(fileReplacement, shaderConfig);
			if (replacementPath != null) {
				String replacementSource = context.readShaderpackText(replacementPath);
				if (isLikelyVulkanCompatible(replacementSource)) {
					source = replacementSource;
				} else if (replacementSource != null) {
					Sulkan.LOGGER.warn("Skip incompatible shader replacement source '{}'.", replacementPath);
				}
			}
		}

		if (source == null) {
			if (mappedPath != null) {
				String mappedSource = context.readShaderpackText(mappedPath);
				if (isLikelyVulkanCompatible(mappedSource)) {
					source = mappedSource;
				} else if (mappedSource != null) {
					Sulkan.LOGGER.warn("Skip incompatible mapped shader source '{}'.", mappedPath);
				}
			}
		}

		if (source == null && targetOptions.isEmpty() && (pipelineProgram == null || pipelineProgram.params().isEmpty())) {
			return null;
		}
		if (source == null) {
			source = context.readUriText(uriString);
			if (source == null) {
				return null;
			}
		}
		if (pipelineProgram != null && pipelineProgram.params() != null && !pipelineProgram.params().isEmpty()) {
			source = applyPipelineParams(source, pipelineProgram.params());
		}
		if (!targetOptions.isEmpty()) {
			source = applyMarkers(source, targetOptions, shaderConfig);
		}
		return new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8));
	}

	private static String extractRelativePath(String uriString) {
		int index = uriString.indexOf(ASSETS_ROOT);
		int offset = ASSETS_ROOT.length();
		if (index < 0) {
			index = uriString.indexOf(JAR_ASSETS_ROOT);
			offset = JAR_ASSETS_ROOT.length();
		}
		if (index < 0) {
			return null;
		}
		String relative = uriString.substring(index + offset);
		return normalizePath(relative);
	}

	private static String normalizeTarget(String target) {
		String normalized = normalizePath(target);
		if (!normalized.startsWith(SHADERS_PREFIX)) {
			normalized = SHADERS_PREFIX + normalized;
		}
		return normalized;
	}

	private static String normalizePath(String path) {
		String normalized = path.replace('\\', '/');
		while (normalized.startsWith("/")) {
			normalized = normalized.substring(1);
		}
		return normalized;
	}

	private static ApplierCache ensureCache(ShaderpackMetadata metadata) {
		ApplierCache local = cache;
		if (local.matches(metadata)) {
			return local;
		}
		ApplierCache rebuilt = ApplierCache.build(metadata);
		cache = rebuilt;
		return rebuilt;
	}

	private static List<ShaderpackOptionDecl> collectTargetOptions(ApplierCache context, List<String> targetCandidates) {
		LinkedHashSet<ShaderpackOptionDecl> unique = new LinkedHashSet<>();
		for (String candidate : targetCandidates) {
			List<ShaderpackOptionDecl> options = context.optionsByTarget.get(candidate);
			context.recordTargetLookup(options != null && !options.isEmpty());
			if (options == null || options.isEmpty()) {
				continue;
			}
			unique.addAll(options);
		}
		if (unique.isEmpty()) {
			return List.of();
		}
		return List.copyOf(unique);
	}

	private static List<String> buildTargetCandidates(String normalizedTarget) {
		LinkedHashSet<String> candidates = new LinkedHashSet<>();
		candidates.add(normalizedTarget);
		addExtensionAlias(normalizedTarget, candidates);

		int slash = normalizedTarget.lastIndexOf('/');
		if (slash >= 0 && slash < normalizedTarget.length() - 1) {
			String fileName = normalizedTarget.substring(slash + 1);
			String rootShaderPath = SHADERS_PREFIX + fileName;
			candidates.add(rootShaderPath);
			addExtensionAlias(rootShaderPath, candidates);
		}
		return List.copyOf(candidates);
	}

	private static List<String> withExtraCandidate(List<String> baseCandidates, String extraPath) {
		LinkedHashSet<String> output = new LinkedHashSet<>(baseCandidates);
		String normalized = normalizePath(extraPath);
		output.add(normalized);
		addExtensionAlias(normalized, output);

		int slash = normalized.lastIndexOf('/');
		if (slash >= 0 && slash < normalized.length() - 1) {
			String rootShaderPath = SHADERS_PREFIX + normalized.substring(slash + 1);
			output.add(rootShaderPath);
			addExtensionAlias(rootShaderPath, output);
		}
		return List.copyOf(output);
	}

	private static void addExtensionAlias(String path, Set<String> output) {
		String alias = extensionAlias(path);
		if (alias != null) {
			output.add(alias);
		}
	}

	private static String extensionAlias(String path) {
		if (path.endsWith(".vsh")) {
			return path.substring(0, path.length() - 4) + ".vert";
		}
		if (path.endsWith(".vert")) {
			return path.substring(0, path.length() - 5) + ".vsh";
		}
		if (path.endsWith(".fsh")) {
			return path.substring(0, path.length() - 4) + ".frag";
		}
		if (path.endsWith(".frag")) {
			return path.substring(0, path.length() - 5) + ".fsh";
		}
		return null;
	}

	private static boolean isLikelyVulkanCompatible(String source) {
		if (source == null) {
			return false;
		}
		return !NON_OPAQUE_UNIFORM_PATTERN.matcher(source).find();
	}

	private static String readUriText(String uriString) {
		try {
			URI uri = new URI(uriString);
			try {
				Path path = Paths.get(uri);
				return Files.readString(path, StandardCharsets.UTF_8);
			} catch (FileSystemNotFoundException ignored) {
				// Fallback to URL stream for jar/file systems not mounted as java.nio.FileSystem.
				try (InputStream stream = uri.toURL().openStream()) {
					return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
				}
			}
		} catch (FileSystemNotFoundException e) {
			Sulkan.LOGGER.warn("Shader file system not available for {}", uriString);
		} catch (Exception e) {
			Sulkan.LOGGER.warn("Failed to read shader source {}", uriString, e);
		}
		return null;
	}

	private static String readShaderpackText(Path shaderpackPath, String relativePath) {
		String normalized = normalizePath(relativePath);
		if (Files.isDirectory(shaderpackPath)) {
			Path file = shaderpackPath.resolve(normalized);
			if (!Files.exists(file)) {
				return null;
			}
			try {
				return Files.readString(file, StandardCharsets.UTF_8);
			} catch (Exception e) {
				Sulkan.LOGGER.warn("Failed to read shaderpack file {}", normalized, e);
				return null;
			}
		}
		if (shaderpackPath.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
			try (ZipFile zipFile = new ZipFile(shaderpackPath.toFile(), StandardCharsets.UTF_8)) {
				ZipEntry entry = zipFile.getEntry(normalized);
				if (entry == null) {
					return null;
				}
				try (InputStream stream = zipFile.getInputStream(entry)) {
					return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
				}
			} catch (Exception e) {
				Sulkan.LOGGER.warn("Failed to read shaderpack zip entry {}", normalized, e);
			}
		}
		return null;
	}

	private static String applyMarkers(String source, List<ShaderpackOptionDecl> options, ShaderpackConfig config) {
		String updated = source;
		for (ShaderpackOptionDecl option : options) {
			String markerKey = option.key();
			if (markerKey == null || markerKey.isBlank()) {
				continue;
			}
			String replacement = resolveReplacement(option, config);
			if (replacement == null) {
				continue;
			}
			String marker = MARKER_PREFIX + markerKey + MARKER_SUFFIX;
			if (!updated.contains(marker)) {
				continue;
			}
			updated = updated.replace(marker, replacement);
		}
		return updated;
	}

	private static String applyPipelineParams(String source, Map<String, String> params) {
		String updated = source;
		for (Map.Entry<String, String> entry : params.entrySet()) {
			String key = entry.getKey();
			if (key == null || key.isBlank()) {
				continue;
			}
			String value = entry.getValue();
			if (value == null) {
				continue;
			}
			String marker = PARAM_MARKER_PREFIX + key + MARKER_SUFFIX;
			if (!updated.contains(marker)) {
				continue;
			}
			updated = updated.replace(marker, value);
		}
		return updated;
	}

	private static String resolveReplacement(ShaderpackOptionDecl option, ShaderpackConfig config) {
		String type = option.type();
		Object value = config.getValue(option.path());
		if (value == null) {
			value = option.defaultValue();
		}
		if (type == null) {
			return value == null ? null : value.toString();
		}
		switch (type.toLowerCase(Locale.ROOT)) {
			case "enum" -> {
				String renderValue = resolveEnumRenderValue(option, value);
				return renderValue != null ? renderValue : (value == null ? null : value.toString());
			}
			default -> {
				return value == null ? null : value.toString();
			}
		}
	}

	private static String resolveEnumRenderValue(ShaderpackOptionDecl option, ShaderpackConfig config) {
		Object value = config.getValue(option.path());
		if (value == null) {
			value = option.defaultValue();
		}
		return resolveEnumRenderValue(option, value);
	}

	private static String resolveEnumRenderValue(ShaderpackOptionDecl option, Object selected) {
		if (selected == null) {
			return null;
		}
		String selectedValue = selected.toString();
		int index = option.values().indexOf(selectedValue);
		if (index < 0) {
			return null;
		}
		List<String> renderValues = option.renderValues();
		if (renderValues == null || renderValues.isEmpty() || index >= renderValues.size()) {
			return null;
		}
		return renderValues.get(index);
	}

	private static List<ShaderpackOptionDecl> flattenOptions(List<ShaderpackOptionDecl> options) {
		if (options == null || options.isEmpty()) {
			return List.of();
		}
		List<ShaderpackOptionDecl> output = new ArrayList<>();
		for (ShaderpackOptionDecl option : options) {
			if (option == null) {
				continue;
			}
			if ("page".equalsIgnoreCase(option.type())) {
				output.addAll(flattenOptions(option.children()));
			} else {
				output.add(option);
			}
		}
		return output;
	}

	public record CacheStats(
		long targetLookups,
		long targetHits,
		long shaderpackReads,
		long shaderpackHits,
		long uriReads,
		long uriHits
	) {
	}

	private static final class ApplierCache {
		private static final ApplierCache EMPTY = new ApplierCache(null, null, Map.of());
		private final ShaderpackMetadata metadata;
		private final Path sourcePath;
		private final Map<String, List<ShaderpackOptionDecl>> optionsByTarget;
		private final ConcurrentMap<String, Optional<String>> shaderpackTextCache = new ConcurrentHashMap<>();
		private final ConcurrentMap<String, Optional<String>> uriTextCache = new ConcurrentHashMap<>();
		private final LongAdder targetLookups = new LongAdder();
		private final LongAdder targetHits = new LongAdder();
		private final LongAdder shaderpackReads = new LongAdder();
		private final LongAdder shaderpackHits = new LongAdder();
		private final LongAdder uriReads = new LongAdder();
		private final LongAdder uriHits = new LongAdder();

		private ApplierCache(ShaderpackMetadata metadata, Path sourcePath, Map<String, List<ShaderpackOptionDecl>> optionsByTarget) {
			this.metadata = metadata;
			this.sourcePath = sourcePath;
			this.optionsByTarget = optionsByTarget;
		}

		private static ApplierCache build(ShaderpackMetadata metadata) {
			Map<String, List<ShaderpackOptionDecl>> byTarget = new HashMap<>();
			for (ShaderpackOptionDecl option : flattenOptions(metadata.options)) {
				if (option == null || option.target() == null || option.key() == null) {
					continue;
				}
				String target = normalizeTarget(option.target());
				byTarget.computeIfAbsent(target, ignored -> new ArrayList<>()).add(option);
			}
			Map<String, List<ShaderpackOptionDecl>> immutable = new HashMap<>();
			for (Map.Entry<String, List<ShaderpackOptionDecl>> entry : byTarget.entrySet()) {
				immutable.put(entry.getKey(), List.copyOf(entry.getValue()));
			}
			return new ApplierCache(metadata, metadata.sourcePath, Map.copyOf(immutable));
		}

		private boolean matches(ShaderpackMetadata metadata) {
			return this.metadata == metadata;
		}

		private void recordTargetLookup(boolean hit) {
			targetLookups.increment();
			if (hit) {
				targetHits.increment();
			}
		}

		private CacheStats snapshotStats() {
			return new CacheStats(
				targetLookups.sum(),
				targetHits.sum(),
				shaderpackReads.sum(),
				shaderpackHits.sum(),
				uriReads.sum(),
				uriHits.sum()
			);
		}

		private String readShaderpackText(String relativePath) {
			String normalized = normalizePath(relativePath);
			shaderpackReads.increment();
			Optional<String> cached = shaderpackTextCache.get(normalized);
			if (cached != null) {
				shaderpackHits.increment();
				return cached.orElse(null);
			}
			Optional<String> loaded = Optional.ofNullable(ShaderpackShaderApplier.readShaderpackText(sourcePath, normalized));
			Optional<String> existing = shaderpackTextCache.putIfAbsent(normalized, loaded);
			if (existing != null) {
				shaderpackHits.increment();
				return existing.orElse(null);
			}
			return loaded.orElse(null);
		}

		private String readUriText(String uriString) {
			uriReads.increment();
			Optional<String> cached = uriTextCache.get(uriString);
			if (cached != null) {
				uriHits.increment();
				return cached.orElse(null);
			}
			Optional<String> loaded = Optional.ofNullable(ShaderpackShaderApplier.readUriText(uriString));
			Optional<String> existing = uriTextCache.putIfAbsent(uriString, loaded);
			if (existing != null) {
				uriHits.increment();
				return existing.orElse(null);
			}
			return loaded.orElse(null);
		}
	}
}
