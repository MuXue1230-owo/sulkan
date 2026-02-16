package cn.mxst.sulkan.shaderpack;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public record ShaderpackGlobalSettings(
	Map<String, Object> values
) {
	public static final ShaderpackGlobalSettings EMPTY = new ShaderpackGlobalSettings(Map.of());

	public ShaderpackGlobalSettings {
		values = values == null ? Map.of() : Map.copyOf(values);
	}

	public boolean isEmpty() {
		return values == null || values.isEmpty();
	}

	public Object get(String key) {
		if (key == null || key.isBlank() || values == null || values.isEmpty()) {
			return null;
		}
		String normalized = normalizeKey(key);
		Object direct = values.get(normalized);
		if (direct != null) {
			return direct;
		}
		return values.get(key);
	}

	public boolean getBoolean(String key, boolean fallback) {
		Object value = get(key);
		if (value instanceof Boolean bool) {
			return bool;
		}
		if (value instanceof Number number) {
			return number.doubleValue() != 0.0D;
		}
		if (value instanceof String text) {
			String normalized = text.trim().toLowerCase(Locale.ROOT);
			if ("true".equals(normalized) || "on".equals(normalized) || "yes".equals(normalized) || "1".equals(normalized)) {
				return true;
			}
			if ("false".equals(normalized) || "off".equals(normalized) || "no".equals(normalized) || "0".equals(normalized)) {
				return false;
			}
		}
		return fallback;
	}

	public String getString(String key, String fallback) {
		Object value = get(key);
		if (value == null) {
			return fallback;
		}
		String text = value.toString();
		return text.isBlank() ? fallback : text;
	}

	public static ShaderpackGlobalSettings from(Map<String, Object> input) {
		if (input == null || input.isEmpty()) {
			return EMPTY;
		}
		Map<String, Object> normalized = new LinkedHashMap<>();
		for (Map.Entry<String, Object> entry : input.entrySet()) {
			if (entry.getKey() == null || entry.getKey().isBlank()) {
				continue;
			}
			normalized.put(normalizeKey(entry.getKey()), entry.getValue());
		}
		if (normalized.isEmpty()) {
			return EMPTY;
		}
		return new ShaderpackGlobalSettings(Map.copyOf(normalized));
	}

	public static String normalizeKey(String key) {
		return key == null ? "" : key.trim().toLowerCase(Locale.ROOT).replace('-', '_');
	}
}
