package dev.soatick.core;

import net.minecraft.entity.Entity;

/**
 * 「鸭子接口」：通过 Mixin 挂到 {@link Entity} 上，
 * 给每个实体实例附加一个 int 槽位号（slot）。
 *
 * 槽位号是实体在 SoA（Structure of Arrays）镜像数组中的下标。
 * -1 表示该实体尚未被追踪（未 tick 过、或数组容量耗尽），
 * 所有优化路径都会对 slot < 0 的实体自动绕行，保证零风险。
 *
 * 为什么不用 HashMap<Entity, Integer>？
 *   1) 装箱 + 哈希寻址 + 指针追逐，在每 tick 数万次调用下开销显著；
 *   2) 字段直读是单次内存访问，与 SoA 的「数据跟着实体走」哲学一致。
 */
public interface SoaDuck {

	/** 返回 SoA 槽位号；-1 = 未追踪 */
	int soatick$getSlot();

	/** 设置 SoA 槽位号（仅由 SoaStore 分配器调用） */
	void soatick$setSlot(int slot);
}
