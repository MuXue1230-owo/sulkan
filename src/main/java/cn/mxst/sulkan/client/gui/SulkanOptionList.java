package cn.mxst.sulkan.client.gui;

import com.mojang.blaze3d.opengl.GlStateManager;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.List;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.Element;
import net.minecraft.util.math.MathHelper;
import net.vulkanmod.config.gui.GuiElement;
import net.vulkanmod.config.gui.render.GuiRenderer;
import net.vulkanmod.config.gui.widget.VAbstractWidget;
import net.vulkanmod.vulkan.util.ColorUtil;
import org.jetbrains.annotations.Nullable;

public final class SulkanOptionList extends GuiElement {
	private final List<Entry> children = new ObjectArrayList<>();
	private Entry focused;
	private boolean scrolling = false;
	private float scrollAmount = 0.0f;
	private int columns = 1;
	private int itemWidth;
	private int itemHeight;
	private int itemMargin;
	private int columnGap;
	private int totalItemHeight;
	private int listLength = 0;

	public SulkanOptionList(int x, int y, int width, int height, int itemHeight) {
		this.setPosition(x, y, width, height);
		this.itemHeight = itemHeight;
		this.itemMargin = 3;
		this.columnGap = 6;
		this.totalItemHeight = this.itemHeight + this.itemMargin;
		this.itemWidth = Math.max(32, (int)(0.95f * width));
	}

	public void setEntries(List<SulkanOptionEntryWidget> entries) {
		this.setEntries(entries, 1);
	}

	public void setEntries(List<SulkanOptionEntryWidget> entries, int columns) {
		this.columns = Math.max(1, columns);
		this.clearEntries();
		this.recalculateItemWidth();
		for (SulkanOptionEntryWidget widget : entries) {
			widget.setDimensions(0, 0, this.itemWidth, this.itemHeight);
			this.addEntry(new Entry(widget, this.itemMargin));
		}
		this.recalculateListLength();
		this.updateEntryPositions();
	}

	private void addEntry(Entry entry) {
		this.children.add(entry);
	}

	public void clearEntries() {
		this.listLength = 0;
		this.children.clear();
		this.focused = null;
	}

	@Override
	public void updateState(double mX, double mY) {
		if (this.focused != null) {
			return;
		}
		super.updateState(mX, mY);
	}

	public void renderWidget(int mouseX, int mouseY) {
		GuiRenderer.enableScissor(this.x, this.y, this.x + this.width, this.y + this.height);
		this.renderList(mouseX, mouseY);
		GuiRenderer.disableScissor();
		int maxScroll = this.getMaxScroll();
		if (maxScroll > 0) {
			GlStateManager._enableBlend();
			int height = this.getHeight();
			int totalLength = this.getTotalLength();
			int barHeight = (int)((float)(height * height) / (float)totalLength);
			barHeight = MathHelper.clamp(barHeight, 32, height - 8);
			int scrollAmount = (int)this.getScrollAmount();
			int barY = scrollAmount * (height - barHeight) / maxScroll + this.getY();
			barY = Math.max(barY, this.getY());
			int scrollbarPosition = this.getScrollbarPosition();
			int thickness = 3;
			int backgroundColor = ColorUtil.ARGB.pack(0.8f, 0.8f, 0.8f, 0.2f);
			GuiRenderer.fill(scrollbarPosition, this.getY(), scrollbarPosition + thickness, this.getY() + height, backgroundColor);
			int barColor = ColorUtil.ARGB.pack(0.3f, 0.0f, 0.0f, 0.6f);
			GuiRenderer.fill(scrollbarPosition, barY, scrollbarPosition + thickness, barY + barHeight, barColor);
		}
	}

	public SulkanOptionEntryWidget getHoveredWidget(double mouseX, double mouseY) {
		if (!this.isMouseOver(mouseX, mouseY)) {
			return null;
		}
		this.updateEntryPositions();
		for (Entry entry : this.children) {
			VAbstractWidget widget = entry.widget;
			if (widget == null || !widget.isMouseOver(mouseX, mouseY)) {
				continue;
			}
			return entry.widget;
		}
		return null;
	}

	private void renderList(int mouseX, int mouseY) {
		this.updateEntryPositions();
		for (Entry entry : this.children) {
			VAbstractWidget widget = entry.widget;
			if (widget == null) {
				continue;
			}
			int rowTop = widget.getY();
			int rowBottom = rowTop + this.itemHeight;
			if (rowBottom >= this.y && rowTop <= this.y + this.height) {
				boolean updateState = this.focused == null;
				entry.render(mouseX, mouseY, updateState);
			}
		}
	}

	private void updateScrollingState(double mouseX, int button) {
		this.scrolling = button == 0 && mouseX >= (double)this.getScrollbarPosition() && mouseX < (double)(this.getScrollbarPosition() + 6);
	}

	private float getScrollAmount() {
		return this.scrollAmount;
	}

	private void setScrollAmount(double amount) {
		this.scrollAmount = (float)MathHelper.clamp(amount, 0.0, this.getMaxScroll());
	}

	private int getTotalLength() {
		return this.listLength;
	}

	private int getMaxScroll() {
		return Math.max(0, this.getTotalLength() - this.height);
	}

	private int getScrollbarPosition() {
		return this.x + this.itemWidth + 5;
	}

	@Nullable
	private Entry getEntryAtPos(double x, double y) {
		if (x > (double)this.getScrollbarPosition() || x < (double)this.x) {
			return null;
		}
		this.updateEntryPositions();
		for (Entry entry : this.children) {
			VAbstractWidget widget = entry.widget;
			if (widget == null) {
				continue;
			}
			if (y >= widget.getY() && y <= widget.getY() + widget.getHeight()) {
				return entry;
			}
		}
		return null;
	}

	@Override
	public boolean mouseClicked(Click event, boolean bl) {
		this.updateScrollingState(event.x(), event.button());
		if (this.isMouseOver(event.x(), event.y())) {
			Entry entry = this.getEntryAtPos(event.x(), event.y());
			if (entry != null && entry.mouseClicked(event, bl)) {
				this.setFocused(entry);
				entry.setFocused(true);
				return true;
			}
			return event.button() == 0;
		}
		return false;
	}

	@Override
	public boolean mouseReleased(Click event) {
		Entry entry;
		if (this.isValidClickButton(event.button()) && (entry = this.getEntryAtPos(event.x(), event.y())) != null && entry.mouseReleased(event)) {
			entry.setFocused(false);
			this.setFocused(null);
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseDragged(Click event, double deltaX, double deltaY) {
		if (event.button() != 0) {
			return false;
		}
		if (this.getFocused() != null) {
			return this.getFocused().mouseDragged(event, deltaX, deltaY);
		}
		if (!this.scrolling) {
			return false;
		}
		double maxScroll = this.getMaxScroll();
		if (event.y() < (double)this.y) {
			this.setScrollAmount(0.0);
		} else if (event.y() > (double)(this.y + this.height)) {
			this.setScrollAmount(maxScroll);
		} else if (maxScroll > 0.0) {
			double barHeight = (double)this.height * (double)this.height / (double)this.getTotalLength();
			double scrollFactor = Math.max(1.0, maxScroll / ((double)this.height - barHeight));
			this.setScrollAmount(this.getScrollAmount() + deltaY * scrollFactor);
		}
		return true;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double xScroll, double yScroll) {
		this.setScrollAmount(this.getScrollAmount() - yScroll * (double)this.totalItemHeight / 2.0);
		return true;
	}

	@Override
	public boolean isMouseOver(double mouseX, double mouseY) {
		return mouseX >= (double)this.x && mouseY >= (double)this.y && mouseX <= (double)(this.x + this.width) && mouseY <= (double)(this.y + this.height);
	}

	private Entry getFocused() {
		return this.focused;
	}

	private void setFocused(Entry focused) {
		this.focused = focused;
	}

	private boolean isValidClickButton(int button) {
		return button == 0;
	}

	private static final class Entry implements Element {
		final SulkanOptionEntryWidget widget;
		private Entry(SulkanOptionEntryWidget widget, int margin) {
			this.widget = widget;
		}

		public void render(int mouseX, int mouseY, boolean updateState) {
			if (this.widget == null) {
				return;
			}
			if (updateState) {
				this.widget.updateState(mouseX, mouseY);
			}
			this.widget.render(mouseX, mouseY);
		}

		public boolean mouseClicked(Click event, boolean bl) {
			return this.widget.mouseClicked(event, bl);
		}

		public boolean mouseReleased(Click event) {
			return this.widget.mouseReleased(event);
		}

		public boolean mouseDragged(Click event, double deltaX, double deltaY) {
			return this.widget.mouseDragged(event, deltaX, deltaY);
		}

		public void setFocused(boolean focused) {
			this.widget.setFocused(focused);
		}

		@Override
		public boolean isFocused() {
			return this.widget.isFocused();
		}
	}

	private void recalculateItemWidth() {
		if (this.columns <= 1) {
			this.itemWidth = Math.max(32, (int)(0.95f * this.width));
			return;
		}
		int scrollbarReserved = 8;
		int available = Math.max(32, this.width - scrollbarReserved);
		int totalGap = this.columnGap * (this.columns - 1);
		this.itemWidth = Math.max(32, (available - totalGap) / this.columns);
	}

	private void recalculateListLength() {
		int rows = this.columns <= 1
			? this.children.size()
			: (this.children.size() + this.columns - 1) / this.columns;
		this.listLength = Math.max(0, rows * this.totalItemHeight);
	}

	private void updateEntryPositions() {
		int baseY = this.y - (int)this.getScrollAmount();
		for (int i = 0; i < this.children.size(); i++) {
			Entry entry = this.children.get(i);
			VAbstractWidget widget = entry.widget;
			if (widget == null) {
				continue;
			}
			int row = this.columns <= 1 ? i : i / this.columns;
			int column = this.columns <= 1 ? 0 : i % this.columns;
			int x = this.x + column * (this.itemWidth + this.columnGap);
			int y = baseY + row * this.totalItemHeight;
			widget.setDimensions(x, y, this.itemWidth, this.itemHeight);
		}
	}
}
