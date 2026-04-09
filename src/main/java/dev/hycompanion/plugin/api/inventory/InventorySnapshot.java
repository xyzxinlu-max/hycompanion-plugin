package dev.hycompanion.plugin.api.inventory;

import java.util.List;
import java.util.Map;

/**
 * NPC 背包内容的快照
 *
 * @param armor      护甲槽位信息
 * @param hotbar     快捷栏物品列表
 * @param storage    存储区物品列表
 * @param heldItem   当前手持物品信息
 * @param totalItems 物品总数
 * @param success    快照获取是否成功
 * @param error      失败时的错误信息
 */
public record InventorySnapshot(
    Map<String, Object> armor,
    List<Map<String, Object>> hotbar,
    List<Map<String, Object>> storage,
    Map<String, Object> heldItem,
    int totalItems,
    boolean success,
    String error
) {
    /**
     * 创建一个成功的背包快照
     */
    public static InventorySnapshot create(
            Map<String, Object> armor,
            List<Map<String, Object>> hotbar,
            List<Map<String, Object>> storage,
            Map<String, Object> heldItem,
            int totalItems) {
        return new InventorySnapshot(armor, hotbar, storage, heldItem, totalItems, true, null);
    }

    /**
     * 创建一个失败的背包快照
     */
    public static InventorySnapshot failed(String error) {
        return new InventorySnapshot(null, null, null, null, 0, false, error);
    }
}
