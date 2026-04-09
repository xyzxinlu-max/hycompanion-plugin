package dev.hycompanion.plugin.api;

import java.util.UUID;

/**
 * 表示游戏世界中的一个玩家
 *
 * @param id       玩家唯一标识符（UUID 或平台 ID）
 * @param name     显示名称
 * @param uuid     玩家的 UUID
 * @param location 当前位置
 */
public record GamePlayer(
        String id,
        String name,
        UUID uuid,
        Location location) {
    /**
     * 使用字符串 ID 创建 GamePlayer 实例
     */
    public static GamePlayer of(String id, String name, Location location) {
        return new GamePlayer(id, name, parseUUID(id), location);
    }

    /**
     * 使用 UUID 创建 GamePlayer 实例
     */
    public static GamePlayer of(UUID uuid, String name, Location location) {
        return new GamePlayer(uuid.toString(), name, uuid, location);
    }

    /**
     * 从字符串解析 UUID，如果格式无效则根据 ID 生成确定性的 UUID
     */
    private static UUID parseUUID(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            // 根据 ID 字符串生成确定性的 UUID
            return UUID.nameUUIDFromBytes(id.getBytes());
        }
    }

    /**
     * 检查玩家是否在某位置的指定范围内
     */
    public boolean isWithinRange(Location other, double range) {
        return location != null && location.distanceTo(other) <= range;
    }
}
