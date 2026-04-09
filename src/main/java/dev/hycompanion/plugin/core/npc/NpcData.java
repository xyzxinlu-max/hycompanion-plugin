package dev.hycompanion.plugin.core.npc;

import dev.hycompanion.plugin.api.Location;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * NPC数据模型 - 表示从后端同步的NPC数据
 *
 * 包含NPC的基本信息、性格设定、行为配置等。
 * 使用volatile字段确保多线程可见性（SocketManager线程更新，游戏线程读取）。
 *
 * @param id           内部UUID（来自后端数据库）
 * @param externalId   用于标识的外部ID（如"merchant_bob"）
 * @param name         游戏中显示的名称
 * @param personality  LLM人格提示词
 * @param greeting     初始问候语
 * @param alignment    DnD风格的阵营（lawful_good、neutral、chaotic_evil等）
 * @param moralProfile NPC的道德约束配置
 * @param syncedAt     上次数据同步的时间
 */
public class NpcData {
    // 后端数据库中的内部ID（不可变）
    private final String id;
    // 外部标识ID，如"merchant_bob"（不可变）
    private final String externalId;
    // 以下字段使用volatile确保线程可见性（SocketManager线程更新，游戏线程读取）
    private volatile String name;           // 游戏中显示的名称
    private volatile String personality;    // LLM人格提示词
    private volatile String greeting;       // 初始问候语
    private volatile Number chatDistance;   // 聊天触发距离
    private volatile boolean broadcastReplies; // 是否广播回复给附近玩家
    private volatile String alignment;      // DnD风格阵营
    private volatile MoralProfile moralProfile; // 道德约束配置
    private volatile boolean invincible;    // 是否无敌
    private volatile boolean preventKnockback; // 是否禁止击退
    private volatile Instant syncedAt;      // 上次同步时间

    /**
     * 构造NPC数据对象
     */
    public NpcData(String id, String externalId, String name, String personality, String greeting,
            Number chatDistance, boolean broadcastReplies, String alignment, MoralProfile moralProfile, boolean invincible,
            boolean preventKnockback, Instant syncedAt) {
        this.id = id;
        this.externalId = externalId;
        this.name = name;
        this.personality = personality;
        this.greeting = greeting;
        this.chatDistance = chatDistance;
        this.broadcastReplies = broadcastReplies;
        this.alignment = alignment;
        this.moralProfile = moralProfile;
        this.invincible = invincible;
        this.preventKnockback = preventKnockback;
        this.syncedAt = syncedAt;
    }

    /**
     * 从另一个NpcData对象更新当前对象的属性。
     * 使用synchronized确保所有字段原子性更新。
     * 仅当externalId相同时才执行更新，非空字段会覆盖当前值。
     */
    public synchronized void updateFrom(NpcData other) {
        if (!this.externalId.equals(other.externalId))
            return;

        if (other.name != null)
            this.name = other.name;
        if (other.personality != null)
            this.personality = other.personality;
        if (other.greeting != null)
            this.greeting = other.greeting;
        if (other.chatDistance != null)
            this.chatDistance = other.chatDistance;
        this.broadcastReplies = other.broadcastReplies;
        if (other.alignment != null)
            this.alignment = other.alignment;
        if (other.moralProfile != null)
            this.moralProfile = other.moralProfile;
        this.invincible = other.invincible;
        this.preventKnockback = other.preventKnockback;
        if (other.syncedAt != null)
            this.syncedAt = other.syncedAt;
    }

    public String id() {
        return id;
    }

    public String externalId() {
        return externalId;
    }

    public String name() {
        return name;
    }

    public String personality() {
        return personality;
    }

    public String greeting() {
        return greeting;
    }

    public Number chatDistance() {
        return chatDistance;
    }

    public boolean broadcastReplies() {
        return broadcastReplies;
    }

    public String alignment() {
        return alignment;
    }

    public MoralProfile moralProfile() {
        return moralProfile;
    }

    public boolean isInvincible() {
        return invincible;
    }

    public boolean isKnockbackDisabled() {
        return preventKnockback;
    }

    public Instant syncedAt() {
        return syncedAt;
    }

    /**
     * 从后端同步数据创建NPC对象。
     * 对alignment和moralProfile提供默认值。
     */
    public static NpcData fromSync(
            String id,
            String externalId,
            String name,
            String personality,
            String greeting,
            Number chatDistance,
            boolean broadcastReplies,
            String alignment,
            MoralProfile moralProfile,
            boolean isInvincible,
            boolean preventKnockback) {
        return new NpcData(
                id,
                externalId,
                name,
                personality,
                greeting,
                chatDistance,
                broadcastReplies,
                alignment != null ? alignment : "neutral",
                moralProfile != null ? moralProfile : MoralProfile.DEFAULT,
                isInvincible,
                preventKnockback,
                Instant.now());
    }

    /**
     * 创建一个更新了同步时间戳的副本
     */
    public NpcData withSyncedAt(Instant newSyncedAt) {
        return new NpcData(
                id, externalId, name, personality, greeting,
                chatDistance, broadcastReplies, alignment, moralProfile, invincible, preventKnockback, newSyncedAt);
    }

    /**
     * NPC的道德约束配置
     *
     * @param ideals               NPC不会违背的核心信念列表
     * @param persuasionResistance 对玩家操控的抵抗程度（total/strong/medium/weak）
     */
    public record MoralProfile(
            List<String> ideals,
            String persuasionResistance) {
        /** 默认道德配置：在职责范围内提供帮助，强抵抗力 */
        public static final MoralProfile DEFAULT = new MoralProfile(
                List.of("Be helpful within my role"),
                "strong");

        /**
         * 检查NPC是否对操控有高抵抗力（total或strong级别）
         */
        public boolean isHighlyResistant() {
            return "total".equals(persuasionResistance) || "strong".equals(persuasionResistance);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        NpcData npcData = (NpcData) o;
        return java.util.Objects.equals(externalId, npcData.externalId);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(externalId);
    }

    @Override
    public String toString() {
        return "NpcData[" +
                "id=" + id + ", " +
                "externalId=" + externalId + ", " +
                "name=" + name + ']';
    }
}
