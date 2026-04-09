package dev.hycompanion.plugin.network.payload;

import java.util.Map;

/**
 * 动作载荷记录类，从后端接收的 MCP 工具执行结果
 * 包含需要在游戏中执行的动作信息
 *
 * @param npcId    NPC 的外部 ID
 * @param playerId 目标玩家的 ID
 * @param action   动作类型（say=说话, emote=表情, open_trade=开启交易, give_quest=给予任务, move_to=移动到）
 * @param params   动作参数的键值对映射
 */
public record ActionPayload(
        String npcId,
        String playerId,
        String action,
        Map<String, Object> params) {
    /**
     * 获取字符串类型的参数
     *
     * @param key 参数键名
     * @return 参数值的字符串表示，不存在则返回 null
     */
    public String getString(String key) {
        Object value = params.get(key);
        return value != null ? value.toString() : null;
    }

    /**
     * 获取字符串类型的参数，带默认值
     *
     * @param key          参数键名
     * @param defaultValue 参数不存在时返回的默认值
     * @return 参数值的字符串表示，不存在则返回默认值
     */
    public String getString(String key, String defaultValue) {
        Object value = params.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    /**
     * 获取数值类型的参数
     *
     * @param key 参数键名
     * @return 参数的 Double 值，不存在或类型不匹配则返回 null
     */
    public Double getNumber(String key) {
        Object value = params.get(key);
        if (value instanceof Number num) {
            return num.doubleValue();
        }
        return null;
    }

    /**
     * 获取数值类型的参数，带默认值
     *
     * @param key          参数键名
     * @param defaultValue 参数不存在时返回的默认值
     * @return 参数的 double 值，不存在则返回默认值
     */
    public double getNumber(String key, double defaultValue) {
        Double value = getNumber(key);
        return value != null ? value : defaultValue;
    }
}
