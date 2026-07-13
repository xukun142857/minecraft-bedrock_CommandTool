package command.plus;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.file.Files;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.BufferedWriter;

/**
 * MinecraftMusicConverter - 优化版
 * 解决了连续违禁值逻辑漏洞及缓冲区数值重复问题
 */
public class MinecraftMusicConverter {

    private final int MODE;
    private final String SCOREBOARD_NAME;
    private final int START_OFFSET;
    private final boolean ENABLE_STACKING;
    private final int MAX_CHARS;
    private final String inputString;
    private final Set<Integer> forbiddenValues;

    private MinecraftMusicConverter(Builder b) {
        this.MODE = b.MODE;
        this.SCOREBOARD_NAME = b.SCOREBOARD_NAME;
        this.START_OFFSET = b.START_OFFSET;
        this.ENABLE_STACKING = b.ENABLE_STACKING;
        this.MAX_CHARS = b.MAX_CHARS;
        this.inputString = b.inputString;
        this.forbiddenValues = b.forbiddenValues;
    }

    public static class Builder {
        private int MODE = 0;
        private String SCOREBOARD_NAME = "t";
        private int START_OFFSET = 0;
        private boolean ENABLE_STACKING = true;
        private int MAX_CHARS = 3000;
        private String inputString = "";
        private Set<Integer> forbiddenValues = new HashSet<>();

        public Builder setMode(int mode) { this.MODE = mode; return this; }
        public Builder setScoreboardName(String name) { this.SCOREBOARD_NAME = name; return this; }
        public Builder setStartOffset(int offset) { this.START_OFFSET = offset; return this; }
        public Builder setEnableStacking(boolean v) { this.ENABLE_STACKING = v; return this; }
        public Builder setMaxChars(int chars) { this.MAX_CHARS = chars; return this; }
        public Builder setInputString(String s) { this.inputString = s; return this; }
        public Builder setForbiddenValues(Set<Integer> vals) { this.forbiddenValues = vals; return this; }

        public MinecraftMusicConverter build() { return new MinecraftMusicConverter(this); }
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
     * 核心逻辑：获取安全的范围描述
     */
    private RangeResult getSafeRange(int start, int end, Set<Integer> allTimesInChain) {
        RangeResult res = new RangeResult();
        int finalStart = start;
        int finalEnd = end;

        // 处理起始点：违禁且不连续
        if (forbiddenValues.contains(start) && !isConsecutiveForbidden(start)) {
            finalStart = start - 1; 
            if (!allTimesInChain.contains(finalStart)) {
                res.corrections.add(finalStart);
            }
        }

        // 处理结束点：违禁且不连续
        if (forbiddenValues.contains(end) && !isConsecutiveForbidden(end)) {
            finalEnd = end + 1;
            if (!allTimesInChain.contains(finalEnd)) {
                res.corrections.add(finalEnd);
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

    // 1. 预排序后直接通过数组处理，避免 Iterator 性能损耗
    Collections.sort(times);
    
    // 使用 BitSet 代替 HashSet<Integer> 记录 timeSet
    // 假设时间戳不会超过 100万 (5万秒)，BitSet 极快且省空间
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
                // 优化：查表或预估长度，避免 String.valueOf
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

// 配合 BitSet 的快速检查
private RangeResult getSafeRangeFast(int start, int end, BitSet timeBitSet) {
    RangeResult res = new RangeResult();
    int finalStart = start;
    int finalEnd = end;

    if (forbiddenValues.contains(start) && !isConsecutiveForbidden(start)) {
        finalStart = start - 1;
        if (!timeBitSet.get(finalStart)) res.corrections.add(finalStart);
    }

    if (forbiddenValues.contains(end) && !isConsecutiveForbidden(end)) {
        finalEnd = end + 1;
        if (!timeBitSet.get(finalEnd)) res.corrections.add(finalEnd);
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

    // --- 其余逻辑保持与原版结构一致 ---

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
    List<Note> notes = new ArrayList<>(lines.length); // 预设容量
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
    notes.sort(Comparator.comparingInt(a -> a.time)); // 使用 lambda 更简洁
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