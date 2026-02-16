package cn.mxst.sulkan.shaderpack;

import cn.mxst.sulkan.Sulkan;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.minecraft.client.MinecraftClient;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

public final class ShaderpackManager {
	public static final String METADATA_FILENAME = "shaderpack.toml";
	private static final String LANG_DIRECTORY = "lang";
	private static final Gson GSON = new Gson();
	private static final Type LANG_MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();
	private static final Set<String> SUPPORTED_RESOURCE_TYPES = Set.of(
		"Texture2D",
		"Texture3D",
		"TextureCube",
		"Buffer",
		"DepthTexture",
		"MotionTexture"
	);
	private static final Set<String> SUPPORTED_RESOLUTIONS = Set.of("internal", "output", "fixed");
	private static final Set<String> SUPPORTED_LIFETIMES = Set.of("per-frame", "persistent", "temporal");
	private static final Set<String> SUPPORTED_FORMATS = Set.of(
		"rgba16f",
		"rgba32f",
		"rgba8",
		"rgba8_srgb",
		"rg16f",
		"rg32f",
		"rg8",
		"r16f",
		"r32f",
		"r8",
		"depth24",
		"depth32f",
		"depth24_stencil8",
		"depth32f_stencil8"
	);
	private static final Set<String> SUPPORTED_EXTENSIONS = Set.of();
	private static final Set<String> SUPPORTED_SPEC_VERSIONS = Set.of("1.0.0");
	private static final Set<String> SUPPORTED_PING_PONG = Set.of("main", "alt");
	private static final Set<String> SUPPORTED_ALPHA_FUNCS = Set.of(
		"never",
		"less",
		"equal",
		"lequal",
		"greater",
		"notequal",
		"gequal",
		"always"
	);
	private static final Set<String> SUPPORTED_BLEND_FACTORS = Set.of(
		"zero",
		"one",
		"src_color",
		"one_minus_src_color",
		"dst_color",
		"one_minus_dst_color",
		"src_alpha",
		"one_minus_src_alpha",
		"dst_alpha",
		"one_minus_dst_alpha",
		"constant_color",
		"one_minus_constant_color",
		"constant_alpha",
		"one_minus_constant_alpha",
		"src1_color",
		"one_minus_src1_color",
		"src1_alpha",
		"one_minus_src1_alpha"
	);
	private static final Set<String> SUPPORTED_LAYER_VALUES = Set.of("solid", "cutout", "translucent");
	private static final Set<String> OPTION_AUTO_EXTRACT_EXTENSIONS = Set.of(".vsh", ".vert", ".fsh", ".frag", ".gsh", ".geom", ".csh", ".comp", ".glsl");
	private static final String AUTO_OPTION_DIRECTIVE = "@sulkan_option";
	private static final Pattern RENDER_TARGET_PATTERN = Pattern.compile("^colortex([0-9]|1[0-5])$");
	private static final Pattern CUSTOM_RENDER_TARGET_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");
	private static final Pattern COLOR_IMAGE_PATTERN = Pattern.compile("^colorimg([0-5])$");
	private static final Pattern SHADOW_COLOR_IMAGE_PATTERN = Pattern.compile("^shadowcolorimg([0-1])$");
	private static final Pattern ENABLED_IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_.-]*$");
	private static final String PIPELINES_DIRECTORY = "pipelines";
	private static final List<String> PIPELINE_STAGE_ORDER = List.of(
		"shadow",
		"gbuffer",
		"lighting",
		"translucent",
		"postprocess",
		"final"
	);
	private static final Map<String, Map<String, String>> STAGE_FIXED_INPUTS = Map.ofEntries(
		Map.entry("shadow", fixedInterface(
			Map.entry("scene_geometry", "Buffer"),
			Map.entry("light_params", "Buffer")
		)),
		Map.entry("gbuffer", fixedInterface(
			Map.entry("scene_geometry", "Buffer"),
			Map.entry("material_params", "Buffer"),
			Map.entry("camera_matrices", "Buffer")
		)),
		Map.entry("lighting", fixedInterface(
			Map.entry("gbuffer_albedo", "Texture2D"),
			Map.entry("gbuffer_normal", "Texture2D"),
			Map.entry("gbuffer_depth", "DepthTexture"),
			Map.entry("shadow_map", "DepthTexture")
		)),
		Map.entry("translucent", fixedInterface(
			Map.entry("lit_color", "Texture2D"),
			Map.entry("gbuffer_depth", "DepthTexture"),
			Map.entry("translucent_geometry", "Buffer")
		)),
		Map.entry("postprocess", fixedInterface(
			Map.entry("scene_color", "Texture2D"),
			Map.entry("scene_depth", "DepthTexture")
		)),
		Map.entry("final", fixedInterface(
			Map.entry("post_color", "Texture2D"),
			Map.entry("post_depth", "DepthTexture"),
			Map.entry("motion_vectors", "MotionTexture")
		))
	);
	private static final Map<String, Map<String, String>> STAGE_FIXED_OUTPUTS = Map.ofEntries(
		Map.entry("shadow", fixedInterface(
			Map.entry("shadow_map", "DepthTexture")
		)),
		Map.entry("gbuffer", fixedInterface(
			Map.entry("gbuffer_albedo", "Texture2D"),
			Map.entry("gbuffer_normal", "Texture2D"),
			Map.entry("gbuffer_depth", "DepthTexture")
		)),
		Map.entry("lighting", fixedInterface(
			Map.entry("lit_color", "Texture2D")
		)),
		Map.entry("translucent", fixedInterface(
			Map.entry("scene_color", "Texture2D")
		)),
		Map.entry("postprocess", fixedInterface(
			Map.entry("post_color", "Texture2D"),
			Map.entry("post_depth", "DepthTexture")
		)),
		Map.entry("final", fixedInterface(
			Map.entry("final_color", "Texture2D"),
			Map.entry("final_depth", "DepthTexture"),
			Map.entry("final_motion", "MotionTexture")
		))
	);
	private static final Pattern PIPELINE_PARAM_PATTERN = Pattern.compile("\\$\\{([A-Za-z0-9_.-]+)}");
	private static final Pattern SEGMENT_TYPED_PARAM_PATTERN = Pattern.compile("^([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+)$");
	private static final String WORLD_KEY_NONE = "__none__";
	private static ShaderpackMetadata activeShaderpack;
	private static Path activePath;
	private static ShaderpackConfig activeConfig;
	private static volatile String cachedWorldKey = WORLD_KEY_NONE;
	private static volatile List<String> cachedWorldCandidates = List.of("any");
	private static final LongAdder worldCandidateRequests = new LongAdder();
	private static final LongAdder worldCandidateHits = new LongAdder();
	private static final int PIPELINE_LOOKUP_CACHE_LIMIT = 512;
	private static final LongAdder pipelineLookupRequests = new LongAdder();
	private static final LongAdder pipelineLookupCacheHits = new LongAdder();
	private static final LongAdder pipelineLookupSegmentHits = new LongAdder();
	private static volatile String pipelineLookupWorldKey = WORLD_KEY_NONE;
	private static volatile ConcurrentMap<String, Optional<ShaderpackPipelineProgram>> pipelineLookupCache = new ConcurrentHashMap<>();
	private static final Set<String> warnedProgramEnabledExpressions = ConcurrentHashMap.newKeySet();

	private ShaderpackManager() {
	}

	public static Path shaderpacksDir() {
		return MinecraftClient.getInstance().runDirectory.toPath().resolve("shaderpacks");
	}

	@SuppressWarnings("null")
	public static List<ShaderpackCandidate> discoverShaderpacks() {
		Path dir = shaderpacksDir();
		try {
			Files.createDirectories(dir);
		} catch (IOException e) {
			Sulkan.LOGGER.error("Failed to create shaderpacks directory.", e);
			return List.of();
		}
		try (Stream<Path> stream = Files.list(dir)) {
			return stream
				.filter(path -> Files.isDirectory(path) || isZip(path))
				.sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
				.map(path -> new ShaderpackCandidate(path.getFileName().toString(), path, loadShaderpack(path)))
				.collect(Collectors.toList());
		} catch (IOException e) {
			Sulkan.LOGGER.error("Failed to list shaderpacks directory.", e);
			return List.of();
		}
	}

	public static ShaderpackMetadata getActiveShaderpack() {
		return activeShaderpack;
	}

	public static Path getActivePath() {
		return activePath;
	}

	public static ShaderpackConfig getActiveConfig() {
		return activeConfig;
	}

	public static CacheStats getWorldCandidateCacheStats() {
		return new CacheStats(worldCandidateRequests.sum(), worldCandidateHits.sum());
	}

	public static PipelineLookupStats getPipelineLookupStats() {
		return new PipelineLookupStats(
			pipelineLookupRequests.sum(),
			pipelineLookupCacheHits.sum(),
			pipelineLookupSegmentHits.sum()
		);
	}

	public static List<String> getCachedWorldCandidates() {
		return cachedWorldCandidates;
	}

	public static boolean isRuntimeHotReloadEnabled() {
		ShaderpackMetadata metadata = activeShaderpack;
		if (metadata == null || metadata.globalSettings == null) {
			return true;
		}
		return readGlobalBooleanSwitch(
			metadata.globalSettings,
			true,
			"hot_reload",
			"runtime.hot_reload",
			"debug.hot_reload",
			"debug_hot_reload"
		);
	}

	public static void refreshActiveConfig() {
		ShaderpackShaderApplier.invalidateCaches();
		ShaderpackComputeDispatcher.invalidateCaches();
		ShaderpackTextureBinder.invalidateCaches();
		invalidatePipelineLookupCache();
		warnedProgramEnabledExpressions.clear();
		if (activePath == null || activeShaderpack == null) {
			activeConfig = null;
			return;
		}
		activeConfig = ShaderpackConfig.loadOrCreate(activePath, activeShaderpack.options);
	}

	public static ShaderpackPipelineProgram resolveActivePipelineProgram(String requestPath) {
		if (activeShaderpack == null || requestPath == null || requestPath.isBlank()) {
			return null;
		}
		String normalizedRequest = ShaderpackPipelineProgram.normalizePath(requestPath);
		if (normalizedRequest.isBlank()) {
			return null;
		}
		List<String> worldCandidates = resolveWorldCandidates();

		pipelineLookupRequests.increment();
		String worldLookupKey = String.join(">", worldCandidates);
		if (!worldLookupKey.equals(pipelineLookupWorldKey)) {
			pipelineLookupWorldKey = worldLookupKey;
			pipelineLookupCache.clear();
		}

		Optional<ShaderpackPipelineProgram> cached = pipelineLookupCache.get(normalizedRequest);
		if (cached != null) {
			pipelineLookupCacheHits.increment();
			return cached.orElse(null);
		}

		ShaderpackPipelineProgram resolved = resolvePipelineProgramBySegments(worldCandidates, normalizedRequest);
		if (resolved != null) {
			pipelineLookupSegmentHits.increment();
			cachePipelineLookup(normalizedRequest, resolved);
			return resolved;
		}

		cachePipelineLookup(normalizedRequest, null);
		return null;
	}

	public static List<ShaderpackPipelineProgram> resolveActivePipelinePrograms() {
		if (activeShaderpack == null) {
			return List.of();
		}
		List<String> worldCandidates = resolveWorldCandidates();
		List<ShaderpackPipelineProgram> resolved = new ArrayList<>();
		for (String stage : PIPELINE_STAGE_ORDER) {
			if (!isStageEnabled(activeShaderpack.stages, stage)) {
				continue;
			}
			List<ShaderpackPipelineProgram> segments = resolveStagePrograms(worldCandidates, stage);
			if (segments == null || segments.isEmpty()) {
				continue;
			}
			resolved.addAll(segments);
		}
		return List.copyOf(resolved);
	}

	private static ShaderpackPipelineProgram resolvePipelineProgramBySegments(
		List<String> worldCandidates,
		String requestPath
	) {
		for (String worldId : worldCandidates) {
			Map<String, List<ShaderpackPipelineProgram>> byStage = activeShaderpack.pipelinePrograms.get(worldId);
			if (byStage == null) {
				continue;
			}
			for (String stage : PIPELINE_STAGE_ORDER) {
				if (!isStageEnabled(activeShaderpack.stages, stage)) {
					continue;
				}
				List<ShaderpackPipelineProgram> segments = byStage.get(stage);
				if (segments == null || segments.isEmpty()) {
					continue;
				}
				List<ShaderpackPipelineProgram> enabledSegments = filterEnabledPrograms(segments);
				if (enabledSegments.isEmpty()) {
					continue;
				}
				ShaderpackPipelineProgram firstSegment = enabledSegments.getFirst();
				if (!firstSegment.matchesRequest(requestPath)) {
					continue;
				}
				for (ShaderpackPipelineProgram segment : enabledSegments) {
					if (segment.resolveSourcePath(requestPath) != null) {
						return segment;
					}
				}
				return firstSegment;
			}
		}
		return null;
	}

	private static List<ShaderpackPipelineProgram> resolveStagePrograms(List<String> worldCandidates, String stage) {
		if (activeShaderpack == null || worldCandidates == null || stage == null || stage.isBlank()) {
			return null;
		}
		for (String worldId : worldCandidates) {
			Map<String, List<ShaderpackPipelineProgram>> byStage = activeShaderpack.pipelinePrograms.get(worldId);
			if (byStage == null) {
				continue;
			}
			List<ShaderpackPipelineProgram> segments = byStage.get(stage);
			if (segments == null || segments.isEmpty()) {
				continue;
			}
			List<ShaderpackPipelineProgram> enabledSegments = filterEnabledPrograms(segments);
			if (enabledSegments.isEmpty()) {
				continue;
			}
			return enabledSegments;
		}
		return null;
	}

	private static boolean isStageEnabled(ShaderpackStages stages, String stage) {
		if (stages == null || stage == null) {
			return false;
		}
		return switch (stage) {
			case "shadow" -> stages.shadow();
			case "gbuffer" -> stages.gbuffer();
			case "lighting" -> stages.lighting();
			case "translucent" -> stages.translucent();
			case "postprocess" -> stages.postprocess();
			case "final" -> stages.finalStage();
			default -> false;
		};
	}

	private static List<ShaderpackPipelineProgram> filterEnabledPrograms(List<ShaderpackPipelineProgram> programs) {
		if (programs == null || programs.isEmpty()) {
			return List.of();
		}
		List<ShaderpackPipelineProgram> enabled = new ArrayList<>();
		for (ShaderpackPipelineProgram program : programs) {
			if (program == null) {
				continue;
			}
			if (isProgramEnabled(program)) {
				enabled.add(program);
			}
		}
		return enabled.isEmpty() ? List.of() : List.copyOf(enabled);
	}

	private static boolean isProgramEnabled(ShaderpackPipelineProgram program) {
		if (program == null) {
			return false;
		}
		String expression = program.enabledExpression();
		if (expression == null || expression.isBlank()) {
			return true;
		}
		ProgramEnabledExpression.Parser parser = new ProgramEnabledExpression.Parser(expression, buildEnabledVariableMap(program));
		try {
			return parser.parse();
		} catch (IllegalArgumentException e) {
			String key = program.stage() + ":" + program.segmentName() + "#" + program.index() + "|" + expression;
			if (warnedProgramEnabledExpressions.add(key)) {
				Sulkan.LOGGER.warn(
					"Invalid enabled expression '{}' for segment {}:{}#{} ({})",
					expression,
					program.stage(),
					program.segmentName(),
					program.index(),
					e.getMessage()
				);
			}
			return false;
		}
	}

	private static Map<String, Object> buildEnabledVariableMap(ShaderpackPipelineProgram program) {
		Map<String, Object> variables = new HashMap<>();
		variables.put("true", Boolean.TRUE);
		variables.put("false", Boolean.FALSE);
		variables.put("segment_index", program.index());
		variables.put("stage", program.stage());
		variables.put("world", program.worldId());
		if (program.params() != null) {
			for (Map.Entry<String, String> entry : program.params().entrySet()) {
				if (entry.getKey() == null || entry.getKey().isBlank()) {
					continue;
				}
				putVariableAliases(variables, entry.getKey(), entry.getValue());
			}
		}

		ShaderpackConfig config = activeConfig;
		if (config != null) {
			for (Map.Entry<String, Object> entry : config.values().entrySet()) {
				String key = entry.getKey();
				if (key == null || key.isBlank()) {
					continue;
				}
				putVariableAliases(variables, key, entry.getValue());
			}
		}
		return variables;
	}

	private static void putVariableAliases(Map<String, Object> output, String key, Object value) {
		if (output == null || key == null || key.isBlank()) {
			return;
		}
		String trimmed = key.trim();
		output.putIfAbsent(trimmed, value);
		output.putIfAbsent(trimmed.toLowerCase(Locale.ROOT), value);
		output.putIfAbsent(trimmed.toUpperCase(Locale.ROOT), value);
		String underscored = trimmed.replace('.', '_').replace('-', '_');
		output.putIfAbsent(underscored, value);
		output.putIfAbsent(underscored.toLowerCase(Locale.ROOT), value);
		output.putIfAbsent(underscored.toUpperCase(Locale.ROOT), value);
	}

	private static boolean readGlobalBooleanSwitch(ShaderpackGlobalSettings settings, boolean fallback, String... keys) {
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

	private static void cachePipelineLookup(String requestPath, ShaderpackPipelineProgram program) {
		if (pipelineLookupCache.size() >= PIPELINE_LOOKUP_CACHE_LIMIT) {
			pipelineLookupCache.clear();
		}
		pipelineLookupCache.putIfAbsent(requestPath, Optional.ofNullable(program));
	}

	public static ShaderpackLoadResult applyShaderpack(Path path) {
		ShaderpackShaderApplier.invalidateCaches();
		ShaderpackComputeDispatcher.invalidateCaches();
		ShaderpackTextureBinder.invalidateCaches();
		invalidatePipelineLookupCache();
		warnedProgramEnabledExpressions.clear();
		ShaderpackLoadResult result = loadShaderpack(path);
		if (result.isValid()) {
			activeShaderpack = result.metadata();
			activePath = path;
			activeConfig = ShaderpackConfig.loadOrCreate(path, activeShaderpack.options);
		} else {
			activeShaderpack = null;
			activePath = null;
			activeConfig = null;
		}
		return result;
	}

	public static void clearActiveShaderpack() {
		ShaderpackShaderApplier.invalidateCaches();
		ShaderpackComputeDispatcher.invalidateCaches();
		ShaderpackTextureBinder.invalidateCaches();
		invalidatePipelineLookupCache();
		warnedProgramEnabledExpressions.clear();
		activeShaderpack = null;
		activePath = null;
		activeConfig = null;
	}

	private static void invalidatePipelineLookupCache() {
		pipelineLookupWorldKey = WORLD_KEY_NONE;
		pipelineLookupCache = new ConcurrentHashMap<>();
	}

	public static ShaderpackLoadResult applySelectedShaderpack(String selectedShaderpack) {
		if (selectedShaderpack == null || selectedShaderpack.isBlank()) {
			clearActiveShaderpack();
			return ShaderpackLoadResult.error("No shaderpack selected.");
		}
		Path path = shaderpacksDir().resolve(selectedShaderpack);
		return applyShaderpack(path);
	}

	public static ShaderpackLoadResult loadShaderpack(Path path) {
		if (Files.isDirectory(path)) {
			return loadFromDirectory(path);
		}
		if (isZip(path)) {
			return loadFromZip(path);
		}
		return ShaderpackLoadResult.error("Unsupported shaderpack type: " + path.getFileName());
	}

	private static boolean isZip(Path path) {
		String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
		return Files.isRegularFile(path) && name.endsWith(".zip");
	}

	private static ShaderpackLoadResult loadFromDirectory(Path path) {
		Path metadataPath = path.resolve(METADATA_FILENAME);
		if (!Files.exists(metadataPath)) {
			return ShaderpackLoadResult.error("Missing " + METADATA_FILENAME + " in " + path.getFileName());
		}
		try (Reader reader = Files.newBufferedReader(metadataPath, StandardCharsets.UTF_8)) {
			return parseAndValidate(reader, metadataPath.toString(), path);
		} catch (IOException e) {
			return ShaderpackLoadResult.error("Failed to read " + METADATA_FILENAME + " from " + path.getFileName());
		}
	}

	private static ShaderpackLoadResult loadFromZip(Path path) {
		try (ZipFile zipFile = new ZipFile(path.toFile(), StandardCharsets.UTF_8)) {
			ZipEntry entry = zipFile.getEntry(METADATA_FILENAME);
			if (entry == null) {
				return ShaderpackLoadResult.error("Missing " + METADATA_FILENAME + " in " + path.getFileName());
			}
			try (Reader reader = new BufferedReader(new InputStreamReader(zipFile.getInputStream(entry), StandardCharsets.UTF_8))) {
				return parseAndValidate(reader, path.toString() + "!/" + METADATA_FILENAME, path);
			}
		} catch (IOException e) {
			return ShaderpackLoadResult.error("Failed to read " + METADATA_FILENAME + " from " + path.getFileName());
		}
	}

	private static ShaderpackLoadResult parseAndValidate(Reader reader, String source, Path sourcePath) {
		List<String> errors = new ArrayList<>();
		List<String> warnings = new ArrayList<>();
		TomlParseResult result;
		try {
			result = Toml.parse(reader);
		} catch (IOException e) {
			errors.add("Failed to parse " + METADATA_FILENAME + ": " + e.getMessage());
			return new ShaderpackLoadResult(null, errors, warnings);
		}
		if (result.hasErrors()) {
			result.errors().forEach(error -> errors.add(error.toString()));
			return new ShaderpackLoadResult(null, errors, warnings);
		}

		TomlTable shaderpackTable = result.getTable("shaderpack");
		if (shaderpackTable == null) {
			errors.add("Missing [shaderpack] table in " + source);
			return new ShaderpackLoadResult(null, errors, warnings);
		}

		String name = requireString(shaderpackTable, "name", "shaderpack", errors);
		String version = requireString(shaderpackTable, "version", "shaderpack", errors);
		String specVersionValue = requireString(shaderpackTable, "spec_version", "shaderpack", errors);

		SemVer specVersion = SemVer.parse(specVersionValue);
		if (specVersion == null) {
			errors.add("Invalid spec_version (expected semver): " + specVersionValue);
		} else if (!SUPPORTED_SPEC_VERSIONS.contains(specVersionValue)) {
			errors.add("Unsupported spec_version: " + specVersionValue);
		}

		if (version != null && SemVer.parse(version) == null) {
			errors.add("Invalid version (expected semver): " + version);
		}

		ShaderpackStages stages = parseStages(result.getTable("stages"), errors);
		List<ShaderpackResourceDecl> resources = parseResources(result.getArray("resources"), errors);
		ShaderpackFeatures features = parseFeatures(result.getTable("features"), errors);
		Map<String, String> translations = loadLangMap(sourcePath, warnings);
		List<ShaderpackOptionDecl> declaredOptions = parseOptions(
			result.getTable("options"),
			features.sulkanConfigOptions() && !features.autoExtractOptions(),
			translations,
			errors,
			warnings
		);
		List<ShaderpackOptionDecl> extractedOptions = features.autoExtractOptions()
			? extractOptionsFromShaders(sourcePath, translations, errors, warnings)
			: List.of();
		List<ShaderpackOptionDecl> options = mergeOptions(declaredOptions, extractedOptions, warnings);
		if (features.sulkanConfigOptions() && options.isEmpty()) {
			errors.add("No shader options available (define [options] or enable auto extraction directives).");
		}
		ShaderpackExtensions extensions = parseExtensions(result.getTable("extensions"), errors);
		ShaderpackGlobalSettings globalSettings = parseGlobalSettings(result, errors);
		ShaderpackTextureBindings textureBindings = parseTextureBindings(result.getTable("textures"), errors, warnings);
		ShaderpackUiLayout uiLayout = parseUiLayout(result.getTable("ui"), errors, warnings);
		ShaderpackIdMappings idMappings = parseIdMappings(result, errors, warnings);
		Map<String, Map<String, List<ShaderpackPipelineProgram>>> pipelinePrograms = parsePipelinePrograms(sourcePath, stages, errors, warnings);

		if (extensions != null && !extensions.required().isEmpty()) {
			for (String ext : extensions.required()) {
				if (!SUPPORTED_EXTENSIONS.contains(ext)) {
					errors.add("Required extension not supported: " + ext);
				}
			}
		}

		ShaderpackMetadata metadata = null;
		if (errors.isEmpty()) {
			Map<String, String> translationMap = translations == null ? Map.of() : Map.copyOf(translations);
			metadata = new ShaderpackMetadata(
				name,
				version,
				specVersion,
				stages,
				resources,
				features,
				extensions,
				options,
				globalSettings,
				textureBindings,
				uiLayout,
				idMappings,
				pipelinePrograms,
				translationMap,
				sourcePath
			);
		}
		return new ShaderpackLoadResult(metadata, errors, warnings);
	}

	private static Map<String, Map<String, List<ShaderpackPipelineProgram>>> parsePipelinePrograms(
		Path shaderpackPath,
		ShaderpackStages stages,
		List<String> errors,
		List<String> warnings
	) {
		Map<String, Map<String, List<ShaderpackPipelineProgram>>> byWorld = new LinkedHashMap<>();
		if (Files.isDirectory(shaderpackPath)) {
			parsePipelineProgramsFromDirectory(shaderpackPath, byWorld, errors, warnings);
		} else if (isZip(shaderpackPath)) {
			parsePipelineProgramsFromZip(shaderpackPath, byWorld, errors, warnings);
		}

		if (byWorld.isEmpty()) {
			errors.add("Missing pipeline mappings under pipelines/[world_id]/[stage].toml.");
			return Map.of();
		}

		if (stages != null) {
			validateRequiredStagesPresent(byWorld, stages, errors);
		}

		Map<String, Map<String, List<ShaderpackPipelineProgram>>> immutable = new LinkedHashMap<>();
		for (Map.Entry<String, Map<String, List<ShaderpackPipelineProgram>>> worldEntry : byWorld.entrySet()) {
			Map<String, List<ShaderpackPipelineProgram>> byStageImmutable = new LinkedHashMap<>();
			for (Map.Entry<String, List<ShaderpackPipelineProgram>> stageEntry : worldEntry.getValue().entrySet()) {
				byStageImmutable.put(stageEntry.getKey(), List.copyOf(stageEntry.getValue()));
			}
			immutable.put(worldEntry.getKey(), Map.copyOf(byStageImmutable));
		}
		return Map.copyOf(immutable);
	}

	private static void parsePipelineProgramsFromDirectory(
		Path shaderpackPath,
		Map<String, Map<String, List<ShaderpackPipelineProgram>>> byWorld,
		List<String> errors,
		List<String> warnings
	) {
		Path pipelinesRoot = shaderpackPath.resolve(PIPELINES_DIRECTORY);
		if (!Files.isDirectory(pipelinesRoot)) {
			return;
		}
		try (Stream<Path> worldStream = Files.list(pipelinesRoot)) {
			List<Path> worldDirs = worldStream
				.filter(Files::isDirectory)
				.sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
				.toList();
			for (Path worldDir : worldDirs) {
				String worldId = worldDir.getFileName().toString();
				parsePipelineWorld(
					worldId,
					stage -> {
						Path path = worldDir.resolve(stage + ".toml");
						return Files.exists(path) ? path : null;
					},
					(path, source) -> Files.newBufferedReader(path, StandardCharsets.UTF_8),
					byWorld,
					errors,
					warnings
				);
			}
		} catch (IOException e) {
			errors.add("Failed to list pipeline mappings in " + pipelinesRoot + ": " + e.getMessage());
		}
	}

	@SuppressWarnings("null")
	private static void parsePipelineProgramsFromZip(
		Path shaderpackPath,
		Map<String, Map<String, List<ShaderpackPipelineProgram>>> byWorld,
		List<String> errors,
		List<String> warnings
	) {
		try (ZipFile zipFile = new ZipFile(shaderpackPath.toFile(), StandardCharsets.UTF_8)) {
			Map<String, Map<String, ZipEntry>> stageEntriesByWorld = new LinkedHashMap<>();
			zipFile.stream().forEach(entry -> {
				if (entry.isDirectory()) {
					return;
				}
				String name = ShaderpackPipelineProgram.normalizePath(entry.getName());
				if (!name.startsWith(PIPELINES_DIRECTORY + "/") || !name.endsWith(".toml")) {
					return;
				}
				String[] segments = name.split("/");
				if (segments.length != 3) {
					return;
				}
				String worldId = segments[1];
				String file = segments[2];
				String stage = file.substring(0, file.length() - ".toml".length());
				if (!PIPELINE_STAGE_ORDER.contains(stage)) {
					return;
				}
				Map<String, ZipEntry> stageEntries = stageEntriesByWorld.computeIfAbsent(worldId, ignored -> new LinkedHashMap<>());
				if (stageEntries.containsKey(stage)) {
					errors.add("Duplicate pipeline file for world '" + worldId + "', stage '" + stage + "' in " + shaderpackPath.getFileName() + ".");
					return;
				}
				stageEntries.put(stage, entry);
			});

			List<String> worlds = new ArrayList<>(stageEntriesByWorld.keySet());
			worlds.sort(String::compareToIgnoreCase);
			for (String worldId : worlds) {
				Map<String, ZipEntry> stageEntries = stageEntriesByWorld.get(worldId);
				parsePipelineWorld(
					worldId,
					stage -> stageEntries.get(stage),
					(entry, source) -> new BufferedReader(new InputStreamReader(zipFile.getInputStream(entry), StandardCharsets.UTF_8)),
					byWorld,
					errors,
					warnings
				);
			}
		} catch (IOException e) {
			errors.add("Failed to read pipeline mappings from " + shaderpackPath.getFileName() + ": " + e.getMessage());
		}
	}

	private static <T> void parsePipelineWorld(
		String worldId,
		java.util.function.Function<String, T> stageLookup,
		PipelineReaderFactory<T> readerFactory,
		Map<String, Map<String, List<ShaderpackPipelineProgram>>> byWorld,
		List<String> errors,
		List<String> warnings
	) {
		Map<String, List<ShaderpackPipelineProgram>> byStage = byWorld.computeIfAbsent(worldId, ignored -> new LinkedHashMap<>());
		boolean hasStageFile = false;
		for (String stage : PIPELINE_STAGE_ORDER) {
			T sourceRef = stageLookup.apply(stage);
			if (sourceRef == null) {
				continue;
			}
			hasStageFile = true;
			String sourceLabel = PIPELINES_DIRECTORY + "/" + worldId + "/" + stage + ".toml";
			try (Reader reader = readerFactory.open(sourceRef, sourceLabel)) {
				List<ShaderpackPipelineProgram> segments = parsePipelineStage(worldId, stage, reader, sourceLabel, errors, warnings);
				if (!segments.isEmpty()) {
					byStage.put(stage, List.copyOf(segments));
				}
			} catch (IOException e) {
				errors.add("Failed to read " + sourceLabel + ": " + e.getMessage());
			}
		}
		if (!hasStageFile) {
			byWorld.remove(worldId);
		}
	}

	private static List<ShaderpackPipelineProgram> parsePipelineStage(
		String worldId,
		String stage,
		Reader reader,
		String sourceLabel,
		List<String> errors,
		List<String> warnings
	) throws IOException {
		TomlParseResult result = Toml.parse(reader);
		if (result.hasErrors()) {
			result.errors().forEach(error -> errors.add(sourceLabel + ": " + error));
			return List.of();
		}

		Map<String, String> stageParams = readStringTable(result.getTable("params"), sourceLabel + ".params", errors);
		Map<String, String> stageContext = new LinkedHashMap<>(resolveParams(stageParams, Map.of(), sourceLabel + ".params", errors));
		Map<String, String> stageProgramEnabled = readProgramEnabledRules(result.getTable("program"), sourceLabel + ".program", errors);
		ShaderpackPipelineAlphaState stageAlphaState = readAlphaState(result, sourceLabel, errors);
		if (stageAlphaState == null) {
			stageAlphaState = ShaderpackPipelineAlphaState.DISABLED;
		}
		ShaderpackPipelineBlendState stageBlendState = readBlendState(result, sourceLabel, errors);
		if (stageBlendState == null) {
			stageBlendState = ShaderpackPipelineBlendState.DISABLED;
		}
		Map<String, List<Integer>> stageBufferSizes = readBufferSizes(result, sourceLabel, errors);
		Map<String, List<Double>> stageBufferScales = readBufferScales(result, sourceLabel, errors);
		List<ShaderpackPipelineProgram> stageSegments = new ArrayList<>();

		TomlArray segmentsArray = result.getArray("segments");
		if (segmentsArray == null || segmentsArray.isEmpty()) {
			TomlArray legacyPrograms = result.getArray("programs");
			if (legacyPrograms != null && !legacyPrograms.isEmpty()) {
				errors.add(sourceLabel + " uses legacy [[programs]] format. Please migrate to [[segments]].");
				return List.of();
			}
			errors.add(sourceLabel + " must define at least one [[segments]] entry.");
			return List.of();
		}

		Set<Integer> usedIndexes = new HashSet<>();
		Set<String> usedNames = new HashSet<>();
		for (int i = 0; i < segmentsArray.size(); i++) {
			Object value = segmentsArray.get(i);
			String context = sourceLabel + ".segments[" + i + "]";
			if (!(value instanceof TomlTable segmentTable)) {
				errors.add("Invalid segment entry in " + context + " (expected table).");
				continue;
			}

			String segmentName = readString(segmentTable, "name", context, errors);
			if (segmentName == null || segmentName.isBlank()) {
				errors.add("Missing " + context + ".name");
				continue;
			}
			segmentName = segmentName.trim();
			if (!usedNames.add(segmentName)) {
				errors.add("Duplicate segment name '" + segmentName + "' in " + sourceLabel + ".");
				continue;
			}

			Long indexLong = readLong(segmentTable, "index", context, errors);
			if (indexLong == null) {
				errors.add("Missing " + context + ".index");
				continue;
			}
			if (indexLong < 0L || indexLong > Integer.MAX_VALUE) {
				errors.add(context + ".index must be between 0 and " + Integer.MAX_VALUE + ".");
				continue;
			}
			int index = indexLong.intValue();
			if (!usedIndexes.add(index)) {
				errors.add("Duplicate segment index '" + index + "' in " + sourceLabel + ".");
				continue;
			}

			if (segmentTable.contains("selectors")) {
				warnings.add(context + ".selectors is deprecated and ignored. Stage matching is now stage-wide.");
			}

			String vertex = normalizeOptionalPath(readString(segmentTable, "vertex", context, errors));
			String fragment = normalizeOptionalPath(readString(segmentTable, "fragment", context, errors));
			String geometry = normalizeOptionalPath(readString(segmentTable, "geometry", context, errors));
			String compute = normalizeOptionalPath(readString(segmentTable, "compute", context, errors));
			String config = normalizeOptionalPath(readString(segmentTable, "config", context, errors));
			Map<String, String> files = readStringTable(segmentTable.getTable("files"), context + ".files", errors);
			if (!files.isEmpty()) {
				warnings.add(context + ".files is deprecated and ignored. Stage matching is now stage-wide.");
			}
			if (vertex == null && fragment == null && geometry == null && compute == null && config == null) {
				errors.add(context + " must define at least one of vertex/fragment/geometry/compute/config.");
				continue;
			}
			if (compute != null && "gbuffer".equals(stage)) {
				errors.add(context + ".compute is not allowed in gbuffer stage.");
				continue;
			}

			Map<String, String> segmentInputs = readTypedSegmentParams(segmentTable.getArray("inputs"), context + ".inputs", errors);
			Map<String, String> segmentOutputs = readTypedSegmentParams(segmentTable.getArray("outputs"), context + ".outputs", errors);
			List<String> drawBufferTargets = readDrawBuffers(segmentTable, context, errors);
			List<String> renderTargets = readRenderTargets(segmentTable, context, errors);
			if (!drawBufferTargets.isEmpty() && !renderTargets.isEmpty() && !drawBufferTargets.equals(renderTargets)) {
				errors.add(context + ".drawbuffers and .rendertargets must resolve to the same targets when both are present.");
			}
			List<String> effectiveRenderTargets = !renderTargets.isEmpty() ? renderTargets : drawBufferTargets;
			if (effectiveRenderTargets.size() > 1 && compute == null) {
				warnings.add(context + " declares multiple render targets; current graphics backend may only use the first target.");
			}
			String pingPong = readPingPong(segmentTable, context, errors);
			Map<String, Boolean> flips = readFlipTable(segmentTable, context, errors);
			List<Integer> workGroups = readIntegerVec3(segmentTable, context, "work_groups", "workGroups", errors);
			List<Integer> workGroupsRender = readIntegerVec3(segmentTable, context, "work_groups_render", "workGroupsRender", errors);
			if ((!workGroups.isEmpty() || !workGroupsRender.isEmpty()) && compute == null) {
				errors.add(context + ".work_groups/work_groups_render requires .compute shader.");
			}
			List<String> imagesRead = readImageBindings(segmentTable, context, "images_read", "imagesRead", errors);
			List<String> imagesWrite = readImageBindings(segmentTable, context, "images_write", "imagesWrite", errors);
			String enabledExpression = readEnabledExpression(segmentTable, context, errors);
			if ((enabledExpression == null || enabledExpression.isBlank()) && stageProgramEnabled.containsKey(segmentName)) {
				enabledExpression = stageProgramEnabled.get(segmentName);
			}
			ShaderpackPipelineAlphaState alphaState = readAlphaState(segmentTable, context, errors);
			if (alphaState == null) {
				alphaState = stageAlphaState;
			}
			ShaderpackPipelineBlendState blendState = readBlendState(segmentTable, context, errors);
			if (blendState == null) {
				blendState = stageBlendState;
			}
			Map<String, List<Integer>> effectiveBufferSizes = mergeBufferSizes(
				stageBufferSizes,
				readBufferSizes(segmentTable, context, errors)
			);
			Map<String, List<Double>> effectiveBufferScales = mergeBufferScales(
				stageBufferScales,
				readBufferScales(segmentTable, context, errors)
			);

			Map<String, String> rawParams = readStringTable(segmentTable.getTable("params"), context + ".params", errors);
			Map<String, String> resolvedLocalParams = resolveParams(rawParams, stageContext, context + ".params", errors);
			Map<String, String> effectiveParams = new LinkedHashMap<>(stageContext);
			effectiveParams.putAll(resolvedLocalParams);
			stageContext.putAll(resolvedLocalParams);

			ShaderpackPipelineProgram segment = new ShaderpackPipelineProgram(
				worldId,
				stage,
				segmentName,
				index,
				Map.copyOf(segmentInputs),
				Map.copyOf(segmentOutputs),
				vertex,
				fragment,
				geometry,
				compute,
				config,
				Map.copyOf(effectiveParams),
				List.copyOf(effectiveRenderTargets),
				pingPong,
				Map.copyOf(flips),
				List.copyOf(workGroups),
				List.copyOf(workGroupsRender),
				List.copyOf(imagesRead),
				List.copyOf(imagesWrite),
				enabledExpression,
				alphaState.normalized(),
				blendState.normalized(),
				Map.copyOf(effectiveBufferSizes),
				Map.copyOf(effectiveBufferScales)
			);
			stageSegments.add(segment);
		}
		if (stageSegments.isEmpty()) {
			errors.add("No valid stage segments loaded from " + sourceLabel + ".");
			return List.of();
		}

		stageSegments.sort(
			Comparator.comparingInt(ShaderpackPipelineProgram::index)
				.thenComparing(ShaderpackPipelineProgram::segmentName, String::compareToIgnoreCase)
		);
		validateStageSegmentFlow(stage, sourceLabel, stageSegments, errors, warnings);
		return List.copyOf(stageSegments);
	}

	private static void validateRequiredStagesPresent(
		Map<String, Map<String, List<ShaderpackPipelineProgram>>> byWorld,
		ShaderpackStages stages,
		List<String> errors
	) {
		Map<String, List<ShaderpackPipelineProgram>> fallbackPrograms = byWorld.get("any");
		if (fallbackPrograms == null) {
			errors.add("Missing fallback pipeline mappings under pipelines/any/.");
			return;
		}
		Set<String> availableStages = fallbackPrograms
			.entrySet()
			.stream()
			.filter(entry -> entry.getValue() != null && !entry.getValue().isEmpty())
			.map(Map.Entry::getKey)
			.collect(Collectors.toSet());
		validateStagePresenceForWorld("any", availableStages, stages, errors);
	}

	private static void validateStagePresenceForWorld(
		String worldId,
		Set<String> availableStages,
		ShaderpackStages stages,
		List<String> errors
	) {
		if (stages.shadow() && !availableStages.contains("shadow")) {
			errors.add("Missing pipeline stage file: pipelines/" + worldId + "/shadow.toml");
		}
		if (stages.gbuffer() && !availableStages.contains("gbuffer")) {
			errors.add("Missing pipeline stage file: pipelines/" + worldId + "/gbuffer.toml");
		}
		if (stages.lighting() && !availableStages.contains("lighting")) {
			errors.add("Missing pipeline stage file: pipelines/" + worldId + "/lighting.toml");
		}
		if (stages.translucent() && !availableStages.contains("translucent")) {
			errors.add("Missing pipeline stage file: pipelines/" + worldId + "/translucent.toml");
		}
		if (stages.postprocess() && !availableStages.contains("postprocess")) {
			errors.add("Missing pipeline stage file: pipelines/" + worldId + "/postprocess.toml");
		}
		if (stages.finalStage() && !availableStages.contains("final")) {
			errors.add("Missing pipeline stage file: pipelines/" + worldId + "/final.toml");
		}
	}

	private static Map<String, String> resolveParams(
		Map<String, String> rawParams,
		Map<String, String> inherited,
		String context,
		List<String> errors
	) {
		if (rawParams.isEmpty()) {
			return Map.of();
		}
		Map<String, String> resolved = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : rawParams.entrySet()) {
			String key = entry.getKey();
			String value = entry.getValue();
			if (value == null) {
				continue;
			}
			Map<String, String> available = new LinkedHashMap<>(inherited);
			available.putAll(resolved);
			String resolvedValue = resolveParamValue(value, available, context + "." + key, errors);
			resolved.put(key, resolvedValue);
		}
		return Map.copyOf(resolved);
	}

	private static String resolveParamValue(String rawValue, Map<String, String> available, String context, List<String> errors) {
		String current = rawValue;
		for (int depth = 0; depth < 8; depth++) {
			Matcher matcher = PIPELINE_PARAM_PATTERN.matcher(current);
			if (!matcher.find()) {
				return current;
			}
			StringBuffer builder = new StringBuffer();
			boolean unresolved = false;
			do {
				String key = matcher.group(1);
				String replacement = available.get(key);
				if (replacement == null) {
					errors.add("Unknown pipeline parameter '" + key + "' in " + context);
					replacement = "";
					unresolved = true;
				}
				matcher.appendReplacement(builder, Matcher.quoteReplacement(replacement));
			} while (matcher.find());
			matcher.appendTail(builder);
			String next = builder.toString();
			if (next.equals(current)) {
				return next;
			}
			current = next;
			if (unresolved) {
				return current;
			}
		}
		errors.add("Pipeline parameter expansion too deep in " + context + ".");
		return current;
	}

	private static Map<String, String> readStringTable(TomlTable table, String context, List<String> errors) {
		if (table == null) {
			return Map.of();
		}
		Map<String, String> values = new LinkedHashMap<>();
		for (Map.Entry<String, Object> entry : table.entrySet()) {
			String key = entry.getKey();
			Object raw = entry.getValue();
			if (!(raw instanceof String str)) {
				errors.add("Invalid string for " + context + "." + key);
				continue;
			}
			values.put(key, str);
		}
		return Map.copyOf(values);
	}

	private static Map<String, String> readProgramEnabledRules(TomlTable table, String context, List<String> errors) {
		if (table == null) {
			return Map.of();
		}
		Map<String, String> rules = new LinkedHashMap<>();
		for (Map.Entry<String, Object> entry : table.entrySet()) {
			String segmentName = entry.getKey();
			if (segmentName == null || segmentName.isBlank()) {
				errors.add("Invalid empty program name in " + context + ".");
				continue;
			}
			String ruleContext = context + "." + segmentName;
			Object rawRule = entry.getValue();
			String expression = null;
			if (rawRule instanceof TomlTable programTable) {
				expression = readEnabledExpression(programTable, ruleContext, errors);
				if (expression == null) {
					errors.add("Missing " + ruleContext + ".enabled");
				}
			} else if (rawRule instanceof Boolean flag) {
				expression = Boolean.toString(flag);
			} else if (rawRule instanceof String value) {
				expression = normalizeEnabledExpression(value, ruleContext, errors);
			} else {
				errors.add("Invalid value for " + ruleContext + " (expected table, string or boolean).");
			}
			if (expression == null || expression.isBlank()) {
				continue;
			}
			rules.put(segmentName.trim(), expression);
		}
		return rules.isEmpty() ? Map.of() : Map.copyOf(rules);
	}

	private static String readEnabledExpression(TomlTable table, String context, List<String> errors) {
		if (table == null) {
			return null;
		}
		Object raw = readFirstPresent(table, "enabled", "program_enabled", "programEnabled");
		if (raw == null) {
			return null;
		}
		if (raw instanceof Boolean flag) {
			return Boolean.toString(flag);
		}
		if (raw instanceof String value) {
			return normalizeEnabledExpression(value, context + ".enabled", errors);
		}
		errors.add("Invalid value for " + context + ".enabled (expected string or boolean).");
		return null;
	}

	private static String normalizeEnabledExpression(String expression, String context, List<String> errors) {
		String normalized = expression == null ? "" : expression.trim();
		if (normalized.isBlank()) {
			errors.add("Empty enabled expression at " + context + ".");
			return null;
		}
		if (ENABLED_IDENTIFIER_PATTERN.matcher(normalized).matches()) {
			return normalized;
		}
		try {
			new ProgramEnabledExpression.Parser(normalized, Map.of()).parse();
		} catch (IllegalArgumentException e) {
			errors.add("Invalid enabled expression at " + context + ": " + e.getMessage());
			return null;
		}
		return normalized;
	}

	private static ShaderpackPipelineAlphaState readAlphaState(TomlTable table, String context, List<String> errors) {
		if (table == null) {
			return null;
		}
		Object raw = readFirstPresent(table, "alpha_test", "alphaTest", "alpha");
		if (raw == null) {
			return null;
		}
		String fieldContext = context + ".alpha_test";
		if (raw instanceof Boolean enabled) {
			return enabled
				? ShaderpackPipelineAlphaState.enabled("greater", 0.0D)
				: ShaderpackPipelineAlphaState.DISABLED;
		}
		if (raw instanceof Long number) {
			return ShaderpackPipelineAlphaState.enabled("greater", number.doubleValue());
		}
		if (raw instanceof Double number) {
			return ShaderpackPipelineAlphaState.enabled("greater", number);
		}
		if (raw instanceof String text) {
			return parseAlphaStateString(text, fieldContext, errors);
		}
		if (raw instanceof TomlTable alphaTable) {
			String function = readString(alphaTable, "func", fieldContext, errors);
			if (function == null) {
				function = readString(alphaTable, "function", fieldContext, errors);
			}
			double reference = 0.0D;
			Object refRaw = readFirstPresent(alphaTable, "ref", "reference");
			if (refRaw instanceof Long refLong) {
				reference = refLong.doubleValue();
			} else if (refRaw instanceof Double refDouble) {
				reference = refDouble;
			} else if (refRaw != null) {
				errors.add("Invalid number for " + fieldContext + ".ref");
			}
			if (function != null) {
				String normalizedFunction = function.trim().toLowerCase(Locale.ROOT);
				if (!SUPPORTED_ALPHA_FUNCS.contains(normalizedFunction)) {
					errors.add("Unsupported alpha func '" + function + "' in " + fieldContext + ".");
					return ShaderpackPipelineAlphaState.DISABLED;
				}
				return ShaderpackPipelineAlphaState.enabled(normalizedFunction, reference);
			}
			return ShaderpackPipelineAlphaState.enabled("greater", reference);
		}
		errors.add("Invalid value for " + fieldContext + " (expected boolean, number, string or table).");
		return ShaderpackPipelineAlphaState.DISABLED;
	}

	private static ShaderpackPipelineAlphaState parseAlphaStateString(String text, String context, List<String> errors) {
		String normalized = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
		if (normalized.isBlank() || "off".equals(normalized) || "false".equals(normalized) || "none".equals(normalized)) {
			return ShaderpackPipelineAlphaState.DISABLED;
		}
		if ("on".equals(normalized) || "true".equals(normalized)) {
			return ShaderpackPipelineAlphaState.enabled("greater", 0.0D);
		}
		String[] tokens = normalized.split("[,\\s]+");
		if (tokens.length == 1) {
			if (SUPPORTED_ALPHA_FUNCS.contains(tokens[0])) {
				return ShaderpackPipelineAlphaState.enabled(tokens[0], 0.0D);
			}
			try {
				double reference = Double.parseDouble(tokens[0]);
				return ShaderpackPipelineAlphaState.enabled("greater", reference);
			} catch (NumberFormatException ignored) {
				errors.add("Invalid alpha state '" + text + "' in " + context + ".");
				return ShaderpackPipelineAlphaState.DISABLED;
			}
		}
		if (tokens.length == 2) {
			if (!SUPPORTED_ALPHA_FUNCS.contains(tokens[0])) {
				errors.add("Unsupported alpha func '" + tokens[0] + "' in " + context + ".");
				return ShaderpackPipelineAlphaState.DISABLED;
			}
			try {
				double reference = Double.parseDouble(tokens[1]);
				return ShaderpackPipelineAlphaState.enabled(tokens[0], reference);
			} catch (NumberFormatException ignored) {
				errors.add("Invalid alpha reference '" + tokens[1] + "' in " + context + ".");
				return ShaderpackPipelineAlphaState.DISABLED;
			}
		}
		errors.add("Invalid alpha state '" + text + "' in " + context + " (expected '<func> <ref>').");
		return ShaderpackPipelineAlphaState.DISABLED;
	}

	private static ShaderpackPipelineBlendState readBlendState(TomlTable table, String context, List<String> errors) {
		if (table == null || !table.contains("blend")) {
			return null;
		}
		Object raw = table.get("blend");
		String fieldContext = context + ".blend";
		if (raw instanceof Boolean enabled) {
			return enabled
				? ShaderpackPipelineBlendState.enabled("src_alpha", "one_minus_src_alpha", "one", "zero")
				: ShaderpackPipelineBlendState.DISABLED;
		}
		if (raw instanceof String value) {
			return parseBlendStateTokens(splitBlendTokens(value), fieldContext, errors);
		}
		if (raw instanceof TomlArray array) {
			List<String> tokens = new ArrayList<>();
			for (int i = 0; i < array.size(); i++) {
				Object entry = array.get(i);
				if (!(entry instanceof String token)) {
					errors.add("Invalid blend factor in " + fieldContext + " at index " + i + ".");
					continue;
				}
				if (!token.isBlank()) {
					tokens.add(token.trim());
				}
			}
			return parseBlendStateTokens(tokens, fieldContext, errors);
		}
		if (raw instanceof TomlTable blendTable) {
			String srcColor = readString(blendTable, "src_color", fieldContext, errors);
			String dstColor = readString(blendTable, "dst_color", fieldContext, errors);
			String srcAlpha = readString(blendTable, "src_alpha", fieldContext, errors);
			String dstAlpha = readString(blendTable, "dst_alpha", fieldContext, errors);
			if (srcColor == null || dstColor == null) {
				errors.add("Missing " + fieldContext + ".src_color/.dst_color");
				return ShaderpackPipelineBlendState.DISABLED;
			}
			if (srcAlpha == null) {
				srcAlpha = srcColor;
			}
			if (dstAlpha == null) {
				dstAlpha = dstColor;
			}
			return buildBlendState(srcColor, dstColor, srcAlpha, dstAlpha, fieldContext, errors);
		}
		errors.add("Invalid value for " + fieldContext + " (expected string, array, table or boolean).");
		return ShaderpackPipelineBlendState.DISABLED;
	}

	private static ShaderpackPipelineBlendState parseBlendStateTokens(List<String> tokens, String context, List<String> errors) {
		if (tokens == null || tokens.isEmpty()) {
			return ShaderpackPipelineBlendState.DISABLED;
		}
		if (tokens.size() == 1) {
			String token = tokens.getFirst().trim().toLowerCase(Locale.ROOT);
			if ("off".equals(token) || "false".equals(token) || "none".equals(token)) {
				return ShaderpackPipelineBlendState.DISABLED;
			}
			if ("on".equals(token) || "true".equals(token)) {
				return ShaderpackPipelineBlendState.enabled("src_alpha", "one_minus_src_alpha", "one", "zero");
			}
			errors.add("Invalid blend token '" + tokens.getFirst() + "' in " + context + ".");
			return ShaderpackPipelineBlendState.DISABLED;
		}
		if (tokens.size() == 2) {
			String src = tokens.get(0);
			String dst = tokens.get(1);
			return buildBlendState(src, dst, src, dst, context, errors);
		}
		if (tokens.size() == 4) {
			return buildBlendState(tokens.get(0), tokens.get(1), tokens.get(2), tokens.get(3), context, errors);
		}
		errors.add("Invalid blend state in " + context + " (expected 'off', two factors or four factors).");
		return ShaderpackPipelineBlendState.DISABLED;
	}

	private static List<String> splitBlendTokens(String value) {
		if (value == null || value.isBlank()) {
			return List.of();
		}
		String[] raw = value.trim().split("[,\\s]+");
		List<String> tokens = new ArrayList<>(raw.length);
		for (String token : raw) {
			if (token == null || token.isBlank()) {
				continue;
			}
			tokens.add(token);
		}
		return List.copyOf(tokens);
	}

	private static ShaderpackPipelineBlendState buildBlendState(
		String srcColor,
		String dstColor,
		String srcAlpha,
		String dstAlpha,
		String context,
		List<String> errors
	) {
		String normalizedSrcColor = normalizeBlendFactor(srcColor, context, errors);
		String normalizedDstColor = normalizeBlendFactor(dstColor, context, errors);
		String normalizedSrcAlpha = normalizeBlendFactor(srcAlpha, context, errors);
		String normalizedDstAlpha = normalizeBlendFactor(dstAlpha, context, errors);
		return ShaderpackPipelineBlendState.enabled(
			normalizedSrcColor,
			normalizedDstColor,
			normalizedSrcAlpha,
			normalizedDstAlpha
		);
	}

	private static String normalizeBlendFactor(String factor, String context, List<String> errors) {
		if (factor == null || factor.isBlank()) {
			errors.add("Missing blend factor in " + context + ".");
			return "one";
		}
		String normalized = factor.trim().toLowerCase(Locale.ROOT);
		if (!SUPPORTED_BLEND_FACTORS.contains(normalized)) {
			errors.add("Unsupported blend factor '" + factor + "' in " + context + ".");
			return "one";
		}
		return normalized;
	}

	private static Map<String, List<Integer>> readBufferSizes(TomlTable table, String context, List<String> errors) {
		if (table == null) {
			return Map.of();
		}
		TomlTable sizeTable = table.getTable("size");
		if (sizeTable == null) {
			return Map.of();
		}
		TomlTable targetTable = sizeTable.getTable("buffer");
		if (targetTable == null) {
			targetTable = sizeTable;
		}
		Map<String, List<Integer>> sizes = new LinkedHashMap<>();
		for (Map.Entry<String, Object> entry : targetTable.entrySet()) {
			String key = entry.getKey();
			if ("buffer".equals(key) && entry.getValue() instanceof TomlTable) {
				continue;
			}
			String target = normalizeRenderTarget(key);
			if (target == null) {
				errors.add("Invalid render target in " + context + ".size.buffer: '" + key + "'.");
				continue;
			}
			List<Integer> value = parseIntegerPair(entry.getValue(), context + ".size.buffer." + key, errors);
			if (value.isEmpty()) {
				continue;
			}
			sizes.put(target, value);
		}
		return sizes.isEmpty() ? Map.of() : Map.copyOf(sizes);
	}

	private static Map<String, List<Double>> readBufferScales(TomlTable table, String context, List<String> errors) {
		if (table == null) {
			return Map.of();
		}
		TomlTable scaleTable = table.getTable("scale");
		if (scaleTable == null) {
			return Map.of();
		}
		TomlTable targetTable = scaleTable.getTable("buffer");
		if (targetTable == null) {
			targetTable = scaleTable;
		}
		Map<String, List<Double>> scales = new LinkedHashMap<>();
		for (Map.Entry<String, Object> entry : targetTable.entrySet()) {
			String key = entry.getKey();
			if ("buffer".equals(key) && entry.getValue() instanceof TomlTable) {
				continue;
			}
			String target = normalizeRenderTarget(key);
			if (target == null) {
				errors.add("Invalid render target in " + context + ".scale: '" + key + "'.");
				continue;
			}
			List<Double> value = parseDoublePair(entry.getValue(), context + ".scale." + key, errors);
			if (value.isEmpty()) {
				continue;
			}
			scales.put(target, value);
		}
		return scales.isEmpty() ? Map.of() : Map.copyOf(scales);
	}

	private static List<Integer> parseIntegerPair(Object raw, String context, List<String> errors) {
		List<Double> pair = parseDoublePair(raw, context, errors);
		if (pair.isEmpty()) {
			return List.of();
		}
		List<Integer> converted = new ArrayList<>(2);
		for (Double value : pair) {
			if (value == null || !Double.isFinite(value) || value <= 0.0D) {
				errors.add("Invalid positive integer in " + context + ".");
				return List.of();
			}
			double rounded = Math.rint(value);
			if (Math.abs(rounded - value) > 0.000001D) {
				errors.add("Expected integer values in " + context + ".");
				return List.of();
			}
			if (rounded > Integer.MAX_VALUE) {
				errors.add("Integer out of range in " + context + ".");
				return List.of();
			}
			converted.add((int) rounded);
		}
		return List.copyOf(converted);
	}

	private static List<Double> parseDoublePair(Object raw, String context, List<String> errors) {
		if (raw instanceof Long number) {
			double value = number.doubleValue();
			if (!Double.isFinite(value) || value <= 0.0D) {
				errors.add("Invalid positive value in " + context + ".");
				return List.of();
			}
			return List.of(value, value);
		}
		if (raw instanceof Double number) {
			double value = number;
			if (!Double.isFinite(value) || value <= 0.0D) {
				errors.add("Invalid positive value in " + context + ".");
				return List.of();
			}
			return List.of(value, value);
		}
		if (raw instanceof String text) {
			String[] tokens = text.trim().split("[,\\s]+");
			if (tokens.length == 1 || tokens.length == 2) {
				try {
					double x = Double.parseDouble(tokens[0]);
					double y = tokens.length == 2 ? Double.parseDouble(tokens[1]) : x;
					if (!Double.isFinite(x) || !Double.isFinite(y) || x <= 0.0D || y <= 0.0D) {
						errors.add("Invalid positive values in " + context + ".");
						return List.of();
					}
					return List.of(x, y);
				} catch (NumberFormatException e) {
					errors.add("Invalid numeric value in " + context + ".");
					return List.of();
				}
			}
			errors.add("Invalid pair value in " + context + " (expected one or two numbers).");
			return List.of();
		}
		if (raw instanceof TomlArray array) {
			if (array.size() != 2) {
				errors.add("Invalid pair value in " + context + " (expected [x, y]).");
				return List.of();
			}
			double[] values = new double[2];
			for (int i = 0; i < 2; i++) {
				Object entry = array.get(i);
				if (entry instanceof Long number) {
					values[i] = number.doubleValue();
				} else if (entry instanceof Double number) {
					values[i] = number;
				} else {
					errors.add("Invalid numeric value in " + context + " at index " + i + ".");
					return List.of();
				}
				if (!Double.isFinite(values[i]) || values[i] <= 0.0D) {
					errors.add("Invalid positive value in " + context + " at index " + i + ".");
					return List.of();
				}
			}
			return List.of(values[0], values[1]);
		}
		errors.add("Invalid value in " + context + " (expected number, string, or [x, y]).");
		return List.of();
	}

	private static Map<String, List<Integer>> mergeBufferSizes(
		Map<String, List<Integer>> base,
		Map<String, List<Integer>> overrides
	) {
		if ((base == null || base.isEmpty()) && (overrides == null || overrides.isEmpty())) {
			return Map.of();
		}
		Map<String, List<Integer>> merged = new LinkedHashMap<>();
		if (base != null) {
			merged.putAll(base);
		}
		if (overrides != null) {
			merged.putAll(overrides);
		}
		return Map.copyOf(merged);
	}

	private static Map<String, List<Double>> mergeBufferScales(
		Map<String, List<Double>> base,
		Map<String, List<Double>> overrides
	) {
		if ((base == null || base.isEmpty()) && (overrides == null || overrides.isEmpty())) {
			return Map.of();
		}
		Map<String, List<Double>> merged = new LinkedHashMap<>();
		if (base != null) {
			merged.putAll(base);
		}
		if (overrides != null) {
			merged.putAll(overrides);
		}
		return Map.copyOf(merged);
	}

	private static List<String> readDrawBuffers(TomlTable table, String context, List<String> errors) {
		Object raw = readFirstPresent(table, "drawbuffers", "draw_buffers");
		if (raw == null) {
			return List.of();
		}
		LinkedHashSet<String> targets = new LinkedHashSet<>();
		String fieldContext = context + ".drawbuffers";
		if (raw instanceof String value) {
			parseDrawBuffersString(value, fieldContext, targets, errors);
			return List.copyOf(targets);
		}
		if (raw instanceof TomlArray array) {
			for (int i = 0; i < array.size(); i++) {
				Object entry = array.get(i);
				if (entry instanceof Long number) {
					int index = number.intValue();
					if (index < 0 || index > 15) {
						errors.add(fieldContext + " index out of range at position " + i + " (expected 0..15).");
						continue;
					}
					targets.add("colortex" + index);
					continue;
				}
				if (entry instanceof String text) {
					String normalized = normalizeRenderTarget(text);
					if (normalized == null) {
						errors.add(fieldContext + " contains invalid render target '" + text + "' at position " + i + ".");
						continue;
					}
					targets.add(normalized);
					continue;
				}
				errors.add("Invalid value in " + fieldContext + " at position " + i + " (expected integer or string).");
			}
			return List.copyOf(targets);
		}
		errors.add("Invalid value for " + fieldContext + " (expected string or string/integer array).");
		return List.of();
	}

	private static List<String> readRenderTargets(TomlTable table, String context, List<String> errors) {
		Object raw = readFirstPresent(table, "rendertargets", "render_targets");
		if (raw == null) {
			return List.of();
		}
		LinkedHashSet<String> targets = new LinkedHashSet<>();
		String fieldContext = context + ".rendertargets";
		if (raw instanceof String value) {
			String[] entries = value.split("[,\\s]+");
			for (String entry : entries) {
				if (entry == null || entry.isBlank()) {
					continue;
				}
				String normalized = normalizeRenderTarget(entry);
				if (normalized == null) {
					errors.add(fieldContext + " contains invalid render target '" + entry + "'.");
					continue;
				}
				targets.add(normalized);
			}
			return List.copyOf(targets);
		}
		if (raw instanceof TomlArray array) {
			for (int i = 0; i < array.size(); i++) {
				Object entry = array.get(i);
				if (!(entry instanceof String text)) {
					errors.add("Invalid string in " + fieldContext + " at index " + i + ".");
					continue;
				}
				String normalized = normalizeRenderTarget(text);
				if (normalized == null) {
					errors.add(fieldContext + " contains invalid render target '" + text + "' at index " + i + ".");
					continue;
				}
				targets.add(normalized);
			}
			return List.copyOf(targets);
		}
		errors.add("Invalid value for " + fieldContext + " (expected string or string array).");
		return List.of();
	}

	private static String readPingPong(TomlTable table, String context, List<String> errors) {
		String pingPong = readString(table, "ping_pong", context, errors);
		if (pingPong == null) {
			pingPong = readString(table, "pingPong", context, errors);
		}
		if (pingPong == null || pingPong.isBlank()) {
			return "main";
		}
		String normalized = pingPong.trim().toLowerCase(Locale.ROOT);
		if (!SUPPORTED_PING_PONG.contains(normalized)) {
			errors.add(context + ".ping_pong must be one of " + SUPPORTED_PING_PONG + ".");
			return "main";
		}
		return normalized;
	}

	private static Map<String, Boolean> readFlipTable(TomlTable table, String context, List<String> errors) {
		if (!table.contains("flip")) {
			return Map.of();
		}
		Object raw = table.get("flip");
		if (!(raw instanceof TomlTable flipTable)) {
			errors.add("Invalid table for " + context + ".flip");
			return Map.of();
		}
		Map<String, Boolean> flips = new LinkedHashMap<>();
		for (Map.Entry<String, Object> entry : flipTable.entrySet()) {
			String rawTarget = entry.getKey();
			String normalizedTarget = normalizeFlipTarget(rawTarget);
			if (normalizedTarget == null) {
				errors.add(context + ".flip." + rawTarget + " is not a valid target.");
				continue;
			}
			if (!(entry.getValue() instanceof Boolean enabled)) {
				errors.add("Invalid boolean for " + context + ".flip." + rawTarget);
				continue;
			}
			Boolean existing = flips.putIfAbsent(normalizedTarget, enabled);
			if (existing != null && !existing.equals(enabled)) {
				errors.add(context + ".flip defines conflicting values for '" + normalizedTarget + "'.");
			}
		}
		return Map.copyOf(flips);
	}

	private static List<Integer> readIntegerVec3(TomlTable table, String context, String keyA, String keyB, List<String> errors) {
		boolean hasA = table.contains(keyA);
		boolean hasB = table.contains(keyB);
		if (!hasA && !hasB) {
			return List.of();
		}
		String key = hasA ? keyA : keyB;
		if (hasA && hasB) {
			errors.add(context + " defines both ." + keyA + " and ." + keyB + "; use only one.");
		}
		Object raw = table.get(key);
		String fieldContext = context + "." + key;
		if (!(raw instanceof TomlArray array)) {
			errors.add("Invalid array for " + fieldContext + " (expected [x, y, z]).");
			return List.of();
		}
		if (array.size() != 3) {
			errors.add(fieldContext + " must contain exactly 3 integers.");
			return List.of();
		}
		List<Integer> values = new ArrayList<>(3);
		for (int i = 0; i < 3; i++) {
			Object entry = array.get(i);
			if (!(entry instanceof Long number)) {
				errors.add("Invalid integer in " + fieldContext + " at index " + i + ".");
				return List.of();
			}
			if (number < 1L || number > Integer.MAX_VALUE) {
				errors.add(fieldContext + " values must be between 1 and " + Integer.MAX_VALUE + ".");
				return List.of();
			}
			values.add(number.intValue());
		}
		return List.copyOf(values);
	}

	private static List<String> readImageBindings(TomlTable table, String context, String keyA, String keyB, List<String> errors) {
		boolean hasA = table.contains(keyA);
		boolean hasB = table.contains(keyB);
		Object raw = hasA ? table.get(keyA) : (hasB ? table.get(keyB) : null);
		if (raw == null) {
			return List.of();
		}
		String fieldContext = context + "." + (hasA ? keyA : keyB);
		LinkedHashSet<String> bindings = new LinkedHashSet<>();
		if (raw instanceof String value) {
			String normalized = normalizeImageBinding(value);
			if (normalized == null) {
				errors.add(fieldContext + " contains invalid image binding '" + value + "'.");
				return List.of();
			}
			bindings.add(normalized);
			return List.copyOf(bindings);
		}
		if (raw instanceof TomlArray array) {
			for (int i = 0; i < array.size(); i++) {
				Object entry = array.get(i);
				if (!(entry instanceof String value)) {
					errors.add("Invalid string in " + fieldContext + " at index " + i + ".");
					continue;
				}
				String normalized = normalizeImageBinding(value);
				if (normalized == null) {
					errors.add(fieldContext + " contains invalid image binding '" + value + "' at index " + i + ".");
					continue;
				}
				bindings.add(normalized);
			}
			return List.copyOf(bindings);
		}
		errors.add("Invalid value for " + fieldContext + " (expected string or string array).");
		return List.of();
	}

	private static Object readFirstPresent(TomlTable table, String... keys) {
		for (String key : keys) {
			if (table.contains(key)) {
				return table.get(key);
			}
		}
		return null;
	}

	private static void parseDrawBuffersString(
		String value,
		String context,
		LinkedHashSet<String> output,
		List<String> errors
	) {
		String trimmed = value == null ? "" : value.trim();
		if (trimmed.isBlank()) {
			return;
		}
		if (trimmed.matches("[0-9,\\s]+")) {
			for (int i = 0; i < trimmed.length(); i++) {
				char c = trimmed.charAt(i);
				if (Character.isWhitespace(c) || c == ',') {
					continue;
				}
				int index = Character.digit(c, 10);
				if (index < 0 || index > 15) {
					errors.add(context + " contains invalid drawbuffers index '" + c + "'.");
					continue;
				}
				output.add("colortex" + index);
			}
			return;
		}
		String[] entries = trimmed.split("[,\\s]+");
		for (String entry : entries) {
			if (entry == null || entry.isBlank()) {
				continue;
			}
			String normalized = normalizeRenderTarget(entry);
			if (normalized == null) {
				errors.add(context + " contains invalid render target '" + entry + "'.");
				continue;
			}
			output.add(normalized);
		}
	}

	private static String normalizeRenderTarget(String rawValue) {
		if (rawValue == null) {
			return null;
		}
		String normalized = rawValue.trim().toLowerCase(Locale.ROOT);
		if (normalized.isBlank()) {
			return null;
		}
		if (normalized.matches("^([0-9]|1[0-5])$")) {
			normalized = "colortex" + normalized;
		}
		if (RENDER_TARGET_PATTERN.matcher(normalized).matches()) {
			return normalized;
		}
		return CUSTOM_RENDER_TARGET_PATTERN.matcher(normalized).matches() ? normalized : null;
	}

	private static String normalizeFlipTarget(String rawValue) {
		String renderTarget = normalizeRenderTarget(rawValue);
		if (renderTarget != null) {
			return renderTarget;
		}
		return normalizeImageBinding(rawValue);
	}

	private static String normalizeImageBinding(String rawValue) {
		if (rawValue == null) {
			return null;
		}
		String normalized = rawValue.trim().toLowerCase(Locale.ROOT);
		if (normalized.isBlank()) {
			return null;
		}
		if (COLOR_IMAGE_PATTERN.matcher(normalized).matches()) {
			return normalized;
		}
		if (SHADOW_COLOR_IMAGE_PATTERN.matcher(normalized).matches()) {
			return normalized;
		}
		return null;
	}

	private static String normalizeOptionalPath(String path) {
		if (path == null || path.isBlank()) {
			return null;
		}
		return ShaderpackPipelineProgram.normalizePath(path);
	}

	private static void validateStageSegmentFlow(
		String stage,
		String sourceLabel,
		List<ShaderpackPipelineProgram> segments,
		List<String> errors,
		List<String> warnings
	) {
		if (segments.isEmpty()) {
			return;
		}
		Map<String, String> fixedInputs = STAGE_FIXED_INPUTS.get(stage);
		Map<String, String> fixedOutputs = STAGE_FIXED_OUTPUTS.get(stage);
		if (fixedInputs == null || fixedOutputs == null) {
			warnings.add("Unknown fixed stage contract for stage '" + stage + "' in " + sourceLabel + ".");
			return;
		}

		ShaderpackPipelineProgram firstSegment = segments.getFirst();
		if (!firstSegment.inputs().equals(fixedInputs)) {
			errors.add(
				sourceLabel + " first segment '" + firstSegment.segmentName()
					+ "' must declare fixed stage inputs: " + formatTypedParams(fixedInputs)
			);
		}

		ShaderpackPipelineProgram lastSegment = segments.getLast();
		if (!lastSegment.outputs().equals(fixedOutputs)) {
			errors.add(
				sourceLabel + " last segment '" + lastSegment.segmentName()
					+ "' must declare fixed stage outputs: " + formatTypedParams(fixedOutputs)
			);
		}

		Map<String, String> produced = new LinkedHashMap<>();
		produced.putAll(firstSegment.outputs());

		for (int i = 1; i < segments.size(); i++) {
			ShaderpackPipelineProgram segment = segments.get(i);
			for (Map.Entry<String, String> inputEntry : segment.inputs().entrySet()) {
				String name = inputEntry.getKey();
				String expectedType = inputEntry.getValue();
				String producedType = produced.get(name);
				if (producedType == null) {
					errors.add(
						sourceLabel + " segment '" + segment.segmentName()
							+ "' input '" + expectedType + ":" + name
							+ "' is not produced by any previous segment."
					);
					continue;
				}
				if (!producedType.equals(expectedType)) {
					errors.add(
						sourceLabel + " segment '" + segment.segmentName()
							+ "' input type mismatch for '" + name
							+ "': expected '" + expectedType + "', got '" + producedType + "'."
					);
				}
			}
			for (Map.Entry<String, String> outputEntry : segment.outputs().entrySet()) {
				String name = outputEntry.getKey();
				String type = outputEntry.getValue();
				String existingType = produced.get(name);
				if (existingType != null && !existingType.equals(type)) {
					errors.add(
						sourceLabel + " segment '" + segment.segmentName()
							+ "' output '" + name + "' changes type from '" + existingType + "' to '" + type + "'."
					);
					continue;
				}
				produced.put(name, type);
			}
		}
	}

	private static Map<String, String> readTypedSegmentParams(TomlArray array, String context, List<String> errors) {
		if (array == null) {
			return Map.of();
		}
		Map<String, String> typedParams = new LinkedHashMap<>();
		for (int i = 0; i < array.size(); i++) {
			Object raw = array.get(i);
			if (!(raw instanceof String value)) {
				errors.add("Invalid string in " + context + " at index " + i);
				continue;
			}
			String normalized = value.trim();
			Matcher matcher = SEGMENT_TYPED_PARAM_PATTERN.matcher(normalized);
			if (!matcher.matches()) {
				errors.add("Invalid typed parameter in " + context + " at index " + i + " (expected 'Type:name').");
				continue;
			}
			String type = matcher.group(1);
			String name = matcher.group(2);
			if (!SUPPORTED_RESOURCE_TYPES.contains(type)) {
				errors.add("Unsupported typed parameter resource type '" + type + "' in " + context + " at index " + i + ".");
				continue;
			}
			String previousType = typedParams.get(name);
			if (previousType != null && !previousType.equals(type)) {
				errors.add(
					"Conflicting typed parameter '" + name + "' in " + context
						+ " (types '" + previousType + "' and '" + type + "')."
				);
				continue;
			}
			typedParams.put(name, type);
		}
		return Map.copyOf(typedParams);
	}

	private static Long readLong(TomlTable table, String key, String context, List<String> errors) {
		if (!table.contains(key)) {
			return null;
		}
		Object raw = table.get(key);
		if (!(raw instanceof Long value)) {
			errors.add("Invalid integer for " + context + "." + key);
			return null;
		}
		return value;
	}

	private static String formatTypedParams(Map<String, String> typedParams) {
		if (typedParams == null || typedParams.isEmpty()) {
			return "[]";
		}
		List<String> entries = new ArrayList<>();
		for (Map.Entry<String, String> entry : typedParams.entrySet()) {
			entries.add(entry.getValue() + ":" + entry.getKey());
		}
		return entries.toString();
	}

	@SafeVarargs
	private static Map<String, String> fixedInterface(Map.Entry<String, String>... entries) {
		Map<String, String> map = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : entries) {
			map.put(entry.getKey(), entry.getValue());
		}
		return Map.copyOf(map);
	}

	private static List<String> resolveWorldCandidates() {
		String worldKey = WORLD_KEY_NONE;
		String worldPath = "";
		String worldNamespace = "";
		try {
			MinecraftClient client = MinecraftClient.getInstance();
			if (client != null && client.world != null && client.world.getRegistryKey() != null) {
				@SuppressWarnings("null")
				var identifier = client.world.getRegistryKey().getValue();
				if (identifier != null) {
					worldKey = identifier.toString().toLowerCase(Locale.ROOT);
					String namespace = identifier.getNamespace();
					worldNamespace = namespace == null ? "" : namespace.toLowerCase(Locale.ROOT);
					String path = identifier.getPath();
					worldPath = path == null ? "" : path.toLowerCase(Locale.ROOT);
				}
			}
		} catch (Exception ignored) {
		}
		String lookupKey = worldKey + "|" + worldPath;
		List<String> cachedCandidates = cachedWorldCandidates;
		worldCandidateRequests.increment();
		if (lookupKey.equals(cachedWorldKey) && !cachedCandidates.isEmpty()) {
			worldCandidateHits.increment();
			return cachedCandidates;
		}

		LinkedHashSet<String> candidates = new LinkedHashSet<>();
		if (!WORLD_KEY_NONE.equals(worldKey) && !worldKey.isBlank()) {
			candidates.add(worldKey);
			candidates.add(worldKey.replace(':', '_').replace('/', '_'));
		}
		addCanonicalWorldCandidates(candidates, worldNamespace, worldPath);
		if (!worldPath.isBlank()) {
			candidates.add(worldPath);
			candidates.add(worldPath.replace('/', '_'));
		}
		candidates.add("any");

		List<String> resolved = List.copyOf(candidates);
		cachedWorldCandidates = resolved;
		cachedWorldKey = lookupKey;
		return resolved;
	}

	private static void addCanonicalWorldCandidates(LinkedHashSet<String> candidates, String worldNamespace, String worldPath) {
		if (worldPath == null || worldPath.isBlank()) {
			return;
		}
		String normalizedNamespace = worldNamespace == null ? "" : worldNamespace.trim().toLowerCase(Locale.ROOT);
		String normalizedPath = worldPath.trim().toLowerCase(Locale.ROOT);
		if ((normalizedNamespace.isBlank() || "minecraft".equals(normalizedNamespace)) && "the_nether".equals(normalizedPath)) {
			candidates.add("world-1");
			return;
		}
		if ((normalizedNamespace.isBlank() || "minecraft".equals(normalizedNamespace)) && "the_end".equals(normalizedPath)) {
			candidates.add("world1");
			return;
		}
		if ((normalizedNamespace.isBlank() || "minecraft".equals(normalizedNamespace)) && "overworld".equals(normalizedPath)) {
			candidates.add("world0");
		}
		if (normalizedPath.matches("^world-?[0-9]+$")) {
			candidates.add(normalizedPath);
		}
	}

	public record CacheStats(long requests, long hits) {
	}

	public record PipelineLookupStats(long requests, long cacheHits, long segmentHits) {
	}

	@FunctionalInterface
	private interface PipelineReaderFactory<T> {
		Reader open(T sourceRef, String sourceLabel) throws IOException;
	}

	private static ShaderpackStages parseStages(TomlTable table, List<String> errors) {
		if (table == null) {
			errors.add("Missing [stages] table.");
			return new ShaderpackStages(false, false, false, false, false, false);
		}
		Boolean shadow = requireBoolean(table, "shadow", "stages", errors);
		Boolean gbuffer = requireBoolean(table, "gbuffer", "stages", errors);
		Boolean lighting = requireBoolean(table, "lighting", "stages", errors);
		Boolean translucent = requireBoolean(table, "translucent", "stages", errors);
		Boolean postprocess = requireBoolean(table, "postprocess", "stages", errors);
		Boolean finalStage = requireBoolean(table, "final", "stages", errors);

		return new ShaderpackStages(
			Boolean.TRUE.equals(shadow),
			Boolean.TRUE.equals(gbuffer),
			Boolean.TRUE.equals(lighting),
			Boolean.TRUE.equals(translucent),
			Boolean.TRUE.equals(postprocess),
			Boolean.TRUE.equals(finalStage)
		);
	}

	private static List<ShaderpackResourceDecl> parseResources(TomlArray array, List<String> errors) {
		if (array == null) {
			return List.of();
		}
		List<ShaderpackResourceDecl> resources = new ArrayList<>();
		Set<String> names = new HashSet<>();
		for (int i = 0; i < array.size(); i++) {
			Object value = array.get(i);
			if (!(value instanceof TomlTable table)) {
				errors.add("Invalid resource entry at index " + i);
				continue;
			}
			String name = requireString(table, "name", "resources[" + i + "]", errors);
			String type = requireString(table, "type", "resources[" + i + "]", errors);
			String resolution = requireString(table, "resolution", "resources[" + i + "]", errors);
			String format = requireString(table, "format", "resources[" + i + "]", errors);
			String lifetime = requireString(table, "lifetime", "resources[" + i + "]", errors);

			if (name != null) {
				if (!names.add(name)) {
					errors.add("Duplicate resource name: " + name);
				}
			}
			if (type != null && !SUPPORTED_RESOURCE_TYPES.contains(type)) {
				errors.add("Unsupported resource type: " + type);
			}
			if (resolution != null && !SUPPORTED_RESOLUTIONS.contains(resolution.toLowerCase(Locale.ROOT))) {
				errors.add("Unsupported resource resolution: " + resolution);
			}
			if (format != null && !SUPPORTED_FORMATS.contains(format.toLowerCase(Locale.ROOT))) {
				errors.add("Unsupported resource format: " + format);
			}
			if (lifetime != null && !SUPPORTED_LIFETIMES.contains(lifetime.toLowerCase(Locale.ROOT))) {
				errors.add("Unsupported resource lifetime: " + lifetime);
			}

			resources.add(new ShaderpackResourceDecl(name, type, resolution, format, lifetime));
		}
		return resources;
	}

	private static ShaderpackExtensions parseExtensions(TomlTable table, List<String> errors) {
		if (table == null) {
			return new ShaderpackExtensions(List.of(), List.of());
		}
		List<String> required = readStringArray(table.getArray("required"), "extensions.required", errors);
		List<String> optional = readStringArray(table.getArray("optional"), "extensions.optional", errors);
		return new ShaderpackExtensions(required, optional);
	}

	private static ShaderpackFeatures parseFeatures(TomlTable table, List<String> errors) {
		if (table == null) {
			return ShaderpackFeatures.defaults();
		}
		Boolean configOptions = readBoolean(table, "sulkan_config_options", "features", errors);
		Boolean autoExtract = readBoolean(table, "auto_extract_options", "features", errors);
		if (autoExtract == null) {
			autoExtract = readBoolean(table, "auto_extract", "features", errors);
		}
		return new ShaderpackFeatures(Boolean.TRUE.equals(configOptions), Boolean.TRUE.equals(autoExtract));
	}

	private static ShaderpackGlobalSettings parseGlobalSettings(TomlParseResult root, List<String> errors) {
		Map<String, Object> values = new LinkedHashMap<>();
		readGlobalTable(values, root.getTable("global"), "", "global", errors);
		readGlobalTable(values, root.getTable("runtime"), "", "runtime", errors);
		return ShaderpackGlobalSettings.from(values);
	}

	private static void readGlobalTable(
		Map<String, Object> output,
		TomlTable table,
		String pathPrefix,
		String context,
		List<String> errors
	) {
		if (table == null) {
			return;
		}
		for (Map.Entry<String, Object> entry : table.entrySet()) {
			String key = entry.getKey();
			if (key == null || key.isBlank()) {
				continue;
			}
			String path = pathPrefix.isBlank() ? key : pathPrefix + "." + key;
			String keyContext = context + "." + key;
			Object value = entry.getValue();
			if (value instanceof TomlTable nested) {
				readGlobalTable(output, nested, path, keyContext, errors);
				continue;
			}
			if (value instanceof TomlArray array) {
				List<Object> parsed = new ArrayList<>(array.size());
				boolean valid = true;
				for (int i = 0; i < array.size(); i++) {
					Object item = array.get(i);
					if (item instanceof String || item instanceof Boolean || item instanceof Long || item instanceof Double) {
						parsed.add(item);
						continue;
					}
					errors.add("Unsupported value in " + keyContext + " at index " + i + " (expected scalar array).");
					valid = false;
					break;
				}
				if (valid) {
					output.put(path, List.copyOf(parsed));
				}
				continue;
			}
			if (value instanceof String || value instanceof Boolean || value instanceof Long || value instanceof Double) {
				output.put(path, value);
			} else {
				errors.add("Unsupported value type in " + keyContext + ".");
			}
		}
	}

	private static ShaderpackTextureBindings parseTextureBindings(
		TomlTable texturesTable,
		List<String> errors,
		List<String> warnings
	) {
		if (texturesTable == null) {
			return ShaderpackTextureBindings.EMPTY;
		}
		Map<String, Map<String, String>> byStage = new LinkedHashMap<>();
		for (Map.Entry<String, Object> entry : texturesTable.entrySet()) {
			String key = entry.getKey();
			if (key == null || key.isBlank()) {
				continue;
			}
			Object value = entry.getValue();
			if (value instanceof TomlTable stageTable) {
				String stage = ShaderpackTextureBindings.normalizeStage(key);
				for (Map.Entry<String, Object> samplerEntry : stageTable.entrySet()) {
					String sampler = samplerEntry.getKey();
					Object samplerValue = samplerEntry.getValue();
					if (!(samplerValue instanceof String source) || source.isBlank()) {
						errors.add("Invalid texture source for textures." + key + "." + sampler + " (expected string).");
						continue;
					}
					putTextureBinding(byStage, stage, sampler, source, warnings);
				}
				continue;
			}
			if (value instanceof String source) {
				String stage = "any";
				String sampler = key;
				int split = key.indexOf('.');
				if (split > 0 && split < key.length() - 1) {
					stage = key.substring(0, split);
					sampler = key.substring(split + 1);
				}
				putTextureBinding(byStage, stage, sampler, source, warnings);
				continue;
			}
			errors.add("Invalid value for textures." + key + " (expected string or table).");
		}
		return ShaderpackTextureBindings.from(byStage);
	}

	private static void putTextureBinding(
		Map<String, Map<String, String>> byStage,
		String stage,
		String sampler,
		String source,
		List<String> warnings
	) {
		if (sampler == null || sampler.isBlank()) {
			return;
		}
		String normalizedStage = ShaderpackTextureBindings.normalizeStage(stage);
		String normalizedSampler = ShaderpackTextureBindings.normalizeSampler(sampler);
		Map<String, String> stageBindings = byStage.computeIfAbsent(normalizedStage, ignored -> new LinkedHashMap<>());
		String previous = stageBindings.put(normalizedSampler, source.trim());
		if (previous != null && !previous.equals(source.trim())) {
			warnings.add(
				"Texture binding textures." + normalizedStage + "." + normalizedSampler
					+ " overwritten from '" + previous + "' to '" + source.trim() + "'."
			);
		}
	}

	private static ShaderpackUiLayout parseUiLayout(TomlTable uiTable, List<String> errors, List<String> warnings) {
		if (uiTable == null) {
			return ShaderpackUiLayout.EMPTY;
		}
		Map<String, List<String>> screens = new LinkedHashMap<>();
		Map<String, Integer> columns = new LinkedHashMap<>();
		Set<String> sliders = new LinkedHashSet<>();
		Map<String, Map<String, Object>> profiles = new LinkedHashMap<>();

		Object screenValue = uiTable.get("screen");
		if (screenValue instanceof TomlArray || screenValue instanceof String) {
			List<String> main = readPathListValue(screenValue, "ui.screen", errors);
			if (!main.isEmpty()) {
				screens.put("main", main);
			}
		} else if (screenValue instanceof TomlTable screenTable) {
			parseUiScreenTable(screenTable, screens, columns, errors);
		} else if (screenValue != null) {
			errors.add("Invalid value for ui.screen (expected array, string, or table).");
		}

		Long mainColumns = readLong(uiTable, "columns", "ui", errors);
		if (mainColumns != null && mainColumns > 0L && mainColumns <= Integer.MAX_VALUE) {
			columns.put("main", mainColumns.intValue());
		}
		TomlTable screenColumns = uiTable.getTable("screen_columns");
		if (screenColumns != null) {
			for (Map.Entry<String, Object> entry : screenColumns.entrySet()) {
				String screenKey = ShaderpackUiLayout.normalizeScreenKey(entry.getKey());
				Integer value = coercePositiveInt(entry.getValue(), "ui.screen_columns." + entry.getKey(), errors);
				if (value != null) {
					columns.put(screenKey, value);
				}
			}
		}

		Object slidersValue = readFirstPresent(uiTable, "sliders", "slider");
		for (String slider : readPathListValue(slidersValue, "ui.sliders", errors)) {
			sliders.add(slider);
		}

		TomlTable profileTable = uiTable.getTable("profile");
		if (profileTable == null) {
			profileTable = uiTable.getTable("profiles");
		}
		if (profileTable != null) {
			for (Map.Entry<String, Object> profileEntry : profileTable.entrySet()) {
				String profileName = profileEntry.getKey();
				Object rawProfile = profileEntry.getValue();
				if (!(rawProfile instanceof TomlTable valueTable)) {
					errors.add("Invalid table for ui.profile." + profileName + ".");
					continue;
				}
				Map<String, Object> values = new LinkedHashMap<>();
				for (Map.Entry<String, Object> optionValue : valueTable.entrySet()) {
					Object scalar = optionValue.getValue();
					if (!(scalar instanceof String || scalar instanceof Boolean || scalar instanceof Long || scalar instanceof Double)) {
						errors.add(
							"Invalid scalar for ui.profile." + profileName + "." + optionValue.getKey()
								+ " (expected string/number/bool)."
						);
						continue;
					}
					values.put(optionValue.getKey(), scalar);
				}
				if (!values.isEmpty()) {
					profiles.put(profileName, Map.copyOf(values));
				}
			}
		}

		if (!screens.isEmpty() && screens.containsKey("main") && screens.get("main").isEmpty()) {
			warnings.add("ui.screen.main is empty.");
		}

		return ShaderpackUiLayout.from(screens, columns, sliders, profiles);
	}

	private static void parseUiScreenTable(
		TomlTable table,
		Map<String, List<String>> screens,
		Map<String, Integer> columns,
		List<String> errors
	) {
		for (Map.Entry<String, Object> entry : table.entrySet()) {
			String screenKey = ShaderpackUiLayout.normalizeScreenKey(entry.getKey());
			Object value = entry.getValue();
			String context = "ui.screen." + entry.getKey();
			if (value instanceof TomlArray || value instanceof String) {
				List<String> paths = readPathListValue(value, context, errors);
				if (!paths.isEmpty()) {
					screens.put(screenKey, paths);
				}
				continue;
			}
			if (value instanceof TomlTable screenTable) {
				Object listValue = readFirstPresent(screenTable, "options", "items", "entries", "list");
				List<String> paths = readPathListValue(listValue, context + ".options", errors);
				if (!paths.isEmpty()) {
					screens.put(screenKey, paths);
				}
				Integer columnValue = coercePositiveInt(screenTable.get("columns"), context + ".columns", errors);
				if (columnValue != null) {
					columns.put(screenKey, columnValue);
				}
				continue;
			}
			errors.add("Invalid value for " + context + " (expected array, string, or table).");
		}
	}

	private static List<String> readPathListValue(Object raw, String context, List<String> errors) {
		if (raw == null) {
			return List.of();
		}
		LinkedHashSet<String> values = new LinkedHashSet<>();
		if (raw instanceof String text) {
			String[] items = text.split("[,\\s]+");
			for (String item : items) {
				if (item == null || item.isBlank()) {
					continue;
				}
				values.add(item.trim());
			}
			return List.copyOf(values);
		}
		if (raw instanceof TomlArray array) {
			for (int i = 0; i < array.size(); i++) {
				Object entry = array.get(i);
				if (!(entry instanceof String text) || text.isBlank()) {
					errors.add("Invalid string in " + context + " at index " + i + ".");
					continue;
				}
				values.add(text.trim());
			}
			return List.copyOf(values);
		}
		errors.add("Invalid value for " + context + " (expected string or string array).");
		return List.of();
	}

	private static ShaderpackIdMappings parseIdMappings(TomlParseResult root, List<String> errors, List<String> warnings) {
		TomlTable idTable = root.getTable("id");
		TomlTable idsTable = root.getTable("ids");
		Map<String, Integer> blocks = readIdMap(selectIdSubTable(idTable, idsTable, "blocks", "block"), "ids.blocks", errors);
		Map<String, Integer> items = readIdMap(selectIdSubTable(idTable, idsTable, "items", "item"), "ids.items", errors);
		Map<String, Integer> entities = readIdMap(selectIdSubTable(idTable, idsTable, "entities", "entity"), "ids.entities", errors);

		Map<String, String> layers = new LinkedHashMap<>();
		readLayerMap(layers, root.getTable("layer"), "layer", errors, warnings);
		readLayerMap(layers, root.getTable("layers"), "layers", errors, warnings);
		readLayerMap(layers, selectIdSubTable(idTable, idsTable, "layer", "layers"), "ids.layer", errors, warnings);

		return ShaderpackIdMappings.from(blocks, items, entities, layers);
	}

	private static TomlTable selectIdSubTable(TomlTable primary, TomlTable secondary, String keyA, String keyB) {
		TomlTable table = primary == null ? null : primary.getTable(keyA);
		if (table == null && primary != null) {
			table = primary.getTable(keyB);
		}
		if (table == null && secondary != null) {
			table = secondary.getTable(keyA);
			if (table == null) {
				table = secondary.getTable(keyB);
			}
		}
		return table;
	}

	private static Map<String, Integer> readIdMap(TomlTable table, String context, List<String> errors) {
		if (table == null) {
			return Map.of();
		}
		Map<String, Integer> values = new LinkedHashMap<>();
		for (Map.Entry<String, Object> entry : table.entrySet()) {
			String key = entry.getKey();
			Integer value = coercePositiveInt(entry.getValue(), context + "." + key, errors);
			if (value == null) {
				continue;
			}
			values.put(key, value);
		}
		return values.isEmpty() ? Map.of() : Map.copyOf(values);
	}

	private static void readLayerMap(
		Map<String, String> output,
		TomlTable table,
		String context,
		List<String> errors,
		List<String> warnings
	) {
		if (table == null) {
			return;
		}
		for (Map.Entry<String, Object> entry : table.entrySet()) {
			String key = entry.getKey();
			Object raw = entry.getValue();
			if (!(raw instanceof String layerValue) || layerValue.isBlank()) {
				errors.add("Invalid layer value for " + context + "." + key + " (expected string).");
				continue;
			}
			String normalized = layerValue.trim().toLowerCase(Locale.ROOT);
			if (!SUPPORTED_LAYER_VALUES.contains(normalized)) {
				warnings.add("Unknown layer '" + layerValue + "' in " + context + "." + key + " (kept as-is).");
			}
			output.put(key, normalized);
		}
	}

	private static Integer coercePositiveInt(Object raw, String context, List<String> errors) {
		if (raw == null) {
			return null;
		}
		if (raw instanceof Long number) {
			if (number <= 0L || number > Integer.MAX_VALUE) {
				errors.add("Invalid positive integer in " + context + ".");
				return null;
			}
			return number.intValue();
		}
		if (raw instanceof Double number) {
			if (!Double.isFinite(number) || number <= 0.0D || number > Integer.MAX_VALUE || Math.rint(number) != number) {
				errors.add("Invalid positive integer in " + context + ".");
				return null;
			}
			return (int) Math.round(number);
		}
		if (raw instanceof String text) {
			try {
				int parsed = Integer.parseInt(text.trim());
				if (parsed <= 0) {
					errors.add("Invalid positive integer in " + context + ".");
					return null;
				}
				return parsed;
			} catch (NumberFormatException e) {
				errors.add("Invalid integer in " + context + ".");
				return null;
			}
		}
		errors.add("Invalid integer in " + context + ".");
		return null;
	}

	private static List<ShaderpackOptionDecl> mergeOptions(
		List<ShaderpackOptionDecl> declared,
		List<ShaderpackOptionDecl> extracted,
		List<String> warnings
	) {
		if ((declared == null || declared.isEmpty()) && (extracted == null || extracted.isEmpty())) {
			return List.of();
		}
		List<ShaderpackOptionDecl> merged = new ArrayList<>();
		Map<String, ShaderpackOptionDecl> declaredByPath = new LinkedHashMap<>();
		collectFlatOptions(declared, declaredByPath);
		if (declared != null && !declared.isEmpty()) {
			merged.addAll(declared);
		}
		if (extracted != null && !extracted.isEmpty()) {
			for (ShaderpackOptionDecl option : extracted) {
				if (option == null || option.path() == null || option.path().isBlank()) {
					continue;
				}
				if (declaredByPath.containsKey(option.path())) {
					warnings.add("Auto extracted option '" + option.path() + "' ignored (already declared in [options]).");
					continue;
				}
				merged.add(option);
				declaredByPath.put(option.path(), option);
			}
		}
		return List.copyOf(merged);
	}

	private static void collectFlatOptions(List<ShaderpackOptionDecl> options, Map<String, ShaderpackOptionDecl> output) {
		if (options == null || options.isEmpty()) {
			return;
		}
		for (ShaderpackOptionDecl option : options) {
			if (option == null) {
				continue;
			}
			if ("page".equalsIgnoreCase(option.type())) {
				collectFlatOptions(option.children(), output);
			} else if (option.path() != null && !option.path().isBlank()) {
				output.put(option.path(), option);
			}
		}
	}

	private static List<ShaderpackOptionDecl> extractOptionsFromShaders(
		Path shaderpackPath,
		Map<String, String> translations,
		List<String> errors,
		List<String> warnings
	) {
		Map<String, ShaderpackOptionDecl> extracted = new LinkedHashMap<>();
		if (Files.isDirectory(shaderpackPath)) {
			extractOptionsFromDirectory(shaderpackPath, translations, extracted, errors, warnings);
		} else if (isZip(shaderpackPath)) {
			extractOptionsFromZip(shaderpackPath, translations, extracted, errors, warnings);
		}
		if (extracted.isEmpty()) {
			return List.of();
		}
		return List.copyOf(extracted.values());
	}

	private static void extractOptionsFromDirectory(
		Path shaderpackPath,
		Map<String, String> translations,
		Map<String, ShaderpackOptionDecl> extracted,
		List<String> errors,
		List<String> warnings
	) {
		Path shadersRoot = shaderpackPath.resolve("shaders");
		if (!Files.isDirectory(shadersRoot)) {
			return;
		}
		try (Stream<Path> stream = Files.walk(shadersRoot)) {
			List<Path> shaderFiles = stream
				.filter(Files::isRegularFile)
				.filter(path -> isShaderTextPath(path.getFileName().toString()))
				.sorted()
				.toList();
			for (Path file : shaderFiles) {
				String relative = ShaderpackPipelineProgram.normalizePath(shaderpackPath.relativize(file).toString());
				parseAutoOptionsFromText(
					Files.readString(file, StandardCharsets.UTF_8),
					relative,
					translations,
					extracted,
					errors,
					warnings
				);
			}
		} catch (IOException e) {
			errors.add("Failed to scan shader files for auto options: " + e.getMessage());
		}
	}

	@SuppressWarnings("null")
	private static void extractOptionsFromZip(
		Path shaderpackPath,
		Map<String, String> translations,
		Map<String, ShaderpackOptionDecl> extracted,
		List<String> errors,
		List<String> warnings
	) {
		try (ZipFile zipFile = new ZipFile(shaderpackPath.toFile(), StandardCharsets.UTF_8)) {
			List<? extends ZipEntry> entries = zipFile.stream()
				.filter(entry -> !entry.isDirectory())
				.filter(entry -> {
					String name = ShaderpackPipelineProgram.normalizePath(entry.getName());
					return name.startsWith("shaders/") && isShaderTextPath(name);
				})
				.sorted(Comparator.comparing(entry -> entry.getName().toLowerCase(Locale.ROOT)))
				.toList();
			for (ZipEntry entry : entries) {
				String relative = ShaderpackPipelineProgram.normalizePath(entry.getName());
				try (Reader reader = new BufferedReader(new InputStreamReader(zipFile.getInputStream(entry), StandardCharsets.UTF_8))) {
					String text = readAll(reader);
					parseAutoOptionsFromText(text, relative, translations, extracted, errors, warnings);
				}
			}
		} catch (IOException e) {
			errors.add("Failed to scan shader zip for auto options: " + e.getMessage());
		}
	}

	private static String readAll(Reader reader) throws IOException {
		StringBuilder builder = new StringBuilder();
		char[] buffer = new char[2048];
		int read;
		while ((read = reader.read(buffer)) >= 0) {
			builder.append(buffer, 0, read);
		}
		return builder.toString();
	}

	private static void parseAutoOptionsFromText(
		String source,
		String sourcePath,
		Map<String, String> translations,
		Map<String, ShaderpackOptionDecl> extracted,
		List<String> errors,
		List<String> warnings
	) {
		if (source == null || source.isBlank()) {
			return;
		}
		String[] lines = source.split("\\R");
		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			int marker = line.toLowerCase(Locale.ROOT).indexOf(AUTO_OPTION_DIRECTIVE);
			if (marker < 0) {
				continue;
			}
			String payload = line.substring(marker + AUTO_OPTION_DIRECTIVE.length()).trim();
			ShaderpackOptionDecl option = parseAutoOptionPayload(
				payload,
				sourcePath,
				i + 1,
				translations,
				errors,
				warnings
			);
			if (option == null) {
				continue;
			}
			ShaderpackOptionDecl previous = extracted.putIfAbsent(option.path(), option);
			if (previous != null) {
				warnings.add(
					"Duplicate auto option '" + option.path() + "' ignored at "
						+ sourcePath + ":" + (i + 1) + "."
				);
			}
		}
	}

	private static ShaderpackOptionDecl parseAutoOptionPayload(
		String payload,
		String sourcePath,
		int lineNumber,
		Map<String, String> translations,
		List<String> errors,
		List<String> warnings
	) {
		if (payload == null || payload.isBlank()) {
			errors.add("Empty auto option directive at " + sourcePath + ":" + lineNumber + ".");
			return null;
		}
		Map<String, String> values = parseDirectiveKeyValues(payload);
		String context = sourcePath + ":" + lineNumber;
		String path = values.get("path");
		String type = values.get("type");
		String defaultRaw = values.get("default");
		if (path == null || path.isBlank() || type == null || type.isBlank() || defaultRaw == null) {
			errors.add("Auto option directive missing required fields path/type/default at " + context + ".");
			return null;
		}
		String normalizedType = type.trim().toLowerCase(Locale.ROOT);
		String markerKey = values.getOrDefault("key", path.trim());
		String target = values.getOrDefault("target", sourcePath);
		String labelKey = values.get("label_key");
		String descriptionKey = values.get("description_key");
		String label = values.get("label");
		String description = values.get("description");
		if (labelKey != null && !translations.isEmpty() && !translations.containsKey(labelKey)) {
			warnings.add("Missing lang key: " + labelKey + " (" + context + ")");
		}
		if (descriptionKey != null && !translations.isEmpty() && !translations.containsKey(descriptionKey)) {
			warnings.add("Missing lang key: " + descriptionKey + " (" + context + ")");
		}

		Object defaultValue;
		List<String> optionValues = List.of();
		List<String> renderValues = List.of();
		switch (normalizedType) {
			case "bool" -> {
				Boolean boolValue = parseBooleanLiteral(defaultRaw);
				if (boolValue == null) {
					errors.add("Invalid bool default '" + defaultRaw + "' in auto option at " + context + ".");
					return null;
				}
				defaultValue = boolValue;
			}
			case "int" -> {
				try {
					defaultValue = Long.parseLong(defaultRaw.trim());
				} catch (NumberFormatException e) {
					errors.add("Invalid int default '" + defaultRaw + "' in auto option at " + context + ".");
					return null;
				}
			}
			case "float" -> {
				try {
					defaultValue = Double.parseDouble(defaultRaw.trim());
				} catch (NumberFormatException e) {
					errors.add("Invalid float default '" + defaultRaw + "' in auto option at " + context + ".");
					return null;
				}
			}
			case "string" -> defaultValue = stripOptionalQuotes(defaultRaw);
			case "enum" -> {
				optionValues = parseDelimitedList(values.get("values"));
				renderValues = parseDelimitedList(values.get("render_values"));
				String selected = stripOptionalQuotes(defaultRaw);
				if (optionValues.isEmpty()) {
					optionValues = List.of(selected);
				}
				if (!optionValues.contains(selected)) {
					errors.add("Enum default '" + selected + "' not present in values at " + context + ".");
					return null;
				}
				if (!renderValues.isEmpty() && renderValues.size() != optionValues.size()) {
					errors.add("render_values length mismatch in auto option at " + context + ".");
					return null;
				}
				defaultValue = selected;
			}
			default -> {
				errors.add("Unsupported auto option type '" + type + "' at " + context + ".");
				return null;
			}
		}

		String normalizedPath = path.trim();
		String id = normalizedPath;
		int dot = normalizedPath.lastIndexOf('.');
		if (dot >= 0 && dot < normalizedPath.length() - 1) {
			id = normalizedPath.substring(dot + 1);
		}
		return new ShaderpackOptionDecl(
			id,
			normalizedPath,
			normalizedType,
			ShaderpackPipelineProgram.normalizePath(target),
			markerKey,
			renderValues,
			defaultValue,
			optionValues,
			null,
			null,
			null,
			labelKey,
			descriptionKey,
			label,
			description,
			List.of()
		);
	}

	private static Map<String, String> parseDirectiveKeyValues(String payload) {
		Map<String, String> values = new LinkedHashMap<>();
		for (String token : tokenizeDirectivePayload(payload)) {
			int separator = token.indexOf('=');
			if (separator <= 0 || separator >= token.length() - 1) {
				continue;
			}
			String key = token.substring(0, separator).trim().toLowerCase(Locale.ROOT);
			String value = token.substring(separator + 1).trim();
			if (!key.isBlank() && !value.isBlank()) {
				values.put(key, stripOptionalQuotes(value));
			}
		}
		return Map.copyOf(values);
	}

	private static List<String> tokenizeDirectivePayload(String payload) {
		if (payload == null || payload.isBlank()) {
			return List.of();
		}
		List<String> tokens = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		boolean inQuote = false;
		char quote = '\0';
		for (int i = 0; i < payload.length(); i++) {
			char c = payload.charAt(i);
			if (inQuote) {
				current.append(c);
				if (c == quote) {
					inQuote = false;
				}
				continue;
			}
			if (c == '\'' || c == '"') {
				inQuote = true;
				quote = c;
				current.append(c);
				continue;
			}
			if (Character.isWhitespace(c)) {
				if (!current.isEmpty()) {
					tokens.add(current.toString());
					current.setLength(0);
				}
				continue;
			}
			current.append(c);
		}
		if (!current.isEmpty()) {
			tokens.add(current.toString());
		}
		return List.copyOf(tokens);
	}

	private static String stripOptionalQuotes(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		if (trimmed.length() >= 2) {
			char first = trimmed.charAt(0);
			char last = trimmed.charAt(trimmed.length() - 1);
			if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
				return trimmed.substring(1, trimmed.length() - 1);
			}
		}
		return trimmed;
	}

	private static List<String> parseDelimitedList(String raw) {
		if (raw == null || raw.isBlank()) {
			return List.of();
		}
		String[] tokens = raw.split("[|,]");
		List<String> values = new ArrayList<>(tokens.length);
		for (String token : tokens) {
			if (token == null || token.isBlank()) {
				continue;
			}
			values.add(stripOptionalQuotes(token));
		}
		return values.isEmpty() ? List.of() : List.copyOf(values);
	}

	private static Boolean parseBooleanLiteral(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String normalized = raw.trim().toLowerCase(Locale.ROOT);
		return switch (normalized) {
			case "true", "on", "yes", "1" -> Boolean.TRUE;
			case "false", "off", "no", "0" -> Boolean.FALSE;
			default -> null;
		};
	}

	private static boolean isShaderTextPath(String fileName) {
		if (fileName == null || fileName.isBlank()) {
			return false;
		}
		String lower = fileName.toLowerCase(Locale.ROOT);
		for (String extension : OPTION_AUTO_EXTRACT_EXTENSIONS) {
			if (lower.endsWith(extension)) {
				return true;
			}
		}
		return false;
	}

	private static List<ShaderpackOptionDecl> parseOptions(
		TomlTable table,
		boolean required,
		Map<String, String> translations,
		List<String> errors,
		List<String> warnings
	) {
		if (table == null) {
			if (required) {
				errors.add("Missing [options] table required by features.sulkan_config_options.");
			}
			return List.of();
		}
		if (table.keySet().isEmpty()) {
			if (required) {
				errors.add("[options] table must contain at least one option.");
			}
			return List.of();
		}
		return parseOptionsTable(table, "", translations, errors, warnings);
	}

	private static List<ShaderpackOptionDecl> parseOptionsTable(
		TomlTable table,
		String pathPrefix,
		Map<String, String> translations,
		List<String> errors,
		List<String> warnings
	) {
		List<ShaderpackOptionDecl> options = new ArrayList<>();
		for (String key : table.keySet()) {
			Object value = table.get(key);
			String idPath = pathPrefix.isEmpty() ? key : pathPrefix + "." + key;
			String context = "options." + idPath;
			if (!(value instanceof TomlTable optionTable)) {
				errors.add("Option '" + idPath + "' must be a table.");
				continue;
			}

			String type = requireString(optionTable, "type", context, errors);
			String labelKey = readString(optionTable, "label_key", context, errors);
			String descriptionKey = readString(optionTable, "description_key", context, errors);
			String label = readString(optionTable, "label", context, errors);
			String description = readString(optionTable, "description", context, errors);

			if (labelKey != null && !translations.isEmpty() && !translations.containsKey(labelKey)) {
				warnings.add("Missing lang key: " + labelKey + " (" + context + ")");
			}
			if (descriptionKey != null && !translations.isEmpty() && !translations.containsKey(descriptionKey)) {
				warnings.add("Missing lang key: " + descriptionKey + " (" + context + ")");
			}

			TomlTable childrenTable = optionTable.getTable("options");
			if (type != null && type.equalsIgnoreCase("page")) {
				if (optionTable.contains("target") || optionTable.contains("key") || optionTable.contains("render_values")) {
					errors.add(context + ".target/key/render_values is not allowed for page options.");
				}
				if (optionTable.contains("default")) {
					errors.add(context + ".default is not allowed for page options.");
				}
				if (optionTable.contains("values")) {
					errors.add(context + ".values is not allowed for page options.");
				}
				if (optionTable.contains("min") || optionTable.contains("max") || optionTable.contains("step")) {
					errors.add(context + ".min/max/step is not allowed for page options.");
				}
				if (childrenTable == null) {
					errors.add("Missing " + context + ".options for page option.");
					options.add(new ShaderpackOptionDecl(
						key,
						idPath,
						type,
						null,
						null,
						List.of(),
						null,
						List.of(),
						null,
						null,
						null,
						labelKey,
						descriptionKey,
						label,
						description,
						List.of()
					));
					continue;
				}
				if (childrenTable.keySet().isEmpty()) {
					errors.add(context + ".options must contain at least one option.");
				}
				List<ShaderpackOptionDecl> children = parseOptionsTable(childrenTable, idPath, translations, errors, warnings);
				options.add(new ShaderpackOptionDecl(
					key,
					idPath,
					type,
					null,
					null,
					List.of(),
					null,
					List.of(),
					null,
					null,
					null,
					labelKey,
					descriptionKey,
					label,
					description,
					children
				));
				continue;
			}

			if (childrenTable != null) {
				errors.add(context + ".options is only allowed for page options.");
			}

			String target = readString(optionTable, "target", context, errors);
			String markerKey = readString(optionTable, "key", context, errors);
			List<String> renderValuesList = List.of();
			boolean renderValuesPresent = false;
			if (optionTable.contains("render_values")) {
				TomlArray renderValues = optionTable.getArray("render_values");
				if (renderValues == null) {
					errors.add("Invalid array for " + context + ".render_values");
				} else {
					renderValuesList = readStringArray(renderValues, context + ".render_values", errors);
					renderValuesPresent = true;
				}
			}
			if (target == null || target.isBlank()) {
				errors.add("Missing " + context + ".target");
			}
			if (markerKey == null || markerKey.isBlank()) {
				errors.add("Missing " + context + ".key");
			}
			boolean useFileMode = "[use_file]".equals(markerKey);

			Object defaultValue = optionTable.get("default");
			if (defaultValue == null) {
				errors.add("Missing " + context + ".default");
			}

			List<String> valuesList = List.of();
			if (type != null) {
				switch (type.toLowerCase(Locale.ROOT)) {
					case "bool" -> {
						if (!(defaultValue instanceof Boolean)) {
							errors.add(context + ".default must be boolean.");
						}
					}
					case "int" -> {
						if (!(defaultValue instanceof Long)) {
							errors.add(context + ".default must be integer.");
						}
						validateNumberField(optionTable, context, "min", true, errors);
						validateNumberField(optionTable, context, "max", true, errors);
						validateNumberField(optionTable, context, "step", true, errors);
					}
					case "float" -> {
						if (!(defaultValue instanceof Double || defaultValue instanceof Long)) {
							errors.add(context + ".default must be number.");
						}
						validateNumberField(optionTable, context, "min", false, errors);
						validateNumberField(optionTable, context, "max", false, errors);
						validateNumberField(optionTable, context, "step", false, errors);
					}
					case "string" -> {
						if (!(defaultValue instanceof String)) {
							errors.add(context + ".default must be string.");
						}
					}
					case "enum" -> {
						TomlArray values = optionTable.getArray("values");
						if (values == null || values.isEmpty()) {
							errors.add(context + ".values must be a non-empty string array.");
						} else {
							boolean allStrings = true;
							List<String> items = new ArrayList<>();
							for (int i = 0; i < values.size(); i++) {
								Object entry = values.get(i);
								if (!(entry instanceof String)) {
									allStrings = false;
									break;
								}
								items.add((String) entry);
							}
							if (!allStrings) {
								errors.add(context + ".values must contain only strings.");
							} else {
								valuesList = List.copyOf(items);
							}
						}
						if (!(defaultValue instanceof String)) {
							errors.add(context + ".default must be string for enum.");
						} else if (!valuesList.isEmpty() && !valuesList.contains(defaultValue)) {
							errors.add(context + ".default must be one of values.");
						}
						if (!renderValuesPresent) {
							errors.add("Missing " + context + ".render_values");
						} else if (!valuesList.isEmpty() && renderValuesList.size() != valuesList.size()) {
							errors.add(context + ".render_values must match values length.");
						}
					}
					default -> errors.add(context + ".type unsupported: " + type);
				}
			}
			if (type != null && !type.equalsIgnoreCase("enum") && renderValuesPresent) {
				errors.add(context + ".render_values is only allowed for enum options.");
			}
			if (useFileMode && (type == null || !type.equalsIgnoreCase("enum"))) {
				errors.add(context + ".key=[use_file] is only allowed for enum options.");
			}

			Number min = getNumber(optionTable, "min");
			Number max = getNumber(optionTable, "max");
			Number step = getNumber(optionTable, "step");
			options.add(new ShaderpackOptionDecl(
				key,
				idPath,
				type,
				target,
				markerKey,
				renderValuesList,
				defaultValue,
				valuesList,
				min,
				max,
				step,
				labelKey,
				descriptionKey,
				label,
				description,
				List.of()
			));
		}
		return options;
	}

	private static void validateNumberField(TomlTable table, String context, String field, boolean integer, List<String> errors) {
		if (!table.contains(field)) {
			return;
		}
		Object value = table.get(field);
		if (integer) {
			if (!(value instanceof Long)) {
				errors.add(context + "." + field + " must be integer.");
			}
		} else {
			if (!(value instanceof Double || value instanceof Long)) {
				errors.add(context + "." + field + " must be number.");
			}
		}
	}

	private static String readString(TomlTable table, String key, String context, List<String> errors) {
		if (!table.contains(key)) {
			return null;
		}
		Object raw = table.get(key);
		if (!(raw instanceof String)) {
			errors.add("Invalid string for " + context + "." + key);
			return null;
		}
		return (String) raw;
	}

	private static Number getNumber(TomlTable table, String key) {
		if (!table.contains(key)) {
			return null;
		}
		Object raw = table.get(key);
		if (raw instanceof Number number) {
			return number;
		}
		return null;
	}

	private static Map<String, String> loadLangMap(Path shaderpackPath, List<String> warnings) {
		String locale = getActiveLocale();
		Map<String, String> map = readLangFile(shaderpackPath, locale, warnings);
		if (map.isEmpty() && !locale.equals("en_us")) {
			map = readLangFile(shaderpackPath, "en_us", warnings);
		}
		return map;
	}

	private static Map<String, String> readLangFile(Path shaderpackPath, String locale, List<String> warnings) {
		String relative = LANG_DIRECTORY + "/" + locale + ".json";
		if (Files.isDirectory(shaderpackPath)) {
			Path path = shaderpackPath.resolve(relative);
			if (!Files.exists(path)) {
				return Map.of();
			}
			try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
				Map<String, String> map = GSON.fromJson(reader, LANG_MAP_TYPE);
				return map == null ? Map.of() : map;
			} catch (IOException e) {
				warnings.add("Failed to read lang file: " + relative);
				return Map.of();
			}
		}
		if (isZip(shaderpackPath)) {
			try (ZipFile zipFile = new ZipFile(shaderpackPath.toFile(), StandardCharsets.UTF_8)) {
				ZipEntry entry = zipFile.getEntry(relative);
				if (entry == null) {
					return Map.of();
				}
				try (Reader reader = new BufferedReader(new InputStreamReader(zipFile.getInputStream(entry), StandardCharsets.UTF_8))) {
					Map<String, String> map = GSON.fromJson(reader, LANG_MAP_TYPE);
					return map == null ? Map.of() : map;
				}
			} catch (IOException e) {
				warnings.add("Failed to read lang file: " + relative);
				return Map.of();
			}
		}
		return Map.of();
	}

	private static String getActiveLocale() {
		try {
			MinecraftClient client = MinecraftClient.getInstance();
			String lang = client != null && client.options != null ? client.options.language : null;
			if (lang != null && !lang.isBlank()) {
				return lang.toLowerCase(Locale.ROOT);
			}
		} catch (Exception ignored) {
		}
		return "en_us";
	}

	private static String requireString(TomlTable table, String key, String context, List<String> errors) {
		String value = table.getString(key);
		if (value == null || value.isBlank()) {
			errors.add("Missing " + context + "." + key);
			return null;
		}
		return value;
	}

	private static Boolean requireBoolean(TomlTable table, String key, String context, List<String> errors) {
		Boolean value = readBoolean(table, key, context, errors);
		if (value == null) {
			errors.add("Missing " + context + "." + key);
		}
		return value;
	}

	private static Boolean readBoolean(TomlTable table, String key, String context, List<String> errors) {
		if (!table.contains(key)) {
			return null;
		}
		Object raw = table.get(key);
		if (!(raw instanceof Boolean)) {
			errors.add("Invalid boolean for " + context + "." + key);
			return null;
		}
		return (Boolean) raw;
	}

	private static List<String> readStringArray(TomlArray array, String context, List<String> errors) {
		if (array == null) {
			return List.of();
		}
		List<String> values = new ArrayList<>();
		for (int i = 0; i < array.size(); i++) {
			Object raw = array.get(i);
			if (!(raw instanceof String str)) {
				errors.add("Invalid string in " + context + " at index " + i);
				continue;
			}
			values.add(str);
		}
		return values;
	}

	private static final class ProgramEnabledExpression {
		private ProgramEnabledExpression() {
		}

		private static final class Parser {
			private final String source;
			private final Map<String, Object> variables;
			private int index;

			private Parser(String source, Map<String, Object> variables) {
				this.source = source == null ? "" : source;
				this.variables = variables == null ? Map.of() : variables;
			}

			private boolean parse() {
				Object value = parseOr();
				skipWhitespace();
				if (index != source.length()) {
					throw error("Unexpected token");
				}
				return asBoolean(value);
			}

			private Object parseOr() {
				Object left = parseAnd();
				while (true) {
					skipWhitespace();
					if (!match("||")) {
						return left;
					}
					Object right = parseAnd();
					left = asBoolean(left) || asBoolean(right);
				}
			}

			private Object parseAnd() {
				Object left = parseEquality();
				while (true) {
					skipWhitespace();
					if (!match("&&")) {
						return left;
					}
					Object right = parseEquality();
					left = asBoolean(left) && asBoolean(right);
				}
			}

			private Object parseEquality() {
				Object left = parseUnary();
				while (true) {
					skipWhitespace();
					if (match("==")) {
						Object right = parseUnary();
						left = compare(left, right);
						continue;
					}
					if (match("!=")) {
						Object right = parseUnary();
						left = !compare(left, right);
						continue;
					}
					return left;
				}
			}

			private Object parseUnary() {
				skipWhitespace();
				if (match("!")) {
					return !asBoolean(parseUnary());
				}
				return parsePrimary();
			}

			private Object parsePrimary() {
				skipWhitespace();
				if (match("(")) {
					Object nested = parseOr();
					skipWhitespace();
					if (!match(")")) {
						throw error("Expected ')'");
					}
					return nested;
				}
				if (peek() == '"' || peek() == '\'') {
					return parseQuotedString();
				}
				String token = parseToken();
				if (token == null || token.isBlank()) {
					throw error("Expected value");
				}
				String lower = token.toLowerCase(Locale.ROOT);
				if ("true".equals(lower)) {
					return Boolean.TRUE;
				}
				if ("false".equals(lower)) {
					return Boolean.FALSE;
				}
				try {
					return Double.parseDouble(token);
				} catch (NumberFormatException ignored) {
				}
				Object variable = resolveVariable(token);
				return variable == null ? Boolean.FALSE : variable;
			}

			private Object resolveVariable(String token) {
				if (variables.containsKey(token)) {
					return variables.get(token);
				}
				String lower = token.toLowerCase(Locale.ROOT);
				if (variables.containsKey(lower)) {
					return variables.get(lower);
				}
				String upper = token.toUpperCase(Locale.ROOT);
				if (variables.containsKey(upper)) {
					return variables.get(upper);
				}
				return null;
			}

			private static boolean compare(Object left, Object right) {
				Object normalizedLeft = normalizeComparable(left);
				Object normalizedRight = normalizeComparable(right);
				if (normalizedLeft instanceof Number && normalizedRight instanceof Number) {
					double a = ((Number) normalizedLeft).doubleValue();
					double b = ((Number) normalizedRight).doubleValue();
					return Double.compare(a, b) == 0;
				}
				return String.valueOf(normalizedLeft).equalsIgnoreCase(String.valueOf(normalizedRight));
			}

			private static Object normalizeComparable(Object value) {
				if (value == null) {
					return Boolean.FALSE;
				}
				if (value instanceof Boolean || value instanceof Number) {
					return value;
				}
				String text = value.toString().trim();
				if (text.isBlank()) {
					return "";
				}
				String lower = text.toLowerCase(Locale.ROOT);
				if ("true".equals(lower)) {
					return Boolean.TRUE;
				}
				if ("false".equals(lower)) {
					return Boolean.FALSE;
				}
				try {
					return Double.parseDouble(text);
				} catch (NumberFormatException ignored) {
					return text;
				}
			}

			private static boolean asBoolean(Object value) {
				if (value == null) {
					return false;
				}
				if (value instanceof Boolean bool) {
					return bool;
				}
				if (value instanceof Number number) {
					return number.doubleValue() != 0.0D;
				}
				String text = value.toString().trim();
				if (text.isBlank()) {
					return false;
				}
				String lower = text.toLowerCase(Locale.ROOT);
				if ("true".equals(lower) || "on".equals(lower) || "yes".equals(lower)) {
					return true;
				}
				if ("false".equals(lower) || "off".equals(lower) || "no".equals(lower)) {
					return false;
				}
				try {
					return Double.parseDouble(text) != 0.0D;
				} catch (NumberFormatException ignored) {
					return true;
				}
			}

			private String parseQuotedString() {
				char quote = peek();
				if (quote != '\'' && quote != '"') {
					throw error("Expected string");
				}
				index++;
				StringBuilder builder = new StringBuilder();
				while (index < source.length()) {
					char c = source.charAt(index++);
					if (c == quote) {
						return builder.toString();
					}
					if (c == '\\' && index < source.length()) {
						char escaped = source.charAt(index++);
						builder.append(escaped);
						continue;
					}
					builder.append(c);
				}
				throw error("Unterminated string literal");
			}

			private String parseToken() {
				skipWhitespace();
				if (index >= source.length()) {
					return null;
				}
				int start = index;
				while (index < source.length()) {
					char c = source.charAt(index);
					if (Character.isWhitespace(c) || c == '(' || c == ')' || c == '!' || c == '&' || c == '|' || c == '=' || c == '\'') {
						break;
					}
					if (c == '"') {
						break;
					}
					index++;
				}
				if (start == index) {
					return null;
				}
				return source.substring(start, index);
			}

			private void skipWhitespace() {
				while (index < source.length() && Character.isWhitespace(source.charAt(index))) {
					index++;
				}
			}

			private boolean match(String token) {
				if (token == null || token.isEmpty()) {
					return false;
				}
				if (source.regionMatches(index, token, 0, token.length())) {
					index += token.length();
					return true;
				}
				return false;
			}

			private char peek() {
				return index < source.length() ? source.charAt(index) : '\0';
			}

			private IllegalArgumentException error(String message) {
				int position = Math.max(0, Math.min(index, source.length()));
				return new IllegalArgumentException(message + " at position " + position);
			}
		}
	}
}
