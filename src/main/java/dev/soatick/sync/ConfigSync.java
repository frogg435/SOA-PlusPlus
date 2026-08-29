package dev.soatick.sync;

import dev.soatick.SoaTick;
import dev.soatick.config.SoaConfig;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/**
 * 服务端→客户端 配置同步通道（soatick:sync）。
 *
 * 服务端在玩家加入与 /soa reload 后，把「分环调度相关」的阈值与分母
 * 推给客户端——客户端位置平滑的重插值步数因此与真实更新间隔对齐
 * （单人模式同进程天然精确；多人模式从此不再靠本地配置猜）。
 *
 * 协议：1 字节版本号 + 1 字节 gating + 3 double 距离 + 4 varint 分母。
 * 客户端接收器在 SoaTickClient 注册（netty 线程回调 → client.execute 跳主线程）。
 */
public final class ConfigSync {

        private ConfigSync() {}

        public static final Identifier CHANNEL = new Identifier("soatick", "sync");
        private static final byte VERSION = 1;

        /** 服务端侧：注册加入事件（主入口调用；集成服务器同样生效，单机自同步） */
        public static void registerServer() {
                ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                                sendTo(handler.player));
        }

        /** 服务端侧：/soa reload 后全量广播 */
        public static void broadcast(MinecraftServer server) {
                for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                        sendTo(p);
                }
        }

        public static void sendTo(ServerPlayerEntity player) {
                SoaConfig cfg = SoaConfig.get();
                PacketByteBuf buf = PacketByteBufs.create();
                buf.writeByte(VERSION);
                buf.writeBoolean(cfg.enabled && cfg.serverGating);
                buf.writeDouble(cfg.nearDistance);
                buf.writeDouble(cfg.midDistance);
                buf.writeDouble(cfg.farDistance);
                buf.writeVarInt(cfg.nearDivisor);
                buf.writeVarInt(cfg.midDivisor);
                buf.writeVarInt(cfg.farDivisor);
                buf.writeVarInt(cfg.beyondDivisor);
                ServerPlayNetworking.send(player, CHANNEL, buf);
                SoaTick.LOGGER.debug("[{}] 已向 {} 同步调度配置",
                                SoaTick.MOD_NAME, player.getName().getString());
        }
}
