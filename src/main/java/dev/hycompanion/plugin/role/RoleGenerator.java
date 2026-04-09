package dev.hycompanion.plugin.role;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.hycompanion.plugin.utils.PluginLogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

/**
 * Hytale NPC角色JSON文件生成器
 *
 * 管理角色文件的生成和缓存：
 * - 启动阶段（setup）：加载上次运行缓存的角色文件
 * - 运行时：通过Socket同步NPC后生成/更新角色文件
 * - 角色文件会被缓存以供下次启动使用
 */
public class RoleGenerator {

    /** 美化输出的Gson实例 */
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 角色JSON文件的输出目录（Hytale资源包路径） */
    private final Path rolesDirectory;
    /** 角色缓存目录（插件数据目录下） */
    private final Path cacheDirectory;
    /** 模组根目录（包含manifest.json） */
    private final Path modDirectory;
    /** 额外的角色输出目录（UserData/Mods/HycompanionRoles），引擎优先加载 */
    private final Path extraRolesDirectory;
    private final PluginLogger logger;
    /** 标记本次会话是否新创建了manifest.json */
    private boolean manifestCreatedThisSession = false;

    /**
     * 构造角色生成器
     *
     * @param modDirectory 模组根目录
     * @param dataFolder   插件数据目录
     * @param logger       日志器
     */
    public RoleGenerator(Path modDirectory, Path dataFolder, PluginLogger logger) {
        this.modDirectory = modDirectory;
        this.rolesDirectory = modDirectory.resolve("Server").resolve("NPC").resolve("Roles");
        this.cacheDirectory = dataFolder.resolve("role_cache");
        this.logger = logger;

        // 引擎优先加载 UserData/Mods/ 下的资源包，需要同步写入
        // 通过遍历路径查找 "Saves" 目录来定位 UserData
        Path extraDir = null;
        Path current = modDirectory.toAbsolutePath();
        while (current != null) {
            if (current.getFileName() != null && "Saves".equals(current.getFileName().toString())) {
                // current = .../UserData/Saves, parent = .../UserData
                Path userDataDir = current.getParent();
                if (userDataDir != null) {
                    extraDir = userDataDir.resolve("Mods").resolve("HycompanionRoles")
                            .resolve("Server").resolve("NPC").resolve("Roles");
                }
                break;
            }
            current = current.getParent();
        }
        this.extraRolesDirectory = extraDir;
    }

    /**
     * 确保模组目录下存在 manifest.json，使Hytale将其识别为资源包
     * 这是动态角色文件能被加载的前提条件
     * 如果manifest不存在则创建，已存在则验证/更新关键字段
     */
    public void ensureModManifest() {
        Path manifestPath = modDirectory.resolve("manifest.json");
        try {
            String targetServerVersion = resolveServerVersionFromPluginManifest();

            JsonObject manifestJson;
            boolean created = false;
            if (Files.exists(manifestPath)) {
                String existingContent = Files.readString(manifestPath);
                JsonElement parsed = GSON.fromJson(existingContent, JsonElement.class);
                if (parsed == null || !parsed.isJsonObject()) {
                    manifestJson = new JsonObject();
                } else {
                    manifestJson = parsed.getAsJsonObject();
                }
            } else {
                manifestJson = new JsonObject();
                created = true;
            }

            // Asset pack identity must match plugin manifest identity for reliable role/animation loading.
            manifestJson.addProperty("Group", "dev.hycompanion");
            manifestJson.addProperty("Name", "Hycompanion");
            if (!manifestJson.has("Version") || manifestJson.get("Version").isJsonNull()) {
                manifestJson.addProperty("Version", "1.1.6");
            }
            if (!manifestJson.has("Description") || manifestJson.get("Description").isJsonNull()) {
                manifestJson.addProperty("Description", "Hycompanion dynamic NPC role assets");
            }

            // Critical for new server validation: explicit target server version.
            manifestJson.addProperty("ServerVersion", targetServerVersion);

            Files.writeString(manifestPath, GSON.toJson(manifestJson));
            if (created) {
                manifestCreatedThisSession = true;
                logger.info("Created mod manifest at: " + manifestPath);
                logger.warn("=================================================================");
                logger.warn("RESTART REQUIRED: manifest.json was created for the asset pack.");
                logger.warn("Please restart the server for Hytale to load the new NPC roles.");
                logger.warn("=================================================================");
            } else {
                logger.debug("Validated/updated mod manifest at: " + manifestPath + " (ServerVersion=" + targetServerVersion + ")");
            }
        } catch (Exception e) {
            logger.error("Failed to create mod manifest: " + e.getMessage());
        }
    }

    /**
     * 从插件自身的manifest.json中解析目标服务器版本号
     * 用于在资源包manifest中设置ServerVersion字段
     *
     * @return 服务器版本字符串，解析失败时返回通配符 "*"
     */
    private String resolveServerVersionFromPluginManifest() {
        try (var in = RoleGenerator.class.getClassLoader().getResourceAsStream("manifest.json")) {
            if (in == null) {
                return "*";
            }
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            JsonElement parsed = GSON.fromJson(content, JsonElement.class);
            if (parsed != null && parsed.isJsonObject()) {
                JsonObject json = parsed.getAsJsonObject();
                if (json.has("ServerVersion") && !json.get("ServerVersion").isJsonNull()) {
                    String value = json.get("ServerVersion").getAsString();
                    if (value != null && !value.isBlank()) {
                        return value;
                    }
                }
            }
        } catch (Exception ignored) {
            // Keep default fallback if resource cannot be parsed.
        }
        return "*";
    }

    /**
     * 检查本次会话是否新创建了manifest.json
     * 如果是，则服务器需要重启才能加载NPC角色
     */
    public boolean isManifestCreatedThisSession() {
        return manifestCreatedThisSession;
    }

    /**
     * 从上次运行的缓存中加载角色文件
     * 在setup()阶段、资源加载之前调用
     *
     * 注意：Hytale服务器在启动时会自动从资源包加载NPC角色。
     * 只要manifest.json已存在，之后添加的角色文件会在下次重启时自动加载。
     * 仅当manifest.json首次创建时才需要显示"需要重启"的提示。
     *
     * @return 加载的缓存角色数量
     */
    public int loadCachedRoles() {
        logger.info("Loading cached role files from previous run...");

        // 确保模组目录有manifest文件以支持资源加载
        ensureModManifest();

        try {
            if (!Files.exists(cacheDirectory)) {
                logger.info("No cached roles found (first run)");
                return 0;
            }

            // 如果角色目录不存在则创建
            Files.createDirectories(rolesDirectory);
            if (extraRolesDirectory != null) {
                Files.createDirectories(extraRolesDirectory);
            }

            // 将缓存的角色文件复制到资源目录（两个位置）
            int copied = 0;
            try (var stream = Files.list(cacheDirectory)) {
                for (Path cachedFile : stream.toList()) {
                    if (cachedFile.toString().endsWith(".json")) {
                        Path targetFile = rolesDirectory.resolve(cachedFile.getFileName());
                        Files.copy(cachedFile, targetFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        // 同时写入引擎优先加载的目录
                        if (extraRolesDirectory != null) {
                            Path extraFile = extraRolesDirectory.resolve(cachedFile.getFileName());
                            Files.copy(cachedFile, extraFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                        copied++;
                        logger.debug("Loaded cached role: " + cachedFile.getFileName());
                    }
                }
            }

            if (copied > 0) {
                logger.info("Loaded " + copied + " cached role files");
            } else {
                logger.info("No cached role files found");
            }
            return copied;

        } catch (Exception e) {
            logger.warn("Failed to load cached roles: " + e.getMessage());
            return 0;
        }
    }

    /**
     * 在启动阶段通过Socket.IO同步获取角色数据（带超时的同步操作）
     * 在资源加载之前运行，确保角色定义是最新的
     *
     * @param backendUrl     后端URL（如 http://192.168.1.169:3000）
     * @param apiKey         服务器API密钥
     * @param timeoutSeconds 连接超时时间（秒）
     * @return 获取并生成的角色数量
     */
    public int fetchRolesViaSocket(String backendUrl, String apiKey, int timeoutSeconds) {
        logger.info("Fetching NPC roles via socket from backend...");

        // 使用CountDownLatch同步等待异步Socket操作完成
        var latch = new java.util.concurrent.CountDownLatch(1);
        var rolesGenerated = new java.util.concurrent.atomic.AtomicInteger(0);
        var connectionError = new java.util.concurrent.atomic.AtomicReference<String>(null);

        try {
            // 创建Socket连接（仅用于角色获取，不重连）
            io.socket.client.IO.Options options = io.socket.client.IO.Options.builder()
                    .setAuth(java.util.Map.of("apiKey", apiKey))
                    .setReconnection(false)
                    .setTransports(new String[] { "websocket", "polling" })
                    .build();

            io.socket.client.Socket socket = io.socket.client.IO.socket(java.net.URI.create(backendUrl), options);

            // 处理连接成功事件
            socket.on(io.socket.client.Socket.EVENT_CONNECT, args -> {
                logger.debug("Socket connected for role fetch");
                // 发送连接事件以触发后端NPC同步
                var payload = new org.json.JSONObject();
                try {
                    payload.put("apiKey", apiKey);
                    var serverInfo = new org.json.JSONObject();
                    serverInfo.put("version", "1.1.6-SNAPSHOT");
                    serverInfo.put("playerCount", 0);
                    payload.put("serverInfo", serverInfo);
                } catch (Exception e) {
                    logger.error("Failed to build connect payload: " + e.getMessage());
                }
                socket.emit("PLUGIN_CONNECT", payload);
            });

            // 处理NPC同步事件 - 接收后端推送的NPC数据并生成角色文件
            socket.on("BACKEND_NPC_SYNC", args -> {
                try {
                    org.json.JSONObject json = (org.json.JSONObject) args[0];
                    String action = json.getString("action");

                    if ("create".equals(action) || "update".equals(action)) {
                        org.json.JSONObject npcJson = json.getJSONObject("npc");
                        NpcRoleData roleData = NpcRoleData.fromNpcJson(npcJson);
                        if (generateAndCacheRole(roleData)) {
                            rolesGenerated.incrementAndGet();
                            logger.debug("Role fetched for: " + roleData.externalId);
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Failed to process NPC sync during setup: " + e.getMessage());
                }
            });

            // 处理同步完成信号（超时也会触发闭锁释放）
            socket.on("BACKEND_SYNC_COMPLETE", args -> {
                logger.debug("Sync complete signal received");
                latch.countDown();
            });

            // 处理连接错误
            socket.on(io.socket.client.Socket.EVENT_CONNECT_ERROR, args -> {
                String error = args.length > 0 ? args[0].toString() : "Unknown error";
                connectionError.set(error);
                logger.warn("Socket connection error: " + error);
                latch.countDown();
            });

            // 发起连接
            socket.connect();

            // 带超时等待同步完成
            boolean completed = latch.await(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);

            // 断开连接并清理
            socket.disconnect();
            socket.close();

            if (!completed) {
                logger.warn("Socket sync timed out after " + timeoutSeconds + "s - using cached roles");
                return 0;
            }

            if (connectionError.get() != null) {
                logger.warn("Socket connection failed: " + connectionError.get() + " - using cached roles");
                return 0;
            }

            int count = rolesGenerated.get();
            if (count > 0) {
                logger.info("Fetched and generated " + count + " NPC roles from backend");
            } else {
                logger.info("No NPCs configured on backend");
            }
            return count;

        } catch (Exception e) {
            logger.warn("Socket role fetch failed: " + e.getMessage() + " - using cached roles");
            return 0;
        }
    }

    /**
     * 为单个NPC生成角色文件并缓存
     * 在Socket同步NPC数据后调用
     * 同时写入资源目录（当前运行）和缓存目录（下次启动）
     *
     * @return 是否成功生成角色文件
     */
    public boolean generateAndCacheRole(NpcRoleData npc) {
        try {
            // 确保输出目录存在
            Files.createDirectories(rolesDirectory);
            Files.createDirectories(cacheDirectory);

            // 生成角色JSON
            JsonObject role = buildRoleJson(npc);
            String jsonContent = GSON.toJson(role);

            // 写入资源目录（供当前运行使用，如果支持热重载）
            Path roleFile = rolesDirectory.resolve(npc.externalId + ".json");
            boolean updated = writeIfChanged(roleFile, jsonContent);

            // 同时缓存以供下次启动使用
            Path cacheFile = cacheDirectory.resolve(npc.externalId + ".json");
            writeIfChanged(cacheFile, jsonContent);

            // 写入引擎优先加载的 HycompanionRoles 目录
            if (extraRolesDirectory != null) {
                try {
                    Files.createDirectories(extraRolesDirectory);
                    Path extraFile = extraRolesDirectory.resolve(npc.externalId + ".json");
                    writeIfChanged(extraFile, jsonContent);
                } catch (IOException ex) {
                    logger.warn("Failed to write to extra roles directory: " + ex.getMessage());
                }
            }

            if (updated) {
                logger.info("Generated and cached role for: " + npc.externalId + " with presets: " + 
                    (npc.behaviorPresets != null ? String.join(",", npc.behaviorPresets) : "default(idle)") +
                    " (roleType: " + npc.roleType + ", reference: " + npc.reference + ")");
                // Log the full JSON for debugging
                logger.info("[RoleDebug] Full JSON for " + npc.externalId + ":\n" + jsonContent);
            }
            return true;

        } catch (IOException e) {
            logger.error("Failed to generate role for " + npc.externalId + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * 仅在内容发生变化时写入文件，避免不必要的I/O操作
     *
     * @return 如果文件被写入则返回true
     */
    private boolean writeIfChanged(Path path, String content) throws IOException {
        if (Files.exists(path)) {
            String existing = Files.readString(path);
            if (existing.equals(content)) {
                logger.debug("Role file unchanged, skipping write: " + path);
                return false;
            }
            logger.info("Role file changed, updating: " + path);
        } else {
            logger.info("Role file new, creating: " + path);
        }
        Files.writeString(path, content);
        return true;
    }

    /**
     * 删除角色文件及其缓存
     * 在NPC被删除时调用
     */
    public void removeRole(String externalId) {
        try {
            Path roleFile = rolesDirectory.resolve(externalId + ".json");
            Path cacheFile = cacheDirectory.resolve(externalId + ".json");

            Files.deleteIfExists(roleFile);
            Files.deleteIfExists(cacheFile);

            logger.debug("Removed role for: " + externalId);
        } catch (IOException e) {
            logger.warn("Failed to remove role for " + externalId + ": " + e.getMessage());
        }
    }

    /**
     * 为指定NPC构建角色JSON对象
     * 根据角色类型和引用模板选择不同的构建策略：
     * - Hycompanion模板的Variant -> 使用Generic格式（支持DisplayNames）
     * - 其他Variant -> 使用继承格式（Type/Reference/Modify）
     * - Abstract/Legacy -> 完整角色定义
     */
    private JsonObject buildRoleJson(NpcRoleData npc) {
        logger.info("Building Role JSON for " + npc.displayName + " | RoleType: " + npc.roleType +
                " | Reference: " + npc.reference + " | Appearance: " + npc.appearance);

        // ============================================================================
        // HYCOMPANION TEMPLATE - Use Generic type for full control (supports
        // DisplayNames)
        // ============================================================================
        if ("Variant".equals(npc.roleType) && "Template_Hycompanion_NPC".equals(npc.reference)) {
            return buildGenericRoleJson(npc);
        }

        // ============================================================================
        // VARIANT ROLE - Uses inheritance from a parent template
        // ============================================================================
        if ("Variant".equals(npc.roleType) && npc.reference != null && !npc.reference.isEmpty()) {
            return buildVariantRoleJson(npc);
        }

        // ============================================================================
        // ABSTRACT / LEGACY ROLE - Full role definition
        // ============================================================================
        return buildAbstractRoleJson(npc);
    }

    /**
     * 基于 Template_Hycompanion_NPC 结构构建Generic角色JSON
     * Generic角色是完全独立的，支持在根级别设置DisplayNames
     * 当需要Variant不支持的特性时使用此方法代替Variant
     */
    private JsonObject buildGenericRoleJson(NpcRoleData npc) {
        JsonObject role = new JsonObject();

        // 类型：Generic，表示独立可生成的角色
        role.addProperty("Type", "Generic");

        // Generic角色不使用状态机（StartState/DefaultSubState/State sensor 均无效）
        // 参考: 原版 Test_Separation_Seek.json — 纯 Generic + Seek，无任何 State 字段

        // 外观设置 - 直接赋值（Generic不像Variant那样使用Parameters计算）
        String appearance = getParameterStringValue(npc.parameters, "Appearance", npc.appearance);
        role.addProperty("Appearance", appearance != null ? appearance : "Outlander");

        // MaxHealth
        Integer maxHealth = getParameterIntValue(npc.parameters, "MaxHealth", npc.maxHealth);
        role.addProperty("MaxHealth", maxHealth != null ? maxHealth : 100);

        // Attitudes
        String playerAttitude = getParameterStringValue(npc.parameters, "DefaultPlayerAttitude",
                npc.defaultPlayerAttitude);
        role.addProperty("DefaultPlayerAttitude",
                playerAttitude != null ? normalizeAttitude(playerAttitude) : "Neutral");

        String npcAttitude = getParameterStringValue(npc.parameters, "DefaultNPCAttitude", npc.defaultNPCAttitude);
        role.addProperty("DefaultNPCAttitude", npcAttitude != null ? normalizeAttitude(npcAttitude) : "Neutral");

        // NameTranslationKey
        String nameKey = getParameterStringValue(npc.parameters, "NameTranslationKey", npc.nameTranslationKey);
        if (nameKey == null || nameKey.isEmpty()) {
            nameKey = "server.npcRoles." + npc.externalId.toLowerCase() + ".name";
        }
        role.addProperty("NameTranslationKey", nameKey);

        // DropList
        String dropList = getParameterStringValue(npc.parameters, "DropList", npc.dropList);
        if (dropList != null && !dropList.isEmpty() && !"Empty".equals(dropList)) {
            role.addProperty("DropList", dropList);
        }

        // DisplayNames - 根级别属性（这就是我们使用Generic而非Variant的原因！）
        com.google.gson.JsonArray displayNamesArray = new com.google.gson.JsonArray();
        if (npc.displayNames != null && npc.displayNames.length > 0) {
            for (String name : npc.displayNames) {
                displayNamesArray.add(name);
            }
        } else if (npc.displayName != null && !npc.displayName.isEmpty()) {
            displayNamesArray.add(npc.displayName);
        }
        if (displayNamesArray.size() > 0) {
            role.add("DisplayNames", displayNamesArray);
        }

        // 物理和行为属性 - 来自模板或NpcRoleData默认值
        role.addProperty("Invulnerable", npc.invulnerable);
        role.addProperty("ApplyAvoidance", npc.applyAvoidance);
        role.addProperty("ApplySeparation", npc.applySeparation);
        role.addProperty("SeparationDistance", npc.separationDistance);
        role.addProperty("Inertia", npc.inertia);
        role.addProperty("KnockbackScale", npc.knockbackScale);
        role.addProperty("BreathesInAir", npc.breathesInAir);
        role.addProperty("BreathesInWater", npc.breathesInWater);

        role.addProperty("InventorySize", npc.inventorySize);
        role.addProperty("HotbarSize", npc.hotbarSize);
        role.addProperty("DeathAnimationTime", npc.deathAnimationTime);
        if (npc.despawnAnimationTime != null) {
            role.addProperty("DespawnAnimationTime", npc.despawnAnimationTime);
        } else {
            role.addProperty("DespawnAnimationTime", 0.8);
        }
        role.addProperty("SpawnLockTime", npc.spawnLockTime);
        if (npc.spawnViewDistance != null) {
            role.addProperty("SpawnViewDistance", npc.spawnViewDistance);
        } else {
            role.addProperty("SpawnViewDistance", 75);
        }
        if (npc.collisionDistance != null) {
            role.addProperty("CollisionDistance", npc.collisionDistance);
        } else {
            role.addProperty("CollisionDistance", 5);
        }
        if (npc.collisionRadius != null) {
            role.addProperty("CollisionRadius", npc.collisionRadius);
        } else {
            role.addProperty("CollisionRadius", -1);
        }

        // 运动控制器列表 - 定义NPC的移动方式（行走、飞行、游泳等）
        com.google.gson.JsonArray motionControllers = new com.google.gson.JsonArray();

        // 优先使用自定义运动控制器，否则使用默认配置
        if (npc.motionControllerList != null && npc.motionControllerList.length() > 0) {
            // Need to convert org.json.JSONArray to Gson JsonArray
            for (int i = 0; i < npc.motionControllerList.length(); i++) {
                org.json.JSONObject mc = npc.motionControllerList.getJSONObject(i);
                JsonObject gsonMc = new JsonObject();
                for (String key : mc.keySet()) {
                    addValueToJsonObject(gsonMc, key, mc.get(key));
                }
                motionControllers.add(gsonMc);
            }
        } else if (npc.motionControllers != null && !npc.motionControllers.isEmpty()) {
            // Legacy format support
            Iterator<String> keys = npc.motionControllers.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                org.json.JSONObject mc = npc.motionControllers.getJSONObject(key);
                JsonObject gsonMc = new JsonObject();
                // Add defaults if missing
                if (!mc.has("Type"))
                    gsonMc.addProperty("Type", "Walk");

                for (String subKey : mc.keySet()) {
                    addValueToJsonObject(gsonMc, subKey, mc.get(subKey));
                }
                motionControllers.add(gsonMc);
            }
        } else {
            // Default Walk Controller
            JsonObject walkController = new JsonObject();
            walkController.addProperty("Type", "Walk");

            // Get MaxSpeed from parameters or use default
            Double maxSpeed = getParameterDoubleValue(npc.parameters, "MaxSpeed", 5.0);
            walkController.addProperty("MaxWalkSpeed", maxSpeed);
            walkController.addProperty("Acceleration", 25);
            walkController.addProperty("MaxRotationSpeed", 180);
            walkController.addProperty("MinJumpHeight", 1.2);
            walkController.addProperty("Gravity", 10);
            walkController.addProperty("MaxFallSpeed", 15);
            motionControllers.add(walkController);
        }
        role.add("MotionControllerList", motionControllers);

        // 获取跟随距离参数，默认为2.0格
        Double followDistance = getParameterDoubleValue(npc.parameters, "FollowDistance", 2.0);

        // 检查是否包含"follow"行为预设
        boolean hasFollowPreset = npc.behaviorPresets != null &&
                java.util.Arrays.stream(npc.behaviorPresets).anyMatch(p -> "follow".equalsIgnoreCase(p));

        // 行为指令集 - 根据行为预设生成（idle、follow、wander等）
        com.google.gson.JsonArray instructions = generateBehaviorInstructions(npc.behaviorPresets, followDistance);
        role.add("Instructions", instructions);

        // Generic角色不支持InteractionInstruction/State - 已移除

        return role;
    }

    /**
     * 构建Variant角色JSON - 从父模板继承并覆盖部分属性
     * 使用简洁的 Type/Reference/Modify 格式
     *
     * 重要：不同模板可用的参数不同，只能修改父模板中已存在的参数
     */
    private JsonObject buildVariantRoleJson(NpcRoleData npc) {
        JsonObject role = new JsonObject();

        // 核心继承字段
        role.addProperty("Type", "Variant");
        role.addProperty("Reference", npc.reference);

        // 构建Modify块 - 覆盖父模板的参数值
        // 重要：只能修改父模板中已存在的参数！
        JsonObject modify = new JsonObject();

        // 判断各模板支持哪些参数
        boolean isIntelligentTemplate = "Template_Intelligent".equals(npc.reference);
        boolean isPredatorTemplate = "Template_Predator".equals(npc.reference);
        boolean isHycompanionTemplate = "Template_Hycompanion_NPC".equals(npc.reference);

        // These templates have Appearance as a computed parameter
        boolean hasAppearanceParam = isIntelligentTemplate || isPredatorTemplate || isHycompanionTemplate;
        // These templates have attitude parameters
        boolean hasAttitudeParam = isIntelligentTemplate || isPredatorTemplate || isHycompanionTemplate;
        // Template_Intelligent and Template_Predator have weapons parameters
        // (Hycompanion doesn't)
        boolean hasWeaponsParam = isIntelligentTemplate || isPredatorTemplate;
        // These templates have DropList as a parameter
        boolean hasDropListParam = isIntelligentTemplate || isPredatorTemplate || isHycompanionTemplate;
        // Most templates have MaxHealth as computed parameter
        boolean hasMaxHealthParam = true;
        // All templates have NameTranslationKey
        boolean hasNameTranslationKeyParam = true;

        // Collect values
        String appearance = getParameterStringValue(npc.parameters, "Appearance", npc.appearance);
        Integer maxHealth = getParameterIntValue(npc.parameters, "MaxHealth", npc.maxHealth);
        String dropList = getParameterStringValue(npc.parameters, "DropList", npc.dropList);

        String nameKey = getParameterStringValue(npc.parameters, "NameTranslationKey", npc.nameTranslationKey);
        if (nameKey == null || nameKey.isEmpty()) {
            nameKey = "server.npcRoles." + npc.externalId.toLowerCase() + ".name";
        }

        // Set Appearance:
        // - For templates with Appearance as a parameter: put direct value in Modify
        // - For templates without (BlankTemplate): we CANNOT override Appearance
        // Variant roles can only have Type/Reference/Modify/Parameters at root
        // The Appearance must be inherited from the parent template
        if (appearance != null && !appearance.isEmpty() && hasAppearanceParam) {
            // Template has Appearance as computed parameter - we can modify it
            modify.addProperty("Appearance", appearance);
        }
        // NOTE: For templates without Appearance parameter (BlankTemplate, etc.),
        // the Appearance is inherited from the parent and cannot be customized.
        // Users should use Template_Intelligent instead, or set roleType to "Generic"

        // Set MaxHealth in Modify (most templates have this as a parameter)
        if (hasMaxHealthParam && maxHealth != null) {
            modify.addProperty("MaxHealth", maxHealth);
        }

        // Set DropList in Modify (only for templates that have it)
        if (hasDropListParam && dropList != null && !dropList.isEmpty()) {
            modify.addProperty("DropList", dropList);
        }

        // Set NameTranslationKey via Compute (referencing our parameter)
        if (hasNameTranslationKeyParam) {
            JsonObject nameCompute = new JsonObject();
            nameCompute.addProperty("Compute", "NameTranslationKey");
            modify.add("NameTranslationKey", nameCompute);
        }

        // Template_Intelligent / Template_Predator specific parameters
        if (hasAttitudeParam) {
            String attitude = getParameterStringValue(npc.parameters, "DefaultPlayerAttitude",
                    npc.defaultPlayerAttitude);
            if (attitude != null) {
                String normalizedAttitude = normalizeAttitude(attitude);
                modify.addProperty("DefaultPlayerAttitude", normalizedAttitude);
            }
        }

        if (hasWeaponsParam) {
            // Weapons array - set directly in Modify
            if (npc.hotbarItems != null && npc.hotbarItems.length > 0) {
                com.google.gson.JsonArray weapons = new com.google.gson.JsonArray();
                for (String item : npc.hotbarItems) {
                    weapons.add(item);
                }
                modify.add("Weapons", weapons);
            }

            // OffHand array
            if (npc.offHandItems != null && npc.offHandItems.length > 0) {
                com.google.gson.JsonArray offHand = new com.google.gson.JsonArray();
                for (String item : npc.offHandItems) {
                    offHand.add(item);
                }
                modify.add("OffHand", offHand);
            }
        }

        // 将用户自定义参数添加到Modify块（这些参数应已在UI端验证过）
        if (npc.parameters != null) {
            for (String key : npc.parameters.keySet()) {
                // Skip parameters we've already handled
                if (key.equals("Appearance") || key.equals("MaxHealth") || key.equals("DropList") ||
                        key.equals("NameTranslationKey") || key.equals("DefaultPlayerAttitude") ||
                        key.equals("Weapons") || key.equals("OffHand")) {
                    continue;
                }

                org.json.JSONObject param = npc.parameters.getJSONObject(key);
                if (param.has("Value")) {
                    Object value = param.get("Value");
                    addValueToJsonObject(modify, key, value);
                }
            }
        }

        // 添加后端明确指定的覆盖值（最高优先级）
        if (npc.modify != null) {
            for (String key : npc.modify.keySet()) {
                Object value = npc.modify.get(key);
                addValueToJsonObject(modify, key, value);
            }
        }

        if (isHycompanionTemplate) {
            addValueToJsonObject(modify, "Appearance", appearance);
            // Display names
            com.google.gson.JsonArray displayNames = new com.google.gson.JsonArray();
            if (npc.displayNames != null && npc.displayNames.length > 0) {
                for (String name : npc.displayNames) {
                    displayNames.add(name);
                }
            } else if (npc.displayName != null && !npc.displayName.isEmpty()) {
                displayNames.add(npc.displayName);
            }
            if (displayNames.size() > 0) {
                addValueToJsonObject(modify, "DisplayNames", displayNames);
            }

        }

        // 添加Modify块
        role.add("Modify", modify);

        // Parameters块 - 定义自有参数供引用
        // 这些参数可被子Variant修改，或通过 {"Compute": "ParamName"} 引用
        JsonObject params = new JsonObject();

        // Appearance parameter - required for model
        JsonObject appearanceParam = new JsonObject();
        appearanceParam.addProperty("Value", appearance != null ? appearance : "Outlander");
        appearanceParam.addProperty("Description", "Model/appearance to use");
        params.add("Appearance", appearanceParam);

        // MaxHealth parameter
        JsonObject healthParam = new JsonObject();
        healthParam.addProperty("Value", maxHealth != null ? maxHealth : 100);
        healthParam.addProperty("Description", "Maximum health for the NPC");
        params.add("MaxHealth", healthParam);

        // NameTranslationKey parameter
        JsonObject nameParam = new JsonObject();
        nameParam.addProperty("Value", nameKey);
        nameParam.addProperty("Description", "Translation key for NPC name");
        params.add("NameTranslationKey", nameParam);

        // DropList parameter
        if (dropList != null && !dropList.isEmpty()) {
            JsonObject dropListParam = new JsonObject();
            dropListParam.addProperty("Value", dropList);
            dropListParam.addProperty("Description", "Drop list reference");
            params.add("DropList", dropListParam);
        }

        // DefaultPlayerAttitude parameter (only for templates that support it)
        if (hasAttitudeParam) {
            String attitude = getParameterStringValue(npc.parameters, "DefaultPlayerAttitude",
                    npc.defaultPlayerAttitude);
            if (attitude != null) {
                JsonObject attitudeParam = new JsonObject();
                attitudeParam.addProperty("Value", normalizeAttitude(attitude));
                attitudeParam.addProperty("Description", "Default attitude towards players");
                params.add("DefaultPlayerAttitude", attitudeParam);
            }
        }

        role.add("Parameters", params);

        // ============================================================================
        // IMPORTANT: Variant roles can ONLY have: Type, Reference, Modify, Parameters
        // ============================================================================
        // The following are NOT valid for Variant roles:
        // - Appearance (even as Compute) - must be in parent template's Parameters
        // - MotionControllerList - inherited from parent template
        // - Instructions - inherited from parent template
        // - InteractionInstruction - inherited from parent template
        // - DisplayNames - not valid for Variant
        //
        // For NPCs that need follow/custom behavior:
        // - Use Template_Intelligent as parent (has Follow built-in)
        // - OR use Generic/Abstract role type with full definition

        return role;
    }

    /**
     * 构建Abstract角色JSON - 完整的角色定义（旧版格式）
     * 从零开始定义所有属性，不依赖父模板继承
     */
    private JsonObject buildAbstractRoleJson(NpcRoleData npc) {
        // ================================================================
        // Variant of Template_Intelligent — 使用引擎内置的 Combat 状态追击
        // Generic 角色的 Player+Seek 已确认不工作（包括原版 Test_Separation_Seek）
        // Template_Intelligent 的 Combat 状态自带 Target sensor + Seek
        // ================================================================
        // 强制使用 Variant + Template_Intelligent
        npc.roleType = "Variant";
        npc.reference = "Template_Intelligent";
        return buildVariantRoleJson(npc);
    }

    /**
     * 根据行为预设生成指令集（状态实现）
     * 遍历每个预设并生成对应的行为指令
     *
     * 可用预设：
     * - "idle"（必须）：静止行为，头部使用Observe运动
     * - "follow"：跟随状态，身体使用Seek运动朝向目标
     * - "wander"：漫游行为，在圆形区域内随机移动
     *
     * @param presets        行为预设名称数组
     * @param followDistance 跟随行为的停止距离（默认2.0格）
     * @return 指令JSON数组
     */
    private com.google.gson.JsonArray generateBehaviorInstructions(String[] presets, double followDistance) {
        com.google.gson.JsonArray instructions = new com.google.gson.JsonArray();

        // 确保至少包含idle预设
        if (presets == null || presets.length == 0) {
            presets = new String[] { "idle" };
        }

        // 检查各预设是否存在
        boolean hasIdlePreset = false;
        boolean hasFollowPreset = false;
        boolean hasWanderPreset = false;

        for (String preset : presets) {
            if (preset == null)
                continue;
            switch (preset.toLowerCase()) {
                case "idle":
                    hasIdlePreset = true;
                    break;
                case "follow":
                    hasFollowPreset = true;
                    break;
                case "wander":
                    hasWanderPreset = true;
                    break;
            }
        }

        // 确保idle始终存在
        if (!hasIdlePreset) {
            hasIdlePreset = true;
        }

        // ============================================================
        // Generic角色行为指令 — 完全匹配原版 Test_Separation_Seek 模式
        // Generic角色不支持State sensor，所以不用State包裹
        // 结构: Instructions > [ { Instructions > [ { Sensor:Player + BodyMotion:Seek } ] } ]
        // ============================================================

        if (hasFollowPreset) {
            // 外层 instruction（无 sensor，始终激活）
            JsonObject outerInstruction = new JsonObject();

            // 内层 instruction: Player sensor + Seek body motion
            com.google.gson.JsonArray innerInstructions = new com.google.gson.JsonArray();
            JsonObject seekInstruction = new JsonObject();

            JsonObject playerSensor = new JsonObject();
            playerSensor.addProperty("Type", "Player");
            playerSensor.addProperty("Range", 80);
            seekInstruction.add("Sensor", playerSensor);

            JsonObject seekBody = new JsonObject();
            seekBody.addProperty("Type", "Seek");
            seekBody.addProperty("AbortDistance", 80);
            seekBody.addProperty("StopDistance", followDistance);
            seekBody.addProperty("SlowDownDistance", 4.0);
            seekInstruction.add("BodyMotion", seekBody);

            JsonObject headMotion = new JsonObject();
            headMotion.addProperty("Type", "Observe");
            com.google.gson.JsonArray headAngle = new com.google.gson.JsonArray();
            headAngle.add(-45.0);
            headAngle.add(45.0);
            headMotion.add("AngleRange", headAngle);
            seekInstruction.add("HeadMotion", headMotion);

            innerInstructions.add(seekInstruction);
            outerInstruction.add("Instructions", innerInstructions);
            instructions.add(outerInstruction);
        }

        return instructions;
    }

    /**
     * NPC角色配置数据类
     * 包含继承系统（新版）和直接字段（旧版兼容）两套属性
     */
    public static class NpcRoleData {
        public String externalId;
        public String displayName;
        public String[] displayNames;
        public String nameTranslationKey;
        public String startState;
        public String startSubState;

        // ============================================================================
        // INHERITANCE SYSTEM (New Role System)
        // ============================================================================

        /**
         * Role type: "Abstract" or "Variant"
         * - Abstract: Standalone role with all behavior defined
         * - Variant: Inherits from a reference template, only overrides specific values
         */
        public String roleType = "Variant";

        /**
         * Parent template reference (for Variant roles)
         * Example: "Template_Intelligent", "Template_Predator"
         */
        public String reference;

        /**
         * Override values for the parent template (for Variant roles)
         * Contains parameter names mapped to their override values
         */
        public org.json.JSONObject modify;

        /**
         * Parameters - configurable values with descriptions
         * Each parameter has: Value, Description, TypeHint (optional)
         */
        public org.json.JSONObject parameters;

        // ============================================================================
        // LEGACY / DIRECT FIELDS (for backwards compatibility and Abstract roles)
        // ============================================================================

        // Hytale Role fields
        public int maxHealth = 100;
        public String appearance = "Outlander";

        // Model
        public String modelPath;
        public Double modelScale;

        public boolean invulnerable = false;
        public String defaultPlayerAttitude = "NEUTRAL";
        public String defaultNPCAttitude = "NEUTRAL";
        public double inertia = 1.0;
        public double knockbackScale = 1.0;
        public boolean applyAvoidance = false;
        public boolean applySeparation = false;
        public double separationDistance = 3.0;

        // Collision
        public Double collisionDistance;
        public Double collisionRadius;

        // Environment
        public boolean breathesInAir = true;
        public boolean breathesInWater = false;
        public Boolean stayInEnvironment;

        // Inventory
        public int inventorySize = 0;
        public int hotbarSize = 3;
        public String[] hotbarItems;
        public Integer offHandSlots;
        public String[] offHandItems;
        public String dropList;
        public Boolean pickupDropOnDeath;

        // Lifecycle
        public double deathAnimationTime = 5.0;
        public Double despawnAnimationTime;
        public double spawnLockTime = 1.5;
        public String spawnParticles;
        public Integer spawnViewDistance;

        // Behavior
        /**
         * Behavior presets array. Available: "idle" (mandatory), "follow", "wander"
         * Default is ["idle"].
         */
        public String[] behaviorPresets = new String[] { "idle" };
        public String[] npcGroups;
        public org.json.JSONObject motionControllers;

        // New format motion controller list (array format)
        public org.json.JSONArray motionControllerList;

        /**
         * 从完整的NPC JSON对象创建角色数据
         * 包括处理根属性（如preventKnockback等覆盖项）
         */
        public static NpcRoleData fromNpcJson(org.json.JSONObject npcJson) {
            String externalId = npcJson.getString("externalId");
            String name = npcJson.getString("name");
            org.json.JSONObject hytaleRole = npcJson.optJSONObject("hytaleRole");

            // Delegate to partial parser
            NpcRoleData data = fromNpcData(externalId, name, hytaleRole);

            // Apply overrides from root NPC properties
            if (npcJson.optBoolean("preventKnockback", false)) {
                data.knockbackScale = 0.0;
            }

            return data;
        }

        /**
         * 从部分数据创建角色数据（旧版/直接格式）
         * 解析hytaleRole对象中的所有字段，支持新旧两套格式
         */
        public static NpcRoleData fromNpcData(String externalId, String name, org.json.JSONObject hytaleRole) {
            NpcRoleData data = new NpcRoleData();
            data.externalId = externalId;
            data.displayName = name;

            if (hytaleRole != null) {
                // ============================================================================
                // INHERITANCE SYSTEM - Parse first for proper precedence
                // ============================================================================

                if (hytaleRole.has("roleType") && !hytaleRole.isNull("roleType")) {
                    data.roleType = hytaleRole.getString("roleType");
                }
                if (hytaleRole.has("reference") && !hytaleRole.isNull("reference")) {
                    data.reference = hytaleRole.getString("reference");
                }
                if (hytaleRole.has("modify") && !hytaleRole.isNull("modify")) {
                    data.modify = hytaleRole.getJSONObject("modify");
                }
                if (hytaleRole.has("parameters") && !hytaleRole.isNull("parameters")) {
                    data.parameters = hytaleRole.getJSONObject("parameters");
                }

                // New format motion controller list (array)
                if (hytaleRole.has("motionControllerList") && !hytaleRole.isNull("motionControllerList")) {
                    data.motionControllerList = hytaleRole.getJSONArray("motionControllerList");
                }

                // ============================================================================
                // LEGACY / DIRECT FIELDS - for backwards compatibility
                // ============================================================================

                if (hytaleRole.has("maxHealth")) {
                    // Handle both number and Compute object
                    Object maxHealthVal = hytaleRole.get("maxHealth");
                    if (maxHealthVal instanceof Number) {
                        data.maxHealth = ((Number) maxHealthVal).intValue();
                    }
                    // If it's a Compute object, leave default - the value will come from parameters
                }
                if (hytaleRole.has("appearance")) {
                    // Handle both string and Compute object
                    Object appearanceVal = hytaleRole.get("appearance");
                    if (appearanceVal instanceof String) {
                        data.appearance = (String) appearanceVal;
                    }
                    // If it's a Compute object, leave default - the value will come from parameters
                }

                // Parse nested "model" object
                if (hytaleRole.has("model") && !hytaleRole.isNull("model")) {
                    org.json.JSONObject model = hytaleRole.getJSONObject("model");
                    if (model.has("path"))
                        data.modelPath = model.getString("path");
                    if (model.has("scale"))
                        data.modelScale = model.getDouble("scale");
                }
                // Fallback for legacy "modelPath" at root
                else if (hytaleRole.has("modelPath") && !hytaleRole.isNull("modelPath")) {
                    data.modelPath = hytaleRole.getString("modelPath");
                }

                // Parse behaviorPresets as an array
                if (hytaleRole.has("behaviorPresets") && !hytaleRole.isNull("behaviorPresets")) {
                    var arr = hytaleRole.getJSONArray("behaviorPresets");
                    data.behaviorPresets = new String[arr.length()];
                    for (int i = 0; i < arr.length(); i++) {
                        data.behaviorPresets[i] = arr.getString(i);
                    }
                }
                // Legacy: support old behaviorPreset (single string) field
                else if (hytaleRole.has("behaviorPreset") && !hytaleRole.isNull("behaviorPreset")) {
                    String preset = hytaleRole.getString("behaviorPreset");
                    // Convert "static" to "idle" for backwards compatibility
                    if ("static".equalsIgnoreCase(preset)) {
                        data.behaviorPresets = new String[] { "idle" };
                    } else {
                        // Include both idle and the specified preset
                        data.behaviorPresets = new String[] { "idle", preset };
                    }
                }

                if (hytaleRole.has("invulnerable"))
                    data.invulnerable = hytaleRole.getBoolean("invulnerable");
                if (hytaleRole.has("defaultPlayerAttitude"))
                    data.defaultPlayerAttitude = hytaleRole.getString("defaultPlayerAttitude");
                if (hytaleRole.has("defaultNPCAttitude"))
                    data.defaultNPCAttitude = hytaleRole.getString("defaultNPCAttitude");
                if (hytaleRole.has("inertia"))
                    data.inertia = hytaleRole.getDouble("inertia");
                if (hytaleRole.has("knockbackScale"))
                    data.knockbackScale = hytaleRole.getDouble("knockbackScale");
                if (hytaleRole.has("applyAvoidance"))
                    data.applyAvoidance = hytaleRole.getBoolean("applyAvoidance");
                if (hytaleRole.has("applySeparation"))
                    data.applySeparation = hytaleRole.getBoolean("applySeparation");
                if (hytaleRole.has("separationDistance"))
                    data.separationDistance = hytaleRole.getDouble("separationDistance");

                if (hytaleRole.has("collisionDistance"))
                    data.collisionDistance = hytaleRole.getDouble("collisionDistance");
                if (hytaleRole.has("collisionRadius"))
                    data.collisionRadius = hytaleRole.getDouble("collisionRadius");

                if (hytaleRole.has("breathesInAir"))
                    data.breathesInAir = hytaleRole.getBoolean("breathesInAir");
                if (hytaleRole.has("breathesInWater"))
                    data.breathesInWater = hytaleRole.getBoolean("breathesInWater");
                if (hytaleRole.has("stayInEnvironment"))
                    data.stayInEnvironment = hytaleRole.getBoolean("stayInEnvironment");

                if (hytaleRole.has("inventorySize"))
                    data.inventorySize = hytaleRole.getInt("inventorySize");
                if (hytaleRole.has("hotbarSize"))
                    data.hotbarSize = hytaleRole.getInt("hotbarSize");
                if (hytaleRole.has("deathAnimationTime"))
                    data.deathAnimationTime = hytaleRole.getDouble("deathAnimationTime");
                if (hytaleRole.has("despawnAnimationTime"))
                    data.despawnAnimationTime = hytaleRole.getDouble("despawnAnimationTime");
                if (hytaleRole.has("spawnLockTime"))
                    data.spawnLockTime = hytaleRole.getDouble("spawnLockTime");
                if (hytaleRole.has("spawnParticles") && !hytaleRole.isNull("spawnParticles"))
                    data.spawnParticles = hytaleRole.getString("spawnParticles");
                if (hytaleRole.has("spawnViewDistance"))
                    data.spawnViewDistance = hytaleRole.getInt("spawnViewDistance");

                // New fields
                if (hytaleRole.has("nameTranslationKey") && !hytaleRole.isNull("nameTranslationKey"))
                    data.nameTranslationKey = hytaleRole.getString("nameTranslationKey");
                if (hytaleRole.has("startState") && !hytaleRole.isNull("startState"))
                    data.startState = hytaleRole.getString("startState");
                if (hytaleRole.has("startSubState") && !hytaleRole.isNull("startSubState"))
                    data.startSubState = hytaleRole.getString("startSubState");
                if (hytaleRole.has("dropList") && !hytaleRole.isNull("dropList"))
                    data.dropList = hytaleRole.getString("dropList");
                if (hytaleRole.has("pickupDropOnDeath"))
                    data.pickupDropOnDeath = hytaleRole.getBoolean("pickupDropOnDeath");

                if (hytaleRole.has("offHandSlots") && !hytaleRole.isNull("offHandSlots"))
                    data.offHandSlots = hytaleRole.getInt("offHandSlots");

                // Arrays
                if (hytaleRole.has("displayNames") && !hytaleRole.isNull("displayNames")) {
                    var arr = hytaleRole.getJSONArray("displayNames");
                    data.displayNames = new String[arr.length()];
                    for (int i = 0; i < arr.length(); i++) {
                        data.displayNames[i] = arr.getString(i);
                    }
                }
                if (hytaleRole.has("hotbarItems") && !hytaleRole.isNull("hotbarItems")) {
                    var arr = hytaleRole.getJSONArray("hotbarItems");
                    data.hotbarItems = new String[arr.length()];
                    for (int i = 0; i < arr.length(); i++) {
                        data.hotbarItems[i] = arr.getString(i);
                    }
                }
                if (hytaleRole.has("offHandItems") && !hytaleRole.isNull("offHandItems")) {
                    var arr = hytaleRole.getJSONArray("offHandItems");
                    data.offHandItems = new String[arr.length()];
                    for (int i = 0; i < arr.length(); i++) {
                        data.offHandItems[i] = arr.getString(i);
                    }
                }

                if (hytaleRole.has("npcGroups") && !hytaleRole.isNull("npcGroups")) {
                    var arr = hytaleRole.getJSONArray("npcGroups");
                    data.npcGroups = new String[arr.length()];
                    for (int i = 0; i < arr.length(); i++) {
                        data.npcGroups[i] = arr.getString(i);
                    }
                }

                // Motion Controllers
                if (hytaleRole.has("motionControllers") && !hytaleRole.isNull("motionControllers")) {
                    data.motionControllers = hytaleRole.getJSONObject("motionControllers");
                }
            }

            return data;
        }
    }

    // ============================================================================
    // 辅助方法
    // ============================================================================

    /**
     * 将任意类型的值添加到JsonObject中
     * 支持数字、字符串、布尔值和JSON数组
     */
    private void addValueToJsonObject(JsonObject obj, String key, Object value) {
        if (value == null) {
            return;
        }

        if (value instanceof Number) {
            if (value instanceof Integer || value instanceof Long) {
                obj.addProperty(key, ((Number) value).longValue());
            } else {
                obj.addProperty(key, ((Number) value).doubleValue());
            }
        } else if (value instanceof Boolean) {
            obj.addProperty(key, (Boolean) value);
        } else if (value instanceof String) {
            obj.addProperty(key, (String) value);
        } else if (value instanceof org.json.JSONArray) {
            org.json.JSONArray arr = (org.json.JSONArray) value;
            com.google.gson.JsonArray gsonArr = new com.google.gson.JsonArray();
            for (int i = 0; i < arr.length(); i++) {
                Object item = arr.get(i);
                if (item instanceof String) {
                    gsonArr.add((String) item);
                } else if (item instanceof Number) {
                    gsonArr.add(((Number) item).doubleValue());
                } else if (item instanceof Boolean) {
                    gsonArr.add((Boolean) item);
                }
            }
            obj.add(key, gsonArr);
        }
    }

    /**
     * 从parameters对象中提取字符串值，未找到时返回fallback值
     */
    private String getParameterStringValue(org.json.JSONObject parameters, String key, String fallback) {
        if (parameters != null && parameters.has(key)) {
            org.json.JSONObject param = parameters.optJSONObject(key);
            if (param != null && param.has("Value")) {
                Object val = param.get("Value");
                if (val instanceof String) {
                    return (String) val;
                }
            }
        }
        return fallback;
    }

    /**
     * 从parameters对象中提取整数值，未找到时返回fallback值
     */
    private Integer getParameterIntValue(org.json.JSONObject parameters, String key, int fallback) {
        if (parameters != null && parameters.has(key)) {
            org.json.JSONObject param = parameters.optJSONObject(key);
            if (param != null && param.has("Value")) {
                Object val = param.get("Value");
                if (val instanceof Number) {
                    return ((Number) val).intValue();
                }
            }
        }
        return fallback;
    }

    /**
     * 从parameters对象中提取浮点数值，未找到时返回fallback值
     */
    private Double getParameterDoubleValue(org.json.JSONObject parameters, String key, double fallback) {
        if (parameters != null && parameters.has(key)) {
            org.json.JSONObject param = parameters.optJSONObject(key);
            if (param != null && param.has("Value")) {
                Object val = param.get("Value");
                if (val instanceof Number) {
                    return ((Number) val).doubleValue();
                }
            }
        }
        return fallback;
    }

    /**
     * 将后端态度字符串标准化为Hytale格式
     * 后端使用：HOSTILE, NEUTRAL, FRIENDLY
     * Hytale使用：Hostile, Neutral, Ignore（FRIENDLY映射为Ignore）
     */
    private String normalizeAttitude(String attitude) {
        if (attitude == null) {
            return "Neutral";
        }

        String upper = attitude.toUpperCase();
        switch (upper) {
            case "HOSTILE":
                return "Hostile";
            case "FRIENDLY":
            case "IGNORE":
                return "Ignore";
            case "NEUTRAL":
            default:
                return "Neutral";
        }
    }
}
