package dev.hycompanion.plugin.network.payload;

import dev.hycompanion.plugin.api.ServerInfo;

/**
 * 连接载荷记录类，在连接到后端时发送
 * 携带 API 密钥用于身份验证，以及服务器基本信息
 *
 * @param apiKey     服务器 API 密钥，用于身份验证
 * @param serverInfo 服务器信息（版本号、在线人数等）
 */
public record ConnectionPayload(
        String apiKey,
        ServerInfo serverInfo) {
    /**
     * 工厂方法，创建连接载荷实例
     *
     * @param apiKey      API 认证密钥
     * @param version     服务器版本号
     * @param playerCount 当前在线玩家数量
     * @return 新的 ConnectionPayload 实例
     */
    public static ConnectionPayload of(String apiKey, String version, int playerCount) {
        return new ConnectionPayload(
                apiKey,
                new ServerInfo(version, playerCount));
    }
}
