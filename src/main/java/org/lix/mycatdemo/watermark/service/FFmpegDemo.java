package org.lix.mycatdemo.watermark.service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * 修复文字水印参数解析问题的 FFmpeg 水印工具类
 * 兼容 Windows 和 Linux 系统
 */
public class FFmpegDemo {
    // 已配置环境变量，直接用 ffmpeg 即可
    private static final String FFMPEG_PATH = "ffmpeg";
    
    /**
     * 判断当前操作系统是否为 Windows
     */
    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("windows");
    }

    /**
     * 给视频添加图片水印（已验证可用）
     *
     * @param position 位置关键字：LEFT_TOP / RIGHT_TOP / LEFT_BOTTOM / RIGHT_BOTTOM / CENTER
     *                 传 null 或空时，默认使用 LEFT_TOP（左上角）
     */
    public static void addImageWatermark(String inputVideoPath, String outputVideoPath,
                                         String watermarkImagePath, String position)
            throws IOException, InterruptedException {
        // 基础参数校验，避免 ffmpeg 返回含糊的 -22 错误
        ensureFileExists(inputVideoPath, "输入视频文件");
        ensureFileExists(watermarkImagePath, "水印图片文件");

        String overlayExpr = resolveImagePosition(position);

        String[] cmd = {
                FFMPEG_PATH,
                "-i", inputVideoPath,
                "-i", watermarkImagePath,
                "-filter_complex", "overlay=" + overlayExpr,
                "-c:v", "libx264",
                "-c:a", "copy",
                "-y",
                outputVideoPath
        };
        executeFFmpegCommand(cmd);
    }

    /**
     * 修复后的文字水印添加方法
     * 解决：1. 路径冒号转义 2. 中文/引号转义 3. 参数格式错误
     *
     * @param position 位置关键字：LEFT_TOP / RIGHT_TOP / LEFT_BOTTOM / RIGHT_BOTTOM / CENTER
     *                 传 null 或空时，默认使用 RIGHT_BOTTOM（右下角）
     */
    public static void addTextWatermark(String inputVideoPath, String outputVideoPath,
                                        String text, String position, String fontPath)
            throws IOException, InterruptedException {
        // 基础参数校验
        ensureFileExists(inputVideoPath, "输入视频文件");
        ensureFileExists(fontPath, "字体文件");

        // 关键：drawtext 参数用 ':' 分隔，而 Windows 路径里有 'C:'，必须把 ':' 转义为 '\:'
        // 否则 ffmpeg 会把 'C:' 截断，导致报错：No option name near '/Windows/Fonts/...'
        String escapedFontPath = escapeDrawtextPath(fontPath);
        String escapedText = escapeDrawtextText(text);
        String drawtextPosition = resolveTextPosition(position);

        String filter = String.format(
                "drawtext=fontfile='%s':text='%s':fontsize=24:fontcolor=white@0.8:%s",
                escapedFontPath,
                escapedText,
                drawtextPosition
        );

        String[] cmd = {
                FFMPEG_PATH,
                "-i", inputVideoPath,
                "-vf", filter,
                "-c:v", "libx264",
                "-c:a", "copy",
                "-y",
                outputVideoPath
        };

        executeFFmpegCommand(cmd);
    }

    private static void executeFFmpegCommand(String[] cmd) throws IOException, InterruptedException {
        // 先检查 ffmpeg 是否已安装且可用
        checkFFmpegInstalled();

        // 打印执行的命令，方便调试
        System.out.println("执行的 FFmpeg 命令：");
        for (String s : cmd) {
            System.out.print(s + " ");
        }
        System.out.println();

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder ffmpegOutput = new StringBuilder();

        try (InputStream inputStream = process.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
                ffmpegOutput.append(line).append(System.lineSeparator());
            }
        }

        int exitCode = process.waitFor();
        if (exitCode == 0) {
            System.out.println("水印添加成功！输出文件：" + cmd[cmd.length - 1]);
        } else {

            throw new RuntimeException("FFmpeg 命令执行失败，退出码：" + exitCode +
                    "\n完整输出信息：\n" + ffmpegOutput);
        }
    }

    /**
     * 检查本机是否已安装 ffmpeg（或环境变量是否配置正确）。
     * 做法：执行一次 `ffmpeg -version`，根据退出码/异常判断。
     */
    private static void checkFFmpegInstalled() {
        Process process = null;
        try {
            process = new ProcessBuilder(FFMPEG_PATH, "-version")
                    .redirectErrorStream(true)
                    .start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException("未检测到可用的 ffmpeg，可执行文件名称为: " + FFMPEG_PATH +
                        "。请确认已安装 ffmpeg 并配置到 PATH 环境变量，或修改 FFMPEG_PATH 为绝对路径。");
            }
        } catch (Exception e) {
            throw new IllegalStateException("检查 ffmpeg 是否安装时出错，请确认本机已安装 ffmpeg 并已加入 PATH。", e);
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    /**
     * 校验文件是否存在，避免 ffmpeg 因路径错误返回 \"Invalid argument (-22)\" 这类模糊错误
     */
    private static void ensureFileExists(String path, String desc) {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException(desc + " 路径为空");
        }
        File file = new File(path);
        if (!file.exists()) {
            throw new IllegalArgumentException(desc + " 不存在，路径为: " + path);
        }
        if (!file.isFile()) {
            throw new IllegalArgumentException(desc + " 不是文件，路径为: " + path);
        }
    }

    /**
     * drawtext 中的路径转义：
     * - 统一使用 '/' 分隔符（FFmpeg 在 Windows 上也支持 '/'）
     * - Windows 路径：将盘符冒号 'C:' 转义为 'C\:'，避免与 drawtext 参数分隔符 ':' 冲突
     * - Linux 路径：不需要转义冒号（Linux 路径通常不包含盘符冒号）
     */
    private static String escapeDrawtextPath(String path) {
        if (path == null) {
            return "";
        }
        // 统一转换为正斜杠（FFmpeg 在 Windows 上也支持）
        String normalizedPath = path.replace("\\", "/");
        
        // 只在 Windows 系统且路径包含盘符冒号时才转义（如 C:/、D:/）
        if (isWindows() && normalizedPath.matches("^[A-Za-z]:/.*")) {
            // 只转义盘符后的冒号，其他冒号不转义（虽然正常路径不应该有其他冒号）
            // 这里需要在 replacement 里生成字面量 "\:"，因此写成 "$1\\\\:"（Java 字符串转义后变成 "\:"）
            normalizedPath = normalizedPath.replaceFirst("^([A-Za-z]):", "$1\\\\:");
        }
        
        return normalizedPath;
    }

    /**
     * drawtext 中的 text 转义：
     * - 反斜杠、单引号、冒号都可能影响解析
     * - 这里把它们转义掉，避免"参数被截断/解析失败"
     */
    private static String escapeDrawtextText(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replace("\\", "\\\\")
                .replace(":", "\\:")
                .replace("'", "\\'");
    }

    /**
     * 解析图片水印位置关键字为 overlay 表达式
     * - LEFT_TOP:       左上角
     * - RIGHT_TOP:      右上角
     * - LEFT_BOTTOM:    左下角
     * - RIGHT_BOTTOM:   右下角
     * - CENTER:         居中
     */
    private static String resolveImagePosition(String pos) {
        String p = (pos == null || pos.trim().isEmpty())
                ? "LEFT_TOP" // 默认图片水印在左上角
                : pos.trim().toUpperCase();

        switch (p) {
            case "RIGHT_TOP":
                return "main_w-overlay_w-10:10";
            case "LEFT_BOTTOM":
                return "10:main_h-overlay_h-10";
            case "RIGHT_BOTTOM":
                return "main_w-overlay_w-10:main_h-overlay_h-10";
            case "CENTER":
                return "(main_w-overlay_w)/2:(main_h-overlay_h)/2";
            case "LEFT_TOP":
            default:
                return "10:10";
        }
    }

    /**
     * 解析文字水印位置关键字为 drawtext 的 x,y 表达式
     * - LEFT_TOP:       左上角
     * - RIGHT_TOP:      右上角
     * - LEFT_BOTTOM:    左下角
     * - RIGHT_BOTTOM:   右下角（默认）
     * - CENTER:         居中
     */
    private static String resolveTextPosition(String pos) {
        String p = (pos == null || pos.trim().isEmpty())
                ? "RIGHT_BOTTOM" // 默认文字水印在右下角
                : pos.trim().toUpperCase();

        switch (p) {
            case "LEFT_TOP":
                return "x=10:y=10";
            case "RIGHT_TOP":
                return "x=w-tw-10:y=10";
            case "LEFT_BOTTOM":
                return "x=10:y=h-th-10";
            case "CENTER":
                return "x=(w-tw)/2:y=(h-th)/2";
            case "RIGHT_BOTTOM":
            default:
                return "x=w-tw-10:y=h-th-10";
        }
    }

    /**
     * 获取系统默认字体路径
     * Windows: C:/Windows/Fonts/msyh.ttc (微软雅黑)
     * Linux: /usr/share/fonts/truetype/dejavu/DejaVuSans.ttf (DejaVu Sans) 或其他常见字体
     */
    public static String getDefaultFontPath() {
        if (isWindows()) {
            // Windows 常见字体路径
            String[] windowsFonts = {
                    "C:/Windows/Fonts/msyh.ttc",      // 微软雅黑
                    "C:/Windows/Fonts/simsun.ttc",    // 宋体
                    "C:/Windows/Fonts/simhei.ttf"    // 黑体
            };
            for (String font : windowsFonts) {
                if (new File(font).exists()) {
                    return font;
                }
            }
            // 如果都不存在，返回最常见的
            return windowsFonts[0];
        } else {
            // Linux 常见字体路径
            String[] linuxFonts = {
                    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
                    "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
                    "/usr/share/fonts/TTF/DejaVuSans.ttf",
                    "/usr/local/share/fonts/DejaVuSans.ttf"
            };
            for (String font : linuxFonts) {
                if (new File(font).exists()) {
                    return font;
                }
            }
            // 如果都不存在，返回最常见的
            return linuxFonts[0];
        }
    }

    // 测试示例
    public static void main(String[] args) {
        try {
            System.out.println("当前操作系统: " + System.getProperty("os.name"));
            
            // 1. 图片水印（已验证可用）
            String inputVideo = isWindows() ? "F:/aaa.mp4" : "/tmp/aaa.mp4";
            String outputWithImage = isWindows() ? "F:/test_with_image_watermark.mp4" : "/tmp/test_with_image_watermark.mp4";
            String watermarkImage = isWindows() ? "F:/default_watermark.png" : "/tmp/default_watermark.png";
            // 这里不传或传 null，使用默认 LEFT_TOP；也可以传 RIGHT_BOTTOM / CENTER 等
            addImageWatermark(inputVideo, outputWithImage, watermarkImage, null);

            // 2. 修复后的文字水印（自动检测系统字体路径）
            String outputWithText = isWindows() ? "F:/test_with_text_watermark.mp4" : "/tmp/test_with_text_watermark.mp4";
            String text = "我的专属视频";
            String fontPath = getDefaultFontPath(); // 自动获取系统默认字体路径
            System.out.println("使用字体路径: " + fontPath);
            // 这里不传或传 null，使用默认 RIGHT_BOTTOM；也可以传 LEFT_TOP / CENTER 等
            addTextWatermark(inputVideo, outputWithText, text, null, fontPath);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}