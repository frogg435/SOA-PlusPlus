package dev.soatick.sync;

import dev.soatick.config.SoaConfig;

/**
 * 服务端配置的客户端镜像（服务端→客户端单向同步）。
 *
 * 客户端位置平滑需要知道服务端「用了什么阈值/分母」才能把
 * 重插值步数对准真实的更新间隔。单人模式二者同进程本就一致；
 * 多人模式下客户端本地配置与服务端可能不同——收到本同步包后，
 * 平滑决策以服务端数值为准（渲染距离等本地体验参数仍以本地为准）。
 *
 * 全 volatile 字段：网络线程写、渲染线程读，单字段原子即可。
 */
public final class ServerSyncStore {

        private ServerSyncStore() {}

        /** 是否收到过同步包（未收到 = 用本地配置兜底） */
        public static volatile boolean present = false;
        /** 服务端总开关 + 分环调度开关（二者都开才会降频，客户端才需要平滑） */
        public static volatile boolean serverGating = true;
        public static volatile double nearDistance = 32.0D;
        public static volatile double midDistance = 64.0D;
        public static volatile double farDistance = 128.0D;
        public static volatile int nearDivisor = 1;
        public static volatile int midDivisor = 2;
        public static volatile int farDivisor = 4;
        public static volatile int beyondDivisor = 8;

        /**
         * 平滑专用：按（同步或本地）阈值与分母求该距离下的重插值步数。
         * 未降频（开关关/近环）返回 1 —— 调用方视作「不接管」。
         */
        public static int smoothDivisorFor(double distSq) {
                SoaConfig cfg = SoaConfig.get();
                double near = present ? nearDistance : cfg.nearDistance;
                double mid = present ? midDistance : cfg.midDistance;
                double far = present ? farDistance : cfg.farDistance;
                boolean gating = present ? serverGating : cfg.serverGating;
                if (!cfg.enabled || !gating) return 1;

                int div;
                if (distSq < near * near) {
                        div = present ? nearDivisor : cfg.nearDivisor;
                } else if (distSq < mid * mid) {
                        div = present ? midDivisor : cfg.midDivisor;
                } else if (distSq < far * far) {
                        div = present ? farDivisor : cfg.farDivisor;
                } else {
                        div = present ? beyondDivisor : cfg.beyondDivisor;
                }
                return div;
        }

        public static void apply(boolean gating, double n, double m, double f,
                        int dn, int dm, int df, int db) {
                present = true;
                serverGating = gating;
                nearDistance = n;
                midDistance = m;
                farDistance = f;
                nearDivisor = dn;
                midDivisor = dm;
                farDivisor = df;
                beyondDivisor = db;
        }
}
