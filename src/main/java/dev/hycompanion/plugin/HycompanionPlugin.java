package dev.hycompanion.plugin;

import dev.hycompanion.plugin.api.HytaleAPI;
import dev.hycompanion.plugin.api.ServerInfo;
import dev.hycompanion.plugin.adapter.MockHytaleAdapter;
import dev.hycompanion.plugin.config.PluginConfig;
import dev.hycompanion.plugin.config.NpcConfigManager;
import dev.hycompanion.plugin.core.context.ContextBuilder;
import dev.hycompanion.plugin.core.npc.NpcManager;
import dev.hycompanion.plugin.handlers.ActionExecutor;
import dev.hycompanion.plugin.handlers.ChatHandler;
import dev.hycompanion.plugin.network.SocketManager;
import dev.hycompanion.plugin.utils.PluginLogger;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Hycompanion Plugin - Main Entry Point
 * Hycompanion 插件 - 主入口点
 *
 * AI-powered NPC companion system for Hytale servers.
 * 面向 Hytale 服务器的 AI 驱动 NPC 伴侣系统。
 * 连接到 Hycompanion 云后端，提供智能、具有上下文感知的 NPC 交互。
 *
 * 本类同时用于独立测试模式（standalone），包含交互式控制台。
 *
 * @author Hycompanion Team / NOLDO
 * @version 1.1.6
 * @see <a href="https://hycompanion.dev">Hycompanion Website</a>
 */
public class HycompanionPlugin {

    // 单例实例，用于全局访问
    private static HycompanionPlugin instance;

    // 插件版本号
    public static final String VERSION = "1.1.6-SNAPSHOT";

    // ===== 插件核心组件 =====
    private PluginConfig config;           // 插件配置
    private PluginLogger logger;           // 日志记录器
    private SocketManager socketManager;   // Socket.IO 网络连接管理器
    private NpcManager npcManager;         // NPC 数据与实例管理器
    private NpcConfigManager npcConfigManager; // NPC 配置缓存管理器
    private ActionExecutor actionExecutor; // 后端动作执行器（处理 say、emote、move 等）
    private ChatHandler chatHandler;       // 玩家聊天处理器
    private ContextBuilder contextBuilder; // 游戏上下文构建器（位置、时间、天气等）
    private HytaleAPI hytaleAPI;           // Hytale API 抽象层

    // 插件状态
    private boolean enabled = false;       // 插件是否已启用
    private Path dataFolder;               // 插件数据文件夹路径

    /**
     * 主构造方法 - 初始化插件实例
     * 设置单例引用和默认数据文件夹路径（用于独立测试模式）
     */
    public HycompanionPlugin() {
        instance = this;
        // 独立测试模式下的默认数据文件夹
        this.dataFolder = Path.of("plugins", "Hycompanion");
    }

    /**
     * 插件启用方法 - 在插件加载时调用
     *
     * 负责完整的初始化流程：
     * 1. 创建数据文件夹并复制默认配置
     * 2. 加载配置文件
     * 3. 初始化 Hytale API 适配器（真实服务器或模拟模式）
     * 4. 初始化各管理器（NPC、动作执行、聊天处理等）
     * 5. 建立与后端的 Socket.IO 连接
     * 6. 注册命令和事件监听器
     */
    public void onEnable() {
        logger = new PluginLogger("Hycompanion");
        logger.info("======================================");
        logger.info("  Hycompanion Plugin v" + VERSION);
        logger.info("  https://hycompanion.dev");
        logger.info("  Sponsor: https://www.verygames.com (Hytale Server Hosting)");
        logger.info("======================================");

        try {
            // 如果数据文件夹不存在则创建
            Files.createDirectories(dataFolder);

            // 如果默认配置文件不存在则复制
            copyDefaultConfig();

            // 加载配置文件
            config = PluginConfig.load(dataFolder.resolve("config.yml"));
            logger.info("Configuration loaded successfully");

            // 根据配置设置调试模式
            logger.setDebugMode(config.gameplay().debugMode());

            // 初始化 Hytale API 适配器
            // 检测当前运行环境：真实 Hytale 服务器还是独立测试模式
            if (isHytaleServerEnvironment()) {
                // 在真实 Hytale 服务器上运行 - 但当前仍使用模拟适配器
                // 注意：真实适配器需要 JavaPlugin 实例（由 HycompanionEntrypoint 提供）
                logger.info("Detected Hytale Server environment");
                logger.info("Hytale API adapter requires JavaPlugin context - using Mock for now");
                hytaleAPI = new MockHytaleAdapter(logger);
            } else {
                // 独立测试模式 - 使用模拟适配器
                hytaleAPI = new MockHytaleAdapter(logger);
                logger.info("Hytale API adapter initialized (Standalone/Mock Mode)");
            }

            // 初始化各管理器
            npcManager = new NpcManager(logger, hytaleAPI);
            npcConfigManager = new NpcConfigManager(dataFolder.resolve(config.npc().cacheDirectory()), logger);
            contextBuilder = new ContextBuilder(hytaleAPI, logger);

            // 初始化动作执行器（处理后端返回的 MCP 工具动作）
            actionExecutor = new ActionExecutor(hytaleAPI, npcManager, logger, config);

            // 初始化聊天处理器（处理玩家与 NPC 的对话）
            chatHandler = new ChatHandler(npcManager, contextBuilder, logger, config);

            // 初始化 Socket.IO 连接并连接到后端
            initializeSocketConnection();

            // 注册插件命令
            registerCommands();

            // 注册事件监听器
            registerEventListeners();

            enabled = true;
            logger.info("Hycompanion enabled successfully!");

        } catch (Exception e) {
            logger.error("Failed to enable Hycompanion", e);
            onDisable();
        }
    }

    /**
     * 插件禁用方法 - 在插件卸载时调用
     * 断开后端连接，保存 NPC 配置缓存
     */
    public void onDisable() {
        logger.info("Disabling Hycompanion...");

        // 断开与后端的 Socket.IO 连接
        if (socketManager != null) {
            socketManager.disconnect();
        }

        // 保存所有 NPC 配置到本地缓存
        if (npcConfigManager != null) {
            npcConfigManager.saveAll();
        }

        enabled = false;
        logger.info("Hycompanion disabled.");
    }

    /**
     * 更新 config.yml 中的 API 密钥并重新加载配置
     * @param newApiKey 新的 API 密钥
     */
    public void updateApiKey(String newApiKey) {
        try {
            Path configPath = dataFolder.resolve("config.yml");
            // Use shared helper to update file
            PluginConfig.updateApiKeyInFile(configPath, newApiKey);
            logger.info("API Key updated in config.yml");

            reload();

        } catch (IOException e) {
            logger.error("Failed to update API key", e);
        }
    }

    /**
     * 重新加载配置并根据需要重连后端
     * 热重载：无需重启插件即可应用新的配置
     */
    public void reload() {
        logger.info("Reloading Hycompanion...");

        try {
            // 重新加载配置文件
            config = PluginConfig.load(dataFolder.resolve("config.yml"));
            logger.setDebugMode(config.gameplay().debugMode());

            // 将新配置传递给各组件
            actionExecutor.setConfig(config);
            chatHandler.setConfig(config);

            // 更新 SocketManager 并在需要时重新连接
            if (socketManager != null) {
                socketManager.setConfig(config);
                socketManager.updateApiKey(config.connection().apiKey());

                if (!socketManager.isConnected() && config.connection().reconnectEnabled()) {
                    socketManager.connect();
                }
            } else {
                initializeSocketConnection();
            }

            logger.info("Reload complete!");
        } catch (Exception e) {
            logger.error("Failed to reload configuration", e);
        }
    }

    /**
     * 强制从后端同步 NPC 数据
     * 向后端发送同步请求，获取最新的 NPC 定义
     */
    public void forceSync() {
        if (socketManager != null && socketManager.isConnected()) {
            socketManager.requestSync();
            logger.info("NPC sync requested");
        } else {
            logger.warn("Cannot sync - not connected to backend");
        }
    }

    /**
     * 获取与后端的连接状态
     * @return 是否已连接到后端
     */
    public boolean isConnected() {
        return socketManager != null && socketManager.isConnected();
    }

    /**
     * 初始化与后端的 Socket.IO 连接
     * 创建 SocketManager 并建立连接，将聊天处理器与之关联
     */
    private void initializeSocketConnection() {
        // 创建服务器信息用于握手认证
        ServerInfo serverInfo = new ServerInfo(
                VERSION,
                getOnlinePlayerCount());

        // 初始化 SocketManager（独立模式下没有 RoleGenerator）
        socketManager = new SocketManager(
                config.connection().url(),
                config.connection().apiKey(),
                serverInfo,
                actionExecutor,
                npcManager,
                npcConfigManager,
                null, // 独立模式下没有 RoleGenerator
                logger,
                config,
                hytaleAPI);

        // 将 SocketManager 注入到聊天处理器中（用于发送聊天消息到后端）
        chatHandler.setSocketManager(socketManager);

        // 发起连接
        logger.info("Connecting to backend: " + config.connection().url());
        socketManager.connect();
    }

    /**
     * 从资源文件中复制默认的 config.yml（如果尚不存在）
     * 首次运行时自动生成默认配置文件
     */
    private void copyDefaultConfig() throws IOException {
        Path configPath = dataFolder.resolve("config.yml");
        if (!Files.exists(configPath)) {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
                if (in != null) {
                    Files.copy(in, configPath, StandardCopyOption.REPLACE_EXISTING);
                    logger.info("Default config.yml created");
                }
            }
        }
    }

    /**
     * 注册插件命令
     * TODO: [HYTALE-API] 与 Hytale 命令系统集成
     */
    private void registerCommands() {
        // TODO: [HYTALE-API] 注册 /hycompanion 命令
        // Example for Spigot-like API:
        // getCommand("hycompanion").setExecutor(new HycompanionCommand(this));
        logger.debug("Commands registration (pending Hytale API)");
    }

    /**
     * 注册事件监听器
     * TODO: [HYTALE-API] 与 Hytale 事件系统集成
     */
    private void registerEventListeners() {
        // TODO: [HYTALE-API] 注册聊天监听器
        // Example for Spigot-like API:
        // Bukkit.getPluginManager().registerEvents(new ChatListener(chatHandler),
        // this);
        logger.debug("Event listeners registration (pending Hytale API)");
    }

    /**
     * 获取当前在线玩家数量
     * TODO: [HYTALE-API] 从实际服务器获取
     */
    private int getOnlinePlayerCount() {
        // TODO: [HYTALE-API] 返回实际玩家数量
        // e.g., return HytaleServer.getOnlinePlayers().size();
        return 0;
    }

    /**
     * 检测是否运行在真实的 Hytale 服务器环境中
     * 通过反射检查 Hytale Server API 核心类是否存在
     * @return 如果在真实 Hytale 服务器上运行则返回 true
     */
    private boolean isHytaleServerEnvironment() {
        try {
            // 检查 Hytale 服务器核心类是否可用
            Class.forName("com.hypixel.hytale.server.core.HytaleServer");
            Class.forName("com.hypixel.hytale.server.core.universe.Universe");
            return true;
        } catch (ClassNotFoundException e) {
            // 独立运行或非 Hytale 平台
            return false;
        }
    }

    // ========== 访问器方法 ==========

    /** 获取插件单例实例 */
    public static HycompanionPlugin getInstance() {
        return instance;
    }

    public PluginConfig getConfig() {
        return config;
    }

    public PluginLogger getLogger() {
        return logger;
    }

    public SocketManager getSocketManager() {
        return socketManager;
    }

    public NpcManager getNpcManager() {
        return npcManager;
    }

    public HytaleAPI getHytaleAPI() {
        return hytaleAPI;
    }

    public ChatHandler getChatHandler() {
        return chatHandler;
    }

    public Path getDataFolder() {
        return dataFolder;
    }

    public boolean isEnabled() {
        return enabled;
    }

    // ========== 独立测试模式入口 ==========

    /**
     * 独立测试模式的 main 方法
     * 启动插件并提供交互式控制台，用于在没有 Hytale 服务器的情况下测试 NPC 对话
     */
    public static void main(String[] args) {
        System.out.println("╔════════════════════════════════════════════════════════════╗");
        System.out.println("║         HYCOMPANION - Standalone Test Mode                 ║");
        System.out.println("║         Interactive Console for Testing                    ║");
        System.out.println("╚════════════════════════════════════════════════════════════╝");
        System.out.println();

        HycompanionPlugin plugin = new HycompanionPlugin();
        plugin.onEnable();

        // 注册 JVM 关闭钩子，确保优雅退出
        Runtime.getRuntime().addShutdownHook(new Thread(plugin::onDisable));

        // 等待与后端建立连接（最多 15 秒）
        System.out.println("\nWaiting for backend connection...");
        int attempts = 0;
        while (!plugin.isConnected() && attempts < 30) {
            try {
                Thread.sleep(500);
                attempts++;
            } catch (InterruptedException e) {
                break;
            }
        }

        if (!plugin.isConnected()) {
            System.out.println("WARNING: Not connected to backend after 15 seconds.");
            System.out.println("Check your config.yml settings (url, api_key)");
        }

        // 启动交互式控制台
        runInteractiveConsole(plugin);
    }

    /**
     * 交互式控制台 - 用于测试与 NPC 的聊天
     * 支持的命令：/chat、/list、/sync、/status、/reload、/npc、/player、/quit 等
     * 也支持快捷聊天：设置默认 NPC 后直接输入消息即可发送
     */
    private static void runInteractiveConsole(HycompanionPlugin plugin) {
        java.util.Scanner scanner = new java.util.Scanner(System.in);

        System.out.println("\n┌─────────────────────────────────────────────────────────────┐");
        System.out.println("│              INTERACTIVE CONSOLE MODE                       │");
        System.out.println("├─────────────────────────────────────────────────────────────┤");
        System.out.println("│  Commands:                                                  │");
        System.out.println("│    /chat <npcId> <message>  - Send chat to NPC              │");
        System.out.println("│    /list                    - List loaded NPCs              │");
        System.out.println("│    /sync                    - Force NPC sync                │");
        System.out.println("│    /status                  - Show connection status        │");
        System.out.println("│    /reload                  - Reload config                 │");
        System.out.println("│    /help                    - Show this help                │");
        System.out.println("│    /quit                    - Exit test mode                │");
        System.out.println("│                                                             │");
        System.out.println("│  Quick chat (after setting default NPC):                    │");
        System.out.println("│    /npc <npcId>             - Set default NPC for chat      │");
        System.out.println("│    <message>                - Send to default NPC           │");
        System.out.println("└─────────────────────────────────────────────────────────────┘");
        System.out.println();

        String defaultNpcId = null;              // 默认聊天目标 NPC ID
        String testPlayerId = "test-player-001"; // 测试玩家 ID
        String testPlayerName = "TestPlayer";    // 测试玩家名称

        System.out.print("> ");
        while (scanner.hasNextLine()) {
            String input = scanner.nextLine().trim();

            if (input.isEmpty()) {
                System.out.print("> ");
                continue;
            }

            if (input.startsWith("/")) {
                // 命令模式：解析并执行控制台命令
                String[] parts = input.substring(1).split("\\s+", 3);
                String cmd = parts[0].toLowerCase();

                switch (cmd) {
                    case "quit", "exit", "q" -> {
                        System.out.println("Goodbye!");
                        plugin.onDisable();
                        System.exit(0);
                    }
                    case "help", "?" -> {
                        System.out.println("Commands:");
                        System.out.println("  /chat <npcId> <message> - Send chat to NPC");
                        System.out.println("  /npc <npcId>           - Set default NPC");
                        System.out.println("  /list                  - List NPCs");
                        System.out.println("  /sync                  - Force sync");
                        System.out.println("  /status                - Connection status");
                        System.out.println("  /reload                - Reload config");
                        System.out.println("  /quit                  - Exit");
                    }
                    case "status" -> {
                        boolean connected = plugin.isConnected();
                        int npcCount = plugin.getNpcManager().getNpcCount();
                        System.out.println("Connection: " + (connected ? "✓ Connected" : "✗ Disconnected"));
                        System.out.println("NPCs loaded: " + npcCount);
                        System.out.println("Default NPC: " + (defaultNpcId != null ? defaultNpcId : "(not set)"));
                        System.out.println("Test player: " + testPlayerName + " (" + testPlayerId + ")");
                    }
                    case "sync" -> {
                        plugin.forceSync();
                        System.out.println("Sync requested...");
                    }
                    case "reload" -> {
                        plugin.reload();
                        System.out.println("Config reloaded.");
                    }
                    case "list" -> {
                        var npcs = plugin.getNpcManager().getAllNpcs();
                        if (npcs.isEmpty()) {
                            System.out.println("No NPCs loaded. Try /sync first.");
                        } else {
                            System.out.println("Loaded NPCs (" + npcs.size() + "):");
                            for (var npc : npcs) {
                                System.out.println("  - " + npc.externalId() + " (" + npc.name() + ")");
                            }
                        }
                    }
                    case "npc" -> {
                        if (parts.length < 2) {
                            System.out.println("Usage: /npc <npcId>");
                            System.out.println("Current: " + (defaultNpcId != null ? defaultNpcId : "(not set)"));
                        } else {
                            defaultNpcId = parts[1];
                            System.out.println("Default NPC set to: " + defaultNpcId);
                        }
                    }
                    case "player" -> {
                        if (parts.length < 2) {
                            System.out.println("Usage: /player <name>");
                            System.out.println("Current: " + testPlayerName);
                        } else {
                            testPlayerName = parts[1];
                            System.out.println("Test player name set to: " + testPlayerName);
                        }
                    }
                    case "chat" -> {
                        if (parts.length < 3) {
                            System.out.println("Usage: /chat <npcId> <message>");
                        } else {
                            String npcId = parts[1];
                            String message = parts[2];
                            sendTestChat(plugin, npcId, testPlayerId, testPlayerName, message);
                        }
                    }
                    default -> System.out.println("Unknown command: " + cmd + ". Type /help for help.");
                }
            } else {
                // 快捷聊天模式：直接发送消息到默认 NPC
                if (defaultNpcId == null) {
                    System.out.println("No default NPC set. Use /npc <npcId> or /chat <npcId> <message>");
                } else {
                    sendTestChat(plugin, defaultNpcId, testPlayerId, testPlayerName, input);
                }
            }

            System.out.print("> ");
        }
    }

    /**
     * 发送测试聊天消息到后端
     * 构建模拟的游戏上下文（位置、时间、天气），通过 SocketManager 发送到云后端
     *
     * @param plugin     插件实例
     * @param npcId      目标 NPC 的 ID
     * @param playerId   测试玩家 ID
     * @param playerName 测试玩家名称
     * @param message    聊天消息内容
     */
    private static void sendTestChat(HycompanionPlugin plugin, String npcId, String playerId, String playerName,
            String message) {
        if (!plugin.isConnected()) {
            System.out.println("ERROR: Not connected to backend!");
            return;
        }

        System.out.println("[" + playerName + " → " + npcId + "]: " + message);

        // 构建模拟的游戏上下文信息
        var context = new com.google.gson.JsonObject();
        context.addProperty("location", "0,64,0");
        context.addProperty("timeOfDay", "noon");
        context.addProperty("weather", "clear");

        // 通过 SocketManager 发送聊天消息到后端
        plugin.getSocketManager().sendChat(npcId, null, playerId, playerName, message, context);

        System.out.println("(Waiting for response...)");
    }
}
