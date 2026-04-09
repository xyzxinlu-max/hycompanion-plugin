package dev.hycompanion.plugin;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.server.npc.AllNPCsLoadedEvent;
import com.hypixel.hytale.server.core.event.events.ShutdownEvent;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerChatEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import dev.hycompanion.plugin.adapter.HytaleServerAdapter;
import dev.hycompanion.plugin.api.HytaleAPI;
import dev.hycompanion.plugin.api.ServerInfo;
import dev.hycompanion.plugin.commands.HycompanionCommand;
import dev.hycompanion.plugin.config.PluginConfig;
import dev.hycompanion.plugin.config.NpcConfigManager;
import dev.hycompanion.plugin.core.context.ContextBuilder;
import dev.hycompanion.plugin.core.npc.NpcGreetingService;
import dev.hycompanion.plugin.core.npc.NpcManager;
import dev.hycompanion.plugin.handlers.ActionExecutor;
import dev.hycompanion.plugin.handlers.ChatHandler;
import dev.hycompanion.plugin.network.SocketManager;
import dev.hycompanion.plugin.role.RoleGenerator;
import dev.hycompanion.plugin.shutdown.ShutdownManager;
import dev.hycompanion.plugin.systems.NpcRespawnSystem;
import dev.hycompanion.plugin.utils.PluginLogger;
import io.sentry.Sentry;
import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;

/**
 * Hycompanion Plugin Entry Point for Hytale Server
 * Hycompanion 插件 - Hytale 服务器入口点
 *
 * 继承 JavaPlugin，作为插件被真实 Hytale 服务器加载时的主入口。
 * 管理完整的插件生命周期：setup（预加载）-> start（启动）-> shutdown（关闭）
 *
 * 与 HycompanionPlugin（独立测试模式）不同，本类直接与 Hytale Server API 交互，
 * 包括事件注册、命令注册、NPC 实体发现、玩家聊天监听等。
 *
 * @author Hycompanion Team
 * @version 1.1.6
 */
public class HycompanionEntrypoint extends JavaPlugin {

    // Hytale 原生日志记录器
    private static final HytaleLogger HYTALE_LOGGER = HytaleLogger.forEnclosingClass();
    // 插件版本号
    public static final String VERSION = "1.1.6-SNAPSHOT";

    // ===== 插件核心组件 =====
    private PluginLogger logger;               // 插件日志记录器
    private PluginConfig config;               // 插件配置
    private HytaleAPI hytaleAPI;               // Hytale API 抽象层（真实服务器适配器）
    private SocketManager socketManager;       // Socket.IO 网络连接管理器
    private NpcManager npcManager;             // NPC 数据与实例管理器
    private NpcConfigManager npcConfigManager; // NPC 配置缓存管理器
    private ActionExecutor actionExecutor;     // 后端动作执行器
    private ChatHandler chatHandler;           // 玩家聊天处理器
    private ContextBuilder contextBuilder;     // 游戏上下文构建器
    private RoleGenerator roleGenerator;       // NPC 角色文件生成器（管理 Hytale 数据资产）
    private NpcGreetingService greetingService;// NPC 靠近玩家时的问候服务
    private Path dataFolder;                   // 插件数据文件夹路径

    // 插件后台任务的守护线程调度器
    private java.util.concurrent.ScheduledExecutorService pluginScheduler;

    // 集中式关闭管理器 - 关闭状态的唯一来源
    private ShutdownManager shutdownManager;

    // 标记实体发现是否已执行（volatile 保证线程可见性）
    private volatile boolean entityDiscoveryDone = false;

    /**
     * 构造方法 - Hytale 插件加载器所需
     * 由 Hytale 服务器在加载插件时自动调用
     *
     * @param init Hytale 提供的插件初始化数据
     */
    public HycompanionEntrypoint(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
        HYTALE_LOGGER.atInfo().log("Hycompanion plugin constructor called - version " + VERSION);
    }

    /**
     * 获取关闭管理器，供其他组件注册关闭回调
     */
    public ShutdownManager getShutdownManager() {
        return shutdownManager;
    }

    /**
     * 检查服务器是否正在关闭中
     * 供 SocketManager 等组件使用，防止在关闭期间执行新操作
     */
    public static boolean isShuttingDown() {
        return getInstance() != null && getInstance().shutdownManager != null 
            && getInstance().shutdownManager.isShuttingDown();
    }

    // 单例实例
    private static HycompanionEntrypoint instance;

    /** 获取插件单例实例 */
    public static HycompanionEntrypoint getInstance() {
        return instance;
    }

    /**
     * 预加载阶段 - 在资源加载之前运行
     *
     * 注意：Hytale 在 setup() 阶段会阻止网络访问。
     * 因此只能在此加载上次运行时缓存的角色文件。
     * 角色同步将在 start() 阶段进行，同步后缓存供下次启动使用。
     *
     * 主要工作：
     * 1. 初始化日志和关闭管理器
     * 2. 配置 Sentry 错误追踪（可选）
     * 3. 加载配置文件
     * 4. 初始化角色生成器并加载缓存的角色文件
     * 5. 注册 Hytale 事件监听器
     */
    @Override
    protected void setup() {
        HYTALE_LOGGER.atInfo().log("Hycompanion setup() starting...");

        // 首先初始化日志记录器（ShutdownManager 需要它）
        logger = new PluginLogger("Hycompanion");

        // 尽早初始化集中式关闭管理器
        shutdownManager = new ShutdownManager(logger);
        HYTALE_LOGGER.atInfo().log("ShutdownManager initialized");

        try {

            // 通过环境变量 SENTRY_DSN 配置 Sentry 错误追踪
            // 设置 SENTRY_DSN 环境变量以启用错误追踪
            String sentryDsn = System.getenv("SENTRY_DSN");
            if (sentryDsn != null && !sentryDsn.isEmpty()) {
                Sentry.init(
                        options -> {
                            options.setDsn(sentryDsn);
                            options.setSendDefaultPii(true);

                            // All events get assigned to the release. See more at
                            // https://docs.sentry.io/workflow/releases/
                            // options.setRelease("io.sentry.samples.console@3.0.0+1");

                            // Configure the background worker which sends events to sentry:
                            // Wait up to 5 seconds before shutdown while there are events to send.
                            options.setShutdownTimeoutMillis(1000);

                            // Enable SDK logging with Debug level
                            // options.setDebug(true);
                            // To change the verbosity, use:
                            // By default it's DEBUG.
                            // options.setDiagnosticLevel(SentryLevel.ERROR);
                            // A good option to have SDK debug log in prod is to use only level ERROR here.

                            // Exclude frames from some packages from being "inApp" so are hidden by default
                            // in Sentry
                            // UI:
                            // options.addInAppExclude("org.jboss");

                            // Include frames from our package
                            // options.addInAppInclude("io.sentry.samples");

                            // Performance configuration options
                            // Set what percentage of traces should be collected
                            options.setTracesSampleRate(1.0); // set 0.5 to send 50% of traces

                            // Determine traces sample rate based on the sampling context
                            // options.setTracesSampler(
                            // context -> {
                            // // only 10% of transactions with "/product" prefix will be collected
                            // if (!context.getTransactionContext().getName().startsWith("/products"))
                            // {
                            // return 0.1;
                            // } else {
                            // return 0.5;
                            // }
                            // });
                        });
                logger.info("Sentry error tracking enabled");
            } else {
                logger.info("Sentry DSN not configured - error tracking disabled");
            }

            logger.info("Setup phase - initializing...");

            // 设置全局未捕获异常处理器，捕获所有未处理的异常
            Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
                logger.error("Uncaught exception in thread " + thread.getName() + ": " + throwable.getMessage());
                if (sentryDsn != null && !sentryDsn.isEmpty()) {
                    Sentry.captureException(throwable);
                }
            });
            if (sentryDsn != null && !sentryDsn.isEmpty()) {
                logger.info("Global uncaught exception handler registered with Sentry");
            } else {
                logger.info("Global uncaught exception handler registered (Sentry disabled)");
            }

            // 获取数据文件夹并提前加载配置
            dataFolder = getDataDirectory();
            HYTALE_LOGGER.atInfo().log("Data directory: " + dataFolder.toString());

            Files.createDirectories(dataFolder);
            copyDefaultConfig();
            config = PluginConfig.load(dataFolder.resolve("config.yml"));
            logger.setDebugMode(config.gameplay().debugMode());

            logger.info("Configuration loaded from " + dataFolder.resolve("config.yml"));

            // 检查 API 密钥是否已配置
            String apiKey = config.connection().apiKey();
            if (apiKey == null || apiKey.isEmpty() || apiKey.equals("YOUR_SERVER_API_KEY")) {
                logger.warn("========================================================");
                logger.warn("  API KEY NOT CONFIGURED!");
                logger.warn("  Your Hycompanion API key is not set in config.yml");
                logger.warn("  ");
                logger.warn("  Get your API key from: https://app.hycompanion.dev");
                logger.warn("  Then use: /hycompanion register <your-api-key>");
                logger.warn("  Or edit config.yml directly");
                logger.warn("========================================================");
            }

            // 初始化角色生成器
            // modDirectory 为 mods/<plugin-id>/，例如 mods/dev.hycompanion_Hycompanion/
            // 注意：getIdentifier() 使用 ":" 但文件夹使用 "_"（兼容 Windows）
            Path modsFolder = dataFolder.getParent();
            String modFolderName = getIdentifier().toString().replace(":", "_");
            Path modDirectory = modsFolder.resolve(modFolderName);
            HYTALE_LOGGER.atInfo().log("Mod directory for role files: " + modDirectory.toString());
            roleGenerator = new RoleGenerator(modDirectory, dataFolder, logger);

            // 从上次运行中加载缓存的角色文件
            // （setup 阶段网络不可用 - 角色同步在 start() 阶段进行）
            int cachedRoles = roleGenerator.loadCachedRoles();
            if (cachedRoles > 0) {
                logger.info("Loaded " + cachedRoles + " cached NPC role files from previous session");
            } else {
                logger.info("No cached roles (first run) - NPCs will sync after server starts");
                logger.info("========================================================");
                logger.info("  FIRST RUN NOTICE");
                logger.info("  Custom NPC roles will be synced and cached during this session.");
                logger.info("  To apply custom NPC appearances/stats, RESTART the server");
                logger.info("  after this session. Cached roles will then be loaded.");
                logger.info("========================================================");
            }

            // 注册 AllNPCsLoadedEvent 以触发实体发现
            // 当 Hytale 完成加载/重载 NPC 实体时触发此事件
            getEventRegistry().register(AllNPCsLoadedEvent.class, event -> onAllNpcsLoaded(event));

            // 注册玩家首次加入世界事件，作为实体发现的备用触发器
            // 区块在玩家连接前不会加载，因此实体不能更早被发现
            getEventRegistry().registerGlobal(AddPlayerToWorldEvent.class, this::onPlayerAddedToWorld);

            // 注册玩家断开连接事件，清除 NPC 跟随目标
            // 防止关闭期间出现"无效实体引用"错误
            getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, this::onPlayerDisconnect);

            // 注册早期关闭事件，在世界关闭前清除 NPC 引用
            // 优先级 -48 = DISCONNECT_PLAYERS 阶段 - 在玩家被移除前清除 NPC
            getEventRegistry().register((short)-48, ShutdownEvent.class, this::onServerShutdown);

            // 注册 NPC 死亡检测系统（用于 NPC 重生）
            com.hypixel.hytale.server.core.universe.world.storage.EntityStore.REGISTRY.registerSystem(new NpcRespawnSystem());
            logger.info("Registered NPC respawn death detection system");
            
            logger.info("Registered event listeners for entity discovery, player disconnect, and shutdown");

            logger.info("Setup phase complete");
            HYTALE_LOGGER.atInfo().log("Hycompanion setup() complete");

        } catch (Exception e) {
            HYTALE_LOGGER.atSevere().log("Failed during setup phase: " + e.getMessage());
            Sentry.captureException(e);
            if (logger != null) {
                logger.error("Failed during setup phase: " + e.getMessage());
            }
            e.printStackTrace();
        }
    }

    /**
     * 启动阶段 - 在资源加载完成后运行
     *
     * 此阶段网络已可用，执行完整的插件初始化：
     * 1. 确保配置和数据文件夹已就绪（兼容 setup 未执行的情况）
     * 2. 初始化 Hytale 真实服务器 API 适配器
     * 3. 初始化各管理器和处理器
     * 4. 建立与后端的 Socket.IO 连接
     * 5. 注册事件监听器和命令
     * 6. 启动 NPC 靠近问候服务
     */
    @Override
    protected void start() {
        HYTALE_LOGGER.atInfo().log("Hycompanion start() beginning...");

        // 日志和配置已在 setup() 中初始化
        // 如果 setup() 未被调用，则在此初始化
        if (logger == null) {
            logger = new PluginLogger("Hycompanion");
        }

        logger.info("======================================");
        logger.info("  Hycompanion Plugin v" + VERSION);
        logger.info("  https://hycompanion.dev");
        logger.info("  Running on Hytale Server");
        logger.info("  Sponsor: https://verygames.com - Host your Hytale server with us!");
        logger.info("======================================");

        try {
            // 确保数据文件夹存在（正常情况下 setup 阶段已创建）
            if (dataFolder == null) {
                dataFolder = getDataDirectory();
                Files.createDirectories(dataFolder);
            }

            // 如果需要则重新加载配置（正常情况下 setup 阶段已加载）
            if (config == null) {
                copyDefaultConfig();
                config = PluginConfig.load(dataFolder.resolve("config.yml"));
                logger.setDebugMode(config.gameplay().debugMode());
            }

            // 如果 setup 阶段未初始化角色生成器，则在此初始化
            if (roleGenerator == null) {
                Path modsFolder = dataFolder.getParent();
                String modFolderName = getIdentifier().toString().replace(":", "_");
                Path modDirectory = modsFolder.resolve(modFolderName);
                roleGenerator = new RoleGenerator(modDirectory, dataFolder, logger);
            }

            logger.info("Configuration loaded successfully");

            // 如果 setup() 中未初始化关闭管理器，则在此初始化
            if (shutdownManager == null) {
                shutdownManager = new ShutdownManager(logger);
                logger.info("ShutdownManager initialized");
            }
            
            // 注册 JVM 关闭钩子，用于早期检测关闭信号
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("JVM shutdown hook triggered - initiating early cleanup");
                if (shutdownManager != null) {
                    shutdownManager.initiateShutdown();
                }
            }, "Hycompanion-Shutdown-Hook"));
            
            // 使用真实服务器 API 初始化 Hytale API 适配器
            hytaleAPI = new HytaleServerAdapter(logger, this, shutdownManager);
            logger.info("Hytale API adapter initialized (Real Server Mode)");

            // 初始化各管理器
            npcManager = new NpcManager(logger, hytaleAPI);
            npcConfigManager = new NpcConfigManager(dataFolder.resolve(config.npc().cacheDirectory()), logger);
            contextBuilder = new ContextBuilder(hytaleAPI, logger);

            // 创建守护线程调度器，用于插件后台任务（如 NPC 靠近检测）
            pluginScheduler = java.util.concurrent.Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "Hycompanion-Background-Worker");
                t.setDaemon(true);
                return t;
            });

            // 初始化动作执行器（处理后端返回的 MCP 工具动作）
            actionExecutor = new ActionExecutor(hytaleAPI, npcManager, logger, config);

            // 初始化聊天处理器（处理玩家与 NPC 的对话）
            chatHandler = new ChatHandler(npcManager, contextBuilder, logger, config);
            chatHandler.setHytaleAPI(hytaleAPI);

            // 将 ActionExecutor 与 ChatHandler 双向关联（通过 setter 解决循环依赖）
            actionExecutor.setChatHandler(chatHandler);

            // 初始化 Socket.IO 连接并连接后端（传入 roleGenerator 用于角色缓存）
            initializeSocketConnection();

            // 注册 Hytale 事件监听器（玩家聊天等）
            registerEventListeners();

            // 注册插件命令（/hycompanion）
            registerCommands();

            // 初始化并启动 NPC 靠近问候服务（每秒检测一次玩家是否靠近 NPC）
            greetingService = new NpcGreetingService(hytaleAPI, npcManager, config, logger);
            greetingService.startProximityChecks(
                    pluginScheduler,
                    1000 // 每 1 秒检测一次
            );

            logger.info("Hycompanion enabled successfully on Hytale Server!");
            HYTALE_LOGGER.atInfo().log("Hycompanion start() complete - plugin fully loaded");

        } catch (Exception e) {
            logger.error("Failed to enable Hycompanion", e);
            HYTALE_LOGGER.atSevere().log("Failed to enable Hycompanion: " + e.getMessage());
            Sentry.captureException(e);
            e.printStackTrace();
            // Don't throw exception - let server continue even if we fail
        }
    }

    /**
     * 最终关闭阶段 - 在服务器关闭时由 Hytale 调用
     *
     * 注意：早期清理工作已在 onServerShutdown()（优先级 -48）中完成，
     * 包括断开 Socket 连接、停止定期任务、设置关闭标志。
     *
     * 此方法执行最终清理：
     * 步骤 1: 清除实体引用（此时 Hytale 区块保存已完成，可以安全操作）
     * 步骤 2: 阻止世界操作
     * 步骤 3: 关闭后台调度器和聊天处理器
     * 步骤 4: 保存 NPC 配置
     * 步骤 5: 关闭 Sentry
     */
    @Override
    protected void shutdown() {
        long startTime = System.currentTimeMillis();
        String threadName = Thread.currentThread().getName();

        HYTALE_LOGGER.atInfo().log("Hycompanion shutdown() starting on thread: " + threadName);

        if (logger != null) {
            logger.debug("[Shutdown] ============================================");
            logger.debug("[Shutdown] FINAL SHUTDOWN PHASE STARTED");
            logger.debug("[Shutdown] Thread: " + threadName);
            logger.debug("[Shutdown] Timestamp: " + startTime);
            logger.debug("[Shutdown] ============================================");
        }

        // NOTE: Early cleanup was already done in onServerShutdown() which runs
        // at ShutdownEvent priority -48 (before player removal).
        // This includes: disconnecting socket, stopping periodic tasks, clearing entity refs.
        // See onServerShutdown() for early shutdown handling.

        // STEP 1: Clear entity references (deferred from early shutdown)
        // This is now safe to do because Hytale's chunk saving is complete
        logger.debug("[Shutdown] Step 1/5: Clearing entity references...");
        long stepStart = System.currentTimeMillis();
        if (hytaleAPI != null) {
            ((HytaleServerAdapter) hytaleAPI).clearAllEntityReferences();
            logger.debug("[Shutdown] Step 1/5: Entity references cleared in " + (System.currentTimeMillis() - stepStart) + "ms");
        } else {
            logger.warn("[Shutdown] Step 1/5: HytaleAPI is null");
        }

        // STEP 2: Block world operations
        logger.debug("[Shutdown] Step 2/5: Blocking world operations...");
        stepStart = System.currentTimeMillis();
        if (shutdownManager != null) {
            shutdownManager.blockWorldOperations();
            logger.debug("[Shutdown] Step 2/5: World operations blocked in " + (System.currentTimeMillis() - stepStart) + "ms");
        } else {
            logger.warn("[Shutdown] Step 2/5: ShutdownManager is null");
        }

        // STEP 3: Shutdown plugin scheduler and chat handler
        logger.debug("[Shutdown] Step 3/5: Shutting down plugin scheduler and chat handler...");
        stepStart = System.currentTimeMillis();
        if (pluginScheduler != null) {
            try {
                pluginScheduler.shutdownNow();
                logger.debug("[Shutdown] Plugin scheduler shutdownNow() called");
                if (!pluginScheduler.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    if (logger != null)
                        logger.warn("[Shutdown] Plugin scheduler did not terminate within 2s timeout");
                } else {
                    logger.debug("[Shutdown] Plugin scheduler terminated successfully");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("[Shutdown] Interrupted while waiting for plugin scheduler");
                Sentry.captureException(e);
            } catch (Exception e) {
                logger.error("[Shutdown] Error shutting down plugin scheduler: " + e.getMessage());
                Sentry.captureException(e);
            } finally {
                pluginScheduler = null;
            }
        } else {
            logger.debug("[Shutdown] Plugin scheduler is null, skipping");
        }
        
        // Shutdown chat handler timeout scheduler
        if (chatHandler != null) {
            try {
                chatHandler.shutdown();
                logger.debug("[Shutdown] ChatHandler shutdown complete");
            } catch (Exception e) {
                logger.error("[Shutdown] Error shutting down ChatHandler: " + e.getMessage());
                Sentry.captureException(e);
            }
        } else {
            logger.debug("[Shutdown] ChatHandler is null, skipping");
        }
        logger.debug("[Shutdown] Step 3/5: Plugin scheduler and chat handler handled in " + (System.currentTimeMillis() - stepStart) + "ms");

        // STEP 4: Save NPC configurations
        logger.debug("[Shutdown] Step 4/5: Saving NPC configurations...");
        stepStart = System.currentTimeMillis();
        if (npcConfigManager != null) {
            try {
                npcConfigManager.saveAll();
                logger.debug("[Shutdown] Step 4/5: NPC configurations saved in " + (System.currentTimeMillis() - stepStart) + "ms");
            } catch (Exception e) {
                logger.error("[Shutdown] Step 4/5: Error saving NPC configs: " + e.getMessage());
                Sentry.captureException(e);
            }
        } else {
            logger.debug("[Shutdown] Step 4/5: NpcConfigManager is null, skipping");
        }
        
        // STEP 5: Close Sentry
        logger.debug("[Shutdown] Step 5/5: Closing Sentry...");
        stepStart = System.currentTimeMillis();
        try {
            io.sentry.Sentry.close();
            logger.debug("[Shutdown] Step 5/5: Sentry closed in " + (System.currentTimeMillis() - stepStart) + "ms");
        } catch (Exception e) {
            logger.debug("[Shutdown] Step 5/5: Error closing Sentry (ignored): " + e.getMessage());
        }

        long totalTime = System.currentTimeMillis() - startTime;
        if (logger != null) {
            logger.debug("[Shutdown] ============================================");
            logger.debug("[Shutdown] FINAL SHUTDOWN COMPLETE");
            logger.debug("[Shutdown] Total time: " + totalTime + "ms");
            logger.debug("[Shutdown] ============================================");
        }

        HYTALE_LOGGER.atInfo().log("Hycompanion shutdown() complete in " + totalTime + "ms");
        instance = null;
    }

    /**
     * 初始化与后端的 Socket.IO 连接
     * 创建 SocketManager，注册重连回调，建立 ChatHandler 与 SocketManager 的双向关联
     */
    private void initializeSocketConnection() {
        // 创建服务器信息用于握手认证
        ServerInfo serverInfo = new ServerInfo(
                VERSION,
                getOnlinePlayerCount());

        socketManager = new SocketManager(
                config.connection().url(),
                config.connection().apiKey(),
                serverInfo,
                actionExecutor,
                npcManager,
                npcConfigManager,
                roleGenerator,
                logger,
                config,
                hytaleAPI);

        // 注册重连后实体发现的回调
        socketManager.setOnReconnectSyncComplete(this::runEntityDiscoveryAfterReconnect);

        // 建立 ChatHandler 与 SocketManager 的双向关联
        chatHandler.setSocketManager(socketManager);
        socketManager.setChatHandler(chatHandler);

        logger.info("Connecting to backend: " + config.connection().url());
        socketManager.connect();
    }

    /**
     * 从资源文件中复制默认的 config.yml（如果尚不存在）
     */
    private void copyDefaultConfig() {
        Path configPath = dataFolder.resolve("config.yml");
        if (!Files.exists(configPath)) {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
                if (in != null) {
                    Files.copy(in, configPath, StandardCopyOption.REPLACE_EXISTING);
                    HYTALE_LOGGER.atInfo().log("Default config.yml created at " + configPath);
                } else {
                    HYTALE_LOGGER.atWarning().log("config.yml not found in resources");
                }
            } catch (IOException e) {
                HYTALE_LOGGER.atSevere().log("Failed to copy default config: " + e.getMessage());
            }
        }
    }

    /**
     * 向 Hytale 事件系统注册事件监听器
     * 主要注册玩家聊天事件，当玩家发送消息时检测附近 NPC 并转发到后端
     */
    private void registerEventListeners() {
        EventRegistry eventRegistry = getEventRegistry();
        logger.info("Registering event listeners...");

        // 注册玩家聊天事件监听器，用于 NPC 交互
        eventRegistry.registerGlobal(PlayerChatEvent.class, event -> {
            try {
                // 获取玩家信息
                String playerUuid = event.getSender().getUuid().toString();
                String playerName = event.getSender().getUsername();
                String message = event.getContent();

                // 跳过空消息或以 "/" 开头的命令
                if (message == null || message.isEmpty() || message.startsWith("/")) {
                    return;
                }

                logger.debug("Player chat: " + playerName + " -> " + message);

                // 从玩家的 Transform 组件获取位置坐标
                com.hypixel.hytale.math.vector.Transform playerTransform = event.getSender().getTransform();
                com.hypixel.hytale.math.vector.Vector3d playerPos = playerTransform.getPosition();

                dev.hycompanion.plugin.api.Location playerLocation = new dev.hycompanion.plugin.api.Location(
                        playerPos.getX(),
                        playerPos.getY(),
                        playerPos.getZ(),
                        hytaleAPI.getWorldName());

                // 创建 GamePlayer 对象供聊天处理器使用
                dev.hycompanion.plugin.api.GamePlayer gamePlayer = new dev.hycompanion.plugin.api.GamePlayer(
                        playerUuid,
                        playerName,
                        event.getSender().getUuid(),
                        playerLocation);

                // 使用 ChatHandler 处理聊天（内置 NPC 靠近检测逻辑）
                // 使用 config.gameplay().chatRange() 查找附近的 NPC
                boolean handled = chatHandler.handleChat(gamePlayer, message);

                if (handled) {
                    logger.debug("Chat message routed to NPC for player: " + playerName);
                } else {
                    logger.debug("No nearby NPCs for player: " + playerName);
                }
            } catch (Exception e) {
                logger.error("Error processing player chat event: " + e.getMessage());
                Sentry.captureException(e);
            }
        });

        logger.info("Event listeners registered with Hytale server");
    }

    /**
     * 向 Hytale 命令系统注册插件命令
     * 注册 /hycompanion 命令（别名：/hyc、/hc）
     */
    private void registerCommands() {
        logger.info("Registering commands...");
        getCommandRegistry().registerCommand(new HycompanionCommand(this));
        logger.info("Commands registered: /hycompanion (aliases: /hyc, /hc)");
    }

    /**
     * 从 Hytale 服务器获取当前在线玩家数量
     */
    private int getOnlinePlayerCount() {
        try {
            return com.hypixel.hytale.server.core.universe.Universe.get().getPlayers().size();
        } catch (Exception e) {
            Sentry.captureException(e);
            return 0;
        }
    }

    // ========== 访问器方法 ==========

    /** 获取插件配置 */
    public PluginConfig getPluginConfig() {
        return config;
    }

    public PluginLogger getPluginLogger() {
        return logger;
    }

    public HytaleAPI getHytaleAPI() {
        return hytaleAPI;
    }

    public SocketManager getSocketManager() {
        return socketManager;
    }

    public NpcManager getNpcManager() {
        return npcManager;
    }

    public ChatHandler getChatHandler() {
        return chatHandler;
    }

    public RoleGenerator getRoleGenerator() {
        return roleGenerator;
    }

    public Path getPluginDataFolder() {
        return dataFolder;
    }

    // ========== 事件处理器 ==========

    /**
     * 当 Hytale 完成加载/重载所有 NPC 实体时调用
     * 这是发现和绑定上次会话中持久化的 NPC 实体的最佳时机。
     *
     * 重要：始终强制重新发现，因为实体引用可能在重载后失效。
     * 处理以下两种情况：
     * - 首次启动（初始发现）
     * - 重连后（角色文件变更触发实体重载，导致引用失效）
     */
    private void onAllNpcsLoaded(AllNPCsLoadedEvent event) {
        logger.info("AllNPCsLoadedEvent received - scheduling entity discovery...");
        logger.info("  Total NPCs in server: " + event.getAllNPCs().size());
        logger.info("  NPCs loaded this cycle: " + event.getLoadedNPCs().size());

        // 此事件触发时始终强制重新发现，因为实体引用可能在重载后失效
        // （例如重连期间角色文件变更后）。确保始终拥有新鲜的实体引用。
        scheduleEntityDiscovery("NPCsLoaded", 2000, true);
    }

    /**
     * 当玩家加入世界时调用
     * 作为 AllNPCsLoadedEvent 未找到实体时的备用发现触发器
     * 同时向管理员玩家显示 API 密钥未配置或需要重启的提示消息
     */
    private void onPlayerAddedToWorld(AddPlayerToWorldEvent event) {

        // 获取 Player 实体组件以检查权限
        Player player = event.getHolder().getComponent(Player.getComponentType());
        boolean isAdmin = player != null && (player.hasPermission("*") || player.hasPermission("hycompanion.admin"));

        // 检查 API 密钥是否未设置（为空、null 或默认值）
        String apiKey = config.connection().apiKey();
        boolean isKeySet = apiKey != null && !apiKey.trim().isEmpty() && !"YOUR_SERVER_API_KEY".equals(apiKey);

        if (!isKeySet && isAdmin) {
            // 向管理员玩家发送 API 密钥未配置的提示消息
            player.sendMessage(
                    Message.raw("Hycompanion API key not set. Please use /hycompanion register [key] to set it.")
                            .color("#FF5555"));
            player.sendMessage(Message.raw("You can get a key on https://app.hycompanion.dev")
                    .color("#AAAAAA"));
        }

        // 检查本次会话是否创建了新的资产包清单（需要重启服务器才能应用 NPC 角色）
        if (roleGenerator != null && roleGenerator.isManifestCreatedThisSession() && isAdmin) {
            player.sendMessage(Message.raw("").color("#FF5555"));
            player.sendMessage(
                    Message.raw("[Hycompanion] SERVER RESTART REQUIRED!")
                            .color("#FF0000"));
            player.sendMessage(
                    Message.raw("The asset pack manifest was just created.")
                            .color("#FF5555"));
            player.sendMessage(
                    Message.raw("Please restart the server to enable NPC roles.")
                            .color("#FF5555"));
            player.sendMessage(Message.raw("").color("#FF5555"));
        }

        // 验证现有 NPC 并在需要时触发重新发现
        // 处理玩家断开连接后实体引用变为陈旧的情况
        if (entityDiscoveryDone) {
            boolean hasValidNpcs = validateAndRediscoverIfNeeded();
            if (hasValidNpcs) {
                logger.debug("Entity discovery already done and valid NPCs exist, skipping rediscovery");
                return;
            }
            // validateAndRediscoverIfNeeded already triggered rediscovery, just log
            logger.info("Entity discovery was done but no valid NPCs found - rediscovery triggered");
            return;
        }

        logger.info("First player joining world - triggering entity discovery...");
        logger.info("  World: " + event.getWorld().getName());

        scheduleEntityDiscovery("PlayerJoin", 3000, false);
    }

    /**
     * 处理玩家断开连接事件 - 清除 NPC 跟随目标
     *
     * 防止服务器关闭时 NPC 持有已移除玩家实体的引用导致"无效实体引用"错误。
     *
     * 注意：服务器关闭期间此事件在 ShutdownThread 上触发（非 WorldThread）。
     * Hytale 实体 API 需要 WorldThread，因此关闭时使用安全清理（不访问实体 API）。
     */
    private void onPlayerDisconnect(PlayerDisconnectEvent event) {
        try {
            PlayerRef playerRef = event.getPlayerRef();
            if (playerRef == null) {
                return;
            }
            
            String playerName = playerRef.getUsername();
            String threadName = Thread.currentThread().getName();
            boolean isWorldThread = threadName.contains("WorldThread");
            
            if (isWorldThread) {
                // 正常断开 - 可以使用完整的实体 API 清理
                logger.debug("Player disconnecting: " + playerName + " - clearing NPC follow targets");
                if (hytaleAPI != null) {
                    ((HytaleServerAdapter) hytaleAPI).clearFollowTargetsForPlayer(playerName);
                }
            } else {
                // 关闭期间断开 - 只能清除内部映射，不能访问 Hytale API
                logger.debug("Player disconnecting on " + threadName + " - safe clearing only");
                if (hytaleAPI != null) {
                    // Use safe clear that doesn't access Hytale entity APIs
                    ((HytaleServerAdapter) hytaleAPI).clearFollowTargetsSafe(playerName);
                }
            }
        } catch (Exception e) {
            logger.debug("Error handling player disconnect: " + e.getMessage());
        }
    }

    // 防止重复执行早期清理的守卫标志
    private volatile boolean earlyShutdownDone = false;

    /**
     * 处理服务器关闭事件 - 在关闭序列的早期���段触发
     * 优先级 -48 = DISCONNECT_PLAYERS 阶段 - 在玩家被移除之前运行
     *
     * 关键：此处尽量少做操作，避免干扰 Hytale 的关闭序列。
     * 特别是不清除实体引用，因为这会导致"无效实体引用"错误，
     * 破坏 Hytale 的玩家移除和区块保存流程。
     *
     * 此处仅执行：
     * 1. 断开后端连接（停止接收���件）
     * 2. 停止定期后台任���
     * 3. 设置关闭标志（不阻止世界操作）
     */
    private void onServerShutdown(ShutdownEvent event) {
        long startTime = System.currentTimeMillis();
        String threadName = Thread.currentThread().getName();
        
        // 防止重复执行
        if (earlyShutdownDone) {
            logger.debug("[Shutdown] Early cleanup already done, skipping (thread: " + threadName + ")");
            return;
        }
        earlyShutdownDone = true;
        
        logger.debug("[Shutdown] ============================================");
        logger.debug("[Shutdown] SHUTDOWN EVENT RECEIVED");
        logger.debug("[Shutdown] Phase: DISCONNECT_PLAYERS (priority -48)");
        logger.debug("[Shutdown] Thread: " + threadName);
        logger.debug("[Shutdown] Timestamp: " + startTime);
        logger.debug("[Shutdown] ============================================");
        
        try {
            // STEP 1: Disconnect from backend to stop incoming events
            logger.debug("[Shutdown] Step 1/3: Disconnecting from backend...");
            long stepStart = System.currentTimeMillis();
            if (socketManager != null) {
                socketManager.disconnect();
                logger.debug("[Shutdown] Step 1/3: Backend disconnected in " + (System.currentTimeMillis() - stepStart) + "ms");
            } else {
                logger.debug("[Shutdown] Step 1/3: SocketManager is null, skipping");
            }
            
            // STEP 2: Stop all periodic background tasks
            logger.debug("[Shutdown] Step 2/3: Stopping NPC greeting service...");
            stepStart = System.currentTimeMillis();
            if (greetingService != null) {
                greetingService.stopProximityChecks();
                logger.debug("[Shutdown] Step 2/3: Greeting service stopped in " + (System.currentTimeMillis() - stepStart) + "ms");
            } else {
                logger.debug("[Shutdown] Step 2/3: GreetingService is null, skipping");
            }
            
            // STEP 3: Set shutdown flag (but DON'T clear entity references here!)
            // Clearing entity references during early shutdown can cause
            // "Invalid entity reference" errors that break Hytale's player removal
            // and chunk saving. We defer entity cleanup to the final shutdown phase.
            logger.debug("[Shutdown] Step 3/3: Setting shutdown flag...");
            stepStart = System.currentTimeMillis();
            if (shutdownManager != null) {
                shutdownManager.setShuttingDown();
                logger.debug("[Shutdown] Step 3/3: Shutdown flag set in " + (System.currentTimeMillis() - stepStart) + "ms");
            } else {
                logger.debug("[Shutdown] Step 3/3: ShutdownManager is null, skipping");
            }
            
            long totalTime = System.currentTimeMillis() - startTime;
            logger.debug("[Shutdown] ============================================");
            logger.debug("[Shutdown] EARLY CLEANUP COMPLETE");
            logger.debug("[Shutdown] Total time: " + totalTime + "ms");
            logger.debug("[Shutdown] NOTE: Entity references NOT cleared - deferring to final shutdown");
            logger.debug("[Shutdown] ============================================");
        } catch (Exception e) {
            logger.error("[Shutdown] ERROR during early shutdown: " + e.getMessage(), e);
            Sentry.captureException(e);
        }
    }

    /**
     * 重连后运行实体发现
     * 由 SocketManager 在重连后批量同步完成时调用
     */
    private void runEntityDiscoveryAfterReconnect() {
        scheduleEntityDiscovery("Reconnect", 2000, true);
    }

    // ========== 统一实体发现机制 ==========

    /**
     * 调度实体发现任务（可配置延迟和行为）
     *
     * 统一处理所有发现触发器：
     * - 启动时：AllNPCsLoadedEvent 后，等待实体重载完成
     * - 玩家加入：首个玩家加入时，等待区块加载
     * - 重连后：后端重连后，始终运行（跳过 entityDiscoveryDone 检查��
     *
     * 使用虚拟线程异步执行，避免阻塞主线程。
     *
     * @param trigger  触发来源标签（用于日志记录）
     * @param delayMs  发现前等待的毫秒数（给 Hytale 时间完成加载）
     * @param forceRun 如果为 true，即使 entityDiscoveryDone 为 true 也运行（用于重���）
     */
    private void scheduleEntityDiscovery(String trigger, long delayMs, boolean forceRun) {
        if (npcManager == null || hytaleAPI == null) {
            logger.debug("[" + trigger + "] Cannot run discovery - NPC manager or API not ready");
            return;
        }

        if (npcManager.getNpcCount() == 0) {
            logger.debug("[" + trigger + "] No NPCs synced yet, skipping discovery");
            return;
        }

        Thread.ofVirtual().start(() -> {
            try {
                if (delayMs > 0) {
                    logger.info("[" + trigger + "] Waiting " + delayMs + "ms before entity discovery...");
                    Thread.sleep(delayMs);
                }

                // Skip if already done (unless forceRun is true, e.g. for reconnect)
                if (!forceRun && entityDiscoveryDone) {
                    logger.debug("[" + trigger + "] Discovery already completed, skipping");
                    return;
                }

                runEntityDiscoveryNow(trigger);

            } catch (InterruptedException e) {
                logger.debug("[" + trigger + "] Discovery interrupted: " + e.getMessage());
            }
        });
    }

    /**
     * 同步执行实体发现（从虚拟线程调用）
     * 扫描所有已同步的 NPC，在游戏世界中查找并绑定其实体引用
     *
     * @param trigger 触发来源标签（用于日志记录）
     * @return 发现结果数组 [已发现数, 已有效数, 失败数]
     */
    private int[] runEntityDiscoveryNow(String trigger) {
        // 清理：移除上次会话/崩溃残留的"思考中"指示器
        // 必须在发现之前完成，以免意外移除活动中的指示器
        if (hytaleAPI != null) {
            hytaleAPI.removeZombieThinkingIndicators();
        }

        logger.info("[" + trigger + "] Running entity discovery for " + npcManager.getNpcCount() + " synced NPCs...");

        int discovered = 0;
        int alreadyValid = 0;
        int failed = 0;

        Set<String> npcIdAnimationsSent = new HashSet<>();
        // ��历所有已定义的 NPC，在世界中查找并绑定其实体实例
        for (dev.hycompanion.plugin.core.npc.NpcData npcData : npcManager.getAllNpcs()) {
            try {
                // Discover existing instances for this role
                java.util.List<java.util.UUID> entityUuids = hytaleAPI
                        .discoverExistingNpcInstances(npcData.externalId());

                for (java.util.UUID entityUuid : entityUuids) {
                    if (entityUuid != null) {
                        // Get the NPC's current location immediately (optional check)
                        var location = hytaleAPI.getNpcInstanceLocation(entityUuid);
                        if (location.isPresent()) {
                            npcManager.bindEntity(npcData.externalId(), entityUuid);
                            logger.info(
                                    "[" + trigger + "] Bound: " + npcData.externalId() + " -> " + entityUuid +
                                            " at " + location.get().toCoordString());
                        } else {
                            // Even if location unavailable, bind it
                            npcManager.bindEntity(npcData.externalId(), entityUuid);
                            logger.warn(
                                    "[" + trigger + "] Bound: " + npcData.externalId() + " -> " + entityUuid +
                                            " (location not available)");
                        }

                        // We count individual instances associated
                        discovered++;
                        entityDiscoveryDone = true;

                        if (!npcIdAnimationsSent.contains(npcData.id())) {
                            // Report available animations to backend for dynamic MCP tool generation
                            var npcInstance = hytaleAPI.getNpcInstance(entityUuid);
                            if (npcInstance != null) {
                                reportNpcAnimations(npcInstance);
                                npcIdAnimationsSent.add(npcData.id());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                logger.debug("[" + trigger + "] Failed for " + npcData.id() + ": " + e.getMessage());
                failed++;
            }
        }

        logger.info("[" + trigger + "] Discovery complete: " + discovered + " bound, " +
                alreadyValid + " already valid" + (failed > 0 ? ", " + failed + " failed" : ""));

        return new int[] { discovered, alreadyValid, failed };
    }

    /**
     * 验证已追踪的 NPC 实体是否仍然有效
     * 如果未找到有效的 NPC，触发重新发现
     *
     * 在玩家加入时调用，确保 NPC 始终可追踪。
     * 处理部分陈旧的情况 - 即使部分 NPC 有效，仍会触发重新发现以确保所有 NPC 都被正确追踪。
     *
     * @return 如果存在有效的 NPC 则返回 true（可能仍触发了重新发现）
     */
    public boolean validateAndRediscoverIfNeeded() {
        if (hytaleAPI == null || npcManager == null) {
            logger.debug("[Validation] Cannot validate - API or manager not ready");
            return false;
        }

        // 检查所有已追踪 NPC 实例的有效性
        var npcInstances = hytaleAPI.getNpcInstances();
        int validCount = 0;
        int invalidCount = 0;
        int totalExpected = npcManager.getNpcCount(); // Number of NPC types synced from backend
        
        for (var npc : npcInstances) {
            if (npc.entityRef() != null && hytaleAPI.isNpcInstanceEntityValid(npc.entityUuid())) {
                validCount++;
            } else {
                invalidCount++;
            }
        }

        logger.debug("[Validation] NPC validation: " + validCount + " valid, " + invalidCount + " invalid/stale");

        // 在以下情况触发重新发现：
        // 1. 完全没有有效 NPC
        // 2. 存在无效 NPC（部分陈旧 - 可能因区块卸载、重生等）
        // 3. 有效实例数少于预期的 NPC 类型数（某些 NPC 从未被发现）
        boolean needsRediscovery = validCount == 0 || invalidCount > 0 || validCount < totalExpected;

        if (needsRediscovery) {
            logger.warn("[Validation] NPC staleness detected (valid=" + validCount + ", invalid=" + invalidCount + 
                       ", expected=" + totalExpected + "). Triggering rediscovery...");
            
            // Reset entityDiscoveryDone to allow rediscovery
            // scheduleEntityDiscovery will handle the delay for chunk loading
            entityDiscoveryDone = false;
            scheduleEntityDiscovery("Validation", 3000, true);
        }
        
        return validCount > 0;
    }

    /**
     * 向后端报告 NPC 可用的动画列表
     * 后端根据 NPC 模型的动画动态生成 MCP 工具
     *
     * @param npcInstanceData NPC 实例数据
     */
    private void reportNpcAnimations(dev.hycompanion.plugin.core.npc.NpcInstanceData npcInstanceData) {
        if (socketManager == null || !socketManager.isConnected()) {
            logger.info("[Animation] Cannot report animations - not connected");
            return;
        }

        try {
            var animations = hytaleAPI.getAvailableAnimations(npcInstanceData.entityUuid());
            if (animations != null && !animations.isEmpty()) {
                socketManager.sendNpcAnimations(npcInstanceData.npcData().id(), animations);
            }
        } catch (Exception e) {
            logger.info("[Animation] Failed to report animations for " + npcInstanceData.npcData().id() + ": "
                    + e.getMessage());
        }
    }

    /**
     * 更新 config.yml 中的 API 密钥并重新加载配置
     * @param newApiKey 新的 API 密钥
     */
    public void updateApiKey(String newApiKey) {
        try {
            Path configPath = dataFolder.resolve("config.yml");
            // 使用共享辅助方法更新配置文件
            PluginConfig.updateApiKeyInFile(configPath, newApiKey);
            logger.info("API Key updated in config.yml");

            // 仅重新加载配置并更新 Socket 认证，无需完全重新初始化
            reload();

        } catch (IOException e) {
            logger.error("Failed to update API key", e);
        }
    }

    /**
     * 重新加载配置并更新各组件
     * 热重载：无需重启插件即可应用新配置
     */
    public void reload() {
        if (logger != null)
            logger.info("Reloading Hycompanion...");

        try {
            // Reload config
            config = PluginConfig.load(dataFolder.resolve("config.yml"));
            if (logger != null)
                logger.setDebugMode(config.gameplay().debugMode());

            // Update components with new config
            if (actionExecutor != null)
                actionExecutor.setConfig(config);
            if (chatHandler != null)
                chatHandler.setConfig(config);

            // Update SocketManager
            if (socketManager != null) {
                socketManager.setConfig(config);
                socketManager.updateApiKey(config.connection().apiKey());
            } else {
                initializeSocketConnection();
            }

            // Auto after reconnect
            // scheduleEntityDiscovery("Reload", 2000, true);

            if (logger != null)
                logger.info("Reload complete!");
        } catch (Exception e) {
            if (logger != null)
                logger.error("Failed to reload configuration", e);
            Sentry.captureException(e);
        }
    }

    /**
     * 手动触发实体重新发现 - 由 /hycompanion rediscover 命令调用
     * 在虚拟线程上同步运行，通过回调返回结果
     *
     * @param resultCallback 结果回调，参数为 [已发现数, 已有效数, 失败数]，出错时为 null
     */
    public void triggerManualRediscovery(java.util.function.Consumer<int[]> resultCallback) {
        if (npcManager == null || hytaleAPI == null) {
            logger.warn("[Command] Cannot rediscover - NPC manager or API not ready");
            resultCallback.accept(null);
            return;
        }

        if (npcManager.getNpcCount() == 0) {
            logger.warn("[Command] No NPCs synced, nothing to rediscover");
            resultCallback.accept(new int[] { 0, 0, 0 });
            return;
        }

        Thread.ofVirtual().start(() -> {
            try {
                int[] results = runEntityDiscoveryNow("Command");
                resultCallback.accept(results);
            } catch (Exception e) {
                logger.error("[Command] Rediscovery failed: " + e.getMessage());
                Sentry.captureException(e);
                resultCallback.accept(null);
            }
        });
    }
}
