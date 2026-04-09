package dev.hycompanion.plugin.core.npc;

import dev.hycompanion.plugin.api.GamePlayer;
import dev.hycompanion.plugin.api.HytaleAPI;
import dev.hycompanion.plugin.config.PluginConfig;
import dev.hycompanion.plugin.utils.PluginLogger;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * NPC问候服务 - 处理玩家首次接近NPC时的问候行为。
 *
 * 追踪每个NPC已问候过的玩家（自服务器启动以来）。
 * 当新玩家接近配置了问候语的NPC时：
 * 1. 将NPC转向玩家
 * 2. 向玩家发送问候消息
 *
 * 如果NPC未设置问候语（null或空），则不会问候玩家。
 */
public class NpcGreetingService {

    private final HytaleAPI hytaleAPI;
    private final NpcManager npcManager;
    private final PluginConfig config;
    private final PluginLogger logger;

    // 追踪已问候的玩家记录，格式为"npcInstanceId:playerId"（线程安全集合）
    private final Set<String> greetedPlayers = ConcurrentHashMap.newKeySet();

    // 定时检查任务的Future引用
    private ScheduledFuture<?> proximityCheckTask;

    // 关闭标志，防止服务器关闭期间执行操作
    private volatile boolean isShutdown = false;

    /**
     * 构造NPC问候服务
     * @param hytaleAPI   Hytale服务器API接口
     * @param npcManager  NPC管理器
     * @param config      插件配置
     * @param logger      日志记录器
     */
    public NpcGreetingService(HytaleAPI hytaleAPI, NpcManager npcManager, PluginConfig config, PluginLogger logger) {
        this.hytaleAPI = hytaleAPI;
        this.npcManager = npcManager;
        this.config = config;
        this.logger = logger;
    }

    /**
     * 启动定期的玩家接近检测任务。
     * 按固定间隔检查玩家是否接近NPC，并触发问候。
     *
     * @param scheduler  用于定时调度的执行器
     * @param intervalMs 检查间隔（毫秒）
     */
    public void startProximityChecks(ScheduledExecutorService scheduler, long intervalMs) {

        proximityCheckTask = scheduler.scheduleAtFixedRate(
                this::checkPlayerProximity,
                intervalMs, // Initial delay
                intervalMs, // Period
                TimeUnit.MILLISECONDS);

        logger.info("NpcGreetingService started with interval " + intervalMs + "ms, range: "
                + config.gameplay().greetingRange());
    }

    /**
     * 停止接近检测任务。
     * 设置关闭标志并取消定时任务。
     */
    public void stopProximityChecks() {
        long startTime = System.currentTimeMillis();
        String threadName = Thread.currentThread().getName();
        
        logger.info("[NpcGreetingService] Stopping on thread: " + threadName);
        
        isShutdown = true;
        if (proximityCheckTask != null) {
            boolean wasCancelled = proximityCheckTask.cancel(true); // Use interrupt to stop blocked operations
            boolean isDone = proximityCheckTask.isDone();
            proximityCheckTask = null;
            logger.info("[NpcGreetingService] Stopped (cancelled: " + wasCancelled + ", done: " + isDone + ") in " + 
                    (System.currentTimeMillis() - startTime) + "ms");
        } else {
            logger.info("[NpcGreetingService] No active task to stop");
        }
    }

    /**
     * 清除所有已问候玩家的记录。
     * 在服务器重启/重载时调用。
     */
    public void clearGreetedPlayers() {
        greetedPlayers.clear();
        logger.debug("Cleared all greeted player records");
    }

    /**
     * 清除指定NPC的所有已问候记录。
     */
    public void clearGreetedPlayersForNpc(UUID npcInstanceId) {
        greetedPlayers.removeIf(key -> key.startsWith(npcInstanceId.toString() + ":"));
    }

    /**
     * 检查玩家是否已被指定NPC问候过。
     */
    public boolean hasBeenGreeted(UUID npcInstanceId, String playerId) {
        return greetedPlayers.contains(npcInstanceId.toString() + ":" + playerId);
    }

    /**
     * 标记玩家已被指定NPC问候过。
     */
    public void markAsGreeted(UUID npcInstanceId, String playerId) {
        greetedPlayers.add(npcInstanceId.toString() + ":" + playerId);
    }

    /**
     * 定期检查玩家是否接近NPC。
     * 在调度线程上运行，遍历所有NPC实例并检查附近玩家。
     */
    private void checkPlayerProximity() {


        // 【关闭修复】优先检查关闭标志，避免服务器关闭期间执行操作
        if (isShutdown || Thread.currentThread().isInterrupted()) {
            logger.debug("Proximity check interrupted, skipping");
            return;
        }

        int greetingRange = config.gameplay().greetingRange();

        if (greetingRange <= 0) {
            logger.debug("Greeting range is 0, skipping proximity check");
            return; // Greeting disabled
        }

        // 【关闭修复】如果没有在线玩家，不浪费资源检查NPC
        // 这也可以防止服务器关闭期间的阻塞操作
        var onlinePlayers = hytaleAPI.getOnlinePlayers();
        if (onlinePlayers.isEmpty()) {
            //logger.debug("No online players, skipping proximity check");
            return;
        }

        // 遍历每个已生成的NPC，检查附近是否有未问候过的玩家
        for (NpcInstanceData npcInstanceData : hytaleAPI.getNpcInstances()) {

            if (Thread.currentThread().isInterrupted()) {
                logger.debug("Proximity check interrupted, skipping");
                return;
            }

            NpcData npc = npcInstanceData.npcData();
            // 跳过没有配置问候语的NPC
            if (!hasGreeting(npc)) {
                logger.debug("NPC [" + npcInstanceData.entityUuid() + "] has no greeting, skipping");
                continue;
            }

            // 跳过未生成或没有位置信息的NPC
            if (!npcInstanceData.isSpawned()) {
                logger.debug("NPC [" + npcInstanceData.entityUuid() + "] is not spawned, skipping");
                continue;
            }

            // 获取NPC的实时位置
            var npcLoc = hytaleAPI.getNpcInstanceLocation(npcInstanceData.entityUuid());
            if (npcLoc.isEmpty()) {
                logger.debug("NPC [" + npcInstanceData.entityUuid() + "] has no location, skipping");
                continue;
            }

            // 获取该NPC附近的玩家列表
            var nearbyPlayers = hytaleAPI.getNearbyPlayers(npcLoc.get(), greetingRange);

            for (GamePlayer player : nearbyPlayers) {
                // 检查该玩家是否已被此NPC问候过
                if (!hasBeenGreeted(npcInstanceData.entityUuid(), player.id())) {
                    // 首次相遇 - 向玩家发出问候！
                    greetPlayer(npcInstanceData.entityUuid(), player);
                }
            }
        }
    }

    /**
     * 检查NPC是否配置了问候语。
     */
    private boolean hasGreeting(NpcData npc) {
        return npc.greeting() != null && !npc.greeting().isEmpty();
    }

    /**
     * 对玩家执行问候操作。
     * 1. 将NPC转向玩家
     * 2. 发送问候消息
     * 3. 标记为已问候
     */
    private void greetPlayer(UUID npcInstanceId, GamePlayer player) {
        logger.debug("Greeting player [" + player.name() + "] with NPC [" + npcInstanceId + "]");
        String playerId = player.id();
        NpcData npc = hytaleAPI.getNpcInstance(npcInstanceId).npcData();

        // 立即标记为已问候，防止重复问候
        markAsGreeted(npcInstanceId, playerId);

        // 将NPC转向玩家方向
        if (player.location() != null) {
            hytaleAPI.rotateNpcInstanceToward(npcInstanceId, player.location());
        }

        // 格式化并发送问候消息
        String greeting = npc.greeting();
        String prefix = config.gameplay().messagePrefix();
        // 移除旧版颜色代码
        prefix = prefix.replaceAll("[§&][0-9a-fk-orA-FK-OR]", "");
        String formattedMessage = prefix + npc.name() + ": " + greeting;

        hytaleAPI.sendNpcMessage(npcInstanceId, playerId, formattedMessage, greeting);

        logger.info("NPC [" + npcInstanceId + "] greeted player [" + player.name() + "]: " + greeting);
    }
}
