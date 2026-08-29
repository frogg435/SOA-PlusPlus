package dev.soatick.server;

/**
 * AI 降级开关的线程域通道。
 *
 * ServerWorld.tickEntity 的门禁决定本实体本 tick 是「满速 / 降级 / 跳过」；
 * 其中「降级」= 放行原版 tick，但砍掉 AI 部分。原版 AI 分散在
 * MobEntity.tickNewAi（目标选择器/导航/移动控制）与 Brain 系行为里，
 * 这些调用点深处无法直接拿到"当前实体该不该降 AI"的上下文——
 * 所以用 ThreadLocal 沿 tick 调用栈向下传递决策结果。
 *
 * 服务端实体 tick 全程单线程，ThreadLocal 读写是数组访问级开销；
 * 值总在 tickEntity HEAD 被「覆写」（不是 set/clear 配对），
 * 即使某次 cancel 导致未复位，下一次覆写也会纠正，无泄漏路径。
 */
public final class AiDegrade {

        private AiDegrade() {}

        private static final ThreadLocal<Boolean> ACTIVE =
                        ThreadLocal.withInitial(() -> Boolean.FALSE);

        /** tickEntity HEAD 覆写：本实体本 tick 是否处于 AI 降级 */
        public static void set(boolean active) {
                ACTIVE.set(active);
        }

        /** Mixin 钩子读取：true = 砍掉本次 AI 更新 */
        public static boolean active() {
                return ACTIVE.get();
        }
}
