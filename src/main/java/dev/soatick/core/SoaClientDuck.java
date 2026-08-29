package dev.soatick.core;

/**
 * 客户端平滑（重插值）状态——挂在 Entity 上的鸭子接口。
 *
 * 【为什么需要】
 * 1.20.1 的位置包处理是「直接 setPosition 瞬移」+ 仅 1 tick 的
 * prevX→x 渲染插值。服务端实体被降频后，位置包每 div tick 才来一次，
 * 客户端观感变成「1 tick 滑行 + div-1 tick 冻结」的量子移动。
 *
 * 【接管方式】
 * 收到位置包时（updateTrackedPositionAndAngles），记录当前视觉位置为
 * 起点、包内目标为终点，撤销瞬移，随后在 tick TAIL 用 div 个 client tick
 * 把实体沿起终点线性推进——渲染插值 lerp(prevX→x) 仍然逐帧平滑。
 * 近环（div≤1）立即退出接管，回到原版行为。
 */
public interface SoaClientDuck {

        SmoothingState soatick$getSmoothing();

        /** 单实体的重插值状态（懒创建，仅客户端使用） */
        final class SmoothingState {
                /** 当前视觉位置（包到达瞬间的渲染位置，作为起点） */
                public double vx, vy, vz;
                public float vYaw, vPitch;
                /** 起点 / 终点 */
                public double x0, y0, z0, x1, y1, z1;
                public float yaw0, yaw1, pitch0, pitch1;
                /** 总步数与剩余步数（client tick） */
                public int total, left;
        }
}
