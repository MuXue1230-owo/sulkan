package cn.mxst.sulkan.shaderpack;

import java.util.Locale;
import java.util.Map;

public record ShaderpackPipelineProgram(
	String worldId,
	String stage,
	String shaderType,
	String vertex,
	String fragment,
	String config,
	Map<String, String> files,
	Map<String, String> params
) {
	public String resolveSourcePath(String requestPath) {
		String normalizedRequest = normalizePath(requestPath);
		String fileMapped = files.get(normalizedRequest);
		if (fileMapped != null && !fileMapped.isBlank()) {
			return normalizePath(fileMapped);
		}
		String kind = detectKind(normalizedRequest);
		if (kind == null) {
			return null;
		}
		return switch (kind) {
			case "vertex" -> vertex;
			case "fragment" -> fragment;
			case "config" -> config;
			default -> null;
		};
	}

	public static String extractShaderType(String requestPath) {
		String normalized = normalizePath(requestPath);
		int slash = normalized.lastIndexOf('/');
		String fileName = slash >= 0 ? normalized.substring(slash + 1) : normalized;
		int dot = fileName.lastIndexOf('.');
		if (dot <= 0) {
			return null;
		}
		return fileName.substring(0, dot);
	}

	public static String normalizePath(String path) {
		String normalized = path == null ? "" : path.replace('\\', '/');
		while (normalized.startsWith("/")) {
			normalized = normalized.substring(1);
		}
		return normalized;
	}

	private static String detectKind(String requestPath) {
		String lower = requestPath.toLowerCase(Locale.ROOT);
		if (lower.endsWith(".vsh") || lower.endsWith(".vert")) {
			return "vertex";
		}
		if (lower.endsWith(".fsh") || lower.endsWith(".frag")) {
			return "fragment";
		}
		if (lower.endsWith(".json")) {
			return "config";
		}
		return null;
	}
}
