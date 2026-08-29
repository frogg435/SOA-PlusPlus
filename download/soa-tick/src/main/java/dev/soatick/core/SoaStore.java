package dev.soatick.core;

import net.minecraft.entity.Entity;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.TntEntity;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.Arrays;

/**
 * SoA（Structure of Arrays）核心存储 —— 本 Mod 的心脏。
 *
 * 【为什么这样设计】
 * 原版实体是典型的面向对象布局（AoS，Array of Structs）：
 * 每个 Entity 是一个巨大对象，x/y/z、速度、血量、AI 状态……几十个字段
 * 散落在同一对象里，且对象之间还通过 passengers/world/dataTracker 相互引用。
 * 实体 tick 时 CPU 要做的决策（该不该 tick？多远？要不要渲染？）
 * 只需要几个标量，却必须沿着指针把整个对象图拖进缓存——
 * 缓存行利用率极低，这正是「实体多了掉帧」的底层原因之一。
 *
 * SoA 把所有实体「决策要用」的字段拆出来，按字段为维度放进连续数组：
 *
 *   x[]  = [e0.x, e1.x, e2.x, ...]   连续 8 字节 × N
 *   y[]  = [e0.y, e1.y, e2.y, ...]
 *   z[]  = [e0.z, e1.z, e2.z, ...]
 *   flags[] / ring[] / category[] / ...
 *
 * 这样一次批量遍历（如「计算每只怪到最近玩家的距离」）只顺序扫描
 * 三个 double 数组，缓存命中率接近 100%；判断逻辑全部是纯数值
 * 运算，不触碰任何对象指针。
 *
 * 【数据如何保持新鲜：写透（write-through）】
 * Mixin 在 Entity 的 setPos / setVelocity / setHealth / baseTick 上
 * 打了 TAIL 钩子：实体在自身 tick 时顺手把字段写进数组（此时该实体
 * 数据本来就热在 L1 缓存里，写入成本几乎为零）。
 * 决策层（服务端分环 Pass / 客户端剔除 Pass）只读数组，从不读对象。
 *
 * 【线程域分离】
 * 服务端实体只在服务端线程 tick，客户端实体只在客户端主线程
 * tick/渲染。因此拆成 ServerSoaStore / ClientSoaStore 两个独立实例，
 * 各自线程封闭（thread-confined），无需任何锁与 volatile 数组。
 *
 * 【兜底策略】
 * 容量耗尽或未 tick 过的实体 slot == -1，所有优化自动绕行，
 * 实体回到原版路径，绝对安全。
 */
public abstract class SoaStore {

        // ===================== SoA 数据列 =====================

        /** 世界坐标（double，与原版精度一致） */
        public final double[] x, y, z;
        /** 速度（float 足够，仅用于统计/调试展示） */
        public final float[] vx, vy, vz;
        /** 血量（LivingEntity 才有效） */
        public final float[] health;
        /** 包围盒半径（渲染剔除球，含 0.5 缓冲） */
        public final float[] radius;
        /** 服务端：到最近玩家的距离平方（每 tick 由调度 Pass 写入） */
        public final float[] distSqNearestPlayer;
        /** 客户端：到相机的距离平方（每帧由剔除 Pass 写入） */
        public final float[] distSqToCamera;
        /** 状态位（见 SoaFlags） */
        public final int[] flags;
        /** 渲染分类（见 SoaFlags.CAT_*） */
        public final byte[] category;
        /** 距离环（见 SoaFlags.RING_*） */
        public final byte[] ring;
        /** 客户端迟滞可见状态：1=渲染 0=剔除 */
        public final byte[] visible;
        /** 槽位 → 实体反查表（供统计与调试；决策路径不读它） */
        public final Entity[] entities;
        /** 槽位所属维度（RegistryKey，interned 单例，可 == 比较） */
        public final Object[] dims;
        /** 实体年龄（Entity.age，掉落物消失计时/合并逻辑用） */
        public final int[] age;
        /** 上次分环计算时的位置快照（float 足够：仅用于「动没动」的位相等比较）
         *  增量环更新：实体没动且玩家位移有界时，直接沿用旧环，跳过距离计算 */
        public final float[] lastRingX, lastRingY, lastRingZ;
        /** 快照是否有效（新槽位 / 重置后为 false，强制首轮全量计算） */
        public final boolean[] ringValid;

        // ===================== 槽位分配器 =====================

        /** 空闲槽位栈 */
        private final int[] freeStack;
        private int freeTop;
        /** 占用槽位稠密列表：Pass 只遍历 occupied[0..occupiedCount)，
         *  而不是扫整个 capacity —— 数组再大，成本也只与活跃实体数成正比 */
        public final int[] occupied;
        public int occupiedCount;
        /** occupied 的反查表，实现 O(1) 交换删除 */
        private final int[] occupiedIndex;

        /**
         * 维度分桶：每个维度一个稠密列表，调度 Pass 只扫本维度桶，
         * 不再全量扫 occupied[] 后逐个过滤维度。
         * RegistryKey 是 interned 单例，用 IdentityHashMap 零装箱、身份比较。
         */
        private final java.util.IdentityHashMap<Object, DimBucket> buckets =
                        new java.util.IdentityHashMap<>();
        /** 槽位在所属维度桶内的下标（-1 = 不在任何桶），O(1) 交换删除用 */
        private final int[] bucketPos;

        /** 单个维度的稠密桶（slots 非以便扩容） */
        public static final class DimBucket {
                public int[] slots;
                public int count;
                DimBucket() { slots = new int[256]; }
        }

        /** 取某维度桶（不存在则建空桶）；仅调度 Pass 与统计使用 */
        public final DimBucket bucketOf(Object dim) {
                DimBucket b = buckets.get(dim);
                if (b == null) {
                        b = new DimBucket();
                        buckets.put(dim, b);
                }
                return b;
        }

        private void bucketAdd(Object dim, int s) {
                DimBucket b = buckets.get(dim);
                if (b == null) {
                        b = new DimBucket();
                        buckets.put(dim, b);
                }
                if (b.count == b.slots.length) {
                        // 桶满：倍增扩容
                        b.slots = java.util.Arrays.copyOf(b.slots, b.slots.length * 2);
                }
                bucketPos[s] = b.count;
                b.slots[b.count++] = s;
        }

        private void bucketRemove(int s) {
                Object dim = dims[s];
                if (dim == null) return;
                DimBucket b = buckets.get(dim);
                if (b == null) return;
                int idx = bucketPos[s];
                if (idx < 0 || idx >= b.count) return;
                int lastSlot = b.slots[--b.count];
                b.slots[idx] = lastSlot;
                bucketPos[lastSlot] = idx;
                bucketPos[s] = -1;
        }

        public final int capacity;

        protected SoaStore(int requestedCapacity) {
                this.capacity = Math.max(256, Math.min(requestedCapacity, 1 << 16));
                this.x = new double[capacity];
                this.y = new double[capacity];
                this.z = new double[capacity];
                this.vx = new float[capacity];
                this.vy = new float[capacity];
                this.vz = new float[capacity];
                this.health = new float[capacity];
                this.radius = new float[capacity];
                this.distSqNearestPlayer = new float[capacity];
                this.distSqToCamera = new float[capacity];
                this.flags = new int[capacity];
                this.category = new byte[capacity];
                this.ring = new byte[capacity];
                this.visible = new byte[capacity];
                this.entities = new Entity[capacity];
                this.dims = new Object[capacity];
                this.age = new int[capacity];
                this.lastRingX = new float[capacity];
                this.lastRingY = new float[capacity];
                this.lastRingZ = new float[capacity];
                this.ringValid = new boolean[capacity];
                this.bucketPos = new int[capacity];
                java.util.Arrays.fill(bucketPos, -1);

                this.freeStack = new int[capacity];
                for (int i = 0; i < capacity; i++) freeStack[i] = i;
                this.freeTop = capacity - 1;
                this.occupied = new int[capacity];
                this.occupiedIndex = new int[capacity];
                Arrays.fill(occupiedIndex, -1);
        }

        /** 分配槽位；容量耗尽返回 -1（调用方放弃追踪，实体走原版路径） */
        public final int alloc() {
                if (freeTop < 0) return -1;
                int s = freeStack[freeTop--];
                occupiedIndex[s] = occupiedCount;
                occupied[occupiedCount++] = s;
                ring[s] = SoaFlags.RING_NEAR;    // 新实体先按最近处理，下一轮 Pass 修正
                visible[s] = 1;                  // 新实体先按可见处理，下一帧 Pass 修正
                bucketPos[s] = -1;               // 尚未入桶，refresh() 首次快照时入桶
                ringValid[s] = false;            // 强制首轮全量距离计算
                return s;
        }

        /** O(1) 释放槽位（交换删除法） */
        public final void free(int s) {
                if (s < 0 || s >= capacity) return;
                int idx = occupiedIndex[s];
                if (idx < 0) return;
                bucketRemove(s);                 // 同步从维度桶移除
                int last = occupied[--occupiedCount];
                occupied[idx] = last;
                occupiedIndex[last] = idx;
                occupiedIndex[s] = -1;
                entities[s] = null;
                flags[s] = 0;
                dims[s] = null;
                ringValid[s] = false;
        }

        /** 整体重置（断线 / 关服时调用：全部槽位回收，实体对象引用清空防泄漏）。
         *  命名 clearAll 以与子类的静态 reset()（单例作废）区分开。 */
        public final void clearAll() {
                occupiedCount = 0;
                freeTop = capacity - 1;
                buckets.clear();
                for (int i = 0; i < capacity; i++) {
                        freeStack[i] = i;
                        occupiedIndex[i] = -1;
                        entities[i] = null;
                        flags[i] = 0;
                        dims[i] = null;
                        bucketPos[i] = -1;
                        ringValid[i] = false;
                }
        }

        /**
         * 写透入口：在 Entity.baseTick HEAD 被调用（实体每 tick 必经之路）。
         * 首次调用时懒分配槽位并做全量快照；之后每 tick 刷新易变字段。
         */
        public final void onBaseTick(Entity e) {
                int s = ((SoaDuck) e).soatick$getSlot();
                if (s < 0) {
                        s = alloc();
                        if (s < 0) return;            // 容量耗尽：不追踪，优化自动绕行
                        ((SoaDuck) e).soatick$setSlot(s);
                        entities[s] = e;
                }
                refresh(e, s);
        }

        /**
         * 快照刷新。此处做若干 instanceof 与字段读——这些读操作发生在
         * 实体自身 tick 的上下文里（对象必然热在缓存中），成本可忽略；
         * 而收益是后续所有批量决策 Pass 完全摆脱对象指针追逐。
         */
        private void refresh(Entity e, int s) {
                x[s] = e.getX();
                y[s] = e.getY();
                z[s] = e.getZ();

                Vec3d v = e.getVelocity();
                vx[s] = (float) v.x;
                vy[s] = (float) v.y;
                vz[s] = (float) v.z;

                // 包围盒半径 + 0.5 缓冲，用于客户端剔除球的保守估计
                Box b = e.getBoundingBox();
                float half = (float) (Math.max(
                                b.maxX - b.minX,
                                Math.max(b.maxY - b.minY, b.maxZ - b.minZ)
                ) * 0.5D + 0.5D);
                radius[s] = half;

                // RegistryKey 是 interned 单例，身份比较即可；
                // 维度变化时同步迁移维度桶（正常流程是旧实体 remove + 新实体新建，
                // 这里只是 belt-and-braces：万一有实体跨维度复用槽位也保证桶一致）
                Object newDim = e.getWorld().getRegistryKey();
                if (dims[s] != newDim) {
                        if (dims[s] != null) bucketRemove(s);
                        dims[s] = newDim;
                        bucketAdd(newDim, s);
                }

                int f = 0;
                if (e.isAlive()) f |= SoaFlags.ALIVE;
                if (e instanceof PlayerEntity) f |= SoaFlags.PLAYER;
                if (e instanceof LivingEntity) f |= SoaFlags.LIVING;
                if (e instanceof MobEntity) f |= SoaFlags.MOB;
                if (e instanceof WitherEntity || e instanceof EnderDragonEntity) f |= SoaFlags.BOSS;
                if (e.hasPassengers()) f |= SoaFlags.VEHICLE;
                if (e.hasVehicle()) f |= SoaFlags.PASSENGER;
                if (e.getCustomName() != null) f |= SoaFlags.NAMED;
                if (e instanceof MobEntity mob && mob.isLeashed()) f |= SoaFlags.LEASHED;
                if (e.isOnGround()) f |= SoaFlags.ON_GROUND;
                flags[s] = f;

                if ((f & SoaFlags.LIVING) != 0) {
                        health[s] = ((LivingEntity) e).getHealth();
                }
                age[s] = e.age;
                category[s] = categoryOf(e);
        }

        /** 渲染分类判定（instanceof 链按「数量多且便宜」排序） */
        public static byte categoryOf(Entity e) {
                if (e instanceof ItemEntity) return SoaFlags.CAT_ITEM;
                if (e instanceof ExperienceOrbEntity) return SoaFlags.CAT_XP;
                if (e instanceof ProjectileEntity) return SoaFlags.CAT_PROJECTILE;
                if (e instanceof WitherEntity || e instanceof EnderDragonEntity) return SoaFlags.CAT_BOSS;
                if (e instanceof PlayerEntity) return SoaFlags.CAT_PLAYER;
                if (e instanceof ArmorStandEntity
                                || e instanceof ItemFrameEntity
                                || e instanceof TntEntity
                                || e instanceof FallingBlockEntity
                                || e instanceof BoatEntity
                                || e instanceof AbstractMinecartEntity) {
                        return SoaFlags.CAT_MISC;
                }
                return SoaFlags.CAT_LIVING;
        }
}
