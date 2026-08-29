package dev.soatick.client.gui;

import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.soatick.SoaTick;

/**
 * ModMenu 集成入口（fabric.mod.json 的 "modmenu" entrypoint）。
 *
 * 仅当玩家安装了 ModMenu 时本类才会被加载；配置界面本体在
 * {@link SoaConfigScreen}（Cloth Config 实现，运行时需要 cloth-config2）。
 * 两者都缺失时本 Mod 一切功能照常，只是没有图形配置界面。
 */
public class ModMenuIntegration implements ModMenuApi {

        @Override
        public com.terraformersmc.modmenu.api.ConfigScreenFactory<?> getModConfigScreenFactory() {
                // 返回前检查 cloth-config2 是否在场；缺失时返回 null（无 GUI 入口）
                if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("cloth-config2")) {
                        SoaTick.LOGGER.info("[{}] 检测到 ModMenu 但缺少 Cloth Config，图形配置不可用（可直接编辑 config/soatick.json）",
                                        SoaTick.MOD_NAME);
                        return screen -> null;
                }
                return screen -> SoaConfigScreen.build(screen);
        }
}
