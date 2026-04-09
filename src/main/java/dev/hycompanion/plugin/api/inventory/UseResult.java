package dev.hycompanion.plugin.api.inventory;

/**
 * 使用手持物品操作的结果
 *
 * @param success               操作是否成功
 * @param usesPerformed         实际执行的使用次数
 * @param targetDestroyed       目标是否被摧毁
 * @param targetHealthRemaining 目标剩余生命值
 * @param toolBroke             工具是否损坏
 * @param error                 失败时的错误信息
 */
public record UseResult(
    boolean success,
    int usesPerformed,
    Boolean targetDestroyed,
    Double targetHealthRemaining,
    boolean toolBroke,
    String error
) {
    /**
     * 创建一个成功的使用结果
     */
    public static UseResult success(int usesPerformed, Boolean targetDestroyed, Double targetHealthRemaining, boolean toolBroke) {
        return new UseResult(true, usesPerformed, targetDestroyed, targetHealthRemaining, toolBroke, null);
    }

    /**
     * 创建一个失败的使用结果
     */
    public static UseResult failed(String error) {
        return new UseResult(false, 0, null, null, false, error);
    }
}
