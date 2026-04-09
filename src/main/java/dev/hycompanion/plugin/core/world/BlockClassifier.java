package dev.hycompanion.plugin.core.world;

import java.util.*;

/**
 * 方块材质分类器 - 根据方块ID和显示名称对方块进行材质类型分类。
 * 使用关键词提取和模式匹配，因为Hytale方块标签通常为空。
 *
 * 这使得LLM能够理解"Cedar Trunk"是"wood"（木材），即使方块标签中
 * 没有明确标注"wood"。
 *
 * @author Hycompanion Team
 */
public class BlockClassifier {

    // 材质类型到关键词列表的映射（有序，更具体的类型排在前面）
    private static final Map<String, List<String>> MATERIAL_KEYWORDS = new LinkedHashMap<>();
    
    static {
        // 木材和树木类材质
        MATERIAL_KEYWORDS.put("wood", Arrays.asList(
            "wood", "trunk", "log", "plank", "timber", "lumber",
            "cedar", "oak", "birch", "spruce", "pine", "fir", "ash", "elm",
            "willow", "maple", "mahogany", "ebony", "teak", "acacia", "bamboo"
        ));
        
        // 石头和岩石类材质
        MATERIAL_KEYWORDS.put("stone", Arrays.asList(
            "stone", "rock", "cobble", "cobblestone", "granite", "marble", 
            "slate", "basalt", "obsidian", "sandstone", "limestone", 
            "quartz", "diorite", "andesite", "pumice", "chalk", "shale"
        ));
        
        // 矿石和矿物类材质
        MATERIAL_KEYWORDS.put("ore", Arrays.asList(
            "ore", "iron", "gold", "copper", "coal", "diamond", "emerald",
            "ruby", "sapphire", "amethyst", "topaz", "coal", "tin", "lead",
            "silver", "mithril", "adamantite", "crystal", "gem"
        ));
        
        // 金属类（加工后的，非矿石）
        MATERIAL_KEYWORDS.put("metal", Arrays.asList(
            "metal", "steel", "bronze", "brass", "iron_bar", "gold_bar",
            "copper_bar", "silver_bar", "plate", "sheet"
        ));
        
        // 土壤和泥土类材质
        MATERIAL_KEYWORDS.put("soil", Arrays.asList(
            "dirt", "soil", "mud", "clay", "sand", "gravel", "silt", 
            "peat", "loam", "earth", "dust"
        ));
        
        // 植物和有机材质
        MATERIAL_KEYWORDS.put("plant", Arrays.asList(
            "plant", "crop", "flower", "sapling", "seed",
            "grass", "fern", "vine", "moss", "lichen", "bush", "shrub",
            "hay", "straw", "reed", "cane", "root", "bulb", "petal"
        )); //Removed  "leaf", "leaves", because they can be higher up in the tree (failing path finding)
        
        // 真菌类材质
        MATERIAL_KEYWORDS.put("fungus", Arrays.asList(
            "mushroom", "fungus", "fungi", "spore", "mycelium", "mold", "mould"
        ));
        
        // 流体类材质
        MATERIAL_KEYWORDS.put("fluid", Arrays.asList(
            "water", "lava", "fluid", "liquid", "flow", "stream", "river",
            "ocean", "lake", "puddle", "source"
        ));
        
        // 玻璃和透明材质
        MATERIAL_KEYWORDS.put("glass", Arrays.asList(
            "glass", "window", "pane", "crystal_clear", "transparent"
        ));
        
        // 砖块和砌体材质
        MATERIAL_KEYWORDS.put("brick", Arrays.asList(
            "brick", "masonry", "tile", "ceramic", "porcelain", "clay_brick"
        ));
        
        // 冰雪类材质
        MATERIAL_KEYWORDS.put("ice", Arrays.asList(
            "ice", "snow", "frost", "frozen", "permafrost", "glacier"
        ));
        
        // 织物和柔软材质
        MATERIAL_KEYWORDS.put("fabric", Arrays.asList(
            "wool", "cloth", "fabric", "cotton", "silk", "linen", "canvas",
            "carpet", "rug", "tapestry", "curtain", "banner"
        ));
        
        // 建筑/结构材质（通用建筑材料）
        MATERIAL_KEYWORDS.put("structural", Arrays.asList(
            "concrete", "cement", "plaster", "drywall", "roofing", "shingle",
            "beam", "pillar", "column", "support", "frame", "scaffold"
        ));
    }

    /**
     * 根据方块ID和显示名称对方块进行分类（简化版本）。
     *
     * @param blockId     方块的唯一ID
     * @param displayName 人类可读的显示名称
     * @return 包含提取的材质类型和关键词的BlockInfo
     */
    public static BlockInfo classify(String blockId, String displayName) {
        return classify(blockId, displayName, null, null);
    }
    
    /**
     * 根据方块ID、显示名称以及Hytale标签进行完整分类。
     *
     * 分类流程：
     * 1. 标准化ID和名称，提取关键词
     * 2. 从Hytale原始标签中补充关键词
     * 3. 通过关键词匹配确定材质类型
     * 4. 从Hytale Type标签推断额外材质类型
     *
     * @param blockId     方块唯一ID（如"Cloth_Block_Wool_Black"）
     * @param displayName 人类可读名称（如"Black Cloth"）
     * @param tags        Hytale方块原始标签（如{"Type": ["Cloth"]}）
     * @param categories  方块类别（如["Blocks.Rocks"]）
     * @return 包含材质类型和关键词的BlockInfo
     */
    public static BlockInfo classify(String blockId, String displayName,
            Map<String, String[]> tags, List<String> categories) {
        // 标准化方块ID和名称，合并为统一字符串用于匹配
        String normalizedId = normalize(blockId);
        String normalizedName = normalize(displayName);
        String combined = normalizeWhitespace(normalizedId + " " + normalizedName);

        // 从ID和名称中提取关键词
        Set<String> keywords = extractKeywords(combined);

        // 从Hytale标签中补充提取关键词（如"Cloth"、"Ore"、"Gold"）
        if (tags != null) {
            for (Map.Entry<String, String[]> tagEntry : tags.entrySet()) {
                String tagKey = tagEntry.getKey(); // e.g., "Type", "Family"
                String[] tagValues = tagEntry.getValue(); // e.g., ["Cloth"], ["Ore"]
                
                for (String tagValue : tagValues) {
                    if (tagValue != null) {
                        keywords.add(normalize(tagValue));
                        // Also add the key=value combination
                        keywords.add(normalize(tagKey + "=" + tagValue));
                    }
                }
            }
        }
        
        // 通过ID/名称的关键词匹配来确定材质类型
        List<String> materialTypes = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : MATERIAL_KEYWORDS.entrySet()) {
            String material = entry.getKey();
            List<String> indicators = entry.getValue();
            
            for (String indicator : indicators) {
                if (containsIndicatorWithBoundaries(combined, indicator)) {
                    if (!materialTypes.contains(material)) {
                        materialTypes.add(material);
                    }
                    keywords.add(indicator);
                    break; // Found a match for this material, move to next
                }
            }
        }
        
        // 从Hytale标签推断材质类型
        // 例如：Type=["Cloth"] -> 材质类型为"fabric"
        // 例如：Type=["Ore"] -> 材质类型为"ore"
        if (tags != null) {
            String[] typeTags = tags.get("Type");
            if (typeTags != null) {
                for (String typeTag : typeTags) {
                    String normalizedTag = normalize(typeTag);
                    switch (normalizedTag) {
                        case "cloth":
                        case "wool":
                            if (!materialTypes.contains("fabric")) materialTypes.add("fabric");
                            break;
                        case "ore":
                            if (!materialTypes.contains("ore")) materialTypes.add("ore");
                            break;
                        case "plant":
                        case "crop":
                            if (!materialTypes.contains("plant")) materialTypes.add("plant");
                            break;
                        case "stone":
                        case "rock":
                            if (!materialTypes.contains("stone")) materialTypes.add("stone");
                            break;
                        case "wood":
                        case "log":
                            if (!materialTypes.contains("wood")) materialTypes.add("wood");
                            break;
                        case "metal":
                            if (!materialTypes.contains("metal")) materialTypes.add("metal");
                            break;
                        case "glass":
                            if (!materialTypes.contains("glass")) materialTypes.add("glass");
                            break;
                        case "ice":
                            if (!materialTypes.contains("ice")) materialTypes.add("ice");
                            break;
                    }
                    keywords.add(normalizedTag);
                }
            }
            
            // 处理Family标签以获取更具体的分类
            String[] familyTags = tags.get("Family");
            if (familyTags != null) {
                for (String familyTag : familyTags) {
                    keywords.add(normalize(familyTag));
                }
            }
        }
        
        // 如果没有匹配到任何材质类型，标记为"misc"（杂项���
        if (materialTypes.isEmpty()) {
            materialTypes.add("misc");
        }
        
        return new BlockInfo(
            blockId,
            displayName,
            Collections.unmodifiableList(materialTypes),
            Collections.unmodifiableList(new ArrayList<>(keywords)),
            categories != null ? Collections.unmodifiableList(categories) : Collections.emptyList()
        );
    }
    
    /**
     * 快速分类 - 仅返回材质类型，不进行完整的信息丰富化。
     * 适用于过滤场景。
     */
    public static List<String> getMaterialTypes(String blockId, String displayName) {
        String combined = normalizeWhitespace(normalize(blockId) + " " + normalize(displayName));
        List<String> types = new ArrayList<>();
        
        for (Map.Entry<String, List<String>> entry : MATERIAL_KEYWORDS.entrySet()) {
            for (String indicator : entry.getValue()) {
                if (containsIndicatorWithBoundaries(combined, indicator)) {
                    types.add(entry.getKey());
                    break;
                }
            }
        }
        
        return types.isEmpty() ? List.of("misc") : types;
    }
    
    /**
     * 检查方块是否属于指定的材质类型。
     */
    public static boolean isMaterialType(String blockId, String displayName, String materialType) {
        return getMaterialTypes(blockId, displayName).contains(materialType.toLowerCase());
    }
    
    /**
     * 标准化字符串用于匹配：转为小写，将下划线和短横线替换为空格。
     */
    private static String normalize(String input) {
        if (input == null) return "";
        return input.toLowerCase()
                   .replace("_", " ")
                   .replace("-", " ");
    }

    /**
     * 合并连续空白字符，简化边界匹配。
     */
    private static String normalizeWhitespace(String input) {
        return input.trim().replaceAll("\\s+", " ");
    }

    /**
     * 以完整词或多词短语的方式匹配指示词（边界感知）。
     * 例如："flow"能匹配"_Flow_"但不匹配"flower"。
     */
    private static boolean containsIndicatorWithBoundaries(String combined, String indicator) {
        String normalizedIndicator = normalizeWhitespace(normalize(indicator));
        if (normalizedIndicator.isEmpty()) {
            return false;
        }

        int fromIndex = 0;
        while (true) {
            int matchStart = combined.indexOf(normalizedIndicator, fromIndex);
            if (matchStart < 0) {
                return false;
            }

            int matchEnd = matchStart + normalizedIndicator.length();
            boolean leftBoundary = matchStart == 0 || combined.charAt(matchStart - 1) == ' ';
            boolean rightBoundary = matchEnd == combined.length() || combined.charAt(matchEnd) == ' ';

            if (leftBoundary && rightBoundary) {
                return true;
            }

            fromIndex = matchStart + 1;
        }
    }
    
    /**
     * 从标准化字符串中提取单个关键词。
     * 跳过长度不超过2的短词。
     */
    private static Set<String> extractKeywords(String normalized) {
        Set<String> keywords = new HashSet<>();
        String[] parts = normalized.split("\\s+");
        for (String part : parts) {
            if (part.length() > 2) { // 跳过过短的词
                keywords.add(part);
            }
        }
        return keywords;
    }
    
    /**
     * 获取所有已知的材质类型列表。
     */
    public static List<String> getAllMaterialTypes() {
        return List.copyOf(MATERIAL_KEYWORDS.keySet());
    }
}
