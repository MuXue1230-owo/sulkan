package cn.mxst.sulkan.shaderpack;

import java.util.Locale;

public record ShaderpackPipelineAlphaState(
	boolean enabled,
	String function,
	double reference
) {
	public static final ShaderpackPipelineAlphaState DISABLED = new ShaderpackPipelineAlphaState(false, "always", 0.0D);
	private static final String DEFAULT_FUNCTION = "greater";
	private static final double DEFAULT_REFERENCE = 0.0D;

	public static ShaderpackPipelineAlphaState enabled(String function, double reference) {
		String normalizedFunction = normalizeFunction(function);
		double normalizedReference = Double.isFinite(reference) ? reference : DEFAULT_REFERENCE;
		return new ShaderpackPipelineAlphaState(true, normalizedFunction, normalizedReference);
	}

	public ShaderpackPipelineAlphaState normalized() {
		if (!enabled) {
			return DISABLED;
		}
		return enabled(function, reference);
	}

	public static String normalizeFunction(String value) {
		if (value == null || value.isBlank()) {
			return DEFAULT_FUNCTION;
		}
		return switch (value.trim().toLowerCase(Locale.ROOT)) {
			case "never", "less", "equal", "lequal", "greater", "notequal", "gequal", "always" -> value.trim().toLowerCase(Locale.ROOT);
			default -> DEFAULT_FUNCTION;
		};
	}
}
