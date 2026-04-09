package dev.hycompanion.plugin.core.context;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * 世界上下文数据 - 随聊天事件一起发送给后端。
 *
 * 提供环境信息以丰富NPC的回复内容。
 *
 * @param location      玩家位置，格式为"x,y,z"字符串
 * @param timeOfDay     当前时间段（dawn/morning/noon/afternoon/dusk/night）
 * @param weather       当前天气（clear/rain/storm/snow）
 * @param nearbyPlayers 附近玩家名称列表
 */
public record WorldContext(
        String location,
        String timeOfDay,
        String weather,
        List<String> nearbyPlayers) {

    /**
     * 转换为JSON对象，用于Socket.IO通信载荷。
     * 仅包含非空字段。
     */
    public JsonObject toJson() {
        JsonObject json = new JsonObject();

        if (location != null) {
            json.addProperty("location", location);
        }

        if (timeOfDay != null) {
            json.addProperty("timeOfDay", timeOfDay);
        }

        if (weather != null) {
            json.addProperty("weather", weather);
        }

        if (nearbyPlayers != null && !nearbyPlayers.isEmpty()) {
            JsonArray playersArray = new JsonArray();
            nearbyPlayers.forEach(playersArray::add);
            json.add("nearbyPlayers", playersArray);
        }

        return json;
    }

    /**
     * 创建仅包含位置的最简上下文
     */
    public static WorldContext minimal(String location) {
        return new WorldContext(location, null, null, List.of());
    }

    /**
     * 创建带有默认模拟值的上下文（位置0,64,0 / 正午 / 晴天）
     */
    public static WorldContext defaultContext() {
        return new WorldContext(
                "0,64,0",
                "noon",
                "clear",
                List.of());
    }
}
