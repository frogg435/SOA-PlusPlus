package dev.soatick.mixin;

import net.minecraft.entity.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * ItemEntity 访问器：暴露私有的 itemAge（掉落物消失计时，6000 tick = 5 分钟）。
 *
 * 注意：ItemEntity 里驱动消失计时的是 itemAge，而不是继承自 Entity 的
 * public int age（后者只是实体的通用存活计数）。物品硬跳过要按真实速率
 * 快进的是 itemAge——javap 校验确认过这一字段差异。
 */
@Mixin(ItemEntity.class)
public interface ItemEntityAccessor {

        @Accessor("itemAge")
        int soatick$getItemAge();

        @Accessor("itemAge")
        void soatick$setItemAge(int age);
}
