package cn.mxst.sulkan.shaderpack;

import cn.mxst.sulkan.Sulkan;
import cn.mxst.sulkan.config.SulkanConfig;
import java.io.ByteArrayInputStream;
import java.io.IOException;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.minecraft.client.MinecraftClient;

public final class ShaderpackShaderApplier {
	private static final String ASSETS_ROOT = "/assets/vulkanmod/";
	private static final String JAR_ASSETS_ROOT = "!/assets/vulkanmod/";
	private static final String SHADERS_PREFIX = "shaders/";
	private static final String MARKER_PREFIX = "@SULKAN:";
	private static final String PARAM_MARKER_PREFIX = "@SULKAN_PARAM:";
	private static final String MARKER_SUFFIX = "@";
	private static final String USE_FILE_KEY = "[use_file]";
	private static final int MAX_INCLUDE_DEPTH = 16;
	private static final List<String> COMPATIBILITY_VARIANT_SUFFIXES = List.of(".sulkan", ".vulkan");
	private static final List<String> COMPATIBILITY_VARIANT_ROOTS = List.of("shaders/sulkan/", "shaders/vulkan/");
	private static final Pattern GLSL_VERSION_PATTERN = Pattern.compile("(?m)^\\s*#version\\s+(\\d+)(?:\\s+(\\w+))?");
	private static final Pattern INCLUDE_DIRECTIVE_PATTERN = Pattern.compile("(?m)^\\s*#include\\s+([\"<])([^\">]+)[\">]\\s*(?://.*)?$");
	private static final Pattern PRAGMA_ONCE_PATTERN = Pattern.compile("(?m)^\\s*#pragma\\s+once\\s*(?://.*)?$");
	private static final Pattern NON_OPAQUE_UNIFORM_PATTERN = Pattern.compile(
		"(?m)^\\s*uniform\\s+(?!(sampler|image|subpassInput|accelerationStructure|atomic_uint)\\b)[A-Za-z_][A-Za-z0-9_]*\\s+[A-Za-z_][A-Za-z0-9_]*(\\s*\\[[^\\]]*\\])?\\s*;"
	);
	private static final Pattern LEGACY_KEYWORD_PATTERN = Pattern.compile("(?m)^\\s*(attribute|varying)\\b");
	private static final Pattern LEGACY_BUILTIN_PATTERN = Pattern.compile(
		"\\b(gl_Vertex|gl_Normal|gl_Color|gl_MultiTexCoord[0-9]+|gl_TextureMatrix|gl_ModelViewMatrix|gl_NormalMatrix)\\b"
	);
	private static final Pattern BARE_USER_INTERFACE_PATTERN = Pattern.compile(
		"(?m)^\\s*(?:flat\\s+|smooth\\s+|noperspective\\s+|centroid\\s+|sample\\s+|invariant\\s+)*(in|out)\\s+[A-Za-z_][A-Za-z0-9_]*\\s+[A-Za-z_][A-Za-z0-9_]*(\\s*\\[[^\\]]*\\])?\\s*;"
	);
	private static final String DEBUG_SAVE_PROPERTY = "sulkan.shaders.debug.save";
	private static final String DEBUG_SAVE_LEGACY_PROPERTY = "shaders.debug.save";
	private static final String DEBUG_EXPORT_DIRECTORY = "sulkan_shader_debug";
	private static final int MAX_EXPORTED_SHADERS = 4096;
	private static final Set<String> exportedShaderKeys = ConcurrentHashMap.newKeySet();
	private static final Set<String> exportWriteErrors = ConcurrentHashMap.newKeySet();
	private static volatile ApplierCache cache = ApplierCache.EMPTY;

	private ShaderpackShaderApplier() {
	}

	public static void invalidateCaches() {
		cache = ApplierCache.EMPTY;
		exportedShaderKeys.clear();
		exportWriteErrors.clear();
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
		String sourcePath = normalizedTarget;
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
				ShaderSourceCandidate replacement = resolveShaderSource(context, replacementPath, "replace");
				if (replacement != null) {
					source = replacement.source();
					sourcePath = replacement.path();
				}
			}
		}

		if (source == null) {
			if (mappedPath != null) {
				ShaderSourceCandidate mapped = resolveShaderSource(context, mappedPath, "mapped");
				if (mapped != null) {
					source = mapped.source();
					sourcePath = mapped.path();
				}
			}
		}

		if (source == null && isIncludeResource(normalizedTarget)) {
			ShaderSourceCandidate includeResource = resolveRawShaderSource(context, normalizedTarget, "include");
			if (includeResource != null) {
				source = includeResource.source();
				sourcePath = includeResource.path();
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
		source = resolveIncludes(source, sourcePath, context);
		if (pipelineProgram != null && pipelineProgram.params() != null && !pipelineProgram.params().isEmpty()) {
			source = applyPipelineParams(source, pipelineProgram.params());
		}
		if (!targetOptions.isEmpty()) {
			source = applyMarkers(source, targetOptions, shaderConfig);
		}
		if (pipelineProgram != null && isShaderTextPath(normalizedTarget.toLowerCase(Locale.ROOT))) {
			source = injectPipelineInterfaceDefines(source, pipelineProgram, normalizedTarget);
		}
		maybeExportShader(normalizedTarget, sourcePath, pipelineProgram, source);
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
		if (path == null) {
			return "";
		}
		String normalized = path.replace('\\', '/');
		while (normalized.startsWith("/")) {
			normalized = normalized.substring(1);
		}
		if (normalized.isBlank()) {
			return "";
		}
		String[] parts = normalized.split("/");
		List<String> cleaned = new ArrayList<>(parts.length);
		for (String part : parts) {
			if (part == null || part.isBlank() || ".".equals(part)) {
				continue;
			}
			if ("..".equals(part)) {
				if (!cleaned.isEmpty()) {
					cleaned.remove(cleaned.size() - 1);
				}
				continue;
			}
			cleaned.add(part);
		}
		return String.join("/", cleaned);
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
		if (path.endsWith(".gsh")) {
			return path.substring(0, path.length() - 4) + ".geom";
		}
		if (path.endsWith(".geom")) {
			return path.substring(0, path.length() - 5) + ".gsh";
		}
		if (path.endsWith(".csh")) {
			return path.substring(0, path.length() - 4) + ".comp";
		}
		if (path.endsWith(".comp")) {
			return path.substring(0, path.length() - 5) + ".csh";
		}
		return null;
	}

	private static ShaderSourceCandidate resolveShaderSource(ApplierCache context, String requestedPath, String kind) {
		String normalized = normalizePath(requestedPath);
		for (String candidatePath : buildShaderSourceCandidates(normalized)) {
			String candidateSource = context.readShaderpackText(candidatePath);
			if (candidateSource == null) {
				continue;
			}
			if (isPathCompatible(candidatePath, candidateSource)) {
				if (!candidatePath.equals(normalized)) {
					context.logCompatibilityFallbackOnce(kind, normalized, candidatePath);
				}
				return new ShaderSourceCandidate(candidatePath, candidateSource);
			}
			context.warnIncompatibleOnce(kind, candidatePath);
		}
		return null;
	}

	private static boolean isPathCompatible(String candidatePath, String source) {
		String lower = normalizePath(candidatePath).toLowerCase(Locale.ROOT);
		if (!isShaderTextPath(lower)) {
			return true;
		}
		if (lower.endsWith(".csh") || lower.endsWith(".comp") || lower.endsWith(".gsh") || lower.endsWith(".geom")) {
			return hasCompatibleVersionPragma(source);
		}
		return isLikelyVulkanCompatible(source);
	}

	private static boolean isShaderTextPath(String lowerPath) {
		return lowerPath.endsWith(".vsh")
			|| lowerPath.endsWith(".vert")
			|| lowerPath.endsWith(".fsh")
			|| lowerPath.endsWith(".frag")
			|| lowerPath.endsWith(".gsh")
			|| lowerPath.endsWith(".geom")
			|| lowerPath.endsWith(".csh")
			|| lowerPath.endsWith(".comp")
			|| lowerPath.endsWith(".glsl");
	}

	private static ShaderSourceCandidate resolveRawShaderSource(ApplierCache context, String requestedPath, String kind) {
		String normalized = normalizePath(requestedPath);
		for (String candidatePath : buildShaderSourceCandidates(normalized)) {
			String candidateSource = context.readShaderpackText(candidatePath);
			if (candidateSource == null) {
				continue;
			}
			if (!candidatePath.equals(normalized)) {
				context.logCompatibilityFallbackOnce(kind, normalized, candidatePath);
			}
			return new ShaderSourceCandidate(candidatePath, candidateSource);
		}
		return null;
	}

	static LoadedSource loadPipelineSource(ShaderpackPipelineProgram pipelineProgram, String sourcePath, String kind) {
		if (pipelineProgram == null || sourcePath == null || sourcePath.isBlank()) {
			return null;
		}
		ShaderpackMetadata metadata = ShaderpackManager.getActiveShaderpack();
		if (metadata == null) {
			return null;
		}
		ApplierCache context = ensureCache(metadata);
		ShaderSourceCandidate candidate = resolveShaderSource(context, sourcePath, kind == null ? "pipeline" : kind);
		if (candidate == null) {
			return null;
		}
		String source = resolveIncludes(candidate.source(), candidate.path(), context);
		if (pipelineProgram.params() != null && !pipelineProgram.params().isEmpty()) {
			source = applyPipelineParams(source, pipelineProgram.params());
		}
		String lowerPath = normalizePath(candidate.path()).toLowerCase(Locale.ROOT);
		if (isShaderTextPath(lowerPath)) {
			source = injectPipelineInterfaceDefines(source, pipelineProgram, candidate.path());
		}
		maybeExportShader(candidate.path(), candidate.path(), pipelineProgram, source);
		return new LoadedSource(candidate.path(), source);
	}

	private static LinkedHashSet<String> buildShaderSourceCandidates(String path) {
		LinkedHashSet<String> candidates = new LinkedHashSet<>();
		String normalized = normalizePath(path);
		addWorldScopedShaderCandidates(candidates, normalized);
		addPathWithCompatibilityCandidates(candidates, normalized);
		return candidates;
	}

	private static void addWorldScopedShaderCandidates(LinkedHashSet<String> output, String path) {
		String normalized = normalizePath(path);
		if (normalized.isBlank() || !normalized.startsWith(SHADERS_PREFIX)) {
			return;
		}
		String relative = normalized.substring(SHADERS_PREFIX.length());
		if (relative.isBlank()) {
			return;
		}
		for (String worldCandidate : ShaderpackManager.getCachedWorldCandidates()) {
			String worldId = normalizePath(worldCandidate);
			if (worldId.isBlank() || "any".equals(worldId) || worldId.indexOf(':') >= 0) {
				continue;
			}
			String worldScopedPath = normalizePath(SHADERS_PREFIX + worldId + "/" + relative);
			addPathWithCompatibilityCandidates(output, worldScopedPath);
		}
	}

	private static void addPathWithCompatibilityCandidates(LinkedHashSet<String> output, String path) {
		String normalized = normalizePath(path);
		if (normalized.isBlank()) {
			return;
		}
		output.add(normalized);
		addExtensionAlias(normalized, output);
		addCompatibilityPathCandidates(normalized, output);
	}

	private static void addCompatibilityPathCandidates(String path, LinkedHashSet<String> output) {
		String normalized = normalizePath(path);
		if (normalized.isBlank()) {
			return;
		}
		int slash = normalized.lastIndexOf('/');
		int dot = normalized.lastIndexOf('.');
		boolean hasExtension = dot > slash;
		if (hasExtension) {
			String base = normalized.substring(0, dot);
			String ext = normalized.substring(dot);
			for (String suffix : COMPATIBILITY_VARIANT_SUFFIXES) {
				String variant = base + suffix + ext;
				output.add(variant);
				addExtensionAlias(variant, output);
			}
		}
		if (normalized.startsWith(SHADERS_PREFIX)) {
			String relative = normalized.substring(SHADERS_PREFIX.length());
			for (String root : COMPATIBILITY_VARIANT_ROOTS) {
				String variant = normalizePath(root + relative);
				output.add(variant);
				addExtensionAlias(variant, output);
			}
		}
	}

	private static boolean isIncludeResource(String path) {
		String normalized = normalizePath(path).toLowerCase(Locale.ROOT);
		return normalized.endsWith(".glsl") || normalized.contains("/include/");
	}

	private static String resolveIncludes(String source, String sourcePath, ApplierCache context) {
		if (source == null || sourcePath == null || !source.contains("#include")) {
			return source;
		}
		return resolveIncludesRecursive(source, normalizePath(sourcePath), context, 0, new LinkedHashSet<>(), new LinkedHashSet<>());
	}

	private static String resolveIncludesRecursive(
		String source,
		String sourcePath,
		ApplierCache context,
		int depth,
		Set<String> includeStack,
		Set<String> pragmaOnceIncludes
	) {
		if (depth > MAX_INCLUDE_DEPTH) {
			Sulkan.LOGGER.warn("Shader include depth exceeded while processing '{}'.", sourcePath);
			return source;
		}
		if (pragmaOnceIncludes.contains(sourcePath)) {
			return "";
		}

		boolean hasPragmaOnce = PRAGMA_ONCE_PATTERN.matcher(source).find();
		String sourceBody = hasPragmaOnce ? PRAGMA_ONCE_PATTERN.matcher(source).replaceAll("") : source;
		if (hasPragmaOnce) {
			pragmaOnceIncludes.add(sourcePath);
		}

		Matcher matcher = INCLUDE_DIRECTIVE_PATTERN.matcher(sourceBody);
		StringBuffer out = new StringBuffer();
		boolean changed = false;
		while (matcher.find()) {
			String includeRef = matcher.group(2);
			IncludeSource includeSource = loadIncludeSource(sourcePath, includeRef, context);
			if (includeSource == null) {
				Sulkan.LOGGER.warn("Shader include '{}' not found while processing '{}'.", includeRef, sourcePath);
				matcher.appendReplacement(out, "");
				changed = true;
				continue;
			}
			if (!includeStack.add(includeSource.path())) {
				Sulkan.LOGGER.warn("Shader include cycle detected for '{}'.", includeSource.path());
				matcher.appendReplacement(out, "");
				changed = true;
				continue;
			}
			String resolved = resolveIncludesRecursive(
				includeSource.source(),
				includeSource.path(),
				context,
				depth + 1,
				includeStack,
				pragmaOnceIncludes
			);
			includeStack.remove(includeSource.path());
			matcher.appendReplacement(out, Matcher.quoteReplacement(resolved));
			changed = true;
		}
		if (!changed) {
			return sourceBody;
		}
		matcher.appendTail(out);
		return out.toString();
	}

	private static IncludeSource loadIncludeSource(String sourcePath, String includeRef, ApplierCache context) {
		LinkedHashSet<String> candidates = includeCandidates(sourcePath, includeRef);
		for (String candidate : candidates) {
			String shaderpackSource = context.readShaderpackText(candidate);
			if (shaderpackSource != null) {
				return new IncludeSource(candidate, shaderpackSource);
			}
			String bundledSource = readBundledShaderText(candidate);
			if (bundledSource != null) {
				return new IncludeSource(candidate, bundledSource);
			}
		}
		return null;
	}

	private static LinkedHashSet<String> includeCandidates(String sourcePath, String includeRef) {
		LinkedHashSet<String> candidates = new LinkedHashSet<>();
		if (includeRef == null) {
			return candidates;
		}
		String trimmed = includeRef.trim();
		if (trimmed.isBlank()) {
			return candidates;
		}
		String rawPath = trimmed.replace('\\', '/');
		boolean rooted = rawPath.startsWith("/");
		while (rawPath.startsWith("/")) {
			rawPath = rawPath.substring(1);
		}
		int namespaceSeparator = rawPath.indexOf(':');
		if (namespaceSeparator >= 0 && namespaceSeparator + 1 < rawPath.length()) {
			rawPath = rawPath.substring(namespaceSeparator + 1);
		}
		String sourceDir = parentDirectory(sourcePath);
		if (rawPath.startsWith("assets/vulkanmod/")) {
			rawPath = rawPath.substring("assets/vulkanmod/".length());
		}
		String normalized = normalizePath(rawPath);
		if (normalized.startsWith("shaders/")) {
			addPathWithCompatibilityCandidates(candidates, normalized);
			return candidates;
		}
		if (!rooted && !sourceDir.isBlank() && !rawPath.isBlank()) {
			addPathWithCompatibilityCandidates(candidates, normalizePath(sourceDir + "/" + rawPath));
		}
		if (!normalized.isBlank()) {
			addPathWithCompatibilityCandidates(candidates, normalizePath("shaders/include/" + normalized));
			addPathWithCompatibilityCandidates(candidates, normalizePath("shaders/" + normalized));
			addPathWithCompatibilityCandidates(candidates, normalized);
		}
		return candidates;
	}

	private static String parentDirectory(String path) {
		String normalized = normalizePath(path);
		int slash = normalized.lastIndexOf('/');
		if (slash <= 0) {
			return "";
		}
		return normalized.substring(0, slash);
	}

	private static String readBundledShaderText(String relativePath) {
		String normalized = normalizePath(relativePath);
		String resourcePath = normalized.startsWith("assets/")
			? "/" + normalized
			: "/assets/vulkanmod/" + normalized;
		try (InputStream stream = ShaderpackShaderApplier.class.getResourceAsStream(resourcePath)) {
			if (stream == null) {
				return null;
			}
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		} catch (Exception e) {
			Sulkan.LOGGER.warn("Failed to read bundled shader include '{}'.", normalized, e);
			return null;
		}
	}

	private static String injectPipelineInterfaceDefines(String source, ShaderpackPipelineProgram program, String shaderPath) {
		if (source == null || source.isBlank() || program == null) {
			return source;
		}
		String block = buildPipelineInterfaceDefines(program, shaderPath);
		if (block.isBlank()) {
			return source;
		}
		Matcher versionMatcher = GLSL_VERSION_PATTERN.matcher(source);
		if (versionMatcher.find()) {
			int insertAt = versionMatcher.end();
			return source.substring(0, insertAt) + "\n" + block + source.substring(insertAt);
		}
		return block + source;
	}

	private static String buildPipelineInterfaceDefines(ShaderpackPipelineProgram program, String shaderPath) {
		ShaderpackMetadata metadata = ShaderpackManager.getActiveShaderpack();
		ShaderpackGlobalSettings globalSettings = metadata == null || metadata.globalSettings == null
			? ShaderpackGlobalSettings.EMPTY
			: metadata.globalSettings;
		ShaderpackIdMappings idMappings = metadata == null || metadata.idMappings == null
			? ShaderpackIdMappings.EMPTY
			: metadata.idMappings;

		List<String> lines = new ArrayList<>();
		lines.add("#define SULKAN_PROGRAM_ENABLED 1");
		lines.add("#define SULKAN_STAGE_" + toMacroToken(program.stage()) + " 1");
		lines.add("#define SULKAN_SEGMENT_INDEX " + Math.max(0, program.index()));
		lines.add("#define SULKAN_HAS_VERTEX " + toInt(program.vertex() != null));
		lines.add("#define SULKAN_HAS_FRAGMENT " + toInt(program.fragment() != null));
		lines.add("#define SULKAN_HAS_GEOMETRY " + toInt(program.geometry() != null));
		lines.add("#define SULKAN_HAS_COMPUTE " + toInt(program.compute() != null));
		lines.add("#define SULKAN_HAS_CONFIG " + toInt(program.config() != null));
		lines.add("#define SULKAN_RENDER_STAGE " + stageRenderCode(program.stage()));
		lines.add("#define SULKAN_RENDER_STAGE_SHADOW 100");
		lines.add("#define SULKAN_RENDER_STAGE_GBUFFER 200");
		lines.add("#define SULKAN_RENDER_STAGE_LIGHTING 300");
		lines.add("#define SULKAN_RENDER_STAGE_TRANSLUCENT 400");
		lines.add("#define SULKAN_RENDER_STAGE_POSTPROCESS 500");
		lines.add("#define SULKAN_RENDER_STAGE_FINAL 600");
		lines.add("#define SULKAN_UNIFORM_RENDER_STAGE SULKAN_RENDER_STAGE");
		lines.add("#define SULKAN_UNIFORM_SEGMENT_INDEX SULKAN_SEGMENT_INDEX");
		lines.add("#define SULKAN_UNIFORM_FRAME_TIME GameTime");
		lines.add("#define SULKAN_UNIFORM_SCREEN_SIZE ScreenSize");
		lines.add("#define SULKAN_UNIFORM_CAMERA_POSITION ChunkOffset");

		appendGlobalDefines(lines, globalSettings);
		appendIdMappingDefines(lines, idMappings);

		List<String> targets = program.renderTargets() == null ? List.of() : program.renderTargets();
		lines.add("#define SULKAN_RENDER_TARGET_COUNT " + targets.size());
		for (String target : targets) {
			lines.add("#define SULKAN_RT_" + toMacroToken(target) + " 1");
		}

		String pingPong = program.pingPong() == null ? "main" : program.pingPong().trim().toLowerCase(Locale.ROOT);
		lines.add("#define SULKAN_PING_PONG_MAIN " + toInt("main".equals(pingPong)));
		lines.add("#define SULKAN_PING_PONG_ALT " + toInt("alt".equals(pingPong)));

		Map<String, Boolean> flips = program.flips() == null ? Map.of() : program.flips();
		for (Map.Entry<String, Boolean> entry : flips.entrySet()) {
			String key = entry.getKey();
			if (key == null || key.isBlank()) {
				continue;
			}
			lines.add("#define SULKAN_FLIP_" + toMacroToken(key) + " " + toInt(Boolean.TRUE.equals(entry.getValue())));
		}

		List<Integer> workGroups = program.workGroups() == null ? List.of() : program.workGroups();
		if (workGroups.size() == 3) {
			lines.add("#define SULKAN_WORK_GROUP_X " + workGroups.get(0));
			lines.add("#define SULKAN_WORK_GROUP_Y " + workGroups.get(1));
			lines.add("#define SULKAN_WORK_GROUP_Z " + workGroups.get(2));
		}

		List<Integer> workGroupsRender = program.workGroupsRender() == null ? List.of() : program.workGroupsRender();
		if (workGroupsRender.size() == 3) {
			lines.add("#define SULKAN_WORK_GROUP_RENDER_X " + workGroupsRender.get(0));
			lines.add("#define SULKAN_WORK_GROUP_RENDER_Y " + workGroupsRender.get(1));
			lines.add("#define SULKAN_WORK_GROUP_RENDER_Z " + workGroupsRender.get(2));
		}

		List<String> imagesRead = program.imagesRead() == null ? List.of() : program.imagesRead();
		for (String binding : imagesRead) {
			if (binding == null || binding.isBlank()) {
				continue;
			}
			lines.add("#define SULKAN_IMAGE_READ_" + toMacroToken(binding) + " 1");
		}
		List<String> imagesWrite = program.imagesWrite() == null ? List.of() : program.imagesWrite();
		for (String binding : imagesWrite) {
			if (binding == null || binding.isBlank()) {
				continue;
			}
			lines.add("#define SULKAN_IMAGE_WRITE_" + toMacroToken(binding) + " 1");
		}

		ShaderpackPipelineAlphaState alphaState = program.alphaState() == null
			? ShaderpackPipelineAlphaState.DISABLED
			: program.alphaState().normalized();
		lines.add("#define SULKAN_ALPHA_ENABLED " + toInt(alphaState.enabled()));
		lines.add("#define SULKAN_ALPHA_FUNC " + alphaFuncCode(alphaState.function()));
		lines.add("#define SULKAN_ALPHA_REF " + formatNumber(alphaState.reference()));
		lines.add("#define SULKAN_ALPHA_FUNC_" + toMacroToken(alphaState.function()) + " 1");

		ShaderpackPipelineBlendState blendState = program.blendState() == null
			? ShaderpackPipelineBlendState.DISABLED
			: program.blendState().normalized();
		lines.add("#define SULKAN_BLEND_ENABLED " + toInt(blendState.enabled()));
		lines.add("#define SULKAN_BLEND_SRC_COLOR " + blendFactorCode(blendState.srcColor()));
		lines.add("#define SULKAN_BLEND_DST_COLOR " + blendFactorCode(blendState.dstColor()));
		lines.add("#define SULKAN_BLEND_SRC_ALPHA " + blendFactorCode(blendState.srcAlpha()));
		lines.add("#define SULKAN_BLEND_DST_ALPHA " + blendFactorCode(blendState.dstAlpha()));

		Map<String, List<Integer>> bufferSizes = program.bufferSizes() == null ? Map.of() : program.bufferSizes();
		lines.add("#define SULKAN_BUFFER_SIZE_COUNT " + bufferSizes.size());
		for (Map.Entry<String, List<Integer>> entry : bufferSizes.entrySet()) {
			String target = entry.getKey();
			List<Integer> value = entry.getValue();
			if (target == null || target.isBlank() || value == null || value.size() != 2) {
				continue;
			}
			lines.add("#define SULKAN_SIZE_BUFFER_" + toMacroToken(target) + "_X " + Math.max(1, value.get(0)));
			lines.add("#define SULKAN_SIZE_BUFFER_" + toMacroToken(target) + "_Y " + Math.max(1, value.get(1)));
		}
		Map<String, List<Double>> bufferScales = program.bufferScales() == null ? Map.of() : program.bufferScales();
		lines.add("#define SULKAN_BUFFER_SCALE_COUNT " + bufferScales.size());
		for (Map.Entry<String, List<Double>> entry : bufferScales.entrySet()) {
			String target = entry.getKey();
			List<Double> value = entry.getValue();
			if (target == null || target.isBlank() || value == null || value.size() != 2) {
				continue;
			}
			lines.add("#define SULKAN_SCALE_" + toMacroToken(target) + "_X " + formatNumber(value.get(0)));
			lines.add("#define SULKAN_SCALE_" + toMacroToken(target) + "_Y " + formatNumber(value.get(1)));
		}

		String shaderKind = detectShaderKind(shaderPath);
		if ("vertex".equals(shaderKind)) {
			lines.add("#define SULKAN_ATTR_POSITION Position");
			lines.add("#define SULKAN_ATTR_COLOR Color");
			lines.add("#define SULKAN_ATTR_UV0 UV0");
			lines.add("#define SULKAN_ATTR_UV1 UV1");
			lines.add("#define SULKAN_ATTR_UV2 UV2");
			lines.add("#define SULKAN_ATTR_NORMAL Normal");
			lines.add("#define SULKAN_ATTR_MC_ENTITY mc_Entity");
			lines.add("#define SULKAN_ATTR_AT_TANGENT at_tangent");
			lines.add("#define SULKAN_ATTR_AT_VELOCITY at_velocity");
		}

		if (lines.isEmpty()) {
			return "";
		}
		StringBuilder builder = new StringBuilder();
		builder.append("// Sulkan pipeline interface\n");
		for (String line : lines) {
			builder.append(line).append('\n');
		}
		return builder.toString();
	}

	private static void appendGlobalDefines(List<String> lines, ShaderpackGlobalSettings settings) {
		if (lines == null || settings == null || settings.isEmpty()) {
			return;
		}
		Map<String, Object> values = settings.values();
		lines.add("#define SULKAN_GLOBAL_COUNT " + values.size());
		for (Map.Entry<String, Object> entry : values.entrySet()) {
			String key = entry.getKey();
			if (key == null || key.isBlank()) {
				continue;
			}
			String token = toMacroToken(key);
			Object value = entry.getValue();
			if (value instanceof List<?> list) {
				lines.add("#define SULKAN_GLOBAL_" + token + "_COUNT " + list.size());
				for (int i = 0; i < list.size(); i++) {
					Object listEntry = list.get(i);
					lines.add("#define SULKAN_GLOBAL_" + token + "_" + i + " " + macroValueLiteral(listEntry));
				}
				continue;
			}
			lines.add("#define SULKAN_GLOBAL_" + token + " " + macroValueLiteral(value));
		}
	}

	private static void appendIdMappingDefines(List<String> lines, ShaderpackIdMappings idMappings) {
		if (lines == null || idMappings == null || idMappings.isEmpty()) {
			return;
		}
		appendNamedIntDefines(lines, "BLOCK", idMappings.blocks());
		appendNamedIntDefines(lines, "ITEM", idMappings.items());
		appendNamedIntDefines(lines, "ENTITY", idMappings.entities());

		lines.add("#define SULKAN_LAYER_SOLID 1");
		lines.add("#define SULKAN_LAYER_CUTOUT 2");
		lines.add("#define SULKAN_LAYER_TRANSLUCENT 3");
		Map<String, String> layers = idMappings.layers();
		lines.add("#define SULKAN_LAYER_MAP_COUNT " + layers.size());
		for (Map.Entry<String, String> entry : layers.entrySet()) {
			String key = entry.getKey();
			String value = entry.getValue();
			if (key == null || key.isBlank() || value == null || value.isBlank()) {
				continue;
			}
			lines.add("#define SULKAN_LAYER_" + toMacroToken(key) + " " + layerCode(value));
		}
	}

	private static void appendNamedIntDefines(List<String> lines, String kind, Map<String, Integer> values) {
		if (values == null) {
			lines.add("#define SULKAN_ID_" + kind + "_COUNT 0");
			return;
		}
		lines.add("#define SULKAN_ID_" + kind + "_COUNT " + values.size());
		for (Map.Entry<String, Integer> entry : values.entrySet()) {
			String key = entry.getKey();
			Integer value = entry.getValue();
			if (key == null || key.isBlank() || value == null) {
				continue;
			}
			lines.add("#define SULKAN_ID_" + kind + "_" + toMacroToken(key) + " " + value.intValue());
		}
	}

	private static String macroValueLiteral(Object value) {
		if (value == null) {
			return "0";
		}
		if (value instanceof Boolean bool) {
			return bool ? "1" : "0";
		}
		if (value instanceof Number number) {
			return formatNumber(number.doubleValue());
		}
		String text = value.toString().replace("\\", "\\\\").replace("\"", "\\\"");
		return "\"" + text + "\"";
	}

	private static int layerCode(String layer) {
		if (layer == null) {
			return 0;
		}
		return switch (layer.trim().toLowerCase(Locale.ROOT)) {
			case "solid" -> 1;
			case "cutout" -> 2;
			case "translucent" -> 3;
			default -> 0;
		};
	}

	private static void maybeExportShader(
		String requestPath,
		String resolvedPath,
		ShaderpackPipelineProgram program,
		String source
	) {
		if (source == null || source.isBlank() || !shouldExportShaders()) {
			return;
		}
		String stage = program == null || program.stage() == null || program.stage().isBlank()
			? inferStageFromPath(requestPath)
			: program.stage().trim().toLowerCase(Locale.ROOT);
		String path = normalizePath(resolvedPath == null || resolvedPath.isBlank() ? requestPath : resolvedPath);
		if (path.isBlank()) {
			path = "unknown/generated_" + Integer.toHexString(source.hashCode()) + ".glsl";
		}
		if (!path.contains(".")) {
			path = path + ".glsl";
		}
		String key = stage + "|" + path + "|" + Integer.toHexString(source.hashCode());
		if (!exportedShaderKeys.add(key)) {
			return;
		}
		if (exportedShaderKeys.size() > MAX_EXPORTED_SHADERS) {
			exportedShaderKeys.clear();
			exportedShaderKeys.add(key);
		}

		Path exportPath = resolveExportPath(stage, path);
		try {
			Files.createDirectories(exportPath.getParent());
			Files.writeString(exportPath, source, StandardCharsets.UTF_8);
		} catch (IOException e) {
			String errorKey = exportPath.toString();
			if (exportWriteErrors.add(errorKey)) {
				Sulkan.LOGGER.warn("Failed to export shader source '{}': {}", exportPath, e.getMessage());
			}
		}
	}

	private static Path resolveExportPath(String stage, String shaderPath) {
		Path runDir = Paths.get(".");
		try {
			MinecraftClient client = MinecraftClient.getInstance();
			if (client != null && client.runDirectory != null) {
				runDir = client.runDirectory.toPath();
			}
		} catch (Exception ignored) {
		}

		String packName = "default";
		Path activePath = ShaderpackManager.getActivePath();
		if (activePath != null && activePath.getFileName() != null) {
			packName = sanitizePathSegment(activePath.getFileName().toString());
		}

		String normalizedStage = stage == null || stage.isBlank() ? "unknown" : sanitizePathSegment(stage);
		String normalizedShaderPath = sanitizeRelativePath(shaderPath);
		return runDir
			.resolve(DEBUG_EXPORT_DIRECTORY)
			.resolve(packName)
			.resolve(normalizedStage)
			.resolve(normalizedShaderPath);
	}

	private static String sanitizePathSegment(String input) {
		if (input == null || input.isBlank()) {
			return "unknown";
		}
		String value = input.replace('\\', '/');
		value = value.replace(':', '_');
		value = value.replaceAll("[^A-Za-z0-9._/-]", "_");
		value = value.replace("../", "");
		value = value.replace("..\\", "");
		while (value.startsWith("/")) {
			value = value.substring(1);
		}
		while (value.endsWith("/")) {
			value = value.substring(0, value.length() - 1);
		}
		return value.isBlank() ? "unknown" : value;
	}

	private static String sanitizeRelativePath(String input) {
		String normalized = sanitizePathSegment(input);
		return normalized.isBlank() ? "unknown.glsl" : normalized;
	}

	private static boolean shouldExportShaders() {
		String property = System.getProperty(DEBUG_SAVE_PROPERTY);
		if (property == null || property.isBlank()) {
			property = System.getProperty(DEBUG_SAVE_LEGACY_PROPERTY);
		}
		if (property != null && !property.isBlank()) {
			return parseBooleanSwitch(property, false);
		}
		SulkanConfig config = SulkanConfig.get();
		if (config != null && Boolean.TRUE.equals(config.debugExportShaders)) {
			return true;
		}
		ShaderpackMetadata metadata = ShaderpackManager.getActiveShaderpack();
		if (metadata == null || metadata.globalSettings == null) {
			return false;
		}
		return readGlobalSwitch(metadata.globalSettings, false,
			"debug.save_shaders",
			"debug.save_shader",
			"debug_save_shaders",
			"shader_debug_save",
			"shader_export_debug"
		);
	}

	private static boolean readGlobalSwitch(ShaderpackGlobalSettings settings, boolean fallback, String... keys) {
		if (settings == null || keys == null || keys.length == 0) {
			return fallback;
		}
		for (String key : keys) {
			Object value = settings.get(key);
			if (value == null) {
				continue;
			}
			return settings.getBoolean(key, fallback);
		}
		return fallback;
	}

	private static boolean parseBooleanSwitch(String raw, boolean fallback) {
		if (raw == null || raw.isBlank()) {
			return fallback;
		}
		String normalized = raw.trim().toLowerCase(Locale.ROOT);
		return switch (normalized) {
			case "true", "1", "yes", "on" -> true;
			case "false", "0", "no", "off" -> false;
			default -> fallback;
		};
	}

	private static String inferStageFromPath(String path) {
		String lower = normalizePath(path).toLowerCase(Locale.ROOT);
		if (lower.contains("terrain_earlyz")) {
			return "shadow";
		}
		if (lower.contains("terrain")) {
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
		return "unknown";
	}

	private static int toInt(boolean value) {
		return value ? 1 : 0;
	}

	private static int stageRenderCode(String stage) {
		if (stage == null) {
			return 0;
		}
		return switch (stage.trim().toLowerCase(Locale.ROOT)) {
			case "shadow" -> 100;
			case "gbuffer" -> 200;
			case "lighting" -> 300;
			case "translucent" -> 400;
			case "postprocess" -> 500;
			case "final" -> 600;
			default -> 0;
		};
	}

	private static int alphaFuncCode(String func) {
		if (func == null) {
			return 7;
		}
		return switch (func.trim().toLowerCase(Locale.ROOT)) {
			case "never" -> 0;
			case "less" -> 1;
			case "equal" -> 2;
			case "lequal" -> 3;
			case "greater" -> 4;
			case "notequal" -> 5;
			case "gequal" -> 6;
			case "always" -> 7;
			default -> 7;
		};
	}

	private static int blendFactorCode(String factor) {
		if (factor == null) {
			return 1;
		}
		return switch (factor.trim().toLowerCase(Locale.ROOT)) {
			case "zero" -> 0;
			case "one" -> 1;
			case "src_color" -> 2;
			case "one_minus_src_color" -> 3;
			case "dst_color" -> 4;
			case "one_minus_dst_color" -> 5;
			case "src_alpha" -> 6;
			case "one_minus_src_alpha" -> 7;
			case "dst_alpha" -> 8;
			case "one_minus_dst_alpha" -> 9;
			case "constant_color" -> 10;
			case "one_minus_constant_color" -> 11;
			case "constant_alpha" -> 12;
			case "one_minus_constant_alpha" -> 13;
			case "src1_color" -> 14;
			case "one_minus_src1_color" -> 15;
			case "src1_alpha" -> 16;
			case "one_minus_src1_alpha" -> 17;
			default -> 1;
		};
	}

	private static String detectShaderKind(String path) {
		String lower = normalizePath(path).toLowerCase(Locale.ROOT);
		if (lower.endsWith(".vsh") || lower.endsWith(".vert")) {
			return "vertex";
		}
		if (lower.endsWith(".fsh") || lower.endsWith(".frag")) {
			return "fragment";
		}
		if (lower.endsWith(".gsh") || lower.endsWith(".geom")) {
			return "geometry";
		}
		if (lower.endsWith(".csh") || lower.endsWith(".comp")) {
			return "compute";
		}
		return "other";
	}

	private static String formatNumber(double value) {
		if (!Double.isFinite(value)) {
			return "0.0";
		}
		if (Math.rint(value) == value) {
			return Long.toString((long) value);
		}
		String text = String.format(Locale.ROOT, "%.8f", value);
		int end = text.length();
		while (end > 0 && text.charAt(end - 1) == '0') {
			end--;
		}
		if (end > 0 && text.charAt(end - 1) == '.') {
			end++;
			if (end > text.length()) {
				text = text + "0";
			}
		}
		return end <= 0 ? "0.0" : text.substring(0, Math.min(end, text.length()));
	}

	private static String toMacroToken(String value) {
		if (value == null || value.isBlank()) {
			return "UNKNOWN";
		}
		String normalized = value.trim().toUpperCase(Locale.ROOT);
		StringBuilder builder = new StringBuilder(normalized.length());
		for (int i = 0; i < normalized.length(); i++) {
			char c = normalized.charAt(i);
			if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
				builder.append(c);
			} else {
				builder.append('_');
			}
		}
		String token = builder.toString();
		while (token.contains("__")) {
			token = token.replace("__", "_");
		}
		if (token.isBlank()) {
			return "UNKNOWN";
		}
		if (token.charAt(0) >= '0' && token.charAt(0) <= '9') {
			return "_" + token;
		}
		return token;
	}

	private record IncludeSource(String path, String source) {
	}

	private record ShaderSourceCandidate(String path, String source) {
	}

	record LoadedSource(String path, String source) {
	}

	private static boolean isLikelyVulkanCompatible(String source) {
		if (source == null) {
			return false;
		}
		Matcher matcher = GLSL_VERSION_PATTERN.matcher(source);
		if (!matcher.find()) {
			return false;
		}
		int version;
		try {
			version = Integer.parseInt(matcher.group(1));
		} catch (NumberFormatException ignored) {
			return false;
		}
		String profile = matcher.group(2);
		if (version < 450 || "compatibility".equalsIgnoreCase(profile)) {
			return false;
		}
		// Legacy MRT outputs are strong signals of non-Vulkan pipeline shaders.
		if (source.contains("gl_FragData[")) {
			return false;
		}
		// Fixed-function style declarations and built-ins indicate legacy OptiFine/Iris paths.
		if (LEGACY_KEYWORD_PATTERN.matcher(source).find() || LEGACY_BUILTIN_PATTERN.matcher(source).find()) {
			return false;
		}
		// Vulkan SPIR-V path requires explicit locations for user stage interfaces.
		if (BARE_USER_INTERFACE_PATTERN.matcher(source).find()) {
			return false;
		}
		return !NON_OPAQUE_UNIFORM_PATTERN.matcher(source).find();
	}

	private static boolean hasCompatibleVersionPragma(String source) {
		if (source == null) {
			return false;
		}
		Matcher matcher = GLSL_VERSION_PATTERN.matcher(source);
		if (!matcher.find()) {
			return false;
		}
		int version;
		try {
			version = Integer.parseInt(matcher.group(1));
		} catch (NumberFormatException ignored) {
			return false;
		}
		String profile = matcher.group(2);
		return version >= 450 && !"compatibility".equalsIgnoreCase(profile);
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
		private final Set<String> warnedIncompatibleSources = ConcurrentHashMap.newKeySet();
		private final Set<String> loggedCompatibilityFallbacks = ConcurrentHashMap.newKeySet();
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

		private void warnIncompatibleOnce(String kind, String path) {
			String key = kind + ":" + path;
			if (warnedIncompatibleSources.add(key)) {
				Sulkan.LOGGER.warn("Skip incompatible {} shader source '{}'.", kind, path);
			}
		}

		private void logCompatibilityFallbackOnce(String kind, String originalPath, String resolvedPath) {
			String key = kind + ":" + originalPath + "->" + resolvedPath;
			if (loggedCompatibilityFallbacks.add(key)) {
				Sulkan.LOGGER.info(
					"Using {} compatibility shader source '{}' for '{}'.",
					kind,
					resolvedPath,
					originalPath
				);
			}
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
