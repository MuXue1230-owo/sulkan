package cn.mxst.sulkan.shaderpack;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public record ShaderpackTextureBindings(
	Map<String, Map<String, String>> byStage
) {
	public static final ShaderpackTextureBindings EMPTY = new ShaderpackTextureBindings(Map.of());

	public ShaderpackTextureBindings {
		byStage = byStage == null ? Map.of() : Map.copyOf(byStage);
	}

	public boolean isEmpty() {
		return byStage == null || byStage.isEmpty();
	}

	public String resolve(String stage, String sampler) {
		if (sampler == null || sampler.isBlank() || byStage == null || byStage.isEmpty()) {
			return null;
		}
		String normalizedStage = normalizeStage(stage);
		String normalizedSampler = normalizeSampler(sampler);
		Map<String, String> stageBindings = byStage.get(normalizedStage);
		if (stageBindings != null) {
			String bound = stageBindings.get(normalizedSampler);
			if (bound != null) {
				return bound;
			}
		}
		Map<String, String> anyBindings = byStage.get("any");
		return anyBindings == null ? null : anyBindings.get(normalizedSampler);
	}

	public static ShaderpackTextureBindings from(Map<String, Map<String, String>> bindings) {
		if (bindings == null || bindings.isEmpty()) {
			return EMPTY;
		}
		Map<String, Map<String, String>> byStage = new LinkedHashMap<>();
		for (Map.Entry<String, Map<String, String>> stageEntry : bindings.entrySet()) {
			String stage = normalizeStage(stageEntry.getKey());
			if (stage.isBlank()) {
				continue;
			}
			Map<String, String> samplers = stageEntry.getValue();
			if (samplers == null || samplers.isEmpty()) {
				continue;
			}
			Map<String, String> normalizedSamplers = new LinkedHashMap<>();
			for (Map.Entry<String, String> samplerEntry : samplers.entrySet()) {
				String sampler = normalizeSampler(samplerEntry.getKey());
				String source = samplerEntry.getValue();
				if (sampler.isBlank() || source == null || source.isBlank()) {
					continue;
				}
				normalizedSamplers.put(sampler, source.trim());
			}
			if (!normalizedSamplers.isEmpty()) {
				byStage.put(stage, Map.copyOf(normalizedSamplers));
			}
		}
		if (byStage.isEmpty()) {
			return EMPTY;
		}
		return new ShaderpackTextureBindings(Map.copyOf(byStage));
	}

	public static String normalizeStage(String stage) {
		if (stage == null || stage.isBlank()) {
			return "any";
		}
		return stage.trim().toLowerCase(Locale.ROOT);
	}

	public static String normalizeSampler(String sampler) {
		return sampler == null ? "" : sampler.trim();
	}
}
