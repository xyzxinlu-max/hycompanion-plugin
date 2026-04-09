package dev.hycompanion.plugin.api.inventory;

/**
 * 丢弃物品操作的结果
 *
 * @param success           操作是否成功
 * @param itemId            丢弃的物品 ID
 * @param quantityDropped   实际丢弃的数量
 * @param remainingQuantity 背包中剩余的该物品数量
 * @param error             失败时的错误信息
 */
public record DropResult(
    boolean success,
    String itemId,
    int quantityDropped,
    int remainingQuantity,
    String error
) {
    /**
     * 创建一个成功的丢弃结果
     */
    public static DropResult success(String itemId, int quantityDropped, int remainingQuantity) {
        return new DropResult(true, itemId, quantityDropped, remainingQuantity, null);
    }

    /**
     * 创建一个失败的丢弃结果
     */
    public static DropResult failed(String error) {
        return new DropResult(false, null, 0, 0, error);
    }
}
