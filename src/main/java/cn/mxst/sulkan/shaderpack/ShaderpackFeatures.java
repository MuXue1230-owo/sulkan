package cn.mxst.sulkan.shaderpack;

public record ShaderpackFeatures(
	boolean sulkanConfigOptions
) {
	public static ShaderpackFeatures defaults() {
		return new ShaderpackFeatures(false);
	}
}
