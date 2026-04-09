package dev.hycompanion.plugin.api;

/**
 * 服务器信息，在连接握手时发送
 *
 * @param version     插件版本号
 * @param playerCount 当前在线玩家数量
 */
public record ServerInfo(
        String version,
        int playerCount) {
    /**
     * 使用当前值创建服务器信息实例
     */
    public static ServerInfo current(String version, int playerCount) {
        return new ServerInfo(version, playerCount);
    }
}
