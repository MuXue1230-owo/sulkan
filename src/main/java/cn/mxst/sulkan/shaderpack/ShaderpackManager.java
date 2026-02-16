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
import java.util.Set;
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
	private static final String PIPELINES_DIRECTORY = "pipelines";
	private static final List<String> PIPELINE_STAGE_ORDER = List.of(
		"shadow",
		"gbuffer",
		"lighting",
		"translucent",
		"postprocess",
		"final"
	);
	private static final Pattern PIPELINE_PARAM_PATTERN = Pattern.compile("\\$\\{([A-Za-z0-9_.-]+)}");
	private static final String WORLD_KEY_NONE = "__none__";
	private static ShaderpackMetadata activeShaderpack;
	private static Path activePath;
	private static ShaderpackConfig activeConfig;
	private static volatile String cachedWorldKey = WORLD_KEY_NONE;
	private static volatile List<String> cachedWorldCandidates = List.of("any");
	private static final LongAdder worldCandidateRequests = new LongAdder();
	private static final LongAdder worldCandidateHits = new LongAdder();

	private ShaderpackManager() {
	}

	public static Path shaderpacksDir() {
		return MinecraftClient.getInstance().runDirectory.toPath().resolve("shaderpacks");
	}

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

	public static void refreshActiveConfig() {
		ShaderpackShaderApplier.invalidateCaches();
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
		String shaderType = ShaderpackPipelineProgram.extractShaderType(requestPath);
		if (shaderType == null || shaderType.isBlank()) {
			return null;
		}
		List<String> worldCandidates = resolveWorldCandidates();
		for (String worldId : worldCandidates) {
			Map<String, ShaderpackPipelineProgram> byShaderType = activeShaderpack.pipelinePrograms.get(worldId);
			if (byShaderType == null) {
				continue;
			}
			ShaderpackPipelineProgram program = byShaderType.get(shaderType);
			if (program != null) {
				return program;
			}
		}
		return null;
	}

	public static ShaderpackLoadResult applyShaderpack(Path path) {
		ShaderpackShaderApplier.invalidateCaches();
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
		activeShaderpack = null;
		activePath = null;
		activeConfig = null;
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
		List<ShaderpackOptionDecl> options = parseOptions(result.getTable("options"), features.sulkanConfigOptions(), translations, errors, warnings);
		ShaderpackExtensions extensions = parseExtensions(result.getTable("extensions"), errors);
		Map<String, Map<String, ShaderpackPipelineProgram>> pipelinePrograms = parsePipelinePrograms(sourcePath, stages, errors, warnings);

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
				pipelinePrograms,
				translationMap,
				sourcePath
			);
		}
		return new ShaderpackLoadResult(metadata, errors, warnings);
	}

	private static Map<String, Map<String, ShaderpackPipelineProgram>> parsePipelinePrograms(
		Path shaderpackPath,
		ShaderpackStages stages,
		List<String> errors,
		List<String> warnings
	) {
		Map<String, Map<String, ShaderpackPipelineProgram>> byWorld = new LinkedHashMap<>();
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

		Map<String, Map<String, ShaderpackPipelineProgram>> immutable = new LinkedHashMap<>();
		for (Map.Entry<String, Map<String, ShaderpackPipelineProgram>> entry : byWorld.entrySet()) {
			immutable.put(entry.getKey(), Map.copyOf(entry.getValue()));
		}
		return Map.copyOf(immutable);
	}

	private static void parsePipelineProgramsFromDirectory(
		Path shaderpackPath,
		Map<String, Map<String, ShaderpackPipelineProgram>> byWorld,
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

	private static void parsePipelineProgramsFromZip(
		Path shaderpackPath,
		Map<String, Map<String, ShaderpackPipelineProgram>> byWorld,
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
		Map<String, Map<String, ShaderpackPipelineProgram>> byWorld,
		List<String> errors,
		List<String> warnings
	) {
		Map<String, ShaderpackPipelineProgram> byShaderType = byWorld.computeIfAbsent(worldId, ignored -> new LinkedHashMap<>());
		boolean hasStageFile = false;
		for (String stage : PIPELINE_STAGE_ORDER) {
			T sourceRef = stageLookup.apply(stage);
			if (sourceRef == null) {
				continue;
			}
			hasStageFile = true;
			String sourceLabel = PIPELINES_DIRECTORY + "/" + worldId + "/" + stage + ".toml";
			try (Reader reader = readerFactory.open(sourceRef, sourceLabel)) {
				parsePipelineStage(worldId, stage, reader, sourceLabel, byShaderType, errors, warnings);
			} catch (IOException e) {
				errors.add("Failed to read " + sourceLabel + ": " + e.getMessage());
			}
		}
		if (!hasStageFile) {
			byWorld.remove(worldId);
		}
	}

	private static void parsePipelineStage(
		String worldId,
		String stage,
		Reader reader,
		String sourceLabel,
		Map<String, ShaderpackPipelineProgram> byShaderType,
		List<String> errors,
		List<String> warnings
	) throws IOException {
		TomlParseResult result = Toml.parse(reader);
		if (result.hasErrors()) {
			result.errors().forEach(error -> errors.add(sourceLabel + ": " + error));
			return;
		}

		Map<String, String> stageParams = readStringTable(result.getTable("params"), sourceLabel + ".params", errors);
		Map<String, String> stageContext = new LinkedHashMap<>(resolveParams(stageParams, Map.of(), sourceLabel + ".params", errors));
		int loadedPrograms = 0;

		TomlArray programsArray = result.getArray("programs");
		if (programsArray == null || programsArray.isEmpty()) {
			errors.add(sourceLabel + " must define at least one [[programs]] entry.");
			return;
		}

		for (int i = 0; i < programsArray.size(); i++) {
			Object value = programsArray.get(i);
			String context = sourceLabel + ".programs[" + i + "]";
			if (!(value instanceof TomlTable programTable)) {
				errors.add("Invalid program entry in " + context + " (expected table).");
				continue;
			}

			String shaderType = readString(programTable, "shader_type", context, errors);
			if (shaderType == null || shaderType.isBlank()) {
				errors.add("Missing " + context + ".shader_type");
				continue;
			}
			shaderType = shaderType.trim();

			String vertex = normalizeOptionalPath(readString(programTable, "vertex", context, errors));
			String fragment = normalizeOptionalPath(readString(programTable, "fragment", context, errors));
			String config = normalizeOptionalPath(readString(programTable, "config", context, errors));
			Map<String, String> files = readStringTable(programTable.getTable("files"), context + ".files", errors);
			Map<String, String> normalizedFiles = new LinkedHashMap<>();
			for (Map.Entry<String, String> entry : files.entrySet()) {
				normalizedFiles.put(
					ShaderpackPipelineProgram.normalizePath(entry.getKey()),
					ShaderpackPipelineProgram.normalizePath(entry.getValue())
				);
			}
			if (vertex == null && fragment == null && config == null && normalizedFiles.isEmpty()) {
				errors.add(context + " must define at least one of vertex/fragment/config/files.");
				continue;
			}

			if (byShaderType.containsKey(shaderType)) {
				errors.add("Duplicate pipeline mapping for world '" + worldId + "', shader_type '" + shaderType + "'.");
				continue;
			}

			Map<String, String> rawParams = readStringTable(programTable.getTable("params"), context + ".params", errors);
			Map<String, String> resolvedLocalParams = resolveParams(rawParams, stageContext, context + ".params", errors);
			Map<String, String> effectiveParams = new LinkedHashMap<>(stageContext);
			effectiveParams.putAll(resolvedLocalParams);
			stageContext.putAll(resolvedLocalParams);

			ShaderpackPipelineProgram program = new ShaderpackPipelineProgram(
				worldId,
				stage,
				shaderType,
				vertex,
				fragment,
				config,
				Map.copyOf(normalizedFiles),
				Map.copyOf(effectiveParams)
			);
			byShaderType.put(shaderType, program);
			loadedPrograms++;
		}
		if (loadedPrograms == 0) {
			errors.add("No valid shader programs loaded from " + sourceLabel + ".");
		}
	}

	private static void validateRequiredStagesPresent(
		Map<String, Map<String, ShaderpackPipelineProgram>> byWorld,
		ShaderpackStages stages,
		List<String> errors
	) {
		Map<String, ShaderpackPipelineProgram> fallbackPrograms = byWorld.get("any");
		if (fallbackPrograms == null) {
			errors.add("Missing fallback pipeline mappings under pipelines/any/.");
			return;
		}
		Set<String> availableStages = fallbackPrograms.values().stream().map(ShaderpackPipelineProgram::stage).collect(Collectors.toSet());
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
		for (String key : table.keySet()) {
			Object raw = table.get(key);
			if (!(raw instanceof String str)) {
				errors.add("Invalid string for " + context + "." + key);
				continue;
			}
			values.put(key, str);
		}
		return Map.copyOf(values);
	}

	private static String normalizeOptionalPath(String path) {
		if (path == null || path.isBlank()) {
			return null;
		}
		return ShaderpackPipelineProgram.normalizePath(path);
	}

	private static List<String> resolveWorldCandidates() {
		String worldKey = WORLD_KEY_NONE;
		String worldPath = "";
		try {
			MinecraftClient client = MinecraftClient.getInstance();
			if (client != null && client.world != null && client.world.getRegistryKey() != null) {
				var identifier = client.world.getRegistryKey().getValue();
				if (identifier != null) {
					worldKey = identifier.toString().toLowerCase(Locale.ROOT);
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

	public record CacheStats(long requests, long hits) {
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

		if (shadow != null && !shadow) {
			errors.add("Stage 'shadow' must be enabled.");
		}
		if (gbuffer != null && !gbuffer) {
			errors.add("Stage 'gbuffer' must be enabled.");
		}
		if (lighting != null && !lighting) {
			errors.add("Stage 'lighting' must be enabled.");
		}
		if (translucent != null && !translucent) {
			errors.add("Stage 'translucent' must be enabled.");
		}
		if (postprocess != null && !postprocess) {
			errors.add("Stage 'postprocess' must be enabled.");
		}
		if (finalStage != null && !finalStage) {
			errors.add("Stage 'final' must be enabled.");
		}

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
		return new ShaderpackFeatures(Boolean.TRUE.equals(configOptions));
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
}
