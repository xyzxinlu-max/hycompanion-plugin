package dev.hycompanion.plugin.network.payload;

import dev.hycompanion.plugin.core.context.WorldContext;

/**
 * 聊天事件载荷记录类，发送到后端的玩家聊天数据
 * 当玩家与 NPC 对话时，将聊天内容及世界上下文一起发送给后端 LLM 处理
 *
 * @param npcId      NPC 的外部 ID
 * @param playerId   玩家的唯一 ID
 * @param playerName 玩家的显示名称
 * @param message    聊天消息内容
 * @param context    世界上下文信息（位置、时间、天气、附近玩家等）
 */
public record ChatEventPayload(
        String npcId,
        String playerId,
        String playerName,
        String message,
        WorldContext context) {
    /**
     * 工厂方法，创建聊天事件载荷实例
     *
     * @param npcId      NPC 的外部 ID
     * @param playerId   玩家 ID
     * @param playerName 玩家显示名称
     * @param message    聊天消息
     * @param context    世界上下文
     * @return 新的 ChatEventPayload 实例
     */
    public static ChatEventPayload of(
            String npcId,
            String playerId,
            String playerName,
            String message,
            WorldContext context) {
        return new ChatEventPayload(npcId, playerId, playerName, message, context);
    }
}
