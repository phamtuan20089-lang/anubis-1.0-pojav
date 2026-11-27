package net.anubis.client.gui;

import imgui.ImDrawList;
import net.minecraft.class_2246;

public final class SignalIcon {
   private SignalIcon() {
   }

   public static boolean cluster(ImDrawList var0, float var1, float var2, float var3) {
      try {
         BlockIcons.draw(var0, class_2246.field_27161, var1, var2, var3);
         return true;
      } catch (Throwable var5) {
         return false;
      }
   }
}
