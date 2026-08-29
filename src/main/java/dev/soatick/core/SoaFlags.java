package dev.soatick.core;

/**
 * SoA 数组里使用的位标志 / 环号 / 渲染分类常量。
 *
 * 全部为 2 的幂位掩码，单次 int 与运算即可完成判断，
 * 这是「数据导向」决策层的基础：判断逻辑不触碰任何对象字段。
 */
public final class SoaFlags {

	private SoaFlags() {}

	// ---------- 实体状态位（flags 数组，int 位域） ----------
	/** 实体存活（isAlive()），未存活实体的槽位仍保留但所有 Pass 跳过 */
	public static final int ALIVE     = 1 << 0;
	/** 玩家：永远满速 tick、永远渲染 */
	public static final int PLAYER    = 1 << 1;
	/** LivingEntity：有血量数据 */
	public static final int LIVING    = 1 << 2;
	/** Boss（凋灵 / 末影龙）：默认豁免一切降频与剔除 */
	public static final int BOSS      = 1 << 3;
	/** 载具（有乘客骑在上面）：整列车厢（载具+乘客）一起满速 */
	public static final int VEHICLE   = 1 << 4;
	/** 乘客（骑在别的实体上）：随载具一起 tick，强制近环 */
	public static final int PASSENGER = 1 << 5;
	/** 有自定义名牌：默认豁免降频 */
	public static final int NAMED     = 1 << 6;
	/** 被拴绳拴住：默认豁免降频 */
	public static final int LEASHED   = 1 << 7;

	// ---------- 距离分环（ring 数组） ----------
	/** 环 0：近环，满速 tick */
	public static final byte RING_NEAR   = 0;
	/** 环 1：中环，默认 1/2 速 */
	public static final byte RING_MID    = 1;
	/** 环 2：远环，默认 1/4 速 */
	public static final byte RING_FAR    = 2;
	/** 环 3：极远环，默认 1/8 速 */
	public static final byte RING_BEYOND = 3;

	// ---------- 渲染分类（category 数组） ----------
	/** 普通 LivingEntity（怪物、动物等） */
	public static final byte CAT_LIVING     = 0;
	/** 玩家：永不剔除 */
	public static final byte CAT_PLAYER     = 1;
	/** 掉落物 ItemEntity */
	public static final byte CAT_ITEM       = 2;
	/** 经验球 ExperienceOrbEntity */
	public static final byte CAT_XP         = 3;
	/** 投射物（箭、雪球、火球等 ProjectileEntity 子类） */
	public static final byte CAT_PROJECTILE = 4;
	/** 杂项：盔甲架、展示框、TNT、掉落方块、船、矿车 */
	public static final byte CAT_MISC       = 5;
	/** Boss：默认不剔除 */
	public static final byte CAT_BOSS       = 6;
}
