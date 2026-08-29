package dev.soatick.core;

import dev.soatick.config.SoaConfig;

/**
 * 客户端 SoA 存储。
 *
 * 客户端实体的 tick 与渲染都发生在客户端主（渲染）线程上，
 * 因此本类同样是线程封闭的——写透 Mixin 与渲染剔除 Pass
 * 不会产生任何数据竞争。
 *
 * 额外保障：客户端断线时（ClientPlayConnectionEvents.DISCONNECT）
 * 调用 {@link #reset()}，避免旧服务器的实体对象引用滞留在
 * entities[] 中造成内存驻留。
 */
public final class ClientSoaStore extends SoaStore {

        private static ClientSoaStore instance;

        private ClientSoaStore(int capacity) {
                super(capacity);
        }

        /** 仅在客户端主线程调用（实体 tick / 渲染 Pass），无需加锁 */
        public static ClientSoaStore get() {
                ClientSoaStore s = instance;
                if (s == null) {
                        instance = s = new ClientSoaStore(SoaConfig.get().clientMaxSlots);
                }
                return s;
        }

        /** 断线时调用：清空全部槽位与实体引用 */
        public static void reset() {
                if (instance != null) {
                        instance.clearAll();
                }
        }
}
