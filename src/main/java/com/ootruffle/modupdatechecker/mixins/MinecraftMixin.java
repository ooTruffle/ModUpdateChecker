package com.ootruffle.modupdatechecker.mixins;

import com.ootruffle.modupdatechecker.ModUpdateChecker;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    @Inject(method = "runTick", at = @At("TAIL"))
    private void modupdatechecker$tick(CallbackInfo ci) {
        ModUpdateChecker.onClientTick();
    }
}
