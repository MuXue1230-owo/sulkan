package cn.mxst.sulkan.client.input;

import cn.mxst.sulkan.Sulkan;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;

public final class SulkanDebugHotkeys {
	private static final KeyBinding.Category CATEGORY = KeyBinding.Category.create(Identifier.of(Sulkan.MOD_ID, "debug"));
	private static KeyBinding openShaderpackListKey;

	private SulkanDebugHotkeys() {
	}

	public static void register() {
		if (openShaderpackListKey != null) {
			return;
		}
		openShaderpackListKey = KeyBindingHelper.registerKeyBinding(
			new KeyBinding(
				"key.sulkan.open_shaderpack_list",
				InputUtil.Type.KEYSYM,
				InputUtil.GLFW_KEY_O,
				CATEGORY
			)
		);
	}

	public static KeyBinding getOpenShaderpackListKey() {
		return openShaderpackListKey;
	}

	public static boolean matchesOpenShaderpackListKey(KeyInput keyInput) {
		return openShaderpackListKey != null && openShaderpackListKey.matchesKey(keyInput);
	}
}
