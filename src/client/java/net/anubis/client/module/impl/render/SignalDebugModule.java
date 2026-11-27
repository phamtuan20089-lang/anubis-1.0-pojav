package net.anubis.client.module.impl.render;

import com.mojang.authlib.GameProfile;
import imgui.ImDrawList;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.LongPredicate;
import net.anubis.client.esp.ChunkScanner;
import net.anubis.client.esp.ChunkScanner.Consumer;
import net.anubis.client.event.EventHandler;
import net.anubis.client.event.impl.BlockUpdateEvent;
import net.anubis.client.event.impl.PacketAppliedEvent;
import net.anubis.client.event.impl.Render3DEvent;
import net.anubis.client.event.impl.TickEvent;
import net.anubis.client.gui.SignalIcon;
import net.anubis.client.gui.WorldLabel;
import net.anubis.client.module.ClientModule;
import net.anubis.client.module.HudOverlayProvider;
import net.anubis.client.module.ModuleCategory;
import net.anubis.client.module.setting.BooleanSetting;
import net.anubis.client.module.setting.ColorSetting;
import net.anubis.client.module.setting.NumberSetting;
import net.anubis.client.util.FoglessLines;
import net.anubis.client.util.HiddenAmethyst;
import net.anubis.client.util.ServerBlockLight;
import net.anubis.client.util.HiddenAmethyst.Reading;
import net.anubis.client.util.playerbypass2.SimulationSignals;
import net.minecraft.class_1657;
import net.minecraft.class_2246;
import net.minecraft.class_2248;
import net.minecraft.class_2338;
import net.minecraft.class_243;
import net.minecraft.class_2596;
import net.minecraft.class_2672;
import net.minecraft.class_2676;
import net.minecraft.class_2680;
import net.minecraft.class_2818;
import net.minecraft.class_310;
import net.minecraft.class_638;

public final class SignalDebugModule extends ClientModule implements Consumer, HudOverlayProvider {
   private static final byte SMALL = 1;
   private static final byte MEDIUM = 2;
   private static final byte LARGE = 3;
   private static final byte CLUSTER = 4;
   private static final byte AMETHYST = 5;
   private static final byte BUDDING = 6;
   private static final byte CALCITE = 7;
   private static final byte BASALT = 8;
   private static final double RATE_PER_FACE_MIN = 0.029296875;
   private static final int EVAL_TICKS = 10;
   private static final int FACE_EVERY_EVALS = 4;
   private static final int MIN_Y = -64;
   private static final int HIDDEN_BUDGET = 4;
   private static final int NEIGHBOUR_WAIT_TICKS = 40;
   private static final int MIN_OPEN_FACES = 3;
   private static final double MIN_EXPECTED = 2.5;
   private static final long AFK_MEMORY_MS = 1800000L;
   private static final long AFK_FORGET_MS = 86400000L;
   private static final int SAVE_TICKS = 1200;
   private final NumberSetting minTotal;
   private final NumberSetting moveRadius;
   private final NumberSetting maxChunks;
   private final NumberSetting pillarTop;
   private final NumberSetting confidence;
   private final NumberSetting growthRate;
   private final NumberSetting simDistance;
   private final NumberSetting hiddenMin;
   private final NumberSetting shellMin;
   private final NumberSetting noBudMin;
   private final NumberSetting assumeHours;
   private final BooleanSetting lightMethod;
   private final BooleanSetting shellMethod;
   private final BooleanSetting revealMethod;
   private final BooleanSetting structureMethod;
   private final BooleanSetting showHistory;
   private final BooleanSetting afkTracking;
   private final BooleanSetting showNatural;
   private final BooleanSetting showTampered;
   private final BooleanSetting showIcon;
   private final ColorSetting naturalColor;
   private final ColorSetting tamperedColor;
   private final Map<Long, Byte> blocks = new HashMap<>();
   private final Map<Long, Boolean> scanned = new HashMap<>();
   private final Map<Long, int[]> hidden = new HashMap<>();
   private final Map<Long, Integer> revealed = new HashMap<>();
   private final Map<Long, Integer> growths = new HashMap<>();
   private final Map<Long, Integer> pending = new LinkedHashMap<>();
   private final ServerBlockLight serverLight = new ServerBlockLight();
   private int darkVotes;
   private int glowVotes;
   private final Map<Long, SignalDebugModule.Info> infos = new HashMap<>();
   private final Map<Long, double[]> savedChunks = new HashMap<>();
   private final Map<String, SignalDebugModule.Afk> afk = new HashMap<>();
   private final Map<Long, int[]> faces = new HashMap<>();
   private final Map<Long, SignalDebugModule.Hist> history = new HashMap<>();
   private volatile List<Long> historyShown = Collections.emptyList();
   private Object lastLevel;
   private String serverKey = "local";
   private long lastEvalMs;
   private int ticks;
   private int evals;
   private volatile List<SignalDebugModule.Shown> shown = Collections.emptyList();

   public SignalDebugModule() {
      super(
         "signal-debug",
         "Signal Debug",
         "PINK chunk = natural, visible full clusters. RED chunk = tampered: HIDDEN (light), SHELL (geode shell without lining), REVEALED (stone that turned into amethyst) and STUCK (statistical growth test, includes time other players were AFK). Needs Min Total; the pillar slides to the chunk with the bigger total.",
         ModuleCategory.BASE_RAID
      );
      this.minTotal = (NumberSetting)this.addSetting("min-total", new NumberSetting("Min Total", 7.0F, 1.0F, 40.0F).step(1.0F));
      this.moveRadius = (NumberSetting)this.addSetting("move-radius", new NumberSetting("Move Radius (chunks)", 3.0F, 0.0F, 8.0F).step(1.0F));
      this.maxChunks = (NumberSetting)this.addSetting("max-chunks", new NumberSetting("Max Chunks", 6.0F, 1.0F, 20.0F).step(1.0F));
      this.pillarTop = (NumberSetting)this.addSetting("pillar-top", new NumberSetting("Pillar Top Y", 320.0F, 64.0F, 400.0F).step(8.0F));
      this.confidence = (NumberSetting)this.addSetting("stuck-confidence", new NumberSetting("Stuck Confidence (%)", 99.0F, 90.0F, 99.9F).step(0.5F));
      this.growthRate = (NumberSetting)this.addSetting("growth-rate", new NumberSetting("Growth Rate x", 1.0F, 0.2F, 4.0F).step(0.1F));
      this.simDistance = (NumberSetting)this.addSetting("sim-distance", new NumberSetting("Sim Distance (0 = auto)", 0.0F, 0.0F, 16.0F).step(1.0F));
      this.hiddenMin = (NumberSetting)this.addSetting("hidden-min", new NumberSetting("Light Min", 2.0F, 1.0F, 20.0F).step(1.0F));
      this.shellMin = (NumberSetting)this.addSetting("shell-min", new NumberSetting("Shell Min Blocks", 30.0F, 10.0F, 200.0F).step(5.0F));
      this.noBudMin = (NumberSetting)this.addSetting("nobud-min", new NumberSetting("No Budding Min Crystals", 20.0F, 8.0F, 100.0F).step(2.0F));
      this.assumeHours = (NumberSetting)this.addSetting("assume-hours", new NumberSetting("Assume Past Ticking (h)", 0.0F, 0.0F, 72.0F).step(1.0F));
      this.lightMethod = (BooleanSetting)this.addSetting("method-light", new BooleanSetting("Method: Light", true));
      this.shellMethod = (BooleanSetting)this.addSetting("method-shell", new BooleanSetting("Method: Geode Shell", true));
      this.revealMethod = (BooleanSetting)this.addSetting("method-reveal", new BooleanSetting("Method: Revealed", true));
      this.structureMethod = (BooleanSetting)this.addSetting("method-structure", new BooleanSetting("Method: Orphan / No Budding", true));
      this.showHistory = (BooleanSetting)this.addSetting("show-history", new BooleanSetting("Remember Flagged Chunks", true));
      this.afkTracking = (BooleanSetting)this.addSetting("afk-tracking", new BooleanSetting("Track AFK Players", true));
      this.showNatural = (BooleanSetting)this.addSetting("show-natural", new BooleanSetting("Show Pink (Natural)", true));
      this.showTampered = (BooleanSetting)this.addSetting("show-tampered", new BooleanSetting("Show Red (Tampered)", true));
      this.showIcon = (BooleanSetting)this.addSetting("show-icon", new BooleanSetting("Cluster Icon", true));
      this.naturalColor = (ColorSetting)this.addSetting("natural-color", new ColorSetting("Pink Color", -33835));
      this.tamperedColor = (ColorSetting)this.addSetting("tampered-color", new ColorSetting("Red Color", -54485));
   }

   protected void onEnable() {
      ChunkScanner.register(this);
      this.lastEvalMs = System.currentTimeMillis();
   }

   protected void onDisable() {
      ChunkScanner.unregister(this);
      this.save();
      this.clear();
      this.afk.clear();
      this.savedChunks.clear();
      this.history.clear();
      this.lastLevel = null;
   }

   private void clear() {
      this.blocks.clear();
      this.scanned.clear();
      this.hidden.clear();
      this.revealed.clear();
      this.growths.clear();
      this.pending.clear();
      this.infos.clear();
      this.faces.clear();
      this.historyShown = Collections.emptyList();
      this.serverLight.clear();
      this.darkVotes = 0;
      this.glowVotes = 0;
      this.shown = Collections.emptyList();
   }

   private static byte kindOf(class_2680 var0) {
      class_2248 var1 = var0.method_26204();
      if (var1 == class_2246.field_27161) {
         return 4;
      } else if (var1 == class_2246.field_27162) {
         return 3;
      } else if (var1 == class_2246.field_27163) {
         return 2;
      } else if (var1 == class_2246.field_27164) {
         return 1;
      } else if (var1 == class_2246.field_27160) {
         return 6;
      } else if (var1 == class_2246.field_27159) {
         return 5;
      } else if (var1 == class_2246.field_27114) {
         return 7;
      } else {
         return (byte)(var1 == class_2246.field_29032 ? 8 : 0);
      }
   }

   public boolean wants(class_2680 var1) {
      return kindOf(var1) != 0;
   }

   public void found(long var1, class_2680 var3) {
      byte var4 = kindOf(var3);
      if (var4 == 0) {
         this.blocks.remove(var1);
      } else {
         this.blocks.put(var1, var4);
      }
   }

   public void removed(long var1) {
      this.blocks.remove(var1);
   }

   public void reset() {
      this.clear();
   }

   public void chunkScanned(long var1, LongPredicate var3) {
      this.blocks.keySet().removeIf(var3x -> chunkOfBlock(var3x) == var1 && !var3.test(var3x));
      this.scanned.put(var1, Boolean.TRUE);
      this.enqueue(var1);
   }

   @EventHandler
   public void onPacketApplied(PacketAppliedEvent var1) {
      class_310 var2 = class_310.method_1551();
      class_638 var3 = var2.field_1687;
      class_2596 var4 = var1.packet;
      if (var4 instanceof class_2672 var5) {
         if (var3 != null) {
            this.serverLight.onChunk(var3, var5.method_11523(), var5.method_11524(), var5.method_38599());
         }

         this.enqueue(chunkKey(var5.method_11523(), var5.method_11524()));
      } else if (var4 instanceof class_2676 var6) {
         if (var3 != null) {
            this.serverLight.onLight(var3, var6.method_11558(), var6.method_11554(), var6.method_38600());
         }

         this.enqueue(chunkKey(var6.method_11558(), var6.method_11554()));
      }
   }

   @EventHandler
   public void onBlockUpdate(BlockUpdateEvent var1) {
      this.serverLight.onBlockChanged(var1.pos);
      long var2 = chunkKey(var1.pos.method_10263() >> 4, var1.pos.method_10260() >> 4);
      this.enqueue(var2);
      if (var1.oldState != null && var1.newState != null) {
         byte var4 = kindOf(var1.oldState);
         byte var5 = kindOf(var1.newState);
         boolean var6 = var1.oldState.method_26215() || var1.oldState.method_27852(class_2246.field_10382);
         boolean var7 = var5 >= 1 && var5 <= 4;
         boolean var8 = var5 == 5 || var5 == 6;
         if (var7 && (var4 == 0 && var6 || var4 >= 1 && var4 <= 3 && var5 > var4)) {
            this.growths.merge(var2, 1, Integer::sum);
         }

         if ((var8 || var7) && var4 == 0 && !var6) {
            this.revealed.merge(var2, 1, Integer::sum);
         }
      }
   }

   private void enqueue(long var1) {
      this.pending.putIfAbsent(var1, this.ticks);
   }

   private Boolean hiddenSolids() {
      class_310 var1 = class_310.method_1551();

      try {
         if (var1.method_1562() != null && var1.method_1562().method_52790() != null && var1.method_1562().method_52790().contains("DonutFolia")) {
            return Boolean.TRUE;
         }
      } catch (Throwable var3) {
      }

      if (this.darkVotes >= 3 && this.darkVotes > this.glowVotes) {
         return Boolean.TRUE;
      } else {
         return this.glowVotes >= 3 && this.glowVotes > this.darkVotes ? Boolean.FALSE : null;
      }
   }

   private void processHidden(class_310 var1) {
      if (!this.pending.isEmpty() && this.lightMethod.get()) {
         class_638 var2 = var1.field_1687;
         int var3 = 0;
         Iterator var4 = this.pending.entrySet().iterator();

         while (var4.hasNext() && var3 < 4) {
            Entry var5 = (Entry)var4.next();
            long var6 = (Long)var5.getKey();
            int var8 = chunkX(var6);
            int var9 = chunkZ(var6);
            if (!var2.method_2935().method_12123(var8, var9)) {
               if (this.ticks - (Integer)var5.getValue() > 600) {
                  var4.remove();
               }
            } else {
               boolean var10 = this.ticks - (Integer)var5.getValue() >= 40
                  || var2.method_2935().method_12123(var8 + 1, var9)
                     && var2.method_2935().method_12123(var8 - 1, var9)
                     && var2.method_2935().method_12123(var8, var9 + 1)
                     && var2.method_2935().method_12123(var8, var9 - 1);
               if (var10) {
                  var4.remove();
                  var3++;

                  try {
                     class_2818 var11 = var2.method_8497(var8, var9);
                     Reading var12 = HiddenAmethyst.read(var2, var11, this.serverLight, this.hiddenSolids());
                     this.darkVotes = this.darkVotes + var12.darkVotes();
                     this.glowVotes = this.glowVotes + var12.glowVotes();
                     if (var12.buds() + var12.clusters() > 0) {
                        this.hidden.put(var6, new int[]{var12.buds(), var12.clusters()});
                     } else {
                        this.hidden.remove(var6);
                     }
                  } catch (Throwable var13) {
                     this.hidden.remove(var6);
                  }
               }
            }
         }
      } else {
         if (!this.lightMethod.get()) {
            this.pending.clear();
         }
      }
   }

   @EventHandler
   public void onTick(TickEvent var1) {
      class_310 var2 = class_310.method_1551();
      if (var2.field_1687 != null && var2.field_1724 != null) {
         this.ticks++;
         if (var2.field_1687 != this.lastLevel) {
            if (this.lastLevel != null) {
               this.save();
            }

            this.lastLevel = var2.field_1687;
            this.serverKey = serverKeyOf(var2);
            this.afk.clear();
            this.savedChunks.clear();
            this.history.clear();
            this.load(var2);
         }

         this.processHidden(var2);
         if (this.ticks % 1200 == 0) {
            this.serverLight.forgetUnloaded(var2.field_1687);
            this.save();
         }

         if (this.ticks % 10 == 0) {
            this.evals++;
            this.shown = this.evaluate(var2);
         }
      } else {
         this.shown = Collections.emptyList();
      }
   }

   private boolean isOpen(class_310 var1, int var2, int var3, int var4) {
      Byte var5 = this.blocks.get(blockKey(var2, var3, var4));
      if (var5 != null) {
         return false;
      } else if (this.scanned.get(chunkKey(var2 >> 4, var4 >> 4)) == null) {
         return false;
      } else {
         class_2680 var6 = var1.field_1687.method_8320(new class_2338(var2, var3, var4));
         return var6.method_26215() || var6.method_27852(class_2246.field_10382);
      }
   }

   private void computeFaces(class_310 var1) {
      this.faces.clear();
      int[][] var2 = new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

      for (Entry var4 : new ArrayList<>(this.blocks.entrySet())) {
         if ((Byte)var4.getValue() == 6) {
            long var5 = (Long)var4.getKey();
            int var7 = (int)(var5 >> 38);
            int var8 = (int)(var5 << 52 >> 52);
            int var9 = (int)(var5 << 26 >> 38);
            int[] var10 = this.faces.computeIfAbsent(chunkOfBlock(var5), var0 -> new int[2]);

            for (int[] var14 : var2) {
               int var15 = var7 + var14[0];
               int var16 = var8 + var14[1];
               int var17 = var9 + var14[2];
               Byte var18 = this.blocks.get(blockKey(var15, var16, var17));
               if (var18 != null) {
                  if (var18 == 4) {
                     var10[1]++;
                  } else if (var18 >= 1 && var18 <= 3) {
                     var10[0]++;
                     var10[1]++;
                  }
               } else if (this.isOpen(var1, var15, var16, var17)) {
                  var10[0]++;
                  var10[1]++;
               }
            }
         }
      }
   }

   private List<SignalDebugModule.Shown> evaluate(class_310 var1) {
      long var2 = System.currentTimeMillis();
      long var4 = Math.max(0L, Math.min(2000L, var2 - this.lastEvalMs));
      double var6 = var4 / 60000.0;
      this.lastEvalMs = var2;
      class_243 var8 = var1.field_1724.method_73189();
      int var9 = (int)Math.floor(var8.field_1352) >> 4;
      int var10 = (int)Math.floor(var8.field_1350) >> 4;
      int var11 = this.simRadius();
      double var12 = 0.029296875 * this.growthRate.get();
      double var14 = 1.0 - this.confidence.get() / 100.0;
      if (this.afkTracking.get()) {
         this.trackPlayers(var1, var2);
      }

      if (this.evals % 4 == 1 || this.faces.isEmpty()) {
         this.computeFaces(var1);
      }

      HashMap var16 = new HashMap();

      for (Entry var18 : new ArrayList<>(this.blocks.entrySet())) {
         byte var19 = (Byte)var18.getValue();
         int[] var20 = var16.computeIfAbsent(chunkOfBlock((Long)var18.getKey()), var0 -> new int[6]);
         if (var19 == 4) {
            var20[0]++;
         } else if (var19 >= 1 && var19 <= 3) {
            var20[var19]++;
         } else if (var19 == 5 || var19 == 6) {
            var20[4]++;
         } else if (var19 == 7 || var19 == 8) {
            var20[5]++;
         }
      }

      for (Long var63 : this.hidden.keySet()) {
         var16.computeIfAbsent(var63, var0 -> new int[6]);
      }

      for (Long var64 : this.revealed.keySet()) {
         var16.computeIfAbsent(var64, var0 -> new int[6]);
      }

      Map var62 = this.structureMethod.get() ? this.countOrphans() : Collections.emptyMap();
      HashMap var65 = new HashMap();

      for (Entry var68 : new ArrayList<>(this.blocks.entrySet())) {
         if ((Byte)var68.getValue() == 6) {
            var65.merge(chunkOfBlock((Long)var68.getKey()), 1, Integer::sum);
         }
      }

      this.infos.keySet().removeIf(var1x -> !var16.containsKey(var1x));
      int var67 = this.minTotal.getInt();
      int var69 = this.hiddenMin.getInt();
      int var21 = this.shellMin.getInt();
      int var22 = this.noBudMin.getInt();
      long var23 = (long)(this.assumeHours.get() * 3600000.0F);
      ArrayList var25 = new ArrayList();

      for (Entry var27 : var16.entrySet()) {
         long var28 = (Long)var27.getKey();
         int[] var30 = (int[])var27.getValue();
         int var31 = chunkX(var28);
         int var32 = chunkZ(var28);
         int[] var33 = this.hidden.get(var28);
         int var34 = var33 != null ? var33[0] : 0;
         int var35 = var33 != null ? var33[1] : 0;
         int var36 = var30[1] + var30[2] + var30[3];
         int var37 = this.growths.getOrDefault(var28, 0);
         int var38 = var34 + var35;
         long var39 = (((var30[0] * 31L + var30[1]) * 31L + var30[2]) * 31L + var30[3]) * 31L + var34 * 7L + var35 * 13L;
         SignalDebugModule.Info var41 = this.infos.get(var28);
         if (var41 == null) {
            var41 = new SignalDebugModule.Info();
            double[] var42 = this.savedChunks.get(var28);
            if (var42 != null && (long)var42[0] == var39) {
               var41.expected = var42[1];
               var41.events = (int)var42[2];
               var41.ownMs = (long)var42[3];
            }

            var41.sig = var39;
            var41.lastGrowths = var37;
            var41.lastHidden = var38;
            this.infos.put(var28, var41);
         }

         if (var37 > var41.lastGrowths) {
            var41.events = var41.events + (var37 - var41.lastGrowths);
         }

         if (var38 > var41.lastHidden) {
            var41.events = var41.events + (var38 - var41.lastHidden);
         }

         var41.lastGrowths = var37;
         var41.lastHidden = var38;
         var41.sig = var39;
         int[] var71 = this.faces.get(var28);
         int var43 = (var71 != null ? var71[0] : 0) + var34;
         int var44 = (var71 != null ? var71[1] : 0) + var34 + var35;
         boolean var45 = Math.abs(var31 - var9) <= var11 && Math.abs(var32 - var10) <= var11;
         if (var45) {
            var41.ownMs += var4;
            var41.expected += var43 * var12 * var6;
         }

         String var46 = null;
         boolean var47 = false;
         if (var43 >= 3 && var41.expected >= 2.5 && poissonCdf(var41.events, var41.expected) < var14) {
            var47 = true;
            var46 = var41.events + " growths in " + Math.max(1L, var41.ownMs / 60000L) + "m (expected " + Math.round(var41.expected) + ")";
         }

         String var48 = null;
         long var49 = 0L;
         if (this.afkTracking.get()) {
            for (SignalDebugModule.Afk var52 : this.afk.values()) {
               if (var2 - var52.seenMs <= 1800000L) {
                  int var53 = (int)Math.floor(var52.x) >> 4;
                  int var54 = (int)Math.floor(var52.z) >> 4;
                  if (Math.abs(var53 - var31) <= var11 && Math.abs(var54 - var32) <= var11) {
                     long var55 = var2 - var52.sinceMs;
                     if (var55 > var49) {
                        var49 = var55;
                        var48 = var52.name;
                     }
                  }
               }
            }
         }

         if (var48 == null && var23 > 0L) {
            var48 = "assumed";
            var49 = var23;
         }

         if (!var47 && var48 != null && var43 >= 3 && var44 >= var43) {
            double var72 = pStillOpen(var49 / 60000.0 * this.growthRate.get());
            if (binomialTail(var44, var72, var43) < var14) {
               var47 = true;
               var46 = var43
                  + "/"
                  + var44
                  + " faces still open after "
                  + ("assumed".equals(var48) ? "assumed " + span(var49) + " of ticking" : var48 + " AFK " + span(var49))
                  + " (expected ~"
                  + Math.round(var44 * var72)
                  + ")";
            }
         }

         if (var47 && var44 > 0) {
            var46 = var46 + " | state looks like ~" + span(Math.round(this.stateAgeMin((double)var43 / var44) * 60000.0)) + " of growth";
         }

         int var73 = this.lightMethod.get() && var38 >= var69 ? var38 : 0;
         int var74 = this.revealMethod.get() ? this.revealed.getOrDefault(var28, 0) : 0;
         if (var74 < 2) {
            var74 = 0;
         }

         int var75 = 0;
         if (this.shellMethod.get() && var30[5] >= var21 && var30[4] == 0 && this.ringScanned(var31, var32) && ringLining(var16, var31, var32) == 0) {
            var75 = Math.max(1, var30[5] / 8);
         }

         int var76 = var62.getOrDefault(var28, 0);
         if (var76 < var69) {
            var76 = 0;
         }

         int var77 = 0;
         if (this.structureMethod.get() && this.ringScanned(var31, var32)) {
            int var56 = var30[0] + var36;
            int var57 = ringCrystals(var16, var31, var32);
            if (var56 >= 1 && var57 >= var22 && ringBudding(var65, var31, var32) == 0 && ringLining(var16, var31, var32) > 0) {
               var77 = var56;
            }
         }

         boolean var78 = var47 || var73 > 0 || var74 > 0 || var75 > 0 || var76 > 0 || var77 > 0;
         int var79 = var30[0];
         if (var78) {
            int var58 = var47 ? var36 + var34 : 0;
            int var59 = var79 + var73 + var74 + var75 + Math.max(var76, var77) + var58;
            if (var59 >= var67 && this.showTampered.get()) {
               var25.add(
                  new SignalDebugModule.Shown(
                     var31,
                     var32,
                     true,
                     var59,
                     var79,
                     var73,
                     var74,
                     var75,
                     var76,
                     var77,
                     var58,
                     var47 ? var43 : 0,
                     var47 ? var46 : null,
                     var47 ? var48 : null,
                     var47 ? var49 : 0L
                  )
               );
            }
         } else if (var79 >= var67 && this.showNatural.get()) {
            var25.add(new SignalDebugModule.Shown(var31, var32, false, var79, var79, 0, 0, 0, 0, 0, 0, 0, null, null, 0L));
         }
      }

      List var70 = this.slide(var25);
      this.updateHistory(var1, var70, var16, var2);
      return var70;
   }

   private int simRadius() {
      int var1 = this.simDistance.getInt();
      if (var1 > 0) {
         return var1;
      } else {
         try {
            int var2 = SimulationSignals.simulation();
            if (var2 > 0) {
               return Math.max(1, var2 - 1);
            }
         } catch (Throwable var3) {
         }

         return 4;
      }
   }

   private Map<Long, Integer> countOrphans() {
      HashMap var1 = new HashMap();
      int[][] var2 = new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

      for (Entry var4 : new ArrayList<>(this.blocks.entrySet())) {
         byte var5 = (Byte)var4.getValue();
         if (var5 >= 1 && var5 <= 4) {
            long var6 = (Long)var4.getKey();
            int var8 = (int)(var6 >> 38);
            int var9 = (int)(var6 << 52 >> 52);
            int var10 = (int)(var6 << 26 >> 38);
            boolean var11 = false;
            boolean var12 = true;

            for (int[] var16 : var2) {
               int var17 = var8 + var16[0];
               int var18 = var10 + var16[2];
               Byte var19 = this.blocks.get(blockKey(var17, var9 + var16[1], var18));
               if (var19 != null && (var19 == 5 || var19 == 6)) {
                  var11 = true;
                  break;
               }

               if (this.scanned.get(chunkKey(var17 >> 4, var18 >> 4)) == null) {
                  var12 = false;
               }
            }

            if (!var11 && var12) {
               var1.merge(chunkOfBlock(var6), 1, Integer::sum);
            }
         }
      }

      return var1;
   }

   private static int ringCrystals(Map<Long, int[]> var0, int var1, int var2) {
      int var3 = 0;

      for (int var4 = -1; var4 <= 1; var4++) {
         for (int var5 = -1; var5 <= 1; var5++) {
            int[] var6 = (int[])var0.get(chunkKey(var1 + var4, var2 + var5));
            if (var6 != null) {
               var3 += var6[0] + var6[1] + var6[2] + var6[3];
            }
         }
      }

      return var3;
   }

   private static int ringBudding(Map<Long, Integer> var0, int var1, int var2) {
      int var3 = 0;

      for (int var4 = -1; var4 <= 1; var4++) {
         for (int var5 = -1; var5 <= 1; var5++) {
            var3 += var0.getOrDefault(chunkKey(var1 + var4, var2 + var5), 0);
         }
      }

      return var3;
   }

   private double stateAgeMin(double var1) {
      double var3 = Math.max(1.0E-6, Math.min(1.0, var1));
      double var5 = 0.0;
      double var7 = 4000.0;

      for (int var9 = 0; var9 < 40; var9++) {
         double var10 = (var5 + var7) / 2.0;
         if (pStillOpen(var10 * this.growthRate.get()) > var3) {
            var5 = var10;
         } else {
            var7 = var10;
         }
      }

      return (var5 + var7) / 2.0;
   }

   private static String label(SignalDebugModule.Shown var0) {
      if (!var0.tampered()) {
         return "NATURAL  clusters x" + var0.natural();
      } else {
         ArrayList var1 = new ArrayList();
         if (var0.light() > 0) {
            var1.add("HIDDEN " + var0.light());
         }

         if (var0.shell() > 0) {
            var1.add("SHELL ~" + var0.shell());
         }

         if (var0.revealed() > 0) {
            var1.add("REVEALED " + var0.revealed());
         }

         if (var0.orphan() > 0) {
            var1.add("ORPHAN " + var0.orphan());
         }

         if (var0.noBud() > 0) {
            var1.add("NO BUDDING " + var0.noBud());
         }

         if (var0.openFaces() > 0) {
            var1.add(var0.stuckBuds() > 0 ? "STUCK " + var0.stuckBuds() + " buds" : "STUCK " + var0.openFaces() + " open faces");
         }

         return "TAMPERED  " + String.join(" + ", var1);
      }
   }

   private void updateHistory(class_310 var1, List<SignalDebugModule.Shown> var2, Map<Long, int[]> var3, long var4) {
      if (!this.showHistory.get()) {
         this.historyShown = Collections.emptyList();
      } else {
         HashMap var6 = new HashMap();

         for (SignalDebugModule.Shown var8 : var2) {
            long var9 = var8.key();
            var6.put(var9, Boolean.TRUE);
            SignalDebugModule.Hist var11 = this.history.get(var9);
            if (var11 == null) {
               var11 = new SignalDebugModule.Hist();
               var11.first = var4;
               this.history.put(var9, var11);
            }

            var11.tampered = var8.tampered();
            var11.total = var8.total();
            var11.head = label(var8);
            var11.last = var4;
            StringBuilder var12 = new StringBuilder();
            if (var8.natural() > 0 && var8.tampered()) {
               var12.append("visible ").append(var8.natural());
            }

            if (var8.why() != null) {
               var12.append(var12.length() > 0 ? " | " : "").append(var8.why());
            }

            var11.sub = var12.toString();
         }

         Iterator var13 = this.history.entrySet().iterator();
         ArrayList var14 = new ArrayList();

         while (var13.hasNext()) {
            Entry var15 = (Entry)var13.next();
            long var10 = (Long)var15.getKey();
            if (!var6.containsKey(var10)) {
               boolean var16 = var1.field_1687.method_2935().method_12123(chunkX(var10), chunkZ(var10));
               if (var16 && var3.containsKey(var10) && this.scanned.get(var10) != null) {
                  var13.remove();
               } else if (!var16) {
                  var14.add(var10);
               }
            }
         }

         var14.sort((var1x, var2x) -> Long.compare(this.history.get(var2x).last, this.history.get(var1x).last));
         this.historyShown = var14.size() > 12 ? new ArrayList<>(var14.subList(0, 12)) : var14;
      }
   }

   private boolean ringScanned(int var1, int var2) {
      for (int var3 = -1; var3 <= 1; var3++) {
         for (int var4 = -1; var4 <= 1; var4++) {
            if (this.scanned.get(chunkKey(var1 + var3, var2 + var4)) == null) {
               return false;
            }
         }
      }

      return true;
   }

   private static int ringLining(Map<Long, int[]> var0, int var1, int var2) {
      int var3 = 0;

      for (int var4 = -1; var4 <= 1; var4++) {
         for (int var5 = -1; var5 <= 1; var5++) {
            int[] var6 = (int[])var0.get(chunkKey(var1 + var4, var2 + var5));
            if (var6 != null) {
               var3 += var6[4];
            }
         }
      }

      return var3;
   }

   private static double poissonCdf(int var0, double var1) {
      double var3 = Math.exp(-var1);
      double var5 = var3;

      for (int var7 = 1; var7 <= var0; var7++) {
         var3 *= var1 / var7;
         var5 += var3;
      }

      return Math.min(1.0, var5);
   }

   private static double pStillOpen(double var0) {
      double var2 = 0.029296875 * var0;
      return Math.max(1.0E-6, Math.min(1.0, poissonCdf(3, var2)));
   }

   private static double binomialTail(int var0, double var1, int var3) {
      if (var3 <= 0) {
         return 1.0;
      } else if (var3 > var0) {
         return 0.0;
      } else {
         double var4 = Math.log(var1);
         double var6 = Math.log(Math.max(1.0E-12, 1.0 - var1));
         double[] var8 = new double[var0 + 2];

         for (int var9 = 2; var9 <= var0 + 1; var9++) {
            var8[var9] = var8[var9 - 1] + Math.log(var9);
         }

         double var12 = 0.0;

         for (int var11 = var3; var11 <= var0; var11++) {
            var12 += Math.exp(var8[var0] - var8[var11] - var8[var0 - var11] + var11 * var4 + (var0 - var11) * var6);
         }

         return Math.min(1.0, var12);
      }
   }

   private List<SignalDebugModule.Shown> slide(List<SignalDebugModule.Shown> var1) {
      int var2 = this.moveRadius.getInt();
      ArrayList var3 = new ArrayList();

      for (SignalDebugModule.Shown var5 : var1) {
         boolean var6 = false;
         if (var2 > 0) {
            for (SignalDebugModule.Shown var8 : var1) {
               if (var8 != var5
                  && var8.tampered() == var5.tampered()
                  && Math.abs(var8.chunkX() - var5.chunkX()) <= var2
                  && Math.abs(var8.chunkZ() - var5.chunkZ()) <= var2
                  && (var8.total() > var5.total() || var8.total() == var5.total() && var8.key() < var5.key())) {
                  var6 = true;
                  break;
               }
            }
         }

         if (!var6) {
            var3.add(var5);
         }
      }

      var3.sort((var0, var1x) -> Integer.compare(var1x.total(), var0.total()));
      int var9 = this.maxChunks.getInt();
      return var3.size() > var9 ? new ArrayList<>(var3.subList(0, var9)) : var3;
   }

   private void trackPlayers(class_310 var1, long var2) {
      List var4 = var1.field_1687.method_18456();
      Iterator var5 = var4.iterator();

      while (true) {
         String var9;
         String var10;
         while (true) {
            if (!var5.hasNext()) {
               this.afk.values().removeIf(var2x -> var2 - var2x.seenMs > 86400000L);
               return;
            }

            if (var5.next() instanceof class_1657 var7 && var7 != var1.field_1724) {
               try {
                  GameProfile var11 = var7.method_7334();
                  var9 = var11.id().toString();
                  var10 = var11.name();
                  break;
               } catch (Throwable var27) {
               }
            }
         }

         double var28 = var7.method_23317();
         double var13 = var7.method_23318();
         double var15 = var7.method_23321();
         float var17 = var7.method_36454();
         float var18 = var7.method_36455();
         SignalDebugModule.Afk var19 = this.afk.get(var9);
         if (var19 == null) {
            var19 = new SignalDebugModule.Afk();
            var19.x = var28;
            var19.y = var13;
            var19.z = var15;
            var19.yaw = var17;
            var19.pitch = var18;
            var19.sinceMs = var2;
            this.afk.put(var9, var19);
         } else {
            double var20 = var28 - var19.x;
            double var22 = var13 - var19.y;
            double var24 = var15 - var19.z;
            boolean var26 = var20 * var20 + var22 * var22 + var24 * var24 > 0.04 || Math.abs(var17 - var19.yaw) > 1.5F || Math.abs(var18 - var19.pitch) > 1.5F;
            if (var26) {
               var19.x = var28;
               var19.y = var13;
               var19.z = var15;
               var19.yaw = var17;
               var19.pitch = var18;
               var19.sinceMs = var2;
            }
         }

         var19.seenMs = var2;
         if (var10 != null && !var10.isEmpty()) {
            var19.name = var10;
         }
      }
   }

   private static String serverKeyOf(class_310 var0) {
      try {
         if (var0.method_1558() != null && var0.method_1558().field_3761 != null) {
            return var0.method_1558().field_3761.replaceAll("[^A-Za-z0-9._-]", "_");
         }
      } catch (Throwable var2) {
      }

      return "local";
   }

   private File store(class_310 var1) {
      return new File(new File(var1.field_1697, "config/anubis"), "signal-debug-v5-" + this.serverKey + ".txt");
   }

   private void load(class_310 var1) {
      try {
         File var2 = this.store(var1);
         if (!var2.isFile()) {
            return;
         }

         long var3 = System.currentTimeMillis();

         for (String var6 : Files.readAllLines(var2.toPath(), StandardCharsets.UTF_8)) {
            String[] var7 = var6.split("\\|", -1);
            if (var7.length == 6 && var7[0].equals("C")) {
               this.savedChunks
                  .put(
                     Long.parseLong(var7[1]),
                     new double[]{Double.parseDouble(var7[2]), Double.parseDouble(var7[3]), Double.parseDouble(var7[4]), Double.parseDouble(var7[5])}
                  );
            } else if (var7.length == 8 && var7[0].equals("H")) {
               SignalDebugModule.Hist var10 = new SignalDebugModule.Hist();
               var10.tampered = var7[2].equals("1");
               var10.total = Integer.parseInt(var7[3]);
               var10.first = Long.parseLong(var7[4]);
               var10.last = Long.parseLong(var7[5]);
               var10.head = var7[6];
               var10.sub = var7[7];
               this.history.put(Long.parseLong(var7[1]), var10);
            } else if (var7.length == 10 && var7[0].equals("A")) {
               SignalDebugModule.Afk var8 = new SignalDebugModule.Afk();
               var8.name = var7[2];
               var8.x = Double.parseDouble(var7[3]);
               var8.y = Double.parseDouble(var7[4]);
               var8.z = Double.parseDouble(var7[5]);
               var8.yaw = Float.parseFloat(var7[6]);
               var8.pitch = Float.parseFloat(var7[7]);
               var8.sinceMs = Long.parseLong(var7[8]);
               var8.seenMs = Long.parseLong(var7[9]);
               if (var3 - var8.seenMs < 86400000L) {
                  this.afk.put(var7[1], var8);
               }
            }
         }
      } catch (Throwable var9) {
      }
   }

   private void save() {
      class_310 var1 = class_310.method_1551();
      if (var1 != null && var1.field_1697 != null) {
         try {
            StringBuilder var2 = new StringBuilder();
            HashMap var3 = new HashMap<>(this.savedChunks);

            for (Entry var5 : this.infos.entrySet()) {
               SignalDebugModule.Info var6 = (SignalDebugModule.Info)var5.getValue();
               var3.put((Long)var5.getKey(), new double[]{var6.sig, var6.expected, var6.events, var6.ownMs});
            }

            int var9 = 0;

            for (Entry var14 : var3.entrySet()) {
               if (var9++ > 4000) {
                  break;
               }

               double[] var7 = (double[])var14.getValue();
               var2.append("C|")
                  .append(var14.getKey())
                  .append('|')
                  .append((long)var7[0])
                  .append('|')
                  .append(var7[1])
                  .append('|')
                  .append((long)var7[2])
                  .append('|')
                  .append((long)var7[3])
                  .append('\n');
            }

            for (Entry var15 : this.history.entrySet()) {
               SignalDebugModule.Hist var17 = (SignalDebugModule.Hist)var15.getValue();
               var2.append("H|")
                  .append(var15.getKey())
                  .append('|')
                  .append(var17.tampered ? 1 : 0)
                  .append('|')
                  .append(var17.total)
                  .append('|')
                  .append(var17.first)
                  .append('|')
                  .append(var17.last)
                  .append('|')
                  .append(var17.head.replace('|', '/'))
                  .append('|')
                  .append(var17.sub.replace('|', '/').replace('\n', ' '))
                  .append('\n');
            }

            for (Entry var16 : this.afk.entrySet()) {
               SignalDebugModule.Afk var18 = (SignalDebugModule.Afk)var16.getValue();
               var2.append("A|")
                  .append((String)var16.getKey())
                  .append('|')
                  .append(var18.name.replace('|', '_'))
                  .append('|')
                  .append(var18.x)
                  .append('|')
                  .append(var18.y)
                  .append('|')
                  .append(var18.z)
                  .append('|')
                  .append(var18.yaw)
                  .append('|')
                  .append(var18.pitch)
                  .append('|')
                  .append(var18.sinceMs)
                  .append('|')
                  .append(var18.seenMs)
                  .append('\n');
            }

            File var13 = this.store(var1);
            var13.getParentFile().mkdirs();
            Files.write(var13.toPath(), var2.toString().getBytes(StandardCharsets.UTF_8));
         } catch (Throwable var8) {
         }
      }
   }

   @EventHandler
   public void onRender3D(Render3DEvent var1) {
      List var2 = this.shown;
      List var3 = this.historyShown;
      if (!var2.isEmpty() || !var3.isEmpty()) {
         FoglessLines var4 = FoglessLines.forFrame(var1.partialTick);
         double var5 = this.pillarTop.get();
         double var7 = -64.0;
         class_310 var9 = class_310.method_1551();
         double var10 = var9.field_1724 != null ? var9.field_1724.method_23318() : 64.0;
         double var12 = Math.max(var7, Math.min(var5, Math.floor(var10 / 16.0) * 16.0));

         for (Long var15 : var3) {
            SignalDebugModule.Hist var16 = this.history.get(var15);
            if (var16 != null) {
               int var17 = (var16.tampered ? this.tamperedColor : this.naturalColor).argb();
               int var18 = var17 & 16777215 | 1711276032;
               double var19 = chunkX(var15) * 16.0;
               double var21 = var19 + 16.0;
               double var23 = chunkZ(var15) * 16.0;
               double var25 = var23 + 16.0;
               edges(var4, var19, var21, var23, var25, var7, var5, var18, 1.5F);
               ring(var4, var19, var21, var23, var25, var12, var18, 1.5F);
               if (var16.tampered) {
                  var4.line(var19, var12, var23, var21, var12, var25, var18, 1.5F);
                  var4.line(var21, var12, var23, var19, var12, var25, var18, 1.5F);
               }
            }
         }

         for (SignalDebugModule.Shown var32 : var2) {
            int var33 = (var32.tampered() ? this.tamperedColor : this.naturalColor).argb();
            double var34 = var32.chunkX() * 16.0;
            double var35 = var34 + 16.0;
            double var36 = var32.chunkZ() * 16.0;
            double var37 = var36 + 16.0;
            if (!var32.tampered()) {
               float var39 = 1.5F;
               edges(var4, var34, var35, var36, var37, var7, var5, var33, var39);
               ring(var4, var34, var35, var36, var37, var7, var33, var39);
               ring(var4, var34, var35, var36, var37, var5, var33, var39);
               ring(var4, var34, var35, var36, var37, var12, var33, var39);
            } else {
               float var38 = 3.0F;
               edges(var4, var34, var35, var36, var37, var7, var5, var33, var38);
               edges(var4, var34 + 4.0, var35 - 4.0, var36 + 4.0, var37 - 4.0, var7, var5, var33, 1.5F);

               for (double var29 : new double[]{var7, var12, var5}) {
                  ring(var4, var34, var35, var36, var37, var29, var33, var38);
                  var4.line(var34, var29, var36, var35, var29, var37, var33, 2.0F);
                  var4.line(var35, var29, var36, var34, var29, var37, var33, 2.0F);
               }
            }
         }
      }
   }

   private static void edges(FoglessLines var0, double var1, double var3, double var5, double var7, double var9, double var11, int var13, float var14) {
      var0.line(var1, var9, var5, var1, var11, var5, var13, var14);
      var0.line(var3, var9, var5, var3, var11, var5, var13, var14);
      var0.line(var1, var9, var7, var1, var11, var7, var13, var14);
      var0.line(var3, var9, var7, var3, var11, var7, var13, var14);
   }

   private static void ring(FoglessLines var0, double var1, double var3, double var5, double var7, double var9, int var11, float var12) {
      var0.line(var1, var9, var5, var3, var9, var5, var11, var12);
      var0.line(var3, var9, var5, var3, var9, var7, var11, var12);
      var0.line(var3, var9, var7, var1, var9, var7, var11, var12);
      var0.line(var1, var9, var7, var1, var9, var5, var11, var12);
   }

   public boolean isHudOverlayEnabled() {
      return this.isEnabled() && (!this.shown.isEmpty() || !this.historyShown.isEmpty());
   }

   private static int abgr(int var0) {
      return var0 & -16711936 | (var0 & 0xFF) << 16 | var0 >> 16 & 0xFF;
   }

   private static String span(long var0) {
      long var2 = var0 / 60000L;
      if (var2 < 1L) {
         return "<1m";
      } else {
         return var2 < 60L ? var2 + "m" : var2 / 60L + "h" + String.format("%02d", var2 % 60L) + "m";
      }
   }

   public void renderHudOverlay(ImDrawList var1, float var2, float var3) {
      List var4 = this.shown;
      class_310 var5 = class_310.method_1551();
      if (var5.field_1724 != null) {
         double var6 = var5.field_1724.method_23318();
         class_243 var8 = var5.field_1724.method_73189();
         long var9 = System.currentTimeMillis();

         for (Long var12 : this.historyShown) {
            SignalDebugModule.Hist var13 = this.history.get(var12);
            if (var13 != null) {
               double var14 = chunkX(var12) * 16.0 + 8.0;
               double var16 = chunkZ(var12) * 16.0 + 8.0;
               float[] var18 = WorldLabel.project(var5, new class_243(var14, var6 + 3.0, var16), var2, var3);
               if (var18 != null) {
                  double var19 = Math.sqrt((var14 - var8.field_1352) * (var14 - var8.field_1352) + (var16 - var8.field_1350) * (var16 - var8.field_1350));
                  String var21 = "Chunk "
                     + chunkX(var12)
                     + ", "
                     + chunkZ(var12)
                     + "  "
                     + Math.round(var19)
                     + "m  | seen "
                     + span(var9 - var13.last)
                     + " ago, flagged for "
                     + span(Math.max(0L, var13.last - var13.first))
                     + (var13.sub.isEmpty() ? "" : "  | " + var13.sub);
                  WorldLabel.drawText(var1, var18[0], var18[1], "[REMEMBERED] " + var13.head, var21, 0.6F);
               }
            }
         }

         for (SignalDebugModule.Shown var27 : var4) {
            double var28 = var27.chunkX() * 16.0 + 8.0;
            double var15 = var27.chunkZ() * 16.0 + 8.0;
            float[] var17 = WorldLabel.project(var5, new class_243(var28, var6 + 3.0, var15), var2, var3);
            if (var17 != null) {
               double var29 = Math.sqrt((var28 - var8.field_1352) * (var28 - var8.field_1352) + (var15 - var8.field_1350) * (var15 - var8.field_1350));
               String var20 = label(var27);
               StringBuilder var30 = new StringBuilder("Chunk " + var27.chunkX() + ", " + var27.chunkZ() + "  " + Math.round(var29) + "m");
               if (var27.tampered()) {
                  if (var27.natural() > 0) {
                     var30.append("  | visible ").append(var27.natural());
                  }

                  if (var27.why() != null) {
                     var30.append("  | ").append(var27.why());
                  }
               }

               float var22 = 20.0F;
               if (this.showIcon.get()) {
                  float var23 = var17[0] - var22 / 2.0F;
                  float var24 = var17[1] - var22 - 4.0F;
                  if (var27.tampered()) {
                     int var25 = abgr(this.tamperedColor.argb());
                     var1.addCircleFilled(var17[0], var24 + var22 / 2.0F, var22 * 0.78F, var25 & 16777215 | 1426063360);
                     var1.addCircle(var17[0], var24 + var22 / 2.0F, var22 * 0.78F, var25, 0, 2.5F);
                  } else {
                     int var31 = abgr(this.naturalColor.argb());
                     var1.addRect(var23 - 2.0F, var24 - 2.0F, var23 + var22 + 2.0F, var24 + var22 + 2.0F, var31, 4.0F);
                  }

                  SignalIcon.cluster(var1, var23, var24, var22);
               }

               WorldLabel.drawText(var1, var17[0], var17[1], var20, var30.toString(), 1.0F);
            }
         }
      }
   }

   private static long blockKey(int var0, int var1, int var2) {
      return class_2338.method_10064(var0, var1, var2);
   }

   private static long chunkKey(int var0, int var1) {
      return var0 & 4294967295L | (var1 & 4294967295L) << 32;
   }

   private static int chunkX(long var0) {
      return (int)(var0 & 4294967295L);
   }

   private static int chunkZ(long var0) {
      return (int)(var0 >>> 32);
   }

   private static long chunkOfBlock(long var0) {
      int var2 = (int)(var0 >> 38);
      int var3 = (int)(var0 << 26 >> 38);
      return chunkKey(var2 >> 4, var3 >> 4);
   }

   private static final class Afk {
      String name = "player";
      double x;
      double y;
      double z;
      float yaw;
      float pitch;
      long sinceMs;
      long seenMs;
   }

   private static final class Hist {
      boolean tampered;
      int total;
      String head = "";
      String sub = "";
      long first;
      long last;
   }

   private static final class Info {
      long sig;
      double expected;
      int events;
      int lastGrowths;
      int lastHidden;
      long ownMs;
   }

   private record Shown(
      int chunkX,
      int chunkZ,
      boolean tampered,
      int total,
      int natural,
      int light,
      int revealed,
      int shell,
      int orphan,
      int noBud,
      int stuckBuds,
      int openFaces,
      String why,
      String afkName,
      long afkMs
   ) {
      long key() {
         return SignalDebugModule.chunkKey(this.chunkX, this.chunkZ);
      }

      int hiddenTotal() {
         return this.light + this.revealed + this.shell + this.orphan + this.noBud;
      }
   }
}
