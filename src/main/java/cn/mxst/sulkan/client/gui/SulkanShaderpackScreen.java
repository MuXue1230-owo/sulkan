package cn.mxst.sulkan.client.gui;

import cn.mxst.sulkan.Sulkan;
import cn.mxst.sulkan.compat.vulkanmod.SulkanVulkanPipelineReloader;
import cn.mxst.sulkan.config.SulkanConfig;
import cn.mxst.sulkan.shaderpack.ShaderpackCandidate;
import cn.mxst.sulkan.shaderpack.ShaderpackConfig;
import cn.mxst.sulkan.shaderpack.ShaderpackConfigSession;
import cn.mxst.sulkan.shaderpack.ShaderpackLoadResult;
import cn.mxst.sulkan.shaderpack.ShaderpackManager;
import cn.mxst.sulkan.shaderpack.ShaderpackMetadata;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.vulkanmod.config.gui.render.GuiRenderer;
import net.vulkanmod.config.gui.widget.VButtonWidget;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.util.ColorUtil;

public final class SulkanShaderpackScreen extends Screen {
	private final Screen parent;
	private SulkanShaderpackList packList;
	private VButtonWidget openFolderButton;
	private VButtonWidget settingsButton;
	private VButtonWidget applyButton;
	private VButtonWidget backButton;
	private String pendingSelection;
	private List<ShaderpackCandidate> candidates = List.of();
	private final Map<String, ShaderpackCandidate> candidateById = new HashMap<>();
	private Text statusMessage;
	private long statusUntil;
	private int statusColor = -1;
	private int buttonRowY;
	private boolean initialized;

	public SulkanShaderpackScreen(Screen parent) {
		super(Text.translatable("sulkan.shaderpack_screen.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		SulkanConfig config = SulkanConfig.get();
		this.pendingSelection = config.selectedShaderpack;
		this.initialized = true;

		int contentWidth = Math.min(this.width - 40, 420);
		int left = (this.width - contentWidth) / 2;

		int openButtonHeight = 20;
		int openButtonY = 32;
		this.openFolderButton = new VButtonWidget(
			left,
			openButtonY,
			contentWidth,
			openButtonHeight,
			Text.translatable("sulkan.shaderpack_screen.open_folder"),
			button -> this.openShaderpacksFolder()
		);
		this.addSelectableChild(this.openFolderButton);

		int buttonHeight = 20;
		int bottomMargin = 8;
		int buttonY = this.height - buttonHeight - bottomMargin;
		int listTop = openButtonY + openButtonHeight + 8;
		int listHeight = Math.max(40, buttonY - listTop - 8);

		this.packList = new SulkanShaderpackList(left, listTop, contentWidth, listHeight, 22);
		this.refreshCandidates();
		this.packList.setEntries(this.buildEntryWidgets());
		this.addSelectableChild(this.packList);

		this.addBottomButtons(buttonY, buttonHeight);
		this.packList.setSelectionChangedListener(this::onSelectionChanged);
		this.packList.setSelectedId(this.pendingSelection);
		this.updateButtons();
	}

	private void addBottomButtons(int buttonY, int buttonHeight) {
		this.buttonRowY = buttonY;
		int padding = 10;
		int margin = 6;
		Text settingsText = Text.translatable("sulkan.shaderpack_screen.settings");
		Text applyText = Text.translatable("sulkan.shaderpack_screen.apply");
		Text backText = Text.translatable("sulkan.shaderpack_screen.back");
		int settingsWidth = this.textRenderer.getWidth(settingsText) + padding * 2;
		int applyWidth = this.textRenderer.getWidth(applyText) + padding * 2;
		int backWidth = this.textRenderer.getWidth(backText) + padding * 2;
		int totalWidth = settingsWidth + applyWidth + backWidth + margin * 2;
		int startX = (this.width - totalWidth) / 2;

		this.settingsButton = new VButtonWidget(
			startX,
			buttonY,
			settingsWidth,
			buttonHeight,
			settingsText,
			button -> this.openShaderpackSettings()
		);
		startX += settingsWidth + margin;
		this.applyButton = new VButtonWidget(
			startX,
			buttonY,
			applyWidth,
			buttonHeight,
			applyText,
			button -> this.applySelection()
		);
		startX += applyWidth + margin;
		this.backButton = new VButtonWidget(
			startX,
			buttonY,
			backWidth,
			buttonHeight,
			backText,
			button -> this.client.setScreen(this.parent)
		);

		this.addSelectableChild(this.settingsButton);
		this.addSelectableChild(this.applyButton);
		this.addSelectableChild(this.backButton);
	}

	private List<SulkanShaderpackEntryWidget> buildEntryWidgets() {
		List<SulkanShaderpackEntryWidget> widgets = new ArrayList<>();
		for (ShaderpackCandidate candidate : this.candidates) {
			Text label = Text.literal(candidate.displayName());
			if (!candidate.isValid()) {
				label = Text.literal(candidate.displayName()).append(Text.translatable("sulkan.shaderpack_screen.invalid_suffix"));
			}
			SulkanShaderpackEntryWidget widget = new SulkanShaderpackEntryWidget(candidate.id(), label, this::onEntrySelected);
			widget.setActive(candidate.isValid());
			widgets.add(widget);
		}
		return widgets;
	}

	private void refreshCandidates() {
		this.candidates = ShaderpackManager.discoverShaderpacks();
		this.candidateById.clear();
		for (ShaderpackCandidate candidate : this.candidates) {
			this.candidateById.put(candidate.id(), candidate);
		}
		if (this.pendingSelection != null && !this.candidateById.containsKey(this.pendingSelection)) {
			this.pendingSelection = "";
		}
	}

	private void onEntrySelected(SulkanShaderpackEntryWidget entry) {
		this.pendingSelection = entry.getId();
		this.updateButtons();
	}

	private void onSelectionChanged(String id) {
		this.pendingSelection = id;
		this.updateButtons();
	}

	private void updateButtons() {
		if (this.settingsButton == null || this.applyButton == null) {
			return;
		}
		SulkanConfig config = SulkanConfig.get();
		boolean hasSelection = this.pendingSelection != null && !this.pendingSelection.isBlank();
		ShaderpackCandidate candidate = hasSelection ? this.candidateById.get(this.pendingSelection) : null;
		boolean validSelection = candidate != null && candidate.isValid();
		@SuppressWarnings("null")
		boolean hasConfigOptions = validSelection && candidate.loadResult().metadata().features.sulkanConfigOptions();
		this.settingsButton.active = hasConfigOptions;
		this.applyButton.active = validSelection && !this.pendingSelection.equals(config.selectedShaderpack);
	}

	private void applySelection() {
		if (this.pendingSelection == null || this.pendingSelection.isBlank()) {
			this.setStatus(Text.translatable("sulkan.shaderpack_screen.status.missing"), false);
			return;
		}
		ShaderpackCandidate candidate = this.candidateById.get(this.pendingSelection);
		if (candidate == null) {
			this.setStatus(Text.translatable("sulkan.shaderpack_screen.status.missing"), false);
			return;
		}
		ShaderpackLoadResult result = ShaderpackManager.applyShaderpack(candidate.path());
		if (!result.isValid()) {
			Sulkan.LOGGER.error("Shaderpack validation failed for {}:", candidate.path().getFileName());
			for (String error : result.errors()) {
				Sulkan.LOGGER.error("  {}", error);
			}
			this.setStatus(Text.translatable("sulkan.shaderpack_screen.status.invalid"), false);
			return;
		}
		SulkanConfig config = SulkanConfig.get();
		config.selectedShaderpack = this.pendingSelection;
		SulkanConfig.save();
		this.applyButton.active = false;
		this.setStatus(Text.translatable("sulkan.shaderpack_screen.status.applied"), true);
		this.reloadResources();
	}

	private void openShaderpackSettings() {
		if (this.pendingSelection == null || this.pendingSelection.isBlank()) {
			return;
		}
		ShaderpackCandidate candidate = this.candidateById.get(this.pendingSelection);
		if (candidate == null || !candidate.isValid()) {
			return;
		}
		ShaderpackMetadata metadata = candidate.loadResult().metadata();
		if (metadata == null || !metadata.features.sulkanConfigOptions()) {
			return;
		}
		ShaderpackConfig config = ShaderpackConfig.loadOrCreate(candidate.path(), metadata.options);
		ShaderpackConfigSession session = new ShaderpackConfigSession(config);
		this.client.setScreen(new SulkanShaderpackSettingsScreen(this, metadata, session));
	}

	private void openShaderpacksFolder() {
		Path shaderpacksDir = MinecraftClient.getInstance().runDirectory.toPath().resolve("shaderpacks");
		try {
			Files.createDirectories(shaderpacksDir);
		} catch (Exception ignored) {
		}
		Util.getOperatingSystem().open(shaderpacksDir.toUri().toString());
	}

	private void reloadResources() {
		MinecraftClient client = this.client;
		if (client == null) {
			return;
		}
		this.setStatus(Text.translatable("sulkan.shaderpack_screen.status.reloading"), true);
		SulkanVulkanPipelineReloader.reloadResourcesAndPipelines(client).thenRun(() -> client.execute(() -> {
			if (this.client != null && this.client.currentScreen == this) {
				this.setStatus(Text.translatable("sulkan.shaderpack_screen.status.reloaded"), true);
			}
		}));
	}

	private void setStatus(Text message, boolean success) {
		this.statusMessage = message;
		this.statusUntil = Util.getMeasuringTimeMs() + 4000;
		this.statusColor = success
			? ColorUtil.ARGB.pack(0.4f, 0.9f, 0.4f, 1.0f)
			: ColorUtil.ARGB.pack(0.9f, 0.4f, 0.4f, 1.0f);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		if (!this.initialized || this.packList == null || this.openFolderButton == null || this.settingsButton == null
			|| this.applyButton == null || this.backButton == null) {
			return;
		}
		GuiRenderer.guiGraphics = context;
		VRenderSystem.enableBlend();
		GuiRenderer.drawCenteredString(this.textRenderer, this.title, this.width / 2, 12, -1);
		this.packList.updateState(mouseX, mouseY);
		this.packList.renderWidget(mouseX, mouseY);
		this.openFolderButton.render(mouseX, mouseY);
		this.settingsButton.render(mouseX, mouseY);
		this.applyButton.render(mouseX, mouseY);
		this.backButton.render(mouseX, mouseY);
		this.renderInvalidTooltip(mouseX, mouseY);
		if (this.statusMessage != null && Util.getMeasuringTimeMs() < this.statusUntil) {
			GuiRenderer.drawCenteredString(this.textRenderer, this.statusMessage, this.width / 2, this.buttonRowY - 12, this.statusColor);
		}
	}

	private void renderInvalidTooltip(int mouseX, int mouseY) {
		SulkanShaderpackEntryWidget hovered = this.packList.getHoveredWidget(mouseX, mouseY);
		if (hovered == null) {
			return;
		}
		ShaderpackCandidate candidate = this.candidateById.get(hovered.getId());
		if (candidate == null || candidate.isValid() || candidate.loadResult() == null) {
			return;
		}
		List<String> errors = candidate.loadResult().errors();
		if (errors.isEmpty()) {
			return;
		}
		StringBuilder builder = new StringBuilder();
		builder.append(Text.translatable("sulkan.shaderpack_screen.invalid_tooltip_title").getString());
		for (String error : errors) {
			builder.append('\n').append("- ").append(error);
		}
		int maxWidth = Math.min(260, this.width - 20);
		List<OrderedText> lines = this.textRenderer.wrapLines(Text.literal(builder.toString()), maxWidth);
		int width = GuiRenderer.getMaxTextWidth(this.textRenderer, lines);
		int height = lines.size() * 10;
		int padding = 3;
		int x = Math.min(mouseX + 12, this.width - width - padding - 2);
		int y = Math.min(mouseY + 12, this.height - height - padding - 2);
		int background = ColorUtil.ARGB.pack(0.05f, 0.05f, 0.05f, 0.7f);
		GuiRenderer.fill(x - padding, y - padding, x + width + padding, y + height + padding, background);
		int border = ColorUtil.ARGB.pack(0.6f, 0.1f, 0.1f, 0.9f);
		GuiRenderer.renderBorder(x - padding, y - padding, x + width + padding, y + height + padding, 1, border);
		int yOffset = 0;
		for (OrderedText line : lines) {
			GuiRenderer.drawString(this.textRenderer, line, x, y + yOffset, -1);
			yOffset += 10;
		}
	}
}
