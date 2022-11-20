package org.minefortress.renderer.gui.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.minefortress.renderer.gui.hud.interfaces.IHudButton;
import org.minefortress.renderer.gui.hud.interfaces.IHudElement;
import org.minefortress.renderer.gui.hud.interfaces.IHudLayer;
import org.minefortress.renderer.gui.hud.interfaces.IItemHudElement;
import org.minefortress.utils.ModUtils;

import java.util.ArrayList;
import java.util.List;

public abstract class AbstractHudLayer extends DrawableHelper implements IHudLayer {

    private final List<IHudElement> fortressHudElements = new ArrayList<>();

    protected final MinecraftClient client;
    protected final ItemRenderer itemRenderer;
    protected final TextRenderer textRenderer;

    private Integer basepointX;
    private Integer basepointY;
    private boolean centeredX;
    private boolean centeredY;

    protected AbstractHudLayer(MinecraftClient client) {
        this.client = client;
        this.itemRenderer = client.getItemRenderer();
        this.textRenderer = client.textRenderer;
    }

    protected final void setBasepoint(int x, int y, boolean centeredX, boolean centeredY) {
        this.basepointX = x;
        this.basepointY = y;
        this.centeredX = centeredX;
        this.centeredY = centeredY;
    }

    protected final void addElement(IHudElement button) {
        if(button instanceof IItemHudElement itemButton)
            itemButton.setItemRenderer(itemRenderer);
        fortressHudElements.add(button);
    }

    public void tick() {
        for(IHudElement button : fortressHudElements)
            button.tick();
    }

    final public void render(MatrixStack p, TextRenderer font, int screenWidth, int screenHeight, double mouseX, double mouseY, float delta) {
        if(basepointX == null || basepointY == null) throw new IllegalStateException("Basepoint not set!");
        this.renderHud(p, font, screenWidth, screenHeight);

        final var baseX = centeredX ? screenWidth / 2 : screenWidth + basepointX;
        final var baseY = centeredY ? screenHeight / 2 : screenHeight + basepointY;

        boolean creative = ModUtils.getFortressClientManager().isCreative();
        for (IHudElement fortressHudButton : fortressHudElements) {
            fortressHudButton.setPosBasedOn(baseX, baseY);
            if(fortressHudButton.shouldRender(creative)) {
                fortressHudButton.render(p, (int)mouseX, (int)mouseY, delta);
            }
        }
    }

    protected void renderHud(MatrixStack matrices, TextRenderer font, int screenWidth, int screenHeight) {}

    final public boolean isHovered() {
        for (IHudElement fortressHudButton : fortressHudElements) {
            if(fortressHudButton.isHovered()) return true;
        }
        return false;
    }
    final public void onClick(double mouseX, double mouseY) {
        for (IHudElement elem : fortressHudElements) {
            if(elem instanceof IHudButton btn && elem.isHovered()) {
                btn.onClick(mouseX, mouseY);
                return;
            }
        }
    }
}