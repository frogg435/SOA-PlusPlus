package dev.soatick;

import dev.soatick.core.ClientSoaStore;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

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
                ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
                                ClientSoaStore.reset());

                SoaTick.LOGGER.info("[{}] 客户端剔除模块就绪（分类距离 + 迟滞防闪烁）",
                                SoaTick.MOD_NAME);
        }
}
