package cn.mxst.sulkan.shaderpack;

import java.util.List;

public record ShaderpackStages(
	boolean shadow,
	boolean gbuffer,
	boolean lighting,
	boolean translucent,
	boolean postprocess,
	boolean finalStage
) {
	public static final List<String> REQUIRED_KEYS = List.of(
		"shadow",
		"gbuffer",
		"lighting",
		"translucent",
		"postprocess",
		"final"
	);

	public boolean allEnabled() {
		return shadow && gbuffer && lighting && translucent && postprocess && finalStage;
	}
}
