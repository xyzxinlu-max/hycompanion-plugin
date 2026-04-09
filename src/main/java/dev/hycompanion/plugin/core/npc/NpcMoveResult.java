package dev.hycompanion.plugin.core.npc;

import dev.hycompanion.plugin.api.Location;

/**
 * NPC移动操作的结果封装。
 * 包含移动是否成功、状态描述以及最终位置。
 *
 * @param success       移动是否成功
 * @param status        状态描述（"success"、"timeout"或失败原因）
 * @param finalLocation 移动后的最终位置（失败时可能为null）
 */
public record NpcMoveResult(boolean success, String status, Location finalLocation) {

    /** 创建移动成功的结果 */
    public static NpcMoveResult success(Location location) {
        return new NpcMoveResult(true, "success", location);
    }

    /** 创建移动超时的结果（NPC未能在规定时间内到达目标） */
    public static NpcMoveResult timeout(Location location) {
        return new NpcMoveResult(false, "timeout", location);
    }

    /** 创建移动失败的结果 */
    public static NpcMoveResult failed(String reason) {
        return new NpcMoveResult(false, reason, null);
    }
}
