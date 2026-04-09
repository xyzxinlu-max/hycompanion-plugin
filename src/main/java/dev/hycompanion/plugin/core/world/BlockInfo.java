package dev.hycompanion.plugin.core.world;

import java.util.List;
import java.util.Objects;

/**
 * 方块类型的丰富信息 - 服务器上可用的方块类型数据。
 * 在启动时发送给后端，使LLM能够发现和识别方块。
 *
 * @param blockId       方块唯一ID（如"Wood_Cedar_Trunk"、"hytale:stone"）
 * @param displayName   人类可读名称（如"Cedar Log"）
 * @param materialTypes 分类后的材质类型（如["wood"]、["stone", "ore"]）
 * @param keywords      从ID和名称中提取的搜索关键词
 * @param categories    来自资产数据的方块类别（如有）
 *
 * @author Hycompanion Team
 */
public record BlockInfo(
        String blockId,
        String displayName,
        List<String> materialTypes,
        List<String> keywords,
        List<String> categories) {

    /**
     * 检查此方块是否匹配指定的材质类型（不区分大小写）。
     */
    public boolean hasMaterialType(String type) {
        return materialTypes.stream()
                .anyMatch(mt -> mt.equalsIgnoreCase(type));
    }

    /**
     * 检查此方块是否匹配给定的关键词（不区分大小写，部分匹配）。
     */
    public boolean matchesKeyword(String keyword) {
        String lowerKeyword = keyword.toLowerCase();
        return keywords.stream()
                .anyMatch(k -> k.toLowerCase().contains(lowerKeyword));
    }

    @Override
    public String toString() {
        return String.format("BlockInfo[%s (%s) - materials: %s]", 
                blockId, displayName, String.join(", ", materialTypes));
    }
}
