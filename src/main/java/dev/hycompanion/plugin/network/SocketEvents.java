package dev.hycompanion.plugin.network;

/**
 * Socket.IO 事件名称常量类
 * 定义了插件与后端之间所有通信事件的名称，必须与后端 events.ts 保持一致
 *
 * @see hycompanion-backend/src/modules/socket/events.ts
 */
public final class SocketEvents {

    // 工具类，禁止实例化
    private SocketEvents() {
    }

    // ========== 插件 → 后端（Plugin → Backend）==========

    /**
     * 插件连接事件，携带 API 密钥进行身份验证
     * 载荷: { apiKey: string, serverInfo: { version, playerCount } }
     */
    public static final String PLUGIN_CONNECT = "plugin:connect";

    /**
     * 玩家向 NPC 发送的聊天消息
     * 载荷: { npcId, playerId, playerName, message, context }
     */
    public static final String PLUGIN_CHAT = "plugin:chat";

    /**
     * 向后端请求 NPC 数据同步
     * 载荷: {}
     */
    public static final String PLUGIN_REQUEST_SYNC = "plugin:request_sync";

    /**
     * 报告 NPC 可用的动画列表（从模型中发现）
     * 在 NPC 实体被发现/生成后发送
     * 载荷: { npcId, animations: string[] }
     */
    public static final String PLUGIN_NPC_ANIMATIONS = "plugin:npc_animations";

    /**
     * 报告服务器上可用的方块列表（从 BlockType 注册表中发现）
     * 连接后启动时发送一次
     * 载荷: { blocks: BlockInfo[] }
     * 其中 BlockInfo = { blockId, displayName, materialTypes, keywords, categories }
     */
    public static final String PLUGIN_BLOCKS_AVAILABLE = "plugin:blocks_available";

    // ========== 后端 → 插件（Backend → Plugin）==========

    /**
     * 聊天响应事件（旧版，现已改用 backend:action）
     * 载荷: { npcId, playerId, message, actions }
     */
    public static final String BACKEND_RESPONSE = "backend:response";

    /**
     * MCP 工具动作，需要在游戏中执行
     * 载荷: { npcId, playerId, action, params }
     */
    public static final String BACKEND_ACTION = "backend:action";

    /**
     * 后端错误通知
     * 载荷: { code, message }
     */
    public static final String BACKEND_ERROR = "backend:error";

    /**
     * NPC 同步事件（创建/更新/删除）
     * 载荷: { action: 'create'|'update'|'delete', npc: { id, externalId, name, ... } }
     */
    public static final String BACKEND_NPC_SYNC = "backend:npc_sync";

    /**
     * 思维链（Chain-of-Thought）状态更新，用于 NPC 思考指示器
     * 载荷: { type, npcInstanceUuid, playerId, message, stepNumber, toolName, ... }
     */
    public static final String BACKEND_COT_UPDATE = "backend:cot_update";

    /**
     * 感知查询事件 —— 后端在 Gemini function calling 循环中请求真实游戏数据
     * 使用 Socket.IO call（带 ack），插件必须通过 ack 返回查询结果
     * 载荷: { npcId, npcInstanceUuid, playerId, action, params }
     * 响应: 与对应 action 的 ack 格式一致（如 scan_blocks 返回方块数据）
     */
    public static final String BACKEND_QUERY = "backend:query";
}
