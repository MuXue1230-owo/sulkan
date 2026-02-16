package cn.mxst.sulkan.shaderpack;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public record ShaderpackPipelineProgram(
	String worldId,
	String stage,
	String segmentName,
	int index,
	Map<String, String> inputs,
	Map<String, String> outputs,
	String vertex,
	String fragment,
	String geometry,
	String compute,
	String config,
	Map<String, String> params,
	List<String> renderTargets,
	String pingPong,
	Map<String, Boolean> flips,
	List<Integer> workGroups,
	List<Integer> workGroupsRender,
	List<String> imagesRead,
	List<String> imagesWrite,
	String enabledExpression,
	ShaderpackPipelineAlphaState alphaState,
	ShaderpackPipelineBlendState blendState,
	Map<String, List<Integer>> bufferSizes,
	Map<String, List<Double>> bufferScales
) {
	private static final Map<String, List<String>> STAGE_MATCH_TARGETS = Map.ofEntries(
		Map.entry("shadow", List.of("shaders/basic/terrain_earlyz/*")),
		Map.entry("gbuffer", List.of("shaders/basic/terrain/*")),
		Map.entry("lighting", List.of("shaders/basic/clouds/*")),
		Map.entry("translucent", List.of("shaders/core/rendertype_item_entity_translucent_cull/*")),
		Map.entry("postprocess", List.of(
			"shaders/basic/blit/*",
			"shaders/post/blit/*"
		)),
		Map.entry("final", List.of(
			"shaders/core/screenquad/*",
			"shaders/core/animate_sprite/*",
			"shaders/core/animate_sprite_blit/*",
			"shaders/core/animate_sprite_blit.fsh"
		))
	);

	public boolean matchesRequest(String requestPath) {
		for (String candidate : buildPathCandidates(requestPath)) {
			if (matchesStageCandidate(stage, candidate)) {
				return true;
			}
		}
		return false;
	}

	public String resolveSourcePath(String requestPath) {
		String normalizedRequest = normalizePath(requestPath);
		String kind = detectKind(normalizedRequest);
		if (kind == null) {
			return null;
		}
		return switch (kind) {
			case "vertex" -> vertex;
			case "fragment" -> fragment;
			case "geometry" -> geometry;
			case "compute" -> compute;
			case "config" -> config;
			default -> null;
		};
	}

	public static String normalizePath(String path) {
		String normalized = path == null ? "" : path.replace('\\', '/');
		while (normalized.startsWith("/")) {
			normalized = normalized.substring(1);
		}
		return normalized;
	}

	public static List<String> buildPathCandidates(String path) {
		String normalized = normalizePath(path);
		if (normalized.isBlank()) {
			return List.of();
		}
		LinkedHashSet<String> candidates = new LinkedHashSet<>();
		candidates.add(normalized);
		addStrippedJarPrefixCandidates(candidates, normalized);
		addAssetsPrefixCandidates(candidates, normalized);
		addNamespaceCandidates(candidates, normalized);
		return List.copyOf(candidates);
	}

	private static void addStrippedJarPrefixCandidates(LinkedHashSet<String> candidates, String normalized) {
		int jarIndex = normalized.indexOf("!/");
		if (jarIndex >= 0 && jarIndex + 2 < normalized.length()) {
			String stripped = normalizePath(normalized.substring(jarIndex + 2));
			if (!stripped.isBlank()) {
				candidates.add(stripped);
				addAssetsPrefixCandidates(candidates, stripped);
			}
		}
	}

	private static void addAssetsPrefixCandidates(LinkedHashSet<String> candidates, String normalized) {
		int assetsIndex = normalized.indexOf("assets/vulkanmod/");
		if (assetsIndex >= 0) {
			String stripped = normalizePath(normalized.substring(assetsIndex + "assets/vulkanmod/".length()));
			if (!stripped.isBlank()) {
				candidates.add(stripped);
			}
		}
	}

	private static void addNamespaceCandidates(LinkedHashSet<String> candidates, String normalized) {
		int namespaceSeparator = normalized.indexOf(':');
		if (namespaceSeparator <= 0 || namespaceSeparator >= normalized.length() - 1) {
			return;
		}
		String stripped = normalizePath(normalized.substring(namespaceSeparator + 1));
		if (!stripped.isBlank()) {
			candidates.add(stripped);
			addAssetsPrefixCandidates(candidates, stripped);
		}
	}

	private static boolean matchesStageCandidate(String stage, String candidate) {
		if (stage == null || stage.isBlank() || candidate == null || candidate.isBlank()) {
			return false;
		}
		List<String> targets = STAGE_MATCH_TARGETS.get(stage.toLowerCase(Locale.ROOT));
		if (targets == null || targets.isEmpty()) {
			return false;
		}
		String normalizedCandidate = normalizePath(candidate).toLowerCase(Locale.ROOT);
		for (String target : targets) {
			if (target.endsWith("*")) {
				String prefix = target.substring(0, target.length() - 1);
				if (normalizedCandidate.startsWith(prefix)) {
					return true;
				}
				continue;
			}
			if (normalizedCandidate.equals(target)) {
				return true;
			}
		}
		return false;
	}

	private static String detectKind(String requestPath) {
		String lower = requestPath.toLowerCase(Locale.ROOT);
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
		if (lower.endsWith(".json")) {
			return "config";
		}
		return null;
	}
}
