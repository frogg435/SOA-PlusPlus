package dev.soatick.command;

import dev.soatick.client.ClientSoaPass;
import dev.soatick.server.ServerSoaScheduler;
import dev.soatick.config.SoaConfig;
import dev.soatick.core.ClientSoaStore;
import dev.soatick.core.ServerSoaStore;
import dev.soatick.core.SoaFlags;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

/**
 * /soa 命令：stats（实时统计） 与 reload（热重载配置）。
 *
 * 权限等级 2（OP）。统计同时覆盖服务端调度与客户端剔除
 * （集成服务器 = 单人游戏时两者都有；专用服务器只有前者）。
 */
public final class SoaCommands {

        private SoaCommands() {}

        public static void register() {
                CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                                dispatcher.register(CommandManager.literal("soa")
                                                .requires(source -> source.hasPermissionLevel(2))
                                                .then(CommandManager.literal("stats").executes(ctx -> {
                                                        sendStats(ctx.getSource());
                                                        return 1;
                                                }))
                                                .then(CommandManager.literal("reload").executes(ctx -> {
                                                        SoaConfig.reload();
                                                        ctx.getSource().sendFeedback(
                                                                        () -> Text.translatable("soatick.cmd.reload"), false);
                                                        return 1;
                                                }))));
        }

        private static void sendStats(ServerCommandSource source) {
                SoaConfig cfg = SoaConfig.get();
                ServerSoaStore ss = ServerSoaStore.get();

                StringBuilder sb = new StringBuilder(256);
                sb.append("[SoA Tick] ").append(tr("soatick.cmd.stats.header")).append('\n');
                sb.append("  ").append(tr(cfg.enabled
                                ? "soatick.cmd.stats.on" : "soatick.cmd.stats.off")).append('\n');

                // 服务端
                sb.append("  ").append(String.format(tr("soatick.cmd.stats.slots"),
                                ss.occupiedCount, ss.capacity)).append('\n');

                // 实时扫描 SoA 列数组：命令本就在服务端线程执行，读数组与调度器无竞争。
                // 修复：此前直接读调度器的 ringCounts（它只是“最后一个 tick 的维度快照”，
                // 会被后续维度覆盖，多维度服务器上恒显示最后一个维度/全 0）。
                int[] rc = new int[4];
                java.util.LinkedHashMap<String, int[]> byDim = new java.util.LinkedHashMap<>();
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
