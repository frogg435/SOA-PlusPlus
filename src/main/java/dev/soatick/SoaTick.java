package dev.soatick;

import dev.soatick.command.SoaCommands;
import dev.soatick.config.SoaConfig;
import dev.soatick.core.ServerSoaStore;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SoA Tick 主入口。
 *
 * 「数据导向」实体优化 Mod：
 * - 服务端：实体热字段镜像进 SoA 连续数组，距离分环错峰降频 tick；
 * - 客户端：分类渲染距离 + 迟滞防闪烁剔除，与 Sodium 正交互补；
 * - 移动端：纯逻辑零 shader、内存占用 ~3MB、默认参数按 ARM 调优，
 *   PojavLauncher 等移动启动器可直接使用。
 */
public class SoaTick implements ModInitializer {

        public static final String MOD_ID = "soatick";
        public static final String MOD_NAME = "SoA Tick";
        public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

        @Override
        public void onInitialize() {
                // 1) 配置先行：SoA 存储的单例容量在首次创建时读取配置
                SoaConfig cfg = SoaConfig.get();

                // 2) 生命周期：服务器启停时重建/回收服务端 SoA 存储
                //    （槽位号长在实体对象上，旧世界实体随服务器一起丢弃，重建是安全的）
                ServerLifecycleEvents.SERVER_STARTING.register(server -> {
                        ServerSoaStore.reset();
                        LOGGER.info("[{}] SoA 存储已就位：服务端槽位上限 {}，调度{}，渲染剔除{}",
                                        MOD_NAME, cfg.serverMaxSlots,
                                        cfg.serverGating ? "开" : "关",
                                        cfg.renderCulling ? "开" : "关");
                });
                ServerLifecycleEvents.SERVER_STOPPED.register(server -> ServerSoaStore.reset());

                dev.soatick.server.MsptTracker.register();

                // 3) 命令 /soa stats|reload|top|toggle|ring + 配置同步
                SoaCommands.register();
                dev.soatick.sync.ConfigSync.registerServer();

                // 4) 兼容性提示
                if (FabricLoader.getInstance().isModLoaded("sodium")) {
                        LOGGER.info("[{}] 检测到 Sodium：本 Mod 仅做实体级决策优化，" +
                                        "与 Sodium 的区块渲染管线完全正交，可放心共存", MOD_NAME);
                }
                LOGGER.info("[{}] 已加载 —— 数据导向实体优化 v{}", MOD_NAME, "0.1.0");
        }
}
