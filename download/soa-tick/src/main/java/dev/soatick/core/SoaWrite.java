package dev.soatick.core;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Vec3d;

/**
 * 写透路由器：Mixin 钩子的统一入口。
 *
 * 根据 e.getWorld().isClient 把写操作路由到正确的线程域存储：
 * - 客户端实体 → ClientSoaStore（客户端主线程）
 * - 服务端实体 → ServerSoaStore（服务端线程）
 *
 * 性能设计：
 * - writePos / writeVel / writeHealth 是「热路径」，先查槽位，
 *   slot < 0 直接返回（未追踪实体只多花一次字段读）；
 * - 真正的槽位分配只发生在 baseTick（实体确实在 tick 才值得追踪），
 *   避免为「构造后即丢弃」的临时实体白白分配槽位。
 */
public final class SoaWrite {

	private SoaWrite() {}

	/** Entity.baseTick HEAD → 懒分配 + 每 tick 快照刷新 */
	public static void onBaseTick(Entity e) {
		if (e.getWorld().isClient) {
			ClientSoaStore.get().onBaseTick(e);
		} else {
			ServerSoaStore.get().onBaseTick(e);
		}
	}

	/** Entity.setPos TAIL → 位置写透（最热的调用点） */
	public static void writePos(Entity e, double x, double y, double z) {
		int s = ((SoaDuck) e).soatick$getSlot();
		if (s < 0) return;
		if (e.getWorld().isClient) {
			ClientSoaStore st = ClientSoaStore.get();
			st.x[s] = x;
			st.y[s] = y;
			st.z[s] = z;
		} else {
			ServerSoaStore st = ServerSoaStore.get();
			st.x[s] = x;
			st.y[s] = y;
			st.z[s] = z;
		}
	}

	/** Entity.setVelocity(Vec3d) TAIL → 速度写透 */
	public static void writeVel(Entity e, Vec3d v) {
		int s = ((SoaDuck) e).soatick$getSlot();
		if (s < 0) return;
		if (e.getWorld().isClient) {
			ClientSoaStore st = ClientSoaStore.get();
			st.vx[s] = (float) v.x;
			st.vy[s] = (float) v.y;
			st.vz[s] = (float) v.z;
		} else {
			ServerSoaStore st = ServerSoaStore.get();
			st.vx[s] = (float) v.x;
			st.vy[s] = (float) v.y;
			st.vz[s] = (float) v.z;
		}
	}

	/** LivingEntity.setHealth TAIL → 血量写透 */
	public static void writeHealth(Entity e, float health) {
		int s = ((SoaDuck) e).soatick$getSlot();
		if (s < 0) return;
		if (e.getWorld().isClient) {
			ClientSoaStore.get().health[s] = health;
		} else {
			ServerSoaStore.get().health[s] = health;
		}
	}

	/** Entity.remove HEAD → 槽位立即回收（O(1) 交换删除） */
	public static void onRemoved(Entity e) {
		int s = ((SoaDuck) e).soatick$getSlot();
		if (s < 0) return;
		((SoaDuck) e).soatick$setSlot(-1);
		if (e.getWorld().isClient) {
			ClientSoaStore.get().free(s);
		} else {
			ServerSoaStore.get().free(s);
		}
	}

	/** 供统计面板读取 LivingEntity 快照血量（调试用，非热路径） */
	public static float snapshotHealth(Entity e) {
		int s = ((SoaDuck) e).soatick$getSlot();
		if (s < 0 || !(e instanceof LivingEntity)) return Float.NaN;
		return e.getWorld().isClient
				? ClientSoaStore.get().health[s]
				: ServerSoaStore.get().health[s];
	}
}
