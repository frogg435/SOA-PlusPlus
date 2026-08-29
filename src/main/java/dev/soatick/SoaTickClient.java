package dev.soatick;

import dev.soatick.core.ClientSoaStore;
import dev.soatick.sync.ConfigSync;
import dev.soatick.sync.ServerSyncStore;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * 客户端入口。
 *
 * 职责很轻：只在断线时清空客户端 SoA 存储，
 * 防止上一台服务器的实体引用滞留在 entities[] 反查表里。
 * （实体对象本身随旧 ClientWorld 丢弃，槽位号随之作废，
 * 新连接的实体全部从 -1 重新分配。）
 */
public class SoaTickClient implements ClientModInitializer {

        @Override
        public void onInitializeClient() {
                ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
                        ClientSoaStore.reset();
                        ServerSyncStore.present = false;     // 断线后回退本地配置
                });

                // 接收服务端调度配置同步（netty 线程 → 主线程落地）
                ClientPlayNetworking.registerGlobalReceiver(ConfigSync.CHANNEL, (client, handler, buf, rs) -> {
                        buf.readByte();                              // 版本号（当前固定 1）
                        boolean gating = buf.readBoolean();
                        double n = buf.readDouble();
                        double m = buf.readDouble();
                        double f = buf.readDouble();
                        int dn = buf.readVarInt();
                        int dm = buf.readVarInt();
                        int df = buf.readVarInt();
                        int db = buf.readVarInt();
                        client.execute(() -> ServerSyncStore.apply(gating, n, m, f, dn, dm, df, db));
                });

                SoaTick.LOGGER.info("[{}] 客户端剔除模块就绪（分类距离 + 迟滞防闪烁 + 遮挡剔除）",
                                SoaTick.MOD_NAME);
        }
}
