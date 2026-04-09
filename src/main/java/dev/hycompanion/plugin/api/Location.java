package dev.hycompanion.plugin.api;

/**
 * 表示游戏世界中的三维坐标位置
 *
 * @param x     X 坐标
 * @param y     Y 坐标（高度）
 * @param z     Z 坐标
 * @param world 世界/维度名称
 */
public record Location(
        double x,
        double y,
        double z,
        String world) {
    /**
     * 创建不指定世界的位置（使用默认世界 "world"）
     */
    public static Location of(double x, double y, double z) {
        return new Location(x, y, z, "world");
    }

    /**
     * 创建指定世界的位置
     */
    public static Location of(double x, double y, double z, String world) {
        return new Location(x, y, z, world);
    }

    /**
     * 从字符串格式解析位置，支持 "x,y,z" 或 "x,y,z,world" 格式
     */
    public static Location parse(String str) {
        String[] parts = str.split(",");
        if (parts.length < 3) {
            throw new IllegalArgumentException("Invalid location format: " + str);
        }

        double x = Double.parseDouble(parts[0].trim());
        double y = Double.parseDouble(parts[1].trim());
        double z = Double.parseDouble(parts[2].trim());
        String world = parts.length > 3 ? parts[3].trim() : "world";

        return new Location(x, y, z, world);
    }

    /**
     * 计算到另一个位置的三维距离
     */
    public double distanceTo(Location other) {
        if (other == null)
            return Double.MAX_VALUE;

        double dx = this.x - other.x;
        double dy = this.y - other.y;
        double dz = this.z - other.z;

        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * 计算到另一个位置的二维距离（忽略 Y 轴高度）
     */
    public double distanceTo2D(Location other) {
        if (other == null)
            return Double.MAX_VALUE;

        double dx = this.x - other.x;
        double dz = this.z - other.z;

        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * 在当前位置基础上添加偏移量，返回新位置
     */
    public Location add(double dx, double dy, double dz) {
        return new Location(x + dx, y + dy, z + dz, world);
    }

    /**
     * 获取方块坐标（向下取整）
     */
    public Location toBlockLocation() {
        return new Location(
                Math.floor(x),
                Math.floor(y),
                Math.floor(z),
                world);
    }

    /**
     * 转换为坐标字符串格式 "x,y,z"
     */
    public String toCoordString() {
        return String.format("%.1f,%.1f,%.1f", x, y, z);
    }

    @Override
    public String toString() {
        return String.format("Location{x=%.2f, y=%.2f, z=%.2f, world='%s'}", x, y, z, world);
    }
}
