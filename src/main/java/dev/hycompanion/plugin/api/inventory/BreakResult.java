package dev.hycompanion.plugin.api.inventory;

import java.util.List;
import java.util.Map;

/**
 * 破坏方块操作的结果
 *
 * @param success                操作是否成功执行
 * @param blockBroken            方块是否被成功破坏
 * @param blockId                被破坏的方块 ID
 * @param attemptsNeeded         破坏方块所需的尝试次数
 * @param drops                  掉落物列表
 * @param dropsDetectedAt        检测到掉落物的位置信息
 * @param toolDurabilityRemaining 工具剩余耐久度
 * @param error                  失败时的错误信息
 */
public record BreakResult(
    boolean success,
    boolean blockBroken,
    String blockId,
    int attemptsNeeded,
    List<Map<String, Object>> drops,
    Map<String, Object> dropsDetectedAt,
    Double toolDurabilityRemaining,
    String error
) {
    /**
     * 创建一个方块成功被破坏的结果
     */
    public static BreakResult success(String blockId, int attemptsNeeded, List<Map<String, Object>> drops,
            Map<String, Object> dropsDetectedAt, Double toolDurabilityRemaining) {
        return new BreakResult(true, true, blockId, attemptsNeeded, drops, dropsDetectedAt, toolDurabilityRemaining, null);
    }

    /**
     * 创建一个操作失败的结果
     */
    public static BreakResult failed(String error) {
        return new BreakResult(false, false, null, 0, null, null, null, error);
    }

    /**
     * 创建一个操作执行了但方块未被破坏的结果
     */
    public static BreakResult unbroken(String error) {
        return new BreakResult(true, false, null, 0, null, null, null, error);
    }
}
