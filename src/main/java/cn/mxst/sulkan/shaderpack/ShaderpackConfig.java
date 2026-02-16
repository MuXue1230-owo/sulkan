package cn.mxst.sulkan.shaderpack;

import cn.mxst.sulkan.Sulkan;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ShaderpackConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String OPTIONS_KEY = "options";
	private final Map<String, Object> values;

	private ShaderpackConfig(Map<String, Object> values) {
		this.values = values;
	}

	public Object getValue(String path) {
		return values.get(path);
	}

	public void setValue(String path, Object value) {
		values.put(path, value);
	}

	public Map<String, Object> values() {
		return Map.copyOf(values);
	}

	public static Path getConfigPath(Path shaderpackPath) {
		String fileName = shaderpackPath.getFileName().toString() + ".sscfg";
		return ShaderpackManager.shaderpacksDir().resolve(fileName);
	}

	public static ShaderpackConfig loadOrCreate(Path shaderpackPath, List<ShaderpackOptionDecl> options) {
		Map<String, ShaderpackOptionDecl> optionMap = new LinkedHashMap<>();
		flattenOptions(options, optionMap);

		Path configPath = getConfigPath(shaderpackPath);
		Map<String, Object> values = new HashMap<>();
		boolean changed = false;

		JsonObject root = null;
		if (Files.exists(configPath)) {
			try (Reader reader = Files.newBufferedReader(configPath)) {
				JsonElement element = JsonParser.parseReader(reader);
				if (element.isJsonObject()) {
					root = element.getAsJsonObject();
				}
			} catch (IOException e) {
				Sulkan.LOGGER.warn("Failed to read shaderpack config {}, using defaults.", configPath.getFileName(), e);
			} catch (Exception e) {
				Sulkan.LOGGER.warn("Invalid shaderpack config {}, using defaults.", configPath.getFileName(), e);
			}
		}

		JsonObject optionsObject = root != null ? root.getAsJsonObject(OPTIONS_KEY) : null;
		for (ShaderpackOptionDecl option : optionMap.values()) {
			Object value = null;
			boolean valid = false;
			if (optionsObject != null && optionsObject.has(option.path())) {
				JsonElement element = optionsObject.get(option.path());
				ParsedValue parsed = parseOptionValue(element, option);
				value = parsed.value();
				valid = parsed.valid();
			}
			if (!valid) {
				value = option.defaultValue();
				changed = true;
			}
			values.put(option.path(), value);
		}

		if (root == null || optionsObject == null) {
			changed = true;
		}

		ShaderpackConfig config = new ShaderpackConfig(values);
		if (changed) {
			config.save(shaderpackPath);
		}
		return config;
	}

	public void save(Path shaderpackPath) {
		Path configPath = getConfigPath(shaderpackPath);
		JsonObject root = new JsonObject();
		JsonObject optionsObject = new JsonObject();
		for (Map.Entry<String, Object> entry : values.entrySet()) {
			optionsObject.add(entry.getKey(), GSON.toJsonTree(entry.getValue()));
		}
		root.add(OPTIONS_KEY, optionsObject);
		try {
			Files.createDirectories(configPath.getParent());
			try (Writer writer = Files.newBufferedWriter(configPath)) {
				GSON.toJson(root, writer);
			}
		} catch (IOException e) {
			Sulkan.LOGGER.warn("Failed to write shaderpack config {}", configPath.getFileName(), e);
		}
	}

	private static ParsedValue parseOptionValue(JsonElement element, ShaderpackOptionDecl option) {
		if (element == null || element.isJsonNull()) {
			return ParsedValue.invalid();
		}
		String type = option.type();
		if (type == null) {
			return ParsedValue.invalid();
		}
		switch (type.toLowerCase(Locale.ROOT)) {
			case "bool" -> {
				if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
					return ParsedValue.invalid();
				}
				return ParsedValue.valid(element.getAsBoolean());
			}
			case "int" -> {
				if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
					return ParsedValue.invalid();
				}
				double value = element.getAsDouble();
				if (value != Math.rint(value)) {
					return ParsedValue.invalid();
				}
				if (!withinBounds(value, option.min(), option.max())) {
					return ParsedValue.invalid();
				}
				return ParsedValue.valid((long) value);
			}
			case "float" -> {
				if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
					return ParsedValue.invalid();
				}
				double value = element.getAsDouble();
				if (!withinBounds(value, option.min(), option.max())) {
					return ParsedValue.invalid();
				}
				return ParsedValue.valid(value);
			}
			case "string" -> {
				if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
					return ParsedValue.invalid();
				}
				return ParsedValue.valid(element.getAsString());
			}
			case "enum" -> {
				if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
					return ParsedValue.invalid();
				}
				String value = element.getAsString();
				if (!option.values().isEmpty() && !option.values().contains(value)) {
					return ParsedValue.invalid();
				}
				return ParsedValue.valid(value);
			}
			default -> {
				return ParsedValue.invalid();
			}
		}
	}

	private static boolean withinBounds(double value, Number min, Number max) {
		if (min != null && value < min.doubleValue()) {
			return false;
		}
		if (max != null && value > max.doubleValue()) {
			return false;
		}
		return true;
	}

	private static void flattenOptions(List<ShaderpackOptionDecl> options, Map<String, ShaderpackOptionDecl> output) {
		for (ShaderpackOptionDecl option : options) {
			if (option == null) {
				continue;
			}
			if ("page".equalsIgnoreCase(option.type())) {
				flattenOptions(option.children(), output);
			} else {
				output.put(option.path(), option);
			}
		}
	}

	private record ParsedValue(Object value, boolean valid) {
		static ParsedValue invalid() {
			return new ParsedValue(null, false);
		}

		static ParsedValue valid(Object value) {
			return new ParsedValue(value, true);
		}
	}
}
