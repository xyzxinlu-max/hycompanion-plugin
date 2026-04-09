package dev.hycompanion.plugin.core.context;

import dev.hycompanion.plugin.api.HytaleAPI;
import dev.hycompanion.plugin.api.Location;
import dev.hycompanion.plugin.utils.PluginLogger;

import java.util.List;

/**
 * 世界上下文构建器 - 为NPC对话构建环境上下文信息。
 *
 * 收集环境数据（时间、天气、附近玩家），丰富发送给后端的上下文，
 * 使NPC的回复更加沉浸和真实。
 */
public class ContextBuilder {

    private final HytaleAPI hytaleAPI;
    private final PluginLogger logger;

    // 附近玩家检测的默认半径（方块/米）
    private static final double NEARBY_PLAYER_RADIUS = 50.0;

    /**
     * 构造上下文构建器
     * @param hytaleAPI Hytale服务器API接口
     * @param logger    日志记录器
     */
    public ContextBuilder(HytaleAPI hytaleAPI, PluginLogger logger) {
        this.hytaleAPI = hytaleAPI;
        this.logger = logger;
    }

    /**
     * 根据玩家位置构建完整的世界上下文。
     * 包含位置坐标、时间、天气和附近玩家信息。
     * 如果构建失败，返回仅包含位置的最简上下文。
     */
    public WorldContext buildContext(Location playerLocation) {
        if (playerLocation == null) {
            return WorldContext.defaultContext();
        }

        try {
            String locationStr = playerLocation.toCoordString();
            String timeOfDay = hytaleAPI.getTimeOfDay();
            String weather = hytaleAPI.getWeather();
            List<String> nearbyPlayers = hytaleAPI.getNearbyPlayerNames(
                    playerLocation,
                    NEARBY_PLAYER_RADIUS);

            return new WorldContext(locationStr, timeOfDay, weather, nearbyPlayers);

        } catch (Exception e) {
            logger.debug("Error building context, using defaults: " + e.getMessage());
            return WorldContext.minimal(playerLocation.toCoordString());
        }
    }

    /**
     * 根据位置字符串构建世界上下文。
     * 先将字符串解析为Location对象，再调用完整的构建方法。
     */
    public WorldContext buildContext(String locationStr) {
        try {
            Location location = Location.parse(locationStr);
            return buildContext(location);
        } catch (Exception e) {
            return WorldContext.defaultContext();
        }
    }

    /**
     * 构建最简上下文（仅包含位置信息）
     */
    public WorldContext buildMinimalContext(Location location) {
        String locationStr = location != null ? location.toCoordString() : "0,64,0";
        return WorldContext.minimal(locationStr);
    }
}
