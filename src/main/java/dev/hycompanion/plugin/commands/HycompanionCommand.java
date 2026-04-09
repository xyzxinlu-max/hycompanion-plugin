package dev.hycompanion.plugin.commands;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import dev.hycompanion.plugin.HycompanionEntrypoint;
import dev.hycompanion.plugin.api.Location;
import dev.hycompanion.plugin.core.npc.NpcData;
import dev.hycompanion.plugin.core.npc.NpcInstanceData;
import dev.hycompanion.plugin.core.npc.NpcManager;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.UUID;

/**
 * /hycompanion 主命令集合类
 *
 * 提供以下子命令：
 * - /hycompanion register <key> - 设置API密钥
 * - /hycompanion status - 查看连接状态
 * - /hycompanion sync - 强制从后端同步NPC数据
 * - /hycompanion rediscover - 重新扫描世界中的NPC实体
 * - /hycompanion list - 列出所有已加载的NPC
 * - /hycompanion spawn <npc-id> - 在玩家位置生成NPC
 * - /hycompanion despawn <npc-id> - 从世界中移除NPC
 * - /hycompanion despawn <external_id>:nearest - 移除距离最近的指定外部ID的NPC
 * - /hycompanion tphere <npc-uuid> - 将NPC传送到玩家位置
 * - /hycompanion tpto <npc-uuid> - 将玩家传送到NPC位置
 * - /hycompanion help - 显示帮助信息
 */
public class HycompanionCommand extends AbstractCommandCollection {

    // Hytale使用十六进制颜色码，而非Minecraft的 § 颜色码
    private static final String COLOR_GOLD = "#FFB800";
    private static final String COLOR_GRAY = "#AAAAAA";
    private static final String COLOR_WHITE = "#FFFFFF";
    private static final String COLOR_GREEN = "#55FF55";
    private static final String COLOR_RED = "#FF5555";
    private static final String COLOR_YELLOW = "#FFFF55";

    /** 插件入口点引用，用于访问插件核心功能 */
    private final HycompanionEntrypoint plugin;

    /**
     * 构造主命令集合，注册所有子命令和别名
     */
    public HycompanionCommand(HycompanionEntrypoint plugin) {
        super("hycompanion", "Hycompanion AI NPC plugin commands");
        this.plugin = plugin;

        // 添加命令别名：/hyc 和 /hc
        this.addAliases(new String[] { "hyc", "hc" });

        // 注册所有子命令
        this.addSubCommand(new RegisterCommand());
        this.addSubCommand(new StatusCommand());
        this.addSubCommand(new SyncCommand());
        this.addSubCommand(new RediscoverCommand());
        this.addSubCommand(new ListCommand());
        this.addSubCommand(new SpawnCommand());
        this.addSubCommand(new DespawnCommand());
        this.addSubCommand(new TpHereCommand());
        this.addSubCommand(new TpToCommand());
        this.addSubCommand(new HelpCommand());
    }

    /** 获取NPC管理器实例 */
    private NpcManager getNpcManager() {
        return plugin.getNpcManager();
    }

    /** 创建带颜色的消息 */
    private static Message colored(String text, String color) {
        return Message.raw(text).color(color);
    }

    /** 创建键值对格式的显示消息（键为灰色，值为白色） */
    private static Message keyValue(String key, String value) {
        return Message.raw("")
                .insert(Message.raw(key + ": ").color(COLOR_GRAY))
                .insert(Message.raw(value).color(COLOR_WHITE));
    }

    // ========== 注册API密钥子命令 ==========

    /** register子命令 - 设置Hycompanion API密钥并重新加载插件 */
    private class RegisterCommand extends CommandBase {
        private final RequiredArg<String> apiKeyArg = this.withRequiredArg(
                "key",
                "The Hycompanion API Key",
                ArgTypes.STRING);

        public RegisterCommand() {
            super("register", "Set the Hycompanion API Key");
        }

        @Override
        protected void executeSync(@Nonnull CommandContext context) {
            String apiKey = context.get(apiKeyArg);

            if (apiKey == null || apiKey.trim().isEmpty()) {
                context.sendMessage(colored("Please provide a valid API Key.", COLOR_RED));
                return;
            }

            context.sendMessage(colored("Updating API Key...", COLOR_YELLOW));
            plugin.updateApiKey(apiKey);
            context.sendMessage(colored("API Key updated! Plugin reloading...", COLOR_GREEN));
        }
    }

    // ========== 状态查询子命令 ==========

    /** status子命令 - 显示插件版本、API密钥状态、连接状态、NPC计数等信息 */
    private class StatusCommand extends CommandBase {
        public StatusCommand() {
            super("status", "Show Hycompanion connection status");
        }

        @Override
        protected void executeSync(@Nonnull CommandContext context) {
            var socketManager = plugin.getSocketManager();
            boolean connected = socketManager != null && socketManager.isConnected();
            int npcCount = getNpcManager().getNpcCount();
            int spawnedCount = getNpcManager().getSpawnedNpcCount();
            var config = plugin.getPluginConfig();
            String apiKey = config.connection().apiKey();
            boolean keyConfigured = apiKey != null && !apiKey.isEmpty() && !apiKey.equals("YOUR_SERVER_API_KEY");

            context.sendMessage(colored("=== Hycompanion Status ===", COLOR_GOLD));
            context.sendMessage(keyValue("Version", HycompanionEntrypoint.VERSION));
            context.sendMessage(Message.raw("")
                    .insert(Message.raw("API Key: ").color(COLOR_GRAY))
                    .insert(Message.raw(keyConfigured ? "Configured" : "Not configured")
                            .color(keyConfigured ? COLOR_GREEN : COLOR_RED)));
            context.sendMessage(Message.raw("")
                    .insert(Message.raw("Connection: ").color(COLOR_GRAY))
                    .insert(Message.raw(connected ? "Connected" : "Disconnected")
                            .color(connected ? COLOR_GREEN : COLOR_RED)));
            context.sendMessage(keyValue("NPCs Loaded", String.valueOf(npcCount)));
            context.sendMessage(keyValue("NPCs Spawned", String.valueOf(spawnedCount)));
            context.sendMessage(keyValue("Backend URL", config.connection().url()));
        }
    }

    // ========== NPC同步子命令 ==========

    /** sync子命令 - 向后端发送同步请求，强制重新获取NPC数据 */
    private class SyncCommand extends CommandBase {
        public SyncCommand() {
            super("sync", "Force NPC sync from backend");
        }

        @Override
        protected void executeSync(@Nonnull CommandContext context) {
            var socketManager = plugin.getSocketManager();

            if (socketManager == null || !socketManager.isConnected()) {
                context.sendMessage(colored("Cannot sync - not connected to backend", COLOR_RED));
                return;
            }

            context.sendMessage(colored("Requesting NPC sync from backend...", COLOR_YELLOW));
            socketManager.requestSync();
            context.sendMessage(colored("Sync request sent!", COLOR_GREEN));
        }
    }

    // ========== 实体重新发现子命令 ==========

    /** rediscover子命令 - 重新扫描世界中的NPC实体并重新绑定实体引用 */
    private class RediscoverCommand extends CommandBase {
        public RediscoverCommand() {
            super("rediscover", "Re-scan world for NPC entities and rebind references");
        }

        @Override
        protected void executeSync(@Nonnull CommandContext context) {
            int npcCount = getNpcManager().getNpcCount();

            if (npcCount == 0) {
                context.sendMessage(colored("No NPCs loaded. Use /hycompanion sync first.", COLOR_RED));
                return;
            }

            context.sendMessage(colored("Starting entity rediscovery for " + npcCount + " NPCs...", COLOR_YELLOW));
            context.sendMessage(Message.raw("")
                    .insert(Message.raw("This will scan the world and rebind any stale entity references.")
                            .color(COLOR_GRAY)));

            plugin.triggerManualRediscovery(results -> {
                if (results == null) {
                    context.sendMessage(colored("Rediscovery failed! Check console for errors.", COLOR_RED));
                    return;
                }

                int discovered = results[0];
                int alreadyValid = results[1];
                int failed = results[2];

                context.sendMessage(colored("=== Rediscovery Complete ===", COLOR_GOLD));
                context.sendMessage(Message.raw("")
                        .insert(Message.raw("Newly bound: ").color(COLOR_GRAY))
                        .insert(Message.raw(String.valueOf(discovered))
                                .color(discovered > 0 ? COLOR_GREEN : COLOR_WHITE)));
                context.sendMessage(Message.raw("")
                        .insert(Message.raw("Already valid: ").color(COLOR_GRAY))
                        .insert(Message.raw(String.valueOf(alreadyValid)).color(COLOR_WHITE)));
                if (failed > 0) {
                    context.sendMessage(Message.raw("")
                            .insert(Message.raw("Failed: ").color(COLOR_GRAY))
                            .insert(Message.raw(String.valueOf(failed)).color(COLOR_RED)));
                }

                if (discovered > 0) {
                    context.sendMessage(colored("NPC entity references refreshed!", COLOR_GREEN));
                } else if (alreadyValid > 0) {
                    context.sendMessage(colored("All NPC references were already valid.", COLOR_GREEN));
                } else {
                    context.sendMessage(colored("No NPC entities found in world. Use /hycompanion spawn to spawn them.",
                            COLOR_YELLOW));
                }
            });
        }
    }

    // ========== NPC列表子命令 ==========

    /** list子命令 - 列出所有已加载的NPC及其生成状态 */
    private class ListCommand extends CommandBase {
        public ListCommand() {
            super("list", "List all loaded NPCs");
        }

        @Override
        protected void executeSync(@Nonnull CommandContext context) {
            Collection<NpcData> npcs = getNpcManager().getAllNpcs();

            if (npcs.isEmpty()) {
                context.sendMessage(colored("No NPCs loaded.", COLOR_GRAY));
                context.sendMessage(Message.raw("")
                        .insert(Message.raw("Create NPCs at: ").color(COLOR_GRAY))
                        .insert(Message.raw("https://app.hycompanion.dev").color(COLOR_WHITE)));
                return;
            }

            context.sendMessage(colored("=== Loaded NPCs (" + npcs.size() + ") ===", COLOR_GOLD));

            Collection<NpcInstanceData> npcInstances = plugin.getHytaleAPI().getNpcInstances();
            for (NpcInstanceData npcInstanceData : npcInstances) {
                Message statusMsg = npcInstanceData.isSpawned()
                        ? Message.raw("[Spawned]").color(COLOR_GREEN)
                        : Message.raw("[Not Spawned]").color(COLOR_GRAY);

                context.sendMessage(Message.raw("")
                        .insert(Message.raw(npcInstanceData.npcData().name()).color(COLOR_WHITE))
                        .insert(Message.raw(" (" + npcInstanceData.npcData().externalId() + ") ").color(COLOR_GRAY))
                        .insert(Message
                                .raw(npcInstanceData.entityUuid() != null ? "[" + npcInstanceData.entityUuid() + "] "
                                        : "")
                                .color(COLOR_GRAY))
                        .insert(statusMsg));
            }
        }
    }

    // ========== NPC生成子命令 ==========

    /** spawn子命令 - 在玩家当前位置生成指定NPC实体 */
    private class SpawnCommand extends AbstractPlayerCommand {
        private final RequiredArg<String> npcIdArg = this.withRequiredArg(
                "npc-id",
                "The external ID of the NPC to spawn",
                ArgTypes.STRING);

        public SpawnCommand() {
            super("spawn", "Spawn an NPC at your location");
        }

        @Override
        protected void execute(@Nonnull CommandContext context,
                @Nonnull Store<EntityStore> store,
                @Nonnull Ref<EntityStore> ref,
                @Nonnull PlayerRef playerRef,
                @Nonnull World world) {
            String npcId = context.get(npcIdArg);

            // Check if NPC exists in our registry
            var npcOpt = getNpcManager().getNpc(npcId);
            if (npcOpt.isEmpty()) {
                context.sendMessage(colored("NPC '" + npcId + "' not found in loaded NPCs.", COLOR_RED));
                context.sendMessage(Message.raw("")
                        .insert(Message.raw("Use: ").color(COLOR_GRAY))
                        .insert(Message.raw("/hycompanion list").color(COLOR_WHITE))
                        .insert(Message.raw(" to see available NPCs").color(COLOR_GRAY)));
                context.sendMessage(Message.raw("")
                        .insert(Message.raw("Sync NPCs with: ").color(COLOR_GRAY))
                        .insert(Message.raw("/hycompanion sync").color(COLOR_WHITE)));
                return;
            }

            NpcData npc = npcOpt.get();

            // 获取玩家当前坐标作为NPC生成位置
            Vector3d playerPos = playerRef.getTransform().getPosition();

            Location spawnLocation = new Location(
                    playerPos.getX(),
                    playerPos.getY(),
                    playerPos.getZ(),
                    world.getName());

            context.sendMessage(colored("Spawning NPC '" + npc.name() + "' at your location...", COLOR_YELLOW));

            var result = plugin.getHytaleAPI().spawnNpc(npcId, npc.name(), spawnLocation);

            if (result.isPresent()) {
                UUID entityUuid = result.get();
                getNpcManager().bindEntity(npcId, entityUuid);
                context.sendMessage(colored("NPC spawned successfully!", COLOR_GREEN));
                context.sendMessage(keyValue("Entity UUID", entityUuid.toString()));
            } else {
                context.sendMessage(colored("Failed to spawn NPC. Check console for errors.", COLOR_RED));
                context.sendMessage(Message.raw("")
                        .insert(Message.raw("Note: NPC role '").color(COLOR_GRAY))
                        .insert(Message.raw(npcId).color(COLOR_WHITE))
                        .insert(Message.raw("' must be defined and server restarted.").color(COLOR_GRAY)));
            }
        }
    }

    // ========== NPC移除子命令 ==========

    /**
     * despawn子命令 - 从世界中移除NPC实体
     * 支持三种标识方式：UUID直接指定、外部ID/名称匹配、外部ID:nearest（最近的）
     */
    private class DespawnCommand extends AbstractPlayerCommand {
        private final RequiredArg<String> npcIdArg = this.withRequiredArg(
                "npc-id",
                "The NPC identifier (UUID, external_id, external_id:nearest)",
                ArgTypes.STRING);

        public DespawnCommand() {
            super("despawn", "Remove an NPC from the world");
        }

        @Override
        protected void execute(@Nonnull CommandContext context,
                @Nonnull Store<EntityStore> store,
                @Nonnull Ref<EntityStore> ref,
                @Nonnull PlayerRef playerRef,
                @Nonnull World world) {
            String identifier = context.get(npcIdArg);

            // 检查是否使用了 :nearest 后缀（查找最近的NPC）
            String nearestExternalId = null;
            if (identifier != null && identifier.toLowerCase().endsWith(":nearest")) {
                nearestExternalId = identifier.substring(0, identifier.length() - ":nearest".length());
            }

            java.util.UUID targetUuid = null;

            if (nearestExternalId != null && !nearestExternalId.isEmpty()) {
                // 根据外部ID查找距离玩家最近的已生成NPC
                Vector3d playerPos = playerRef.getTransform().getPosition();
                Location playerLocation = new Location(
                        playerPos.getX(), playerPos.getY(), playerPos.getZ(), world.getName());

                var nearestOpt = getNpcManager().findNearestSpawnedNpcByExternalId(nearestExternalId, playerLocation);

                if (nearestOpt.isPresent()) {
                    NpcInstanceData nearest = nearestOpt.get();
                    targetUuid = nearest.entityUuid();
                    var nearestLocOpt = plugin.getHytaleAPI().getNpcInstanceLocation(targetUuid);
                    double distance = nearestLocOpt.map(loc -> loc.distanceTo(playerLocation)).orElse(0.0);
                    context.sendMessage(colored("Found nearest '" + nearestExternalId + "' at distance " +
                            String.format("%.1f", distance) + " blocks.", COLOR_GRAY));
                } else {
                    context.sendMessage(colored("No spawned NPC found with external ID '" + nearestExternalId + "' nearby.", COLOR_RED));
                    return;
                }
            } else {
                // 首先尝试将标识符解析为UUID，失败则按名称或外部ID查找
                try {
                    targetUuid = java.util.UUID.fromString(identifier);
                } catch (IllegalArgumentException e) {
                    // Not a UUID, try to find instance by name or externalId
                    var instances = plugin.getHytaleAPI().getNpcInstances().stream()
                            .filter(inst -> inst.npcData().externalId().equalsIgnoreCase(identifier) ||
                                    inst.npcData().name().equalsIgnoreCase(identifier))
                            .toList();

                    if (!instances.isEmpty()) {
                        targetUuid = instances.get(0).entityUuid();
                        if (instances.size() > 1) {
                            context.sendMessage(colored("Found " + instances.size() + " matches. Despawning the first one.",
                                    COLOR_YELLOW));
                        }
                    }
                }
            }

            if (targetUuid == null) {
                context.sendMessage(colored("No spawned NPC found matching '" + identifier + "'.", COLOR_RED));
                return;
            }

            context.sendMessage(colored("Despawning NPC instance " + targetUuid + "...", COLOR_YELLOW));

            try {
                boolean removed = plugin.getHytaleAPI().removeNpc(targetUuid);

                if (removed) {
                    getNpcManager().unbindEntity(targetUuid);
                    context.sendMessage(colored("NPC removed from world!", COLOR_GREEN));
                } else {
                    // Even if API returns false, ensure we clean up the binding
                    getNpcManager().unbindEntity(targetUuid);
                    context.sendMessage(
                            colored("NPC may have already been removed. Cleaned up tracking.", COLOR_YELLOW));
                }
            } catch (Exception e) {
                // Handle any errors gracefully
                context.sendMessage(colored("Error removing NPC: " + e.getMessage(), COLOR_RED));
                // Still try to clean up the binding
                getNpcManager().unbindEntity(targetUuid);
                context.sendMessage(colored("Cleaned up NPC tracking data.", COLOR_GRAY));
            }
        }
    }

    // ========== NPC传送到玩家位置子命令 ==========

    /** tphere子命令 - 将指定NPC传送到玩家当前位置 */
    private class TpHereCommand extends AbstractPlayerCommand {
        private final RequiredArg<String> npcUuidArg = this.withRequiredArg(
                "npc-uuid",
                "The entity UUID of the NPC to teleport (e.g., 14f6ec7b-f6e9-47a3-94d8-5167ad795195)",
                ArgTypes.STRING);

        public TpHereCommand() {
            super("tphere", "Teleport an NPC to your location");
        }

        @Override
        protected void execute(@Nonnull CommandContext context,
                @Nonnull Store<EntityStore> store,
                @Nonnull Ref<EntityStore> ref,
                @Nonnull PlayerRef playerRef,
                @Nonnull World world) {
            String npcUuidStr = context.get(npcUuidArg);

            // Parse the UUID
            java.util.UUID npcUuid;
            try {
                npcUuid = java.util.UUID.fromString(npcUuidStr);
            } catch (IllegalArgumentException e) {
                context.sendMessage(colored("Invalid UUID format: '" + npcUuidStr + "'", COLOR_RED));
                context.sendMessage(Message.raw("")
                        .insert(Message.raw("Example: ").color(COLOR_GRAY))
                        .insert(Message.raw("14f6ec7b-f6e9-47a3-94d8-5167ad795195").color(COLOR_WHITE)));
                return;
            }

            // Check if NPC exists
            NpcInstanceData npcInstance = plugin.getHytaleAPI().getNpcInstance(npcUuid);
            if (npcInstance == null) {
                context.sendMessage(colored("No spawned NPC found with UUID: " + npcUuid, COLOR_RED));
                return;
            }

            // Get player position
            Vector3d playerPos = playerRef.getTransform().getPosition();
            Location targetLocation = new Location(
                    playerPos.getX(),
                    playerPos.getY(),
                    playerPos.getZ(),
                    world.getName());

            context.sendMessage(colored("Teleporting NPC '" + npcInstance.npcData().name() + "' to your location...", COLOR_YELLOW));

            boolean success = plugin.getHytaleAPI().teleportNpcTo(npcUuid, targetLocation);

            if (success) {
                context.sendMessage(colored("NPC teleported successfully!", COLOR_GREEN));
            } else {
                context.sendMessage(colored("Failed to teleport NPC. Check console for errors.", COLOR_RED));
            }
        }
    }

    // ========== 玩家传送到NPC位置子命令 ==========

    /** tpto子命令 - 将玩家传送到指定NPC的位置 */
    private class TpToCommand extends AbstractPlayerCommand {
        private final RequiredArg<String> npcUuidArg = this.withRequiredArg(
                "npc-uuid",
                "The entity UUID of the NPC to teleport to (e.g., 14f6ec7b-f6e9-47a3-94d8-5167ad795195)",
                ArgTypes.STRING);

        public TpToCommand() {
            super("tpto", "Teleport yourself to an NPC's location");
        }

        @Override
        protected void execute(@Nonnull CommandContext context,
                @Nonnull Store<EntityStore> store,
                @Nonnull Ref<EntityStore> ref,
                @Nonnull PlayerRef playerRef,
                @Nonnull World world) {
            String npcUuidStr = context.get(npcUuidArg);

            // Parse the UUID
            java.util.UUID npcUuid;
            try {
                npcUuid = java.util.UUID.fromString(npcUuidStr);
            } catch (IllegalArgumentException e) {
                context.sendMessage(colored("Invalid UUID format: '" + npcUuidStr + "'", COLOR_RED));
                context.sendMessage(Message.raw("")
                        .insert(Message.raw("Example: ").color(COLOR_GRAY))
                        .insert(Message.raw("14f6ec7b-f6e9-47a3-94d8-5167ad795195").color(COLOR_WHITE)));
                return;
            }

            // Check if NPC exists and get its location
            var npcLocationOpt = plugin.getHytaleAPI().getNpcInstanceLocation(npcUuid);
            if (npcLocationOpt.isEmpty()) {
                context.sendMessage(colored("No spawned NPC found with UUID: " + npcUuid, COLOR_RED));
                return;
            }

            Location npcLocation = npcLocationOpt.get();

            context.sendMessage(colored("Teleporting you to the NPC...", COLOR_YELLOW));

            boolean success = plugin.getHytaleAPI().teleportPlayerTo(playerRef.getUuid().toString(), npcLocation);

            if (success) {
                context.sendMessage(colored("You have been teleported to the NPC!", COLOR_GREEN));
            } else {
                context.sendMessage(colored("Failed to teleport. Check console for errors.", COLOR_RED));
            }
        }
    }

    // ========== 帮助子命令 ==========

    /** help子命令 - 显示所有可用命令及其说明 */
    private class HelpCommand extends CommandBase {
        public HelpCommand() {
            super("help", "Show all Hycompanion commands");
        }

        @Override
        protected void executeSync(@Nonnull CommandContext context) {
            context.sendMessage(colored("=== Hycompanion Commands ===", COLOR_GOLD));
            sendHelpLine(context, "/hycompanion register <key>", "Set API Key");
            sendHelpLine(context, "/hycompanion status", "Show connection status");
            sendHelpLine(context, "/hycompanion sync", "Force NPC sync from backend");
            sendHelpLine(context, "/hycompanion rediscover", "Re-scan world for NPC entities");
            sendHelpLine(context, "/hycompanion list", "List all loaded NPCs");
            sendHelpLine(context, "/hycompanion spawn <npc-id>", "Spawn NPC at your location");
            sendHelpLine(context, "/hycompanion despawn <npc-id>", "Remove NPC from world");
            sendHelpLine(context, "/hycompanion despawn <external_id>:nearest", "Remove nearest NPC by external ID");
            sendHelpLine(context, "/hycompanion tphere <npc-uuid>", "Teleport NPC to your location");
            sendHelpLine(context, "/hycompanion tpto <npc-uuid>", "Teleport to NPC's location");
            sendHelpLine(context, "/hycompanion help", "Show this help message");
            context.sendMessage(Message.raw(""));
            context.sendMessage(Message.raw("")
                    .insert(Message.raw("Aliases: ").color(COLOR_GRAY))
                    .insert(Message.raw("/hyc, /hc").color(COLOR_WHITE)));
            context.sendMessage(Message.raw("")
                    .insert(Message.raw("Dashboard: ").color(COLOR_GRAY))
                    .insert(Message.raw("https://app.hycompanion.dev").color(COLOR_WHITE)));
        }

        private void sendHelpLine(CommandContext context, String command, String description) {
            context.sendMessage(Message.raw("")
                    .insert(Message.raw(command).color(COLOR_YELLOW))
                    .insert(Message.raw(" - " + description).color(COLOR_GRAY)));
        }
    }
}
