package dev.hycompanion.plugin.api.inventory;

import java.util.Map;

/**
 * 卸下装备操作的结果
 *
 * @param success        操作是否成功
 * @param slot           卸下装备的槽位
 * @param itemRemoved    被移除的物品信息
 * @param movedToStorage 物品是否被移至存储区
 * @param destroyed      物品是否被销毁
 * @param error          失败时的错误信息
 */
public record UnequipResult(
    boolean success,
    String slot,
    Map<String, Object> itemRemoved,
    boolean movedToStorage,
    boolean destroyed,
    String error
) {
    /**
     * 创建一个成功卸下装备的结果（物品移至存储区）
     */
    public static UnequipResult success(String slot, Map<String, Object> itemRemoved, boolean movedToStorage) {
        return new UnequipResult(true, slot, itemRemoved, movedToStorage, false, null);
    }

    /**
     * 创建一个成功卸下并销毁物品的结果
     */
    public static UnequipResult destroyed(String slot, Map<String, Object> itemRemoved) {
        return new UnequipResult(true, slot, itemRemoved, false, true, null);
    }

    /**
     * 创建一个失败的卸下装备结果
     */
    public static UnequipResult failed(String error) {
        return new UnequipResult(false, null, null, false, false, error);
    }
}
