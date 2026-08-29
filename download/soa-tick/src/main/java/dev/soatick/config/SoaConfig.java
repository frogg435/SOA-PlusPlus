package dev.soatick.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.soatick.core.SoaFlags;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 配置（config/soatick.json，Gson 直接序列化，零第三方依赖）。
 *
 * 设计原则：
 * - 所有默认值都按「PojavLauncher（ARM 大核）也能流畅运行」标定；
 * - 距离与降频分母全部可调，兼容各种玩法服务器；
 * - 任何非法值都会被 sanitize() 钳制到安全区间。
 */
public final class SoaConfig {

        private static final Logger LOGGER = LoggerFactory.getLogger("SoA Tick");
        private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
        private static final Path PATH = FabricLoader.getInstance()
                        .getConfigDir().resolve("soatick.json");

        private static SoaConfig instance = load();

        // ==================== 全局 ====================
        /** 总开关（关闭后所有 Mixin 钩子都变成空操作） */
        public boolean enabled = true;

        // ==================== 服务端：距离分环降频 ====================
        /** 服务端 Tick 调度开关 */
        public boolean serverGating = true;
        /** 近环边界（格）：≤此距离满速 tick */
        public double nearDistance = 32.0D;
        /** 中环边界（格）：≤此距离 1/2 速 */
        public double midDistance = 64.0D;
        /** 远环边界（格）：≤此距离 1/4 速，超过则 1/8 速 */
        public double farDistance = 128.0D;
        /** 近环降频分母（建议保持 1） */
        public int nearDivisor = 1;
        /** 中环降频分母（2 的幂） */
        public int midDivisor = 2;
        /** 远环降频分母（2 的幂） */
        public int farDivisor = 4;
        /** 极远环降频分母（2 的幂） */
        public int beyondDivisor = 8;

        /** Boss（凋灵/末影龙）豁免降频 */
        public boolean exemptBosses = true;
        /** 有自定义名牌的实体豁免降频（宠物、展示用怪） */
        public boolean exemptNamed = true;
        /** 被拴绳拴住的生物豁免降频 */
        public boolean exemptLeashed = true;
        /** 载具与乘客豁免降频（保证骑乘/矿车手感） */
        public boolean exemptVehiclesAndPassengers = true;

        // ==================== 服务端：进阶砍伐（v0.2） ====================
        /**
         * 物品硬跳过起始环（-1=关闭，0-3=从该环起生效，默认 2=远环）。
         * 生效环内的掉落物完全停止 tick（含合并/浮力），但消失计时按真实速率
         * 快进（每个被跳过的 tick +1 age），不会出现「远处掉落物永不消失」。
         */
        public int itemHardSkipFromRing = 2;
        /**
         * AI 降级起始环（-1=关闭，默认 2=远环）。
         * 生效环内的生物本 tick 放行但砍掉 AI（目标选择器/导航/Brain），
         * 保留物理与移动——比整体跳过更平滑，远处怪仍会走动但不再思考。
         */
        public int aiDegradeFromRing = 2;
        /**
         * 推挤碰撞跳过起始环（-1=关闭，默认 2=远环）。
         * 只要推挤双方任一方在生效环内，这对实体间的 push 交互直接取消——
         * 大型怪堆场景的 CPU 大头之一。
         */
        public int pushSkipFromRing = 2;
        /** 远景掉落物自动合并开关（配合物品硬跳过，防止远处实体数堆积） */
        public boolean itemMerge = true;
        /** 合并搜索半径（格）：同距离内同种掉落物会被吸入较大堆 */
        public double itemMergeRadius = 2.0D;

        /** 服务端 SoA 槽位上限（改动需重启服务器生效） */
        public int serverMaxSlots = 32768;

        // ==================== 客户端：分类渲染剔除 ====================
        /** 客户端渲染剔除开关 */
        public boolean renderCulling = true;
        /** 生物渲染距离上限（格），0 = 不限制 */
        public double livingRenderDistance = 128.0D;
        /** 掉落物渲染距离上限，0 = 不限制 */
        public double itemRenderDistance = 48.0D;
        /** 经验球渲染距离上限，0 = 不限制 */
        public double xpOrbRenderDistance = 40.0D;
        /** 投射物渲染距离上限，0 = 不限制 */
        public double projectileRenderDistance = 64.0D;
        /** 杂项（盔甲架/展示框/TNT/掉落方块/船/矿车）渲染距离上限，0 = 不限制 */
        public double miscRenderDistance = 96.0D;
        /** Boss 渲染距离上限，0 = 不限制（默认永远渲染） */
        public double bossRenderDistance = 0.0D;
        /** 迟滞缓冲带（格）：剔除边界两侧的缓冲，防止实体在临界距离忽隐忽现 */
        public double hysteresisBlocks = 8.0D;

        /** 客户端 SoA 槽位上限（改动需重启客户端生效） */
        public int clientMaxSlots = 16384;

        // ==================== 存取 ====================

        public static SoaConfig get() {
                return instance;
        }

        /** /soa reload：重新读盘并钳制 */
        public static void reload() {
                instance = load();
                LOGGER.info("[SoA Tick] 配置已重载（槽位上限等结构性参数需重启生效）");
        }

        private static SoaConfig load() {
                SoaConfig cfg = new SoaConfig();
                try {
                        if (Files.exists(PATH)) {
                                SoaConfig read = GSON.fromJson(
                                                Files.readString(PATH, StandardCharsets.UTF_8), SoaConfig.class);
                                if (read != null) cfg = read;
                        }
                        cfg.sanitize();
                        Files.createDirectories(PATH.getParent());
                        Files.writeString(PATH, GSON.toJson(cfg), StandardCharsets.UTF_8);
                } catch (Exception ex) {
                        LOGGER.warn("[SoA Tick] 配置读写失败，本次使用默认值: {}", ex.toString());
                }
                return cfg;
        }

        /** 把所有非法值钳制到安全区间 */
        private void sanitize() {
                nearDistance = clampMin(nearDistance, 8.0D);
                midDistance = Math.max(midDistance, nearDistance + 8.0D);
                farDistance = Math.max(farDistance, midDistance + 8.0D);
                nearDivisor = pow2(nearDivisor);
                midDivisor = pow2(midDivisor);
                farDivisor = pow2(farDivisor);
                beyondDivisor = pow2(beyondDivisor);
                itemHardSkipFromRing = clampRing(itemHardSkipFromRing);
                aiDegradeFromRing = clampRing(aiDegradeFromRing);
                pushSkipFromRing = clampRing(pushSkipFromRing);
                livingRenderDistance = clampMin(livingRenderDistance, 0.0D);
                itemRenderDistance = clampMin(itemRenderDistance, 0.0D);
                xpOrbRenderDistance = clampMin(xpOrbRenderDistance, 0.0D);
                projectileRenderDistance = clampMin(projectileRenderDistance, 0.0D);
                miscRenderDistance = clampMin(miscRenderDistance, 0.0D);
                bossRenderDistance = clampMin(bossRenderDistance, 0.0D);
                hysteresisBlocks = clampMin(hysteresisBlocks, 0.0D);
        }

        private static double clampMin(double v, double min) {
                return Double.isNaN(v) ? min : Math.max(min, v);
        }

        /** 钳到 [1, 64] 内最大的 2 的幂（保证位运算取模正确） */
        private static int pow2(int v) {
                if (v < 1) return 1;
                v = Math.min(v, 64);
                return Integer.highestOneBit(v);
        }

        /** 环阈值钳制：合法域 {-1, 0, 1, 2, 3}，-1 = 关闭 */
        private static int clampRing(int v) {
                if (v < 0) return -1;
                return Math.min(v, 3);
        }

        /** 环号 → 降频分母（2 的幂，供位运算调度使用） */
        public int divisorForRing(byte ring) {
                return switch (ring) {
                        case SoaFlags.RING_NEAR -> nearDivisor;
                        case SoaFlags.RING_MID -> midDivisor;
                        case SoaFlags.RING_FAR -> farDivisor;
                        default -> beyondDivisor;
                };
        }

        /** 渲染分类 → 距离上限；≤0 表示该类不剔除 */
        public double renderLimitForCategory(byte category) {
                return switch (category) {
                        case SoaFlags.CAT_ITEM -> itemRenderDistance;
                        case SoaFlags.CAT_XP -> xpOrbRenderDistance;
                        case SoaFlags.CAT_PROJECTILE -> projectileRenderDistance;
                        case SoaFlags.CAT_MISC -> miscRenderDistance;
                        case SoaFlags.CAT_BOSS -> bossRenderDistance;
                        case SoaFlags.CAT_PLAYER -> 0.0D;   // 玩家永不剔除
                        default -> livingRenderDistance;
                };
        }
}
