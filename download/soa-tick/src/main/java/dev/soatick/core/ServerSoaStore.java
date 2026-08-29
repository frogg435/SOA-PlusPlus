package dev.soatick.core;

import dev.soatick.config.SoaConfig;

/**
 * 服务端 SoA 存储。
 *
 * 所有服务端实体（ServerWorld 中的 Entity 实例）只会在服务端线程
 * 被 tick，因此本类全程线程封闭，无需同步。
 * 与 ClientSoaStore 完全隔离：两个世界（逻辑服务端 / 逻辑客户端）
 * 的实体是不同的对象实例，槽位号互不冲突。
 */
public final class ServerSoaStore extends SoaStore {

	private static volatile ServerSoaStore instance;

	private ServerSoaStore(int capacity) {
		super(capacity);
	}

	/** 双检锁懒初始化；首个调用点必然是服务端线程的首次世界 tick */
	public static ServerSoaStore get() {
		ServerSoaStore s = instance;
		if (s == null) {
			synchronized (ServerSoaStore.class) {
				if (instance == null) {
					instance = new ServerSoaStore(SoaConfig.get().serverMaxSlots);
				}
				s = instance;
			}
		}
		return s;
	}

	/**
	 * 服务器停止 / 重启前调用。此时旧世界实体已全部丢弃，
	 * 实例整体作废，下次启动按（可能已修改的）配置容量重建。
	 */
	public static void reset() {
		instance = null;
	}
}
