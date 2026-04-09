package dev.hycompanion.plugin.api.inventory;

import java.util.Map;

/**
 * 装备物品操作的结果
 *
 * @param success      操作是否成功
 * @param itemId       装备的物品 ID
 * @param equippedSlot 物品被装备到的槽位
 * @param previousItem 该槽位之前的物品信息（如果有）
 * @param error        失败时的错误信息
 */
public record EquipResult(
    boolean success,
    String itemId,
    String equippedSlot,
    Map<String, Object> previousItem,
    String error
) {
    /**
     * 创建一个成功的装备结果
     */
    public static EquipResult success(String itemId, String equippedSlot, Map<String, Object> previousItem) {
        return new EquipResult(true, itemId, equippedSlot, previousItem, null);
    }

    /**
     * 创建一个失败的装备结果
     */
    public static EquipResult failed(String error) {
        return new EquipResult(false, null, null, null, error);
    }
}
