package dev.hycompanion.plugin.core.npc;

import dev.hycompanion.plugin.api.Location;

/**
 * NPC搜索结果封装 - 在附近NPC查找时返回。
 * 包含匹配的NPC实例数据、当前位置以及与搜索中心的距离。
 *
 * @param instance NPC实例数据
 * @param location NPC的当前位置
 * @param distance NPC与搜索中心点之间的距离（单位：方块/米）
 */
public record NpcSearchResult(
        NpcInstanceData instance,
        Location location,
        double distance) {
}
