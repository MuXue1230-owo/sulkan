package cn.mxst.sulkan.shaderpack;

public record ShaderpackFeatures(
	boolean sulkanConfigOptions,
	boolean autoExtractOptions
) {
	public static ShaderpackFeatures defaults() {
		return new ShaderpackFeatures(false, false);
	}
}
