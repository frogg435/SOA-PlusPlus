package dev.soatick.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.soatick.client.ClientSoaPass;
import dev.soatick.server.ServerSoaScheduler;
import dev.soatick.config.SoaConfig;
import dev.soatick.core.ClientSoaStore;
import dev.soatick.core.ServerSoaStore;
import dev.soatick.core.SoaFlags;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * /soa 命令族（权限等级 2 = OP）：
 * - stats   实时统计
 * - reload  热重载配置
 * - top     实体类型数量排行（定位刷怪泛滥）
 * - toggle  实时开关某功能并写回配置
 * - ring    查询单个实体的调度状态
 */
public final class SoaCommands {

        private SoaCommands() {}

        private record Feature(String key, java.util.function.BooleanSupplier get,
                               java.util.function.Consumer<Boolean> set, boolean isRingThreshold) {}

        private static final List<Feature> FEATURES = List.of(
                new Feature("serverGating", () -> SoaConfig.get().serverGating,
                                v -> SoaConfig.get().serverGating = v, false),
                new Feature("itemHardSkip", () -> SoaConfig.get().itemHardSkipFromRing >= 0,
                                v -> SoaConfig.get().itemHardSkipFromRing = v ? 2 : -1, true),
                new Feature("aiDegrade", () -> SoaConfig.get().aiDegradeFromRing >= 0,
                                v -> SoaConfig.get().aiDegradeFromRing = v ? 2 : -1, true),
                new Feature("pushSkip", () -> SoaConfig.get().pushSkipFromRing >= 0,
                                v -> SoaConfig.get().pushSkipFromRing = v ? 2 : -1, true),
                new Feature("itemMerge", () -> SoaConfig.get().itemMerge,
                                v -> SoaConfig.get().itemMerge = v, false),
                new Feature("renderCulling", () -> SoaConfig.get().renderCulling,
                                v -> SoaConfig.get().renderCulling = v, false),
                new Feature("smoothDegrade", () -> SoaConfig.get().smoothDegrade,
                                v -> SoaConfig.get().smoothDegrade = v, false),
                new Feature("occlusionCulling", () -> SoaConfig.get().occlusionCulling,
                                v -> SoaConfig.get().occlusionCulling = v, false),
                new Feature("lodNametags", () -> SoaConfig.get().lodNametags,
                                v -> SoaConfig.get().lodNametags = v, false),
                new Feature("lodShadows", () -> SoaConfig.get().lodShadows,
                                v -> SoaConfig.get().lodShadows = v, false));

        public static void register() {
                CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
                        LiteralArgumentBuilder<ServerCommandSource> root =
                                        CommandManager.literal("soa")
                                                        .requires(source -> source.hasPermissionLevel(2));

                        root.then(CommandManager.literal("stats").executes(ctx -> {
                                sendStats(ctx.getSource());
                                return 1;
                        }));
                        root.then(CommandManager.literal("reload").executes(ctx -> {
                                SoaConfig.reload();
                                dev.soatick.sync.ConfigSync.broadcast(ctx.getSource().getServer());
                                ctx.getSource().sendFeedback(
                                                () -> Text.translatable("soatick.cmd.reload"), false);
                                return 1;
                        }));
                        root.then(CommandManager.literal("top").executes(ctx -> {
                                sendTop(ctx.getSource());
                                return 1;
                        }));

                        for (Feature f : FEATURES) {
                                root.then(CommandManager.literal("toggle")
                                                .then(CommandManager.literal(f.key()).executes(ctx -> {
                                                        boolean now = !f.get().getAsBoolean();
                                                        f.set.accept(now);
                                                        SoaConfig.save();
                                                        ctx.getSource().sendFeedback(() -> Text.literal(
                                                                        String.format(tr("soatick.cmd.toggle"),
                                                                                        f.key(), now ? "ON" : "OFF")), false);
                                                        return 1;
                                                })));
                        }

                        root.then(CommandManager.literal("ring")
                                        .then(CommandManager.argument("entity", EntityArgumentType.entity())
                                                        .executes(ctx -> {
                                                                showRing(ctx.getSource(),
                                                                                EntityArgumentType.getEntity(ctx, "entity"));
                                                                return 1;
                                                        })));

                        dispatcher.register(root);
                });
        }

        // ===================== /soa top：类型数量排行 =====================

        private static void sendTop(ServerCommandSource source) {
                ServerSoaStore ss = ServerSoaStore.get();
                Map<String, int[]> byType = new LinkedHashMap<>();
                for (int k = 0; k < ss.occupiedCount; k++) {
                        int s = ss.occupied[k];
                        if ((ss.flags[s] & SoaFlags.ALIVE) == 0) continue;
                        Entity e = ss.entities[s];
                        if (e == null || e.isRemoved()) continue;
                        String id = EntityType.getId(e.getType()).toString();
                        int[] arr = byType.computeIfAbsent(id, x -> new int[4]);
                        arr[ss.ring[s]]++;
                }
                List<Map.Entry<String, int[]>> sorted = new ArrayList<>(byType.entrySet());
                sorted.sort(Comparator.comparingInt((Map.Entry<String, int[]> en) ->
                                en.getValue()[0] + en.getValue()[1] + en.getValue()[2] + en.getValue()[3]).reversed());

                StringBuilder sb = new StringBuilder(256);
                sb.append("[SoA Tick] ").append(String.format(tr("soatick.cmd.top.header"), byType.size())).append('\n');
                int shown = 0;
                for (var en : sorted) {
                        if (shown++ >= 10) break;
                        int[] a = en.getValue();
                        sb.append("  ").append(String.format(tr("soatick.cmd.top.line"),
                                        en.getKey(), a[0] + a[1] + a[2] + a[3],
                                        a[0], a[1], a[2], a[3])).append('\n');
                }
                source.sendFeedback(() -> Text.literal(sb.toString()), false);
        }

        // ===================== /soa ring：单实体状态 =====================

        private static void showRing(ServerCommandSource source, Entity entity) {
                int s = ((dev.soatick.core.SoaDuck) entity).soatick$getSlot();
                if (s < 0) {
                        source.sendFeedback(() -> Text.literal(
                                        "[SoA Tick] " + tr("soatick.cmd.ring.untracked")), false);
                        return;
                }
                ServerSoaStore ss = ServerSoaStore.get();
                byte ring = ss.ring[s];
                String ringName = switch (ring) {
                        case SoaFlags.RING_NEAR -> "NEAR";
                        case SoaFlags.RING_MID -> "MID";
                        case SoaFlags.RING_FAR -> "FAR";
                        default -> "BEYOND";
                };
                byte ov = ((dev.soatick.core.SoaDuck) entity).soatick$getRuleOverride();
                String ovName = ov == 0 ? "exempt" : ov == 1 ? "half" : ov == 2 ? "quarter"
                                : ov == 3 ? "eighth" : "-";
                String msg = String.format(tr("soatick.cmd.ring.line"),
                                s, ringName, ss.distSqNearestPlayer[s], ovName,
                                ServerSoaScheduler.totalSkipped, ServerSoaScheduler.totalItemHardSkipped);
                source.sendFeedback(() -> Text.literal("[SoA Tick] " + msg), false);
        }

        // ===================== /soa stats =====================

        private static void sendStats(ServerCommandSource source) {
                SoaConfig cfg = SoaConfig.get();
                ServerSoaStore ss = ServerSoaStore.get();

                StringBuilder sb = new StringBuilder(256);
                sb.append("[SoA Tick] ").append(tr("soatick.cmd.stats.header")).append('\n');
                sb.append("  ").append(tr(cfg.enabled
                                ? "soatick.cmd.stats.on" : "soatick.cmd.stats.off")).append('\n');

                sb.append("  ").append(String.format(tr("soatick.cmd.stats.slots"),
                                ss.occupiedCount, ss.capacity)).append('\n');

                // 实时扫描 SoA 列数组：命令本就在服务端线程执行，读数组与调度器无竞争。
                int[] rc = new int[4];
                LinkedHashMap<String, int[]> byDim = new LinkedHashMap<>();
                for (int k = 0; k < ss.occupiedCount; k++) {
                        int s = ss.occupied[k];
                        if ((ss.flags[s] & SoaFlags.ALIVE) == 0) continue;
                        int r = ss.ring[s];
                        rc[r]++;
                        String dn = dimName(ss.dims[s]);
                        int[] arr = byDim.computeIfAbsent(dn, x -> new int[4]);
                        arr[r]++;
                }
                sb.append("  ").append(String.format(tr("soatick.cmd.stats.ring"),
                                rc[SoaFlags.RING_NEAR], rc[SoaFlags.RING_MID],
                                rc[SoaFlags.RING_FAR], rc[SoaFlags.RING_BEYOND])).append('\n');
                for (var e : byDim.entrySet()) {
                        int[] a = e.getValue();
                        sb.append("    ").append(String.format(tr("soatick.cmd.stats.dim"),
                                        e.getKey(), a[0], a[1], a[2], a[3])).append('\n');
                }
                sb.append("  ").append(String.format(tr("soatick.cmd.stats.gate"),
                                ServerSoaScheduler.totalSkipped, ServerSoaScheduler.totalTicked)).append('\n');
                sb.append("  ").append(String.format(tr("soatick.cmd.stats.pass"),
                                ServerSoaScheduler.avgPassMicros())).append('\n');
                sb.append("  ").append(String.format(tr("soatick.cmd.stats.reuse"),
                                ServerSoaScheduler.ringReuses)).append('\n');
                sb.append("  ").append(String.format(tr("soatick.cmd.stats.adv"),
                                ServerSoaScheduler.totalAiDegraded,
                                ServerSoaScheduler.totalItemHardSkipped,
                                ServerSoaScheduler.totalPushSkipped,
                                ServerSoaScheduler.totalMerges)).append('\n');

                // 客户端（仅单人/客户端环境）
                if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
                        ClientSoaStore cs = ClientSoaStore.get();
                        sb.append("  ").append(String.format(tr("soatick.cmd.stats.client"),
                                        cs.occupiedCount, cs.capacity,
                                        ClientSoaPass.lastCulled, ClientSoaPass.lastTotal,
                                        ClientSoaPass.lastPassNanos / 1000.0D)).append('\n');
                }

                source.sendFeedback(() -> Text.literal(sb.toString()), false);
        }

        /** 服务端侧解析翻译键（集成服务器共享客户端语言，专用服务器回退 en_us） */
        private static String tr(String key) {
                return Text.translatable(key).getString();
        }

        /** 维度键美化：ResourceKey<World> → "minecraft:overworld" */
        private static String dimName(Object dimKey) {
                if (dimKey instanceof net.minecraft.registry.RegistryKey<?> rk) {
                        return rk.getValue().toString();
                }
                return String.valueOf(dimKey);
        }
}
