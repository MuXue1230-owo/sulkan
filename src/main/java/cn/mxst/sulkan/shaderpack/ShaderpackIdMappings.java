package cn.mxst.sulkan.shaderpack;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public record ShaderpackIdMappings(
	Map<String, Integer> blocks,
	Map<String, Integer> items,
	Map<String, Integer> entities,
	Map<String, String> layers
) {
	public static final ShaderpackIdMappings EMPTY = new ShaderpackIdMappings(Map.of(), Map.of(), Map.of(), Map.of());

	public ShaderpackIdMappings {
		blocks = blocks == null ? Map.of() : Map.copyOf(blocks);
		items = items == null ? Map.of() : Map.copyOf(items);
		entities = entities == null ? Map.of() : Map.copyOf(entities);
		layers = layers == null ? Map.of() : Map.copyOf(layers);
	}

	public boolean isEmpty() {
		return blocks.isEmpty() && items.isEmpty() && entities.isEmpty() && layers.isEmpty();
	}

	public static ShaderpackIdMappings from(
		Map<String, Integer> blocks,
		Map<String, Integer> items,
		Map<String, Integer> entities,
		Map<String, String> layers
	) {
		return new ShaderpackIdMappings(
			normalizeIntMap(blocks),
			normalizeIntMap(items),
			normalizeIntMap(entities),
			normalizeStringMap(layers)
		);
	}

	private static Map<String, Integer> normalizeIntMap(Map<String, Integer> input) {
		if (input == null || input.isEmpty()) {
			return Map.of();
		}
		Map<String, Integer> normalized = new LinkedHashMap<>();
		for (Map.Entry<String, Integer> entry : input.entrySet()) {
			String key = normalizeKey(entry.getKey());
			Integer value = entry.getValue();
			if (key.isBlank() || value == null) {
				continue;
			}
			normalized.put(key, value);
		}
		return normalized.isEmpty() ? Map.of() : Map.copyOf(normalized);
	}

	private static Map<String, String> normalizeStringMap(Map<String, String> input) {
		if (input == null || input.isEmpty()) {
			return Map.of();
		}
		Map<String, String> normalized = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : input.entrySet()) {
			String key = normalizeKey(entry.getKey());
			String value = entry.getValue();
			if (key.isBlank() || value == null || value.isBlank()) {
				continue;
			}
			normalized.put(key, value.trim().toLowerCase(Locale.ROOT));
		}
		return normalized.isEmpty() ? Map.of() : Map.copyOf(normalized);
	}

	private static String normalizeKey(String value) {
		return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
	}
}
