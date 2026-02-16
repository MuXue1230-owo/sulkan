package cn.mxst.sulkan.config;

import cn.mxst.sulkan.Sulkan;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

public final class SulkanConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String CONFIG_FILE_NAME = "sulkan_settings.json";
	private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE_NAME);

	private static SulkanConfig instance = new SulkanConfig();
	private static boolean loaded = false;

	public boolean enableShaderpack = false;
	public String selectedShaderpack = "";
	public Boolean enableHotReload = true;
	public Boolean debugExportShaders = false;

	private SulkanConfig() {
	}

	public static SulkanConfig get() {
		if (!loaded) {
			load();
		}
		return instance;
	}

	@SuppressWarnings("null")
	public static void load() {
		SulkanConfig config = null;
		if (Files.exists(CONFIG_PATH)) {
			try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
				config = GSON.fromJson(reader, SulkanConfig.class);
			} catch (IOException e) {
				Sulkan.LOGGER.error("Failed to read Sulkan config, using defaults.", e);
			}
		}
		if (config == null) {
			config = new SulkanConfig();
		}
		config.normalize();
		instance = config;
		loaded = true;
		if (!Files.exists(CONFIG_PATH)) {
			save();
		}
	}

	public static void save() {
		SulkanConfig config = get();
		config.normalize();
		try {
			Files.createDirectories(CONFIG_PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
				GSON.toJson(config, writer);
			}
		} catch (IOException e) {
			Sulkan.LOGGER.error("Failed to write Sulkan config.", e);
		}
	}

	private void normalize() {
		if (selectedShaderpack == null) {
			selectedShaderpack = "";
		}
		if (enableHotReload == null) {
			enableHotReload = true;
		}
		if (debugExportShaders == null) {
			debugExportShaders = false;
		}
	}
}
