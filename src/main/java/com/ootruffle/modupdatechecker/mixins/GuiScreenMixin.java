package com.ootruffle.modupdatechecker.mixins;

import com.ootruffle.modupdatechecker.Config;
import com.ootruffle.modupdatechecker.UpdateChecker;
import com.ootruffle.modupdatechecker.ui.UpdatesScreen;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds the "Mod Updates" button to the top right of the title screen and pause menu. */
@Mixin(GuiScreen.class)
public abstract class GuiScreenMixin {

    @Unique
    private static final int BUTTON_ID = 0x4D5543;

    @Shadow
    protected List<GuiButton> buttonList;

    @Shadow
    public int width;

    @Shadow
    public Minecraft mc;

    @Unique
    private GuiButton modupdatechecker$button;

    @Unique
    private boolean modupdatechecker$wanted() {
        final Object self = this;
        return Config.get().showMenuButton && (self instanceof GuiMainMenu || self instanceof GuiIngameMenu);
    }

    @Inject(method = "setWorldAndResolution", at = @At("TAIL"))
    private void modupdatechecker$addButton(Minecraft mc, int width, int height, CallbackInfo ci) {
        modupdatechecker$button = null;
        if (!modupdatechecker$wanted()) return;
        modupdatechecker$button = new GuiButton(BUTTON_ID, width - 105, 5, 100, 20, "Mod Updates");
        buttonList.add(modupdatechecker$button);
    }

    @Inject(method = "drawScreen", at = @At("HEAD"))
    private void modupdatechecker$label(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        final GuiButton button = modupdatechecker$button;
        if (button == null) return;
        final int updates = UpdateChecker.updateCount();
        final int problems = com.ootruffle.modupdatechecker.install.ModRemover.problemCount();
        if (problems > 0) {
            button.displayString = "§cMod Problems (" + problems + ")";
        } else if (UpdateChecker.running()) {
            button.displayString = "Checking mods...";
        } else if (updates > 0) {
            button.displayString = "\u00a7eMod Updates (" + updates + ")";
        } else {
            button.displayString = "Mod Updates";
        }
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void modupdatechecker$click(int mouseX, int mouseY, int mouseButton, CallbackInfo ci) {
        final GuiButton button = modupdatechecker$button;
        if (button == null || mouseButton != 0 || !button.mousePressed(mc, mouseX, mouseY)) return;
        button.playPressSound(mc.getSoundHandler());
        mc.displayGuiScreen(UpdatesScreen.create((GuiScreen) (Object) this));
        ci.cancel();
    }
}
