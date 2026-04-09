package dev.hycompanion.plugin.api.inventory;

import java.util.List;
import java.util.Map;

/**
 * 拾取物品操作的结果
 *
 * @param success        操作是否成功
 * @param itemsPickedUp  实际拾取的物品数量
 * @param itemsByType    按类型分组的拾取物品列表
 * @param itemsRemaining 地面上剩余的物品数量
 * @param error          失败时的错误信息
 */
public record PickupResult(
    boolean success,
    int itemsPickedUp,
    List<Map<String, Object>> itemsByType,
    int itemsRemaining,
    String error
) {
    /**
     * 创建一个成功的拾取结果
     */
    public static PickupResult success(int itemsPickedUp, List<Map<String, Object>> itemsByType, int itemsRemaining) {
        return new PickupResult(true, itemsPickedUp, itemsByType, itemsRemaining, null);
    }

    /**
     * 创建一个失败的拾取结果
     */
    public static PickupResult failed(String error) {
        return new PickupResult(false, 0, null, 0, error);
    }
}
