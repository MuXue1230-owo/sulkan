package cn.mxst.sulkan.shaderpack;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public record ShaderpackUiLayout(
	Map<String, List<String>> screens,
	Map<String, Integer> screenColumns,
	Set<String> sliders,
	Map<String, Map<String, Object>> profiles
) {
	public static final ShaderpackUiLayout EMPTY = new ShaderpackUiLayout(Map.of(), Map.of(), Set.of(), Map.of());

	public ShaderpackUiLayout {
		screens = screens == null ? Map.of() : Map.copyOf(screens);
		screenColumns = screenColumns == null ? Map.of() : Map.copyOf(screenColumns);
		sliders = sliders == null ? Set.of() : Set.copyOf(sliders);
		profiles = profiles == null ? Map.of() : Map.copyOf(profiles);
	}

	public boolean hasAnyLayout() {
		return !(screens.isEmpty() && sliders.isEmpty() && profiles.isEmpty() && screenColumns.isEmpty());
	}

	public List<String> resolveScreenOrder(String screenKey) {
		String key = normalizeScreenKey(screenKey);
		List<String> explicit = screens.get(key);
		if (explicit != null) {
			return explicit;
		}
		return screens.getOrDefault("main", List.of());
	}

	public int resolveScreenColumns(String screenKey, int fallback) {
		String key = normalizeScreenKey(screenKey);
		Integer explicit = screenColumns.get(key);
		if (explicit != null && explicit > 0) {
			return explicit;
		}
		Integer main = screenColumns.get("main");
		if (main != null && main > 0) {
			return main;
		}
		return fallback;
	}

	public boolean isSlider(String optionPath) {
		if (optionPath == null || optionPath.isBlank() || sliders.isEmpty()) {
			return false;
		}
		String normalized = normalizePath(optionPath);
		return sliders.contains(normalized) || sliders.contains(optionPath);
	}

	public static ShaderpackUiLayout from(
		Map<String, List<String>> screenMap,
		Map<String, Integer> columns,
		Set<String> sliderPaths,
		Map<String, Map<String, Object>> profileMap
	) {
		Map<String, List<String>> normalizedScreens = new LinkedHashMap<>();
		if (screenMap != null) {
			for (Map.Entry<String, List<String>> entry : screenMap.entrySet()) {
				String key = normalizeScreenKey(entry.getKey());
				List<String> value = entry.getValue();
				if (value == null || value.isEmpty()) {
					continue;
				}
				LinkedHashSet<String> ordered = new LinkedHashSet<>();
				for (String path : value) {
					if (path == null || path.isBlank()) {
						continue;
					}
					ordered.add(normalizePath(path));
				}
				if (!ordered.isEmpty()) {
					normalizedScreens.put(key, List.copyOf(ordered));
				}
			}
		}

		Map<String, Integer> normalizedColumns = new LinkedHashMap<>();
		if (columns != null) {
			for (Map.Entry<String, Integer> entry : columns.entrySet()) {
				String key = normalizeScreenKey(entry.getKey());
				Integer value = entry.getValue();
				if (value == null || value <= 0) {
					continue;
				}
				normalizedColumns.put(key, value);
			}
		}

		Set<String> normalizedSliders = new LinkedHashSet<>();
		if (sliderPaths != null) {
			for (String slider : sliderPaths) {
				if (slider == null || slider.isBlank()) {
					continue;
				}
				normalizedSliders.add(normalizePath(slider));
			}
		}

		Map<String, Map<String, Object>> normalizedProfiles = new LinkedHashMap<>();
		if (profileMap != null) {
			for (Map.Entry<String, Map<String, Object>> entry : profileMap.entrySet()) {
				String profile = entry.getKey();
				if (profile == null || profile.isBlank()) {
					continue;
				}
				Map<String, Object> values = entry.getValue();
				if (values == null || values.isEmpty()) {
					continue;
				}
				Map<String, Object> normalizedValues = new LinkedHashMap<>();
				for (Map.Entry<String, Object> valueEntry : values.entrySet()) {
					if (valueEntry.getKey() == null || valueEntry.getKey().isBlank()) {
						continue;
					}
					normalizedValues.put(normalizePath(valueEntry.getKey()), valueEntry.getValue());
				}
				if (!normalizedValues.isEmpty()) {
					normalizedProfiles.put(profile.trim(), Map.copyOf(normalizedValues));
				}
			}
		}

		if (normalizedScreens.isEmpty() && normalizedColumns.isEmpty() && normalizedSliders.isEmpty() && normalizedProfiles.isEmpty()) {
			return EMPTY;
		}
		return new ShaderpackUiLayout(
			Map.copyOf(normalizedScreens),
			Map.copyOf(normalizedColumns),
			Set.copyOf(normalizedSliders),
			Map.copyOf(normalizedProfiles)
		);
	}

	public static String normalizeScreenKey(String key) {
		if (key == null || key.isBlank()) {
			return "main";
		}
		return key.trim().toLowerCase(Locale.ROOT);
	}

	public static String normalizePath(String path) {
		return path == null ? "" : path.trim();
	}
}
