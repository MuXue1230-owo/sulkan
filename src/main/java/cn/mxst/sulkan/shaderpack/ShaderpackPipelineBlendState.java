package cn.mxst.sulkan.shaderpack;

import java.util.Locale;

public record ShaderpackPipelineBlendState(
	boolean enabled,
	String srcColor,
	String dstColor,
	String srcAlpha,
	String dstAlpha
) {
	public static final ShaderpackPipelineBlendState DISABLED = new ShaderpackPipelineBlendState(
		false,
		"one",
		"zero",
		"one",
		"zero"
	);

	public static ShaderpackPipelineBlendState enabled(
		String srcColor,
		String dstColor,
		String srcAlpha,
		String dstAlpha
	) {
		return new ShaderpackPipelineBlendState(
			true,
			normalizeFactor(srcColor),
			normalizeFactor(dstColor),
			normalizeFactor(srcAlpha),
			normalizeFactor(dstAlpha)
		);
	}

	public ShaderpackPipelineBlendState normalized() {
		if (!enabled) {
			return DISABLED;
		}
		return enabled(srcColor, dstColor, srcAlpha, dstAlpha);
	}

	public static String normalizeFactor(String value) {
		if (value == null || value.isBlank()) {
			return "one";
		}
		String normalized = value.trim().toLowerCase(Locale.ROOT);
		return switch (normalized) {
			case "zero",
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
				"one_minus_src1_alpha" -> normalized;
			default -> "one";
		};
	}
}
