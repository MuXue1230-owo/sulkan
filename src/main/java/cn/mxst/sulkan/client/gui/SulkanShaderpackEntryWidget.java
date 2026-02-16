package cn.mxst.sulkan.client.gui;

import java.util.function.Consumer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.Text;
import net.vulkanmod.config.gui.render.GuiRenderer;
import net.vulkanmod.config.gui.widget.VAbstractWidget;
import net.vulkanmod.vulkan.util.ColorUtil;

public final class SulkanShaderpackEntryWidget extends VAbstractWidget {
	private final String id;
	private final Text label;
	private final Consumer<SulkanShaderpackEntryWidget> onSelect;
	private boolean selected;

	public SulkanShaderpackEntryWidget(String id, Text label, Consumer<SulkanShaderpackEntryWidget> onSelect) {
		this.id = id;
		this.label = label;
		this.onSelect = onSelect;
	}

	public String getId() {
		return id;
	}

	public void setSelected(boolean selected) {
		this.selected = selected;
	}

	@Override
	public void renderWidget(double mouseX, double mouseY) {
		TextRenderer textRenderer = MinecraftClient.getInstance().textRenderer;
		int background = ColorUtil.ARGB.pack(0.0f, 0.0f, 0.0f, this.active ? 0.45f : 0.3f);
		GuiRenderer.fill(this.x, this.y, this.x + this.width, this.y + this.height, background);
		if (this.active) {
			this.renderHovering(0, 0);
		}
		int color = this.active ? -1 : -6250336;
		GuiRenderer.drawString(textRenderer, this.label.asOrderedText(), this.x + 8, this.y + (this.height - 8) / 2, color);
		if (this.selected) {
			int barColor = ColorUtil.ARGB.pack(0.3f, 0.0f, 0.0f, 1.0f);
			GuiRenderer.fillBox(this.x, this.y, 1, this.height, barColor);
			int fillColor = ColorUtil.ARGB.pack(0.3f, 0.0f, 0.0f, 0.2f);
			GuiRenderer.fillBox(this.x, this.y, this.width, this.height, fillColor);
		}
	}

	@Override
	public void onClick(double mouseX, double mouseY) {
		if (this.active) {
			this.onSelect.accept(this);
		}
	}
}
