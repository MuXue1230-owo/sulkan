package net.vulkanmod.config.gui.widget;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.Text;
import net.vulkanmod.config.gui.render.GuiRenderer;
import net.vulkanmod.config.option.SulkanActionOption;
import net.vulkanmod.vulkan.util.ColorUtil;

public final class SulkanActionOptionWidget extends OptionWidget<SulkanActionOption> {
	public SulkanActionOptionWidget(SulkanActionOption option, Text name) {
		super(option, name);
	}

	@Override
	protected void renderControls(double mouseX, double mouseY) {
		int x0 = this.controlX;
		int y0 = this.y + 2;
		int height = this.height - 4;
		int width = this.controlWidth;
		int background = ColorUtil.ARGB.pack(0.0f, 0.0f, 0.0f, this.active ? 0.45f : 0.3f);
		GuiRenderer.fill(x0, y0, x0 + width, y0 + height, background);
		if (this.active && this.controlHovered) {
			int borderColor = ColorUtil.ARGB.pack(0.3f, 0.0f, 0.0f, 0.8f);
			GuiRenderer.renderBorder(x0, y0, x0 + width, y0 + height, 1, borderColor);
		}
		int color = this.active ? -1 : -6250336;
		TextRenderer textRenderer = MinecraftClient.getInstance().textRenderer;
		GuiRenderer.drawCenteredString(textRenderer, this.getDisplayedValue(), x0 + width / 2, this.y + (this.height - 8) / 2, color);
	}

	@Override
	public void onClick(double mouseX, double mouseY) {
		this.option.press();
	}

	@Override
	public void onRelease(double mouseX, double mouseY) {
	}

	@Override
	protected void onDrag(double mouseX, double mouseY, double deltaX, double deltaY) {
	}
}
