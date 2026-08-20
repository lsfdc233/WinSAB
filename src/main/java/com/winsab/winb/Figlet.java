package com.winsab.winb;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 迷你 figlet 渲染器。
 *
 * 解析打包在资源中的 standard.flf (figlet "Standard" 字体, 与
 * BackPackManager 启动横幅同款), 并复刻 pyfiglet/figlet 的
 * smushing 规则, 渲染 ASCII 艺术字。
 */
public final class Figlet {

    private static final String FONT_RESOURCE = "/standard.flf";

    // figlet smush 模式位
    private static final int SM_EQUAL = 1;      // 相同字符叠合 (不含 hardblank)
    private static final int SM_LOWLINE = 2;    // _ 与层级字符叠合
    private static final int SM_HIERARCHY = 4;  // 层级: |, /\, [], {}, (), <>
    private static final int SM_PAIR = 8;       // [] -> |, {} -> |, () -> |
    private static final int SM_BIGX = 16;      // /+\ -> |, >+< -> X 等
    private static final int SM_HARDBLANK = 32; // hardblank + hardblank
    private static final int SM_KERN = 64;
    private static final int SM_SMUSH = 128;

    private Figlet() {
    }

    /** 渲染一行文本为 figlet 艺术字 (默认宽度 80, 与 figlet/pyfiglet 一致) */
    public static String render(String text) {
        return render(text, 80);
    }

    /** 渲染一行文本, 超过 width 时在最近空格处换行 (复刻 figlet 的 blank-markers 逻辑) */
    public static String render(String text, int width) {
        Font font = Font.load();
        int height = font.height;

        List<String> buffer = emptyRows(height);
        List<List<String>> product = new ArrayList<>();
        java.util.ArrayDeque<BlankMark> blankMarks = new java.util.ArrayDeque<>();
        int prevCharWidth = 0;

        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\n') {
                // 显式换行: 直接冲刷当前行
                product.add(copyRows(buffer));
                buffer = emptyRows(height);
                blankMarks.clear();
                prevCharWidth = 0;
                i++;
                continue;
            }

            List<String> glyph = font.chars.get((int) c);
            if (glyph == null) {
                i++; // 字体中不存在的字符跳过
                continue;
            }
            int curCharWidth = font.widths.get((int) c);
            if (curCharWidth > width) {
                i++; // 单字符宽度超过行宽, 无法容纳
                continue;
            }

            int maxSmush = smushAmount(font, buffer, glyph, curCharWidth, prevCharWidth);
            int totalWidth = buffer.get(0).length() + curCharWidth - maxSmush;

            if (c == ' ') {
                blankMarks.push(new BlankMark(copyRows(buffer), i));
            }

            if (totalWidth >= width) {
                // 换行: 优先回到最近一个空格处断开
                if (!blankMarks.isEmpty()) {
                    BlankMark mark = blankMarks.pop();
                    product.add(mark.rows);
                    i = mark.iterator; // 循环末尾 i++ 会跳过该空格
                    buffer = emptyRows(height);
                    blankMarks.clear();
                    prevCharWidth = 0;
                } else {
                    product.add(copyRows(buffer));
                    i--; // 重放当前字符到新行
                    buffer = emptyRows(height);
                    blankMarks.clear();
                    prevCharWidth = 0;
                }
            } else {
                addGlyphToBuffer(font, buffer, glyph, maxSmush, prevCharWidth, curCharWidth);
            }
            prevCharWidth = curCharWidth;
            i++;
        }

        if (!buffer.get(0).isEmpty()) {
            product.add(buffer);
        }

        // 组装输出: hardblank 替换为空格, 行尾空格去掉
        StringBuilder out = new StringBuilder();
        boolean firstLine = true;
        for (List<String> line : product) {
            if (!firstLine) {
                out.append('\n');
            }
            firstLine = false;
            for (int row = 0; row < height; row++) {
                String rowText = line.get(row).replace(font.hardBlank, ' ');
                int end = rowText.length();
                while (end > 0 && rowText.charAt(end - 1) == ' ') {
                    end--;
                }
                out.append(rowText, 0, end);
                if (row < height - 1) {
                    out.append('\n');
                }
            }
        }
        return out.toString();
    }

    private static List<String> emptyRows(int height) {
        List<String> rows = new ArrayList<>(height);
        for (int r = 0; r < height; r++) {
            rows.add("");
        }
        return rows;
    }

    private static List<String> copyRows(List<String> rows) {
        return new ArrayList<>(rows);
    }

    /** 把字形添加到当前行 (smushRow + 追加) */
    private static void addGlyphToBuffer(Font font, List<String> buffer, List<String> glyph, int maxSmush,
                                         int prevCharWidth, int curCharWidth) {
        int height = buffer.size();
        for (int row = 0; row < height; row++) {
            String addRight = glyph.get(row);
            String addLeft = buffer.get(row);

            StringBuilder merged = new StringBuilder(addLeft);
            for (int k = 0; k < maxSmush; k++) {
                int idx = addLeft.length() - maxSmush + k;
                if (idx >= 0 && idx < addLeft.length()) {
                    char left = addLeft.charAt(idx);
                    char right = k < addRight.length() ? addRight.charAt(k) : ' ';
                    Character smushed = smushChars(font, left, right, prevCharWidth, curCharWidth);
                    if (smushed != null) {
                        merged.setCharAt(idx, smushed);
                    }
                }
            }
            String tail = addRight.length() > maxSmush ? addRight.substring(maxSmush) : "";
            buffer.set(row, merged.append(tail).toString());
        }
    }

    /** 空格断行标记: 记录添加空格前的一行快照与空格位置 */
    private static final class BlankMark {
        final List<String> rows;
        final int iterator;

        BlankMark(List<String> rows, int iterator) {
            this.rows = rows;
            this.iterator = iterator;
        }
    }

    /** 计算当前字符能与左侧缓冲区叠合的列数 (pyfiglet smushAmount) */
    private static int smushAmount(Font font, List<String> buffer, List<String> curChar,
                                   int curCharWidth, int prevCharWidth) {
        if ((font.smushMode & (SM_SMUSH | SM_KERN)) == 0) {
            return 0;
        }
        int maxSmush = curCharWidth;
        for (int row = 0; row < font.height; row++) {
            String lineLeft = buffer.get(row);
            String lineRight = curChar.get(row);

            // 左侧最后一个非空格字符的下标
            int trimmedLeft = lineLeft.length();
            while (trimmedLeft > 0 && lineLeft.charAt(trimmedLeft - 1) == ' ') {
                trimmedLeft--;
            }
            int linebd = trimmedLeft - 1;
            if (linebd < 0) {
                linebd = 0;
            }
            char ch1;
            if (linebd < lineLeft.length()) {
                ch1 = lineLeft.charAt(linebd);
            } else {
                linebd = 0;
                ch1 = '\0'; // 空串
            }

            // 右侧第一个非空格字符的下标
            int charbd = 0;
            while (charbd < lineRight.length() && lineRight.charAt(charbd) == ' ') {
                charbd++;
            }
            char ch2;
            if (charbd < lineRight.length()) {
                ch2 = lineRight.charAt(charbd);
            } else {
                charbd = lineRight.length();
                ch2 = '\0'; // 空串
            }

            int amt = charbd + lineLeft.length() - 1 - linebd;
            if (ch1 == '\0' || ch1 == ' ') {
                amt += 1;
            } else if (ch2 != '\0' && smushChars(font, ch1, ch2, prevCharWidth, curCharWidth) != null) {
                amt += 1;
            }
            if (amt < maxSmush) {
                maxSmush = amt;
            }
        }
        return maxSmush;
    }

    /** 两个边缘字符能否叠合, 返回叠合后的字符 (pyfiglet smushChars, 恒为左到右方向) */
    private static Character smushChars(Font font, char left, char right, int prevCharWidth, int curCharWidth) {
        if (left == ' ') {
            return right;
        }
        if (right == ' ') {
            return left;
        }
        // 宽度为 1 或 0 的字符不参与叠合
        if (prevCharWidth < 2 || curCharWidth < 2) {
            return null;
        }
        // 仅 kerning
        if ((font.smushMode & SM_SMUSH) == 0) {
            return null;
        }
        // 通用重叠 (模式低 6 位全为 0)
        if ((font.smushMode & 63) == 0) {
            if (left == font.hardBlank) {
                return right;
            }
            if (right == font.hardBlank) {
                return left;
            }
            return right;
        }
        if ((font.smushMode & SM_HARDBLANK) != 0) {
            if (left == font.hardBlank && right == font.hardBlank) {
                return left;
            }
        }
        if (left == font.hardBlank || right == font.hardBlank) {
            return null;
        }
        if ((font.smushMode & SM_EQUAL) != 0 && left == right) {
            return left;
        }

        if ((font.smushMode & SM_LOWLINE) != 0) {
            if (left == '_' && "|/\\[]{}()<>".indexOf(right) >= 0) {
                return right;
            }
            if (right == '_' && "|/\\[]{}()<>".indexOf(left) >= 0) {
                return left;
            }
        }

        if ((font.smushMode & SM_HIERARCHY) != 0) {
            if ("|".indexOf(left) >= 0 && "/\\[]{}()<>".indexOf(right) >= 0) {
                return right;
            }
            if ("|".indexOf(right) >= 0 && "/\\[]{}()<>".indexOf(left) >= 0) {
                return left;
            }
            if ("/\\".indexOf(left) >= 0 && "[]{}()<>".indexOf(right) >= 0) {
                return right;
            }
            if ("/\\".indexOf(right) >= 0 && "[]{}()<>".indexOf(left) >= 0) {
                return left;
            }
            if ("[]".indexOf(left) >= 0 && "{}()<>".indexOf(right) >= 0) {
                return right;
            }
            if ("[]".indexOf(right) >= 0 && "{}()<>".indexOf(left) >= 0) {
                return left;
            }
            if ("{}".indexOf(left) >= 0 && "()<>".indexOf(right) >= 0) {
                return right;
            }
            if ("{}".indexOf(right) >= 0 && "()<>".indexOf(left) >= 0) {
                return left;
            }
            if ("()".indexOf(left) >= 0 && "<>".indexOf(right) >= 0) {
                return right;
            }
            if ("()".indexOf(right) >= 0 && "<>".indexOf(left) >= 0) {
                return left;
            }
        }

        if ((font.smushMode & SM_PAIR) != 0) {
            String pair = "" + left + right;
            if (pair.equals("[]") || pair.equals("{}") || pair.equals("()")) {
                return '|';
            }
            pair = "" + right + left;
            if (pair.equals("[]") || pair.equals("{}") || pair.equals("()")) {
                return '|';
            }
        }

        if ((font.smushMode & SM_BIGX) != 0) {
            if (left == '/' && right == '\\') {
                return '|';
            }
            if (right == '/' && left == '\\') {
                return 'Y';
            }
            if (left == '>' && right == '<') {
                return 'X';
            }
        }
        return null;
    }

    /** 解析后的字体 */
    static final class Font {
        int height;
        char hardBlank;
        int smushMode;
        Map<Integer, List<String>> chars = new HashMap<>();
        Map<Integer, Integer> widths = new HashMap<>();

        static Font load() {
            try (InputStream in = Figlet.class.getResourceAsStream(FONT_RESOURCE)) {
                if (in == null) {
                    throw new IllegalStateException("缺少字体资源 " + FONT_RESOURCE);
                }
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                return parse(reader);
            } catch (IOException e) {
                throw new IllegalStateException("读取字体资源失败: " + e.getMessage(), e);
            }
        }

        static Font parse(BufferedReader reader) throws IOException {
            Font font = new Font();
            String header = reader.readLine();
            if (header == null || !(header.startsWith("flf2") || header.startsWith("tlf2"))) {
                throw new IOException("无效的 figlet 字体头: " + header);
            }
            String[] parts = header.substring(5).trim().split("\\s+");
            if (parts.length < 6) {
                throw new IOException("figlet 字体头字段不足: " + header);
            }
            font.hardBlank = parts[0].charAt(0);
            font.height = Integer.parseInt(parts[1]);
            int oldLayout = Integer.parseInt(parts[4]);
            int commentLines = Integer.parseInt(parts[5]);
            Integer fullLayout = parts.length > 7 ? Integer.parseInt(parts[7]) : null;
            if (fullLayout == null) {
                if (oldLayout == 0) {
                    fullLayout = 64;
                } else if (oldLayout < 0) {
                    fullLayout = 0;
                } else {
                    fullLayout = (oldLayout & 31) | 128;
                }
            }
            font.smushMode = fullLayout;

            for (int i = 0; i < commentLines; i++) {
                reader.readLine();
            }

            // 标准 ASCII 字符集 32..126
            for (int code = 32; code <= 126; code++) {
                List<String> glyph = new ArrayList<>();
                int width = 0;
                Character endMark = null;
                for (int row = 0; row < font.height; row++) {
                    String line = reader.readLine();
                    if (line == null) {
                        break;
                    }
                    if (endMark == null) {
                        char last = ' ';
                        for (int j = line.length() - 1; j >= 0; j--) {
                            if (line.charAt(j) != ' ') {
                                last = line.charAt(j);
                                break;
                            }
                        }
                        endMark = last;
                    }
                    // 去掉行尾的 endmark(1~2 个) 及其后的空白;
                    // 注意: endmark 之前的空格属于字形宽度, 必须保留
                    // (pyfiglet 的 reEndMarker 只去掉 endmark 之后的空白)
                    int len = line.length();
                    int count = 0;
                    while (len > 0 && line.charAt(len - 1) == endMark && count < 2) {
                        len--;
                        count++;
                    }
                    line = line.substring(0, len);
                    if (line.length() > width) {
                        width = line.length();
                    }
                    glyph.add(line);
                }
                if (code == 32 || !isEmptyGlyph(glyph)) {
                    font.chars.put(code, glyph);
                    font.widths.put(code, width);
                }
            }
            return font;
        }

        private static boolean isEmptyGlyph(List<String> glyph) {
            for (String row : glyph) {
                if (!row.isEmpty()) {
                    return false;
                }
            }
            return true;
        }
    }
}
