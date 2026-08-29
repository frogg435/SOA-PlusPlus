package dev.soatick.client.gui;

import dev.soatick.config.SoaConfig;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * SoA++ 图形配置界面（Cloth Config 实现，ModMenu「配置」按钮进入）。
 *
 * 布局：全局 / 服务端 / 客户端 三个分类；保存时写回 SoaConfig 单例并落盘。
 * 槽位容量等「重启生效」参数有意不放进 GUI，避免误改后不生效造成困惑
 * （JSON 里仍然可改，README 有说明）。
 */
public final class SoaConfigScreen {

        private SoaConfigScreen() {}

        public static Screen build(Screen parent) {
                SoaConfig cfg = SoaConfig.get();
                ConfigBuilder builder = ConfigBuilder.create()
                                .setParentScreen(parent)
                                .setTitle(Text.literal("SOA++ 配置"))
                                .setSavingRunnable(() -> SoaConfig.save());

                ConfigEntryBuilder eb = builder.entryBuilder();

                // ==================== 全局 ====================
                ConfigCategory global = builder.getOrCreateCategory(Text.literal("全局"));
                global.addEntry(eb.startBooleanToggle(Text.literal("总开关 (enabled)"), cfg.enabled)
                                .setDefaultValue(true)
                                .setTooltip(Text.literal("关闭后所有 Mixin 钩子退化为空操作"))
                                .setSaveConsumer(v -> cfg.enabled = v)
                                .build());

                // ==================== 服务端 ====================
                ConfigCategory server = builder.getOrCreateCategory(Text.literal("服务端 · 距离分环降频"));
                server.addEntry(eb.startBooleanToggle(Text.literal("分环调度 (serverGating)"), cfg.serverGating)
                                .setDefaultValue(true).setSaveConsumer(v -> cfg.serverGating = v).build());
                server.addEntry(eb.startDoubleField(Text.literal("近环边界（格，满速）"), cfg.nearDistance)
                                .setDefaultValue(32.0D).setMin(8.0D).setMax(256.0D)
                                .setSaveConsumer(v -> cfg.nearDistance = v).build());
                server.addEntry(eb.startDoubleField(Text.literal("中环边界（格，1/2 速）"), cfg.midDistance)
                                .setDefaultValue(64.0D).setMin(16.0D).setMax(384.0D)
                                .setSaveConsumer(v -> cfg.midDistance = v).build());
                server.addEntry(eb.startDoubleField(Text.literal("远环边界（格，1/4 速）"), cfg.farDistance)
                                .setDefaultValue(128.0D).setMin(32.0D).setMax(512.0D)
                                .setSaveConsumer(v -> cfg.farDistance = v).build());

                server.addEntry(eb.startBooleanToggle(Text.literal("物品硬跳过（远环起）"), cfg.itemHardSkipFromRing >= 0)
                                .setDefaultValue(true)
                                .setSaveConsumer(v -> cfg.itemHardSkipFromRing = v ? 2 : -1).build());
                server.addEntry(eb.startBooleanToggle(Text.literal("AI 降级档位（远环起）"), cfg.aiDegradeFromRing >= 0)
                                .setDefaultValue(true)
                                .setSaveConsumer(v -> cfg.aiDegradeFromRing = v ? 2 : -1).build());
                server.addEntry(eb.startBooleanToggle(Text.literal("推挤碰撞跳过（远环起）"), cfg.pushSkipFromRing >= 0)
                                .setDefaultValue(true)
                                .setSaveConsumer(v -> cfg.pushSkipFromRing = v ? 2 : -1).build());
                server.addEntry(eb.startBooleanToggle(Text.literal("远景掉落物自动合并"), cfg.itemMerge)
                                .setDefaultValue(true).setSaveConsumer(v -> cfg.itemMerge = v).build());
                server.addEntry(eb.startDoubleField(Text.literal("掉落物合并半径（格）"), cfg.itemMergeRadius)
                                .setDefaultValue(2.0D).setMin(0.5D).setMax(8.0D)
                                .setSaveConsumer(v -> cfg.itemMergeRadius = v).build());

                server.addEntry(eb.startBooleanToggle(Text.literal("Boss 豁免"), cfg.exemptBosses)
                                .setDefaultValue(true).setSaveConsumer(v -> cfg.exemptBosses = v).build());
                server.addEntry(eb.startBooleanToggle(Text.literal("名牌实体豁免"), cfg.exemptNamed)
                                .setDefaultValue(true).setSaveConsumer(v -> cfg.exemptNamed = v).build());
                server.addEntry(eb.startBooleanToggle(Text.literal("拴绳生物豁免"), cfg.exemptLeashed)
                                .setDefaultValue(true).setSaveConsumer(v -> cfg.exemptLeashed = v).build());
                server.addEntry(eb.startBooleanToggle(Text.literal("载具与乘客豁免"), cfg.exemptVehiclesAndPassengers)
                                .setDefaultValue(true).setSaveConsumer(v -> cfg.exemptVehiclesAndPassengers = v).build());

                // ==================== 客户端 ====================
                ConfigCategory client = builder.getOrCreateCategory(Text.literal("客户端 · 渲染与观感"));
                client.addEntry(eb.startBooleanToggle(Text.literal("距离渲染剔除"), cfg.renderCulling)
                                .setDefaultValue(true).setSaveConsumer(v -> cfg.renderCulling = v).build());
                client.addEntry(eb.startDoubleField(Text.literal("生物渲染距离（格，0=不限）"), cfg.livingRenderDistance)
                                .setDefaultValue(128.0D).setMin(0.0D).setMax(512.0D)
                                .setSaveConsumer(v -> cfg.livingRenderDistance = v).build());
                client.addEntry(eb.startDoubleField(Text.literal("掉落物渲染距离"), cfg.itemRenderDistance)
                                .setDefaultValue(48.0D).setMin(0.0D).setMax(256.0D)
                                .setSaveConsumer(v -> cfg.itemRenderDistance = v).build());
                client.addEntry(eb.startDoubleField(Text.literal("经验球渲染距离"), cfg.xpOrbRenderDistance)
                                .setDefaultValue(40.0D).setMin(0.0D).setMax(256.0D)
                                .setSaveConsumer(v -> cfg.xpOrbRenderDistance = v).build());
                client.addEntry(eb.startDoubleField(Text.literal("投射物渲染距离"), cfg.projectileRenderDistance)
                                .setDefaultValue(64.0D).setMin(0.0D).setMax(256.0D)
                                .setSaveConsumer(v -> cfg.projectileRenderDistance = v).build());
                client.addEntry(eb.startDoubleField(Text.literal("杂项渲染距离"), cfg.miscRenderDistance)
                                .setDefaultValue(96.0D).setMin(0.0D).setMax(256.0D)
                                .setSaveConsumer(v -> cfg.miscRenderDistance = v).build());
                client.addEntry(eb.startDoubleField(Text.literal("迟滞缓冲带（格）"), cfg.hysteresisBlocks)
                                .setDefaultValue(8.0D).setMin(0.0D).setMax(32.0D)
                                .setSaveConsumer(v -> cfg.hysteresisBlocks = v).build());

                client.addEntry(eb.startBooleanToggle(Text.literal("降频实体位置平滑"), cfg.smoothDegrade)
                                .setDefaultValue(true)
                                .setTooltip(Text.literal("消除远处怪「滑行-冻结」的量子移动观感"))
                                .setSaveConsumer(v -> cfg.smoothDegrade = v).build());
                client.addEntry(eb.startBooleanToggle(Text.literal("遮挡剔除（被墙挡住不渲染）"), cfg.occlusionCulling)
                                .setDefaultValue(true).setSaveConsumer(v -> cfg.occlusionCulling = v).build());
                client.addEntry(eb.startDoubleField(Text.literal("遮挡检测最近距离（格）"), cfg.occlusionMinDistance)
                                .setDefaultValue(48.0D).setMin(8.0D).setMax(128.0D)
                                .setSaveConsumer(v -> cfg.occlusionMinDistance = v).build());
                client.addEntry(eb.startBooleanToggle(Text.literal("LOD：远处不渲染名牌"), cfg.lodNametags)
                                .setDefaultValue(true).setSaveConsumer(v -> cfg.lodNametags = v).build());
                client.addEntry(eb.startBooleanToggle(Text.literal("LOD：远处不渲染影子"), cfg.lodShadows)
                                .setDefaultValue(true).setSaveConsumer(v -> cfg.lodShadows = v).build());

                return builder.build();
        }
}
