package command.plus;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.file.Files;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.BufferedWriter;

public class MinecraftMusicConverter {

    private final int MODE;
    private final String SCOREBOARD_NAME;
    private final int START_OFFSET;
    private final boolean ENABLE_STACKING;
    private final int MAX_CHARS;
    private final String inputString;
    private final Set<Integer> forbiddenValues;
    private final boolean enableExtraSensitiveOptimization; // 【新增】是否开启附加违禁数值优化
    private final NeteaseSensitiveDetector detector;       // 【新增】检测器实例

    private MinecraftMusicConverter(Builder b) {
        this.MODE = b.MODE;
        this.SCOREBOARD_NAME = b.SCOREBOARD_NAME;
        this.START_OFFSET = b.START_OFFSET;
        this.ENABLE_STACKING = b.ENABLE_STACKING;
        this.MAX_CHARS = b.MAX_CHARS;
        this.inputString = b.inputString;
        this.forbiddenValues = b.forbiddenValues;
        this.enableExtraSensitiveOptimization = b.enableExtraSensitiveOptimization;
        this.detector = b.enableExtraSensitiveOptimization ? new NeteaseSensitiveDetector() : null;
    }

    public static class Builder {
        private int MODE = 0;
        private String SCOREBOARD_NAME = "t";
        private int START_OFFSET = 0;
        private boolean ENABLE_STACKING = true;
        private int MAX_CHARS = 3000;
        private String inputString = "";
        private Set<Integer> forbiddenValues = new HashSet<>();
        private boolean enableExtraSensitiveOptimization = true; // 【新增】默认开启

        public Builder setMode(int mode) { this.MODE = mode; return this; }
        public Builder setScoreboardName(String name) { this.SCOREBOARD_NAME = name; return this; }
        public Builder setStartOffset(int offset) { this.START_OFFSET = offset; return this; }
        public Builder setEnableStacking(boolean v) { this.ENABLE_STACKING = v; return this; }
        public Builder setMaxChars(int chars) { this.MAX_CHARS = chars; return this; }
        public Builder setInputString(String s) { this.inputString = s; return this; }
        public Builder setForbiddenValues(Set<Integer> vals) { this.forbiddenValues = vals; return this; }
        public Builder setEnableExtraSensitiveOptimization(boolean enable) { this.enableExtraSensitiveOptimization = enable; return this; } // 【新增】

        public MinecraftMusicConverter build() { return new MinecraftMusicConverter(this); }
    }

    // --- 【新增】网易敏感数值检测器 Java 实现版 ---
    public static class NeteaseSensitiveDetector {
        private final List<Pattern> patterns = new ArrayList<>();

        public NeteaseSensitiveDetector() {
            String boundaryLeft64 = "[（）+\\-#0-9一-龥{}/a-z,\\s。°￥¥%:.\\…，~]";
        String boundaryRight64 = "[（）（）{}+\\-#0-9_a-z一-龥=.\\，,\\—\\…'%１２３４５６７８９０]|\\s[0-9]";

        patterns.add(Pattern.compile("^[\\s\\.,，]*?[6][\\s\\.,，]*?[4][\\s\\.,，]*?[8][\\s\\.,，]*?[9][\\s\\.,，]*?$", Pattern.CASE_INSENSITIVE));
        patterns.add(Pattern.compile("^[\\s.,，]?[6][\\s.,，]?[4][\\s.,，]?[8][\\s.,，]?[9][\\s.,，]?$", Pattern.CASE_INSENSITIVE));
        patterns.add(Pattern.compile("^[\\s.,，]?[8][\\s.,，]?[9][\\s.,，]?[6][\\s.,，]?[4][\\s.,，]?$", Pattern.CASE_INSENSITIVE));

        patterns.add(Pattern.compile(
                "^(?!.?@).?(?<!" + boundaryLeft64 + ")([6б６⒍⑥ｂБЬ㈥⑹][^一-龥1-9１２３４５６７８９０a-z=/]?[4４⒋④㈣⑷Ч４ㄐчㄐ])(?!(" + boundaryRight64 + "))",
                Pattern.CASE_INSENSITIVE));

        patterns.add(Pattern.compile(
                "(?<![\\[\\<\\>\\(\\)\\（\\）\\+\\-#0-9一-龥\\\\\\{\\}\\/a-z\\,\\s_。°￥¥%\\:\\.\\…\\'\\\"\\，\\^\\~\\]])[捌八8][^一-龥、\\-#0-9a-z=]*?[九玖9](?!(\\-JPE|\\\\|[\\.\\-][0-9]))(?!([\\[\\<\\>\\(\\)\\（\\）\\{\\}\\+\\-#0-9_a-z一-龥\\=\\*\\.\\，\\,\\—\\…\\'\\\"\\'%１２３４５６７８９０\\]]|\\s[0-9]|\\s法师|\\s求组))",
                Pattern.CASE_INSENSITIVE));

        patterns.add(Pattern.compile(
                "(?<![\\<\\>\\(\\%\\~#0-9a-z座米零一二三五七世开期周])([8八捌][9九玖]|[6六陆][4四肆]|[5五][3三][5五])(?![_a-z0-9个道分级进月班舍寝室八节点,\\)])",
                Pattern.CASE_INSENSITIVE));

        patterns.add(Pattern.compile(
                "^(?!.*?@).*?(?<![\\[\\<\\>\\(\\)\\（\\）\\+\\-#0-9一-龥\\\\\\{\\}\\/a-z\\,\\s_。°￥¥%\\:\\.\\…\\，\\~\\]])([6б６⒍⑥ｂБЬ㈥⑹六陆][^一-龥1-9１２３４５６７８９０a-z=\\/]*?[4４⒋④㈣⑷四肆Ч４ㄐчㄐ])(?!([\\[\\<\\>\\(\\)\\（\\）\\{\\}\\+\\-#0-9_a-z一-龥\\=\\*\\.\\，\\,\\—\\…\\'%１２３４５６７８９０\\]]|\\s[0-9]))",
                Pattern.CASE_INSENSITIVE));

        patterns.add(Pattern.compile(
                "(?<![\\[\\<\\>\\(\\)\\（\\）\\+\\-#0-9一-龥\\\\\\{\\}\\/a-z\\,\\s_。°￥¥%\\:\\.\\…\\，\\~\\]])([捌八8Ⅷ][^一-龥0-9a-z=ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩⅪⅫ]*?[九玖9Ⅸ][^一-龥0-9a-z=ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩⅪⅫ]*?[6б６⒍⑥ｂБЬ㈥⑹六陆Ⅵ][^一-龥0-9a-z=ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩⅪⅫ]*?[0零]?[^一-龥0-9a-z=ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩⅪⅫ]*?[4４⒋④㈣⑷四肆Ч４ㄐчㄐⅣ]|1?9?[捌八8][九玖9].?[6六陆溜][0零]?[4四肆]|1?9?[6六陆][0零]?[4四肆][^一-龥0-9a-z]?[捌八8][九玖9]|469891)(?!([\\[\\<\\>\\(\\)\\（\\）\\+\\-#0-9_a-z一-龥\\*\\.\\，\\,\\—\\…\\'%１２３４５６７８９０\\]]|\\s[0-9]))",
                Pattern.CASE_INSENSITIVE));

        patterns.add(Pattern.compile(
                "^[\\s\\.\\,\\，。丶]*?[8八捌叭][\\s\\.\\,\\，。丶]*?[9九玖][\\s\\.\\,\\，。丶]*?[6六陆][\\s\\.\\,\\，。丶]*?[4四肆][\\s\\.\\,\\，。丶_]*?$",
                Pattern.CASE_INSENSITIVE));

        patterns.add(Pattern.compile(
                "^[\\s\\.\\,\\，。丶]*?[8八捌叭][\\s\\.\\,\\，。丶]*?[9九玖][\\s\\.\\,\\，。丶]*?[6六陆][\\s\\.\\,\\，。丶]*?[4四肆][\\s\\.\\,\\，。丶]*?[8八捌叭][\\s\\.\\,\\，。丶]*?[9九玖][\\s\\.\\,\\，。丶]*?$",
                Pattern.CASE_INSENSITIVE));

        patterns.add(Pattern.compile(
                "^[\\s\\.\\,\\，。丶]*?[6六陆][\\s\\.\\,\\，。丶]*?[4四肆][\\s\\.\\,\\，。丶]*?[8八捌叭][\\s\\.\\,\\，。丶]*?[9九玖][\\s\\.\\,\\，。丶]*?[6六陆][\\s\\.\\,\\，。丶]*?[4四肆][\\s\\.\\,\\，。丶]*?$",
                Pattern.CASE_INSENSITIVE));

        patterns.add(Pattern.compile(
                "(?<![\\(a-z0-9#\\)])((liu|[6六])\\s*?(si|[4四])\\s*?(ba|[8八])\\s*?(jiu|[9九])|[六6陆][十0拾]?[四4肆][八8捌][十0拾]?[九9玖])(?![\\(_0-9a-z\\)])",
                Pattern.CASE_INSENSITIVE));

        patterns.add(Pattern.compile(
                "^(?!.*?@[as]).*?(?<![\\[\\<\\>\\(\\)\\（\\）\\+\\-#0-9a-z\\\\\\{\\}\\/\\,\\s_。°￥¥%\\:\\.\\…\\，\\]])[8八][^一-龥a-z0-9]*?[9九][^一-龥a-z0-9]*?[6六][^一-龥a-z0-9]*?[4四](?![\\[\\<\\>\\(\\)\\（\\）\\+\\-#0-9a-z\\\\\\{\\}\\/\\,\\s。°￥¥%\\:\\.\\…\\，\\]])",
                Pattern.CASE_INSENSITIVE));

        patterns.add(Pattern.compile(
                "(?<![a-z0-9#])(ba|[8八])\\s*?(jiu|[9九])\\s*?(liu|[6六])\\s*?(si|[4四])(?![0-9a-z])",
                Pattern.CASE_INSENSITIVE));

        patterns.add(Pattern.compile(
                "(?<![a-z0-9#])[8八][s\\.\\,\\，丶]*?[9九][s\\.\\,\\，丶]*?l[s\\.\\,\\，丶]*?si?(?![0-9a-z])",
                Pattern.CASE_INSENSITIVE));

        patterns.add(Pattern.compile(
                "6\\+3[^a-z一-龥0-9]*?3\\+3[^a-z一-龥0-9]*?2\\+2(?![0-9a-z])",
                Pattern.CASE_INSENSITIVE));

        patterns.add(Pattern.compile(
                "[3].{0,3}?[7].{0,3}?[5].{0,3}?[4].{0,3}?[1一].{0,3}?[5]",
                Pattern.CASE_INSENSITIVE));

        patterns.add(Pattern.compile(
                "(?<![0-9#])37[a-z\\s\\.\\,\\，。丶]*?年[0-9a-z\\s\\.\\,\\，。丶]*?前",
                Pattern.CASE_INSENSITIVE));

        patterns.add(Pattern.compile(
                "(?<![0-9#])[4四]?[\\s\\.\\,\\，。丶]*?[2二][\\s\\.\\,\\，。丶]*?[6六][\\s\\.\\,\\，。丶]*?社论",
                Pattern.CASE_INSENSITIVE));

        patterns.add(Pattern.compile(
                "^[^\\sa-zA-Z一-龥0-9]*?(?<![#])[3３三叁]([^一-龥0-9１２３４五六七八九零a-z=/]*?|十)[7七柒][^a-zA-Z一-龥0-9]*?$",
                Pattern.CASE_INSENSITIVE));
        }

        public boolean matchText(String text) {
            for (Pattern p : patterns) {
                if (p.matcher(text).find()) {
                    return true;
                }
            }
            return false;
        }
    }

    // --- 核心工具方法 ---

    /**
     * 判断一个违禁值是否是“孤立”的。
     * 如果它的前一个或后一个数也是违禁的，则返回 true (表示连续)。
     */
    private boolean isConsecutiveForbidden(int val) {
        return forbiddenValues.contains(val - 1) || forbiddenValues.contains(val + 1);
    }

    /**
     * 核心逻辑：获取安全的范围描述（同步了贴合真实语境的优化算法）
     */
    private RangeResult getSafeRange(int start, int end, Set<Integer> allTimesInChain) {
        RangeResult res = new RangeResult();
        int finalStart = start;
        int finalEnd = end;

        // 1. 基础违禁数值检查
        if (forbiddenValues.contains(start) && !isConsecutiveForbidden(start)) {
            finalStart = start - 1; 
            if (!allTimesInChain.contains(finalStart)) {
                res.corrections.add(finalStart);
            }
        }

        if (forbiddenValues.contains(end) && !isConsecutiveForbidden(end)) {
            finalEnd = end + 1;
            if (!allTimesInChain.contains(finalEnd)) {
                res.corrections.add(finalEnd);
            }
        }

        // 2. 贴合真实语境的网易敏感词检测与规避
        if (detector != null) {
            if (start == end) {
                // 【单个数场景】：严格模拟实际生成的 "t=!s," 形式进行审查
                String testStr = SCOREBOARD_NAME + "=!" + start + ",";
                if (detector.matchText(testStr)) {
                    // 触发敏感时，整体向前偏移 1 位，确保 finalStart 依然等于 finalEnd（保持单数语义）
                    finalStart = start - 1;
                    finalEnd = end - 1;
                    if (!allTimesInChain.contains(finalStart)) {
                        res.corrections.add(finalStart);
                    }
                }
            } else {
                // 【两个数区间场景】：严格模拟实际生成的 "t=!s..e," 形式进行审查
                String testStr = SCOREBOARD_NAME + "=!" + start + ".." + end + ",";
                if (detector.matchText(testStr)) {
                    // 如果整个选择器区间串触发了敏感，则对前方数值进行偏移规避
                    finalStart = start - 1;
                    if (!allTimesInChain.contains(finalStart)) {
                        res.corrections.add(finalStart);
                    }
                } else {
                    // 如果整体安全，再分别确认在独立拆分或单端放入选择器时的边界安全性
                    boolean startSensitive = detector.matchText(SCOREBOARD_NAME + "=!" + start + ",");
                    boolean endSensitive = detector.matchText(SCOREBOARD_NAME + "=!" + end + ",");

                    if (startSensitive) {
                        finalStart = start - 1;
                        if (!allTimesInChain.contains(finalStart)) {
                            res.corrections.add(finalStart);
                        }
                    }
                    if (endSensitive) {
                        finalEnd = end + 1;
                        if (!allTimesInChain.contains(finalEnd)) {
                            res.corrections.add(finalEnd);
                        }
                    }
                }
            }
        }

        if (finalStart == finalEnd) {
            res.selectorPart = SCOREBOARD_NAME + "=!" + finalStart;
        } else {
            res.selectorPart = SCOREBOARD_NAME + "=!" + finalStart + ".." + finalEnd;
        }
        return res;
    }


    // 优化方案：引入预处理和更高效的集合处理
    private void writeCommandBlock(StringBuilder out, List<Integer> times, String inst, String pitch, String volume) {
        if (times == null || times.isEmpty()) return;

        // 1. 预排序后直接通过数组处理
        Collections.sort(times);
        
        int maxTime = times.get(times.size() - 1);
        BitSet timeBitSet = new BitSet(maxTime + 2);
        for (int t : times) timeBitSet.set(t);

        List<RangeResult> rangeResults = new ArrayList<>(times.size() / 2);
        
        int start = times.get(0);
        int prev = start;
        for (int i = 1; i < times.size(); i++) {
            int cur = times.get(i);
            if (cur != prev + 1) {
                rangeResults.add(getSafeRangeFast(start, prev, timeBitSet));
                start = cur;
            }
            prev = cur;
        }
        rangeResults.add(getSafeRangeFast(start, prev, timeBitSet));

        // 2. 预缓存固定字符串
        final String prefix = "/execute as @a[scores={" + SCOREBOARD_NAME + "=!" + (START_OFFSET - 1) + "}] at @s unless entity @s[scores={";
        final String runPart = " run playsound " + inst + " @s ~ ~ ~ " + volume + " " + pitch;
        final int baseLen = prefix.length() + 2 + runPart.length() + 10;

        List<String> currentSelectors = new ArrayList<>();
        Set<Integer> currentCorrections = new TreeSet<>();
        int currentLen = baseLen;

        for (RangeResult rr : rangeResults) {
            int partLen = rr.selectorPart.length() + 1;
            int correctionExtraLen = 0;
            
            for (Integer c : rr.corrections) {
                if (!currentCorrections.contains(c)) {
                    correctionExtraLen += (SCOREBOARD_NAME.length() + (c > 1000 ? 5 : 3)); 
                }
            }

            if (MAX_CHARS > 0 && (currentLen + partLen + correctionExtraLen > MAX_CHARS)) {
                flushCommand(out, prefix, currentSelectors, currentCorrections, runPart);
                currentSelectors.clear();
                currentCorrections.clear();
                currentLen = baseLen;
            }

            currentSelectors.add(rr.selectorPart);
            currentCorrections.addAll(rr.corrections);
            currentLen += (partLen + correctionExtraLen);
        }
        flushCommand(out, prefix, currentSelectors, currentCorrections, runPart);
    }

    /**
     * 【同步】配合 BitSet 的快速检查并应用敏感数优化
     */
    private RangeResult getSafeRangeFast(int start, int end, BitSet timeBitSet) {
        RangeResult res = new RangeResult();
        int finalStart = start;
        int finalEnd = end;

        // 1. 基础违禁数值检查
        if (forbiddenValues.contains(start) && !isConsecutiveForbidden(start)) {
            finalStart = start - 1;
            if (!timeBitSet.get(finalStart)) res.corrections.add(finalStart);
        }

        if (forbiddenValues.contains(end) && !isConsecutiveForbidden(end)) {
            finalEnd = end + 1;
            if (!timeBitSet.get(finalEnd)) res.corrections.add(finalEnd);
        }

        // 2. 贴合真实语境的网易敏感词检测与规避（高并发快速版）
        if (detector != null) {
            if (start == end) {
                // 【单个数场景】
                String testStr = SCOREBOARD_NAME + "=!" + start + ",";
                if (detector.matchText(testStr)) {
                    finalStart = start - 1;
                    finalEnd = end - 1;
                    if (!timeBitSet.get(finalStart)) {
                        res.corrections.add(finalStart);
                    }
                }
            } else {
                // 【两个数区间场景】
                String testStr = SCOREBOARD_NAME + "=!" + start + ".." + end + ",";
                if (detector.matchText(testStr)) {
                    finalStart = start - 1;
                    if (!timeBitSet.get(finalStart)) {
                        res.corrections.add(finalStart);
                    }
                } else {
                    boolean startSensitive = detector.matchText(SCOREBOARD_NAME + "=!" + start + ",");
                    boolean endSensitive = detector.matchText(SCOREBOARD_NAME + "=!" + end + ",");

                    if (startSensitive) {
                        finalStart = start - 1;
                        if (!timeBitSet.get(finalStart)) {
                            res.corrections.add(finalStart);
                        }
                    }
                    if (endSensitive) {
                        finalEnd = end + 1;
                        if (!timeBitSet.get(finalEnd)) {
                            res.corrections.add(finalEnd);
                        }
                    }
                }
            }
        }

        if (finalStart == finalEnd) {
            res.selectorPart = SCOREBOARD_NAME + "=!" + finalStart;
        } else {
            res.selectorPart = SCOREBOARD_NAME + "=!" + finalStart + ".." + finalEnd;
        }
        return res;
    }

    private void flushCommand(StringBuilder out, String prefix,
                              List<String> selectors,
                              Set<Integer> corrections,
                              String runPart) {
        if (selectors.isEmpty()) return;

        out.append(prefix);

        for (int i = 0; i < selectors.size(); i++) {
            out.append(selectors.get(i));
            if (i < selectors.size() - 1) {
                out.append(",");
            }
        }

        out.append("}]");

        if (!corrections.isEmpty()) {
            out.append(" if entity @s[scores={");

            int count = 0;
            for (Integer c : corrections) {
                out.append(SCOREBOARD_NAME)
                   .append("=!")
                   .append(c);

                if (++count < corrections.size()) {
                    out.append(",");
                }
            }

            out.append("}]");
        }

        out.append(runPart).append("\n");
    }

    // --- 内部数据类 ---

    private static class RangeResult {
        String selectorPart;
        List<Integer> corrections = new ArrayList<>();
    }

    private static class Note {
        int time; String inst; String pitch; String volume;
        Note(int t, String i, String p, String v) { this.time = t; this.inst = i; this.pitch = p; this.volume = v; }
    }

    public String convertToString() {
        List<Note> notes = parseInputString(this.inputString);
        if (MODE == 1) return generateMode1(notes);
        return generateMode2(notes);
    }
    
    public boolean convertToFile(File outputPath) {
        String result = convertToString();
        try (BufferedWriter bw = Files.newBufferedWriter(outputPath.toPath())) {
            bw.write(result);
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    private static final Pattern NOTE_PATTERN = Pattern.compile("^\\s*(\\d+)\\s+([A-Za-z0-9_.]+)\\s+([A-Za-z0-9_.]+)(?:\\s+([A-Za-z0-9_.]+))?\\s*$");

    private List<Note> parseInputString(String input) {
        if (input == null || input.isEmpty()) return new ArrayList<>();
        String[] lines = input.split("\\n");
        List<Note> notes = new ArrayList<>(lines.length); 
        for (String line : lines) {
            Matcher m = NOTE_PATTERN.matcher(line);
            if (m.matches()) {
                notes.add(new Note(
                    Integer.parseInt(m.group(1)), 
                    m.group(2), 
                    m.group(3), 
                    (m.group(4) != null ? m.group(4) : "1")
                ));
            }
        }
        notes.sort(Comparator.comparingInt(a -> a.time)); 
        return notes;
    }

    private String generateMode1(List<Note> notes) {
        StringBuilder sb = new StringBuilder();
        int prev = 0;
        for (int i = 0; i < notes.size(); i++) {
            Note n = notes.get(i);
            int d = Math.max(0, n.time - prev);
            String c = d + " /playsound note." + n.inst + " @a ~ ~ ~ " + n.volume + " " + n.pitch + "\n";
            if (i == 0) sb.append("$0,0,0\n").append(c).append("$\n");
            else if (i == 1) sb.append("$1,0,1\n").append(c);
            else sb.append(c);
            prev = n.time;
        }
        return sb.append("$\n").toString();
    }

    private String generateMode2(List<Note> notes) {
        StringBuilder out = new StringBuilder();
        if (notes.isEmpty()) return "";

        List<Note> processed = new ArrayList<>();
        for (Note n : notes) processed.add(new Note(n.time + START_OFFSET, n.inst, n.pitch, n.volume));
        int lastTime = processed.get(processed.size() - 1).time;

        Map<String, List<Integer>> organized = new LinkedHashMap<>();
        for (Note n : processed) {
            String key = n.inst + "||" + n.pitch + "||" + n.volume;
            if (!organized.containsKey(key)) organized.put(key, new ArrayList<Integer>());
            organized.get(key).add(n.time);
        }

        out.append("$0,0,0\n=== 初始化 ===\n/scoreboard objectives add ").append(SCOREBOARD_NAME).append(" dummy\n$\n$1,0,1\n");
        out.append("/scoreboard players set @a ").append(SCOREBOARD_NAME).append(" ").append(START_OFFSET - 1).append("\n$\n\n");
        out.append("$2,0,0\n=== 循环 ===\n/execute as @a[scores={").append(SCOREBOARD_NAME).append("=").append(START_OFFSET - 1).append("..}] run scoreboard players add @s ").append(SCOREBOARD_NAME).append(" 1\n$\n\n");

        boolean first = true;
        for (Map.Entry<String, List<Integer>> entry : organized.entrySet()) {
            String[] parts = entry.getKey().split("\\|\\|", -1);
            List<Integer> timeList = entry.getValue();
            Collections.sort(timeList);

            List<List<Integer>> layers = new ArrayList<>();
            for (Integer t : timeList) {
                boolean placed = false;
                for (List<Integer> layer : layers) {
                    if (!layer.get(layer.size() - 1).equals(t)) {
                        layer.add(t); placed = true; break;
                    }
                }
                if (!placed) { List<Integer> nl = new ArrayList<>(); nl.add(t); layers.add(nl); }
            }
            if (!ENABLE_STACKING && !layers.isEmpty()) {
                List<List<Integer>> tmp = new ArrayList<>(); tmp.add(layers.get(0)); layers = tmp;
            }

            if (first) { out.append("$1,0,1\n"); first = false; }
            for (List<Integer> layer : layers) {
                writeCommandBlock(out, layer, parts[0], parts[2], parts[1]);
            }
        }
        out.append("\n/execute as @a[scores={").append(SCOREBOARD_NAME).append("=").append(lastTime).append("}] run scoreboard players reset @s ").append(SCOREBOARD_NAME).append("\n$\n");
        return out.toString();
    }
}