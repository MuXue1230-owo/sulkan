package cn.mxst.sulkan.shaderpack;

public final class ShaderpackConfigSession {
	private final ShaderpackConfig config;
	private boolean dirty;

	public ShaderpackConfigSession(ShaderpackConfig config) {
		this.config = config;
	}

	public ShaderpackConfig config() {
		return config;
	}

	public boolean isDirty() {
		return dirty;
	}

	public void markDirty() {
		this.dirty = true;
	}

	public void clearDirty() {
		this.dirty = false;
	}
}
