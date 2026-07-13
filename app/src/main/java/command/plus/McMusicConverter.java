package command.plus;

import android.util.Log;

import com.leff.midi.MidiFile;
import com.leff.midi.MidiTrack;
import com.leff.midi.event.MidiEvent;
import com.leff.midi.event.NoteOn;
import com.leff.midi.event.ProgramChange;
import com.leff.midi.event.Controller;
import com.leff.midi.event.meta.Tempo;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Locale;
import java.util.HashSet;
import java.util.Arrays;

/**
 * 可配置的 McMusicConverter：实例化时通过 Builder 传入参数与文件路径，
 * 提供 convertAndRender(callback) 方法用于执行完整流程并回调进度/完成/错误。
 */
public class McMusicConverter {
    private static final String TAG = "McConverter";

    // ====== 可配置参数（每个实例独有） ======
    private double mcTick = 0.05;
    private int maxSimulNotes = 5;
    private int pitchPrecision = 4;
    private double globalPitchFactor = 0.5;
    private int volumeMode = 0; // "MATCH" or "FIXED"
    private int simplificationLevel = 0;
    private String ScoreboardName = "t";
    private int StartOffset = 0;
    private int MaxChars = 10000;
    private boolean EnableStacking = true;
    private java.util.Set<Integer> ForbiddenScores;
    private InputStream midiStream;
    
    // 新增：乐器映射列表（包含轨道、事件、名称、音高等）
    private List<InstrumentDef> instrumentMappings;

    // 输入/输出文件（可由外部设置）
    private File midiFile;
    private File sampleFolder;
    private File outputWav;
    private File outputTxt;

    // ====== 回调接口 ======
    public interface Callback {
        void onProgress(String message, int percent); // percent 0..100, -1 表示未知
        void onComplete(File wavFile, File txtFile, String summary);
        void onError(Exception e);
    }

    // ====== 数据结构 ======
    
    /**
     * 乐器映射定义
     * channels: 适用的轨道(MIDI Channel)列表，null或空表示匹配所有轨道
     * events: 适用的事件值列表 (对于旋律轨道是Program号，对于打击乐是Note号)，null或空表示匹配所有
     * name: 音效名称 (将用于读取 name.wav)
     * basePitch: 乐器的基准音高
     * isDrum: 是否为打击乐 (通常 Channel 9(第10轨) 为 true)
     */
    public static class InstrumentDef {
        public List<Integer> channels;
        public List<Integer> events;
        public String name;
        public int basePitch;
        public boolean isDrum;

        public InstrumentDef(List<Integer> channels, List<Integer> events, String name, int basePitch, boolean isDrum) {
            this.channels = channels;
            this.events = events;
            this.name = name;
            this.basePitch = basePitch;
            this.isDrum = isDrum;
        }
    }

    public static class McNote {
        public double time;
        public int tick;
        public int trackId; // 记录来源轨道ID
        public String inst;
        public int midiNote;
        public int velocity;
        public double volume;
        public double pitch;
        public int basePitch; // 基准音高
        public boolean isDrum;

        public McNote copy() {
            McNote n = new McNote();
            n.time = time; n.tick = tick; n.trackId = trackId; n.inst = inst;
            n.midiNote = midiNote; n.velocity = velocity;
            n.volume = volume; n.pitch = pitch; n.basePitch = basePitch;
            n.isDrum = isDrum;
            return n;
        }
    }

    // 用于MIDI解析时维持全局时间轴和轨道追踪
    private static class EventWrapper implements Comparable<EventWrapper> {
        public MidiEvent event;
        public int trackId;

        public EventWrapper(MidiEvent event, int trackId) {
            this.event = event;
            this.trackId = trackId;
        }

        @Override
        public int compareTo(EventWrapper other) {
            return Long.compare(this.event.getTick(), other.event.getTick());
        }
    }

    // ====== 构造器（私有）+ Builder ======
    private McMusicConverter() {
        initDefaultMappings();
    }

    private void initDefaultMappings() {
        instrumentMappings = new ArrayList<>();
        // 这里可以配置你的默认乐器映射。
        // 示例: 0-7 为钢琴 (Harp)，基准音高54
        instrumentMappings.add(new InstrumentDef(
                null, 
                Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7), 
                "harp", 
                54, 
                false
        ));
    }

    public static class Builder {
        private final McMusicConverter inst = new McMusicConverter();

        public Builder setMcTick(double v) { inst.mcTick = v; return this; }
        public Builder setMaxSimulNotes(int v) { inst.maxSimulNotes = v; return this; }
        public Builder setPitchPrecision(int v) { inst.pitchPrecision = v; return this; }
        public Builder setGlobalPitchFactor(double v) { inst.globalPitchFactor = v; return this; }
        public Builder setVolumeMode(int v) { inst.volumeMode = v; return this; }
        public Builder setSimplificationLevel(int v) { inst.simplificationLevel = v; return this; }
        public Builder setMidiFile(File f) { inst.midiFile = f; return this; }
        public Builder setSampleFolder(File f) { inst.sampleFolder = f; return this; }
        public Builder setOutputWav(File f) { inst.outputWav = f; return this; }
        public Builder setOutputTxt(File f) { inst.outputTxt = f; return this; }

        public Builder setInstrumentMappings(List<InstrumentDef> map) { inst.instrumentMappings = map; return this; }
        
        public Builder setScoreboardName(String v) { inst.ScoreboardName = v; return this; }
        public Builder setStartOffset(int v) { inst.StartOffset = v; return this; }
        public Builder setMaxChars(int v) { inst.MaxChars = v; return this; }
        public Builder setEnableStacking(boolean v) { inst.EnableStacking = v; return this; }
        public Builder setForBiddenScores(java.util.Set<Integer> val) { inst.ForbiddenScores = val; return this; }
        public Builder setMidiStream(InputStream is) {
    inst.midiStream = is;
    return this;
}

        public McMusicConverter build() { return inst; }
    }

    // =========== 主要外部方法 ===========
    public void convertAndRender(Callback callback) {
        if (callback == null) callback = new Callback() {
            @Override public void onProgress(String message, int percent) {}
            @Override public void onComplete(File wavFile, File txtFile, String summary) {}
            @Override public void onError(Exception e) { e.printStackTrace(); }
        };

        try {
            callback.onProgress("Parsing MIDI...", 5);
            //long start = System.currentTimeMillis();
            List<McNote> parsed;

if (midiStream != null) {
    parsed = parseMidi(midiStream);
} else if (midiFile != null) {
    parsed = parseMidi(new FileInputStream(midiFile));
} else {
    throw new IllegalArgumentException("No MIDI source set");
}
            //Log.d(TAG, "parseMidi took: " + (System.currentTimeMillis() - start) + "ms");
            
            callback.onProgress("Processing notes...", 25);
            //long start1 = System.currentTimeMillis();
            List<McNote> processed = process(parsed);
            //Log.d(TAG, "process took: " + (System.currentTimeMillis() - start1) + "ms");
            
            callback.onProgress("Simplifying notes...", 45);
            List<McNote> simplified;
            //long start2 = System.currentTimeMillis();
            simplified = simplify(processed);
            //Log.d(TAG, "simplify took: " + (System.currentTimeMillis() - start2) + "ms");
            

            callback.onProgress("Rendering audio...", 70);
            if (outputWav == null) throw new IllegalArgumentException("outputWav is not set");
            //long start3 = System.currentTimeMillis();
            renderAudio(simplified, sampleFolder, outputWav);
            //Log.d(TAG, "renderAudio took: " + (System.currentTimeMillis() - start3) + "ms");
            
            callback.onProgress("Saving text...", 95);
            String input = saveTextToString(simplified);
            if (outputTxt != null) saveCommandText(input, outputTxt, ScoreboardName, StartOffset, MaxChars, EnableStacking, ForbiddenScores);
            
            String summary = generateSummary(parsed, processed, simplified);
            
            callback.onProgress("Done", 100);
            callback.onComplete(outputWav, outputTxt, summary);
        } catch (Exception e) {
            callback.onError(e);
        }
    }

    // =========== 1) MIDI 解析 ===========
    public List<McNote> parseMidi(InputStream is) throws IOException {
    List<McNote> rawEvents = new ArrayList<>();

    MidiFile midi = new MidiFile(is);

    ArrayList<EventWrapper> allEvents = new ArrayList<>();

    List<MidiTrack> tracks = midi.getTracks();

    for (int i = 0; i < tracks.size(); i++) {
        for (MidiEvent event : tracks.get(i).getEvents()) {
            allEvents.add(new EventWrapper(event, i));
        }
    }

    Collections.sort(allEvents);

    int resolution = midi.getResolution();

    long lastTick = 0;
    double currentTimeSec = 0.0;

    double secondsPerTick =
            500000.0 / 1_000_000.0 / (double) resolution;

    int[] channelProgram = new int[16];

    for (int i = 0; i < 16; i++) {
        channelProgram[i] = 0;
    }

    for (EventWrapper wrapper : allEvents) {

        MidiEvent E = wrapper.event;

        long tick = E.getTick();

        long delta = tick - lastTick;

        if (delta != 0) {
            currentTimeSec += delta * secondsPerTick;
            lastTick = tick;
        }

        if (E instanceof Tempo) {

            Tempo t = (Tempo) E;

            secondsPerTick =
                    t.getMpqn() / 1_000_000.0 / (double) resolution;

            continue;
        }

        if (E instanceof ProgramChange) {

            ProgramChange pc = (ProgramChange) E;

            int ch = pc.getChannel() & 0x0F;

            channelProgram[ch] =
                    pc.getProgramNumber() & 0x7F;

            continue;
        }

        if (E instanceof NoteOn) {

            NoteOn noteOn = (NoteOn) E;

            if (noteOn.getVelocity() > 0) {

                int note = noteOn.getNoteValue();
                int velocity = noteOn.getVelocity();

                int channel = noteOn.getChannel() & 0x0F;

                int program = channelProgram[channel];

                List<InstrumentDef> matchedInstruments =
                        findInstruments(channel, program, note);

                for (InstrumentDef def : matchedInstruments) {

                    McNote mcNote = new McNote();

                    mcNote.time = currentTimeSec;
                    mcNote.trackId = wrapper.trackId;

                    mcNote.midiNote = note;
                    mcNote.velocity = velocity;

                    mcNote.inst = def.name;

                    mcNote.basePitch = def.basePitch;

                    mcNote.isDrum = def.isDrum;

                    rawEvents.add(mcNote);
                }
            }
        }
    }

    return rawEvents;
}

    private List<InstrumentDef> findInstruments(int channel, int program, int note) {
        List<InstrumentDef> matched = new ArrayList<>();
        if (instrumentMappings == null) return matched;
        
        for (InstrumentDef def : instrumentMappings) {
            // 匹配轨道
            if (def.channels != null && !def.channels.isEmpty() && !def.channels.contains(channel)) {
                continue;
            }
            
            // 匹配事件 (打击乐用note号匹配，旋律用program号匹配)
           boolean isDrumChannel = (channel == 9);
            
            int eventVal = isDrumChannel ? note : program;
            if (def.events != null && !def.events.isEmpty() && !def.events.contains(eventVal)) {
                continue;
            }
            
            matched.add(def);
        }
        return matched;
    }

    // =========== 2) Processor ===========
    public List<McNote> process(List<McNote> events) {
        List<McNote> processed = new ArrayList<>();
        for (McNote e : events) {
            McNote finalE = e.copy();
            finalE.tick = (int) Math.round(e.time / mcTick);
            if (volumeMode == 0) {
                finalE.volume = Math.round((e.velocity / 127.0) * 1000.0) / 1000.0;
            } else {
                finalE.volume = 1.0;
            }
            
            if (e.isDrum) {
                finalE.pitch = 1.0 * globalPitchFactor;
            } else {
                double rawPitch = Math.pow(2, (e.midiNote - e.basePitch) / 12.0);
                double p = rawPitch * globalPitchFactor;
                double scale = Math.pow(10, pitchPrecision);
                finalE.pitch = Math.round(p * scale) / scale;
            }
            processed.add(finalE);
        }
        return processed;
    }

    public List<McNote> simplify(List<McNote> processed) {
        if (simplificationLevel == 0) return hardLimit(processed);

        Map<Integer, List<McNote>> tickMap = new TreeMap<>();
        for (McNote n : processed) {
            if (!tickMap.containsKey(n.tick)) tickMap.put(n.tick, new ArrayList<>());
            tickMap.get(n.tick).add(n);
        }

        double volThreshold = simplificationLevel * 0.05;
        int maxCount = Math.max(1, maxSimulNotes - (simplificationLevel * 3));
        List<McNote> finalEvents = new ArrayList<>();

        for (List<McNote> notes : tickMap.values()) {
            Collections.sort(notes, (o1, o2) -> Double.compare(o2.volume, o1.volume));
            List<McNote> kept = new ArrayList<>();
            for (int i = 0; i < notes.size(); i++) {
                if (i == 0) { kept.add(notes.get(i)); continue; }
                if (notes.get(i).volume >= volThreshold && kept.size() < maxCount) {
                    kept.add(notes.get(i));
                }
            }
            finalEvents.addAll(kept);
        }
        return finalEvents;
    }

    private List<McNote> hardLimit(List<McNote> notes) {
        Map<Integer, List<McNote>> tickMap = new TreeMap<>();
        for (McNote n : notes) {
            if (!tickMap.containsKey(n.tick)) tickMap.put(n.tick, new ArrayList<>());
            tickMap.get(n.tick).add(n);
        }
        List<McNote> finalEvents = new ArrayList<>();
        for (List<McNote> group : tickMap.values()) {
            Collections.sort(group, (o1, o2) -> Double.compare(o2.volume, o1.volume));
            if (group.size() > maxSimulNotes) {
                finalEvents.addAll(group.subList(0, maxSimulNotes));
            } else {
                finalEvents.addAll(group);
            }
        }
        Collections.sort(finalEvents, (o1, o2) -> Integer.compare(o1.tick, o2.tick));
        return finalEvents;
    }

    // =========== 3) Audio rendering ===========
    private static class WavSample {
        float[] monoData;
        int sampleRate;
        int channels;
        int bitsPerSample;
        public WavSample(float[] d, int sr, int ch, int bps) { monoData = d; sampleRate = sr; channels = ch; bitsPerSample = bps; }
    }

    private WavSample loadWavSampleDetailed(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] header12 = new byte[12];
            if (fis.read(header12) != 12) return new WavSample(new float[0], 44100, 1, 16);

            int channels = 1;
            int sampleRate = 44100;
            int bitsPerSample = 16;
            int dataSize = 0;

            byte[] chunkHeader = new byte[8];
            while (fis.read(chunkHeader) == 8) {
                String chunkId = new String(chunkHeader, 0, 4, "US-ASCII");
                int chunkSize = ((chunkHeader[4] & 0xff)) |
                                ((chunkHeader[5] & 0xff) << 8) |
                                ((chunkHeader[6] & 0xff) << 16) |
                                ((chunkHeader[7] & 0xff) << 24);
                if ("fmt ".equals(chunkId)) {
                    byte[] fmt = new byte[chunkSize];
                    fis.read(fmt);
                    int audioFormat = (fmt[0] & 0xff) | ((fmt[1] & 0xff) << 8);
                    channels = fmt[2] & 0xff;
                    sampleRate = (fmt[4] & 0xff) | ((fmt[5] & 0xff) << 8) | ((fmt[6] & 0xff) << 16) | ((fmt[7] & 0xff) << 24);
                    bitsPerSample = (fmt[14] & 0xff) | ((fmt[15] & 0xff) << 8);
                } else if ("data".equals(chunkId)) {
                    dataSize = chunkSize;
                    byte[] data = new byte[dataSize];
                    int read = 0;
                    while (read < dataSize) {
                        int r = fis.read(data, read, dataSize - read);
                        if (r < 0) break;
                        read += r;
                    }
                    int bytesPerSample = bitsPerSample / 8;
                    int frameCount = dataSize / (bytesPerSample * channels);
                    float[] mono = new float[frameCount];
                    ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
                    if (bitsPerSample == 16) {
                        for (int i = 0; i < frameCount; i++) {
                            int acc = 0;
                            for (int ch = 0; ch < channels; ch++) {
                                short s = bb.getShort();
                                acc += s;
                            }
                            float avg = (float) acc / (channels * 32768.0f);
                            mono[i] = Math.max(-1.0f, Math.min(1.0f, avg));
                        }
                    } else if (bitsPerSample == 8) {
                        for (int i = 0; i < frameCount; i++) {
                            int acc = 0;
                            for (int ch = 0; ch < channels; ch++) {
                                int u = bb.get() & 0xff;
                                int s = u - 128;
                                acc += s;
                            }
                            float avg = (float) acc / (channels * 128.0f);
                            mono[i] = Math.max(-1.0f, Math.min(1.0f, avg));
                        }
                    } else {
                        return new WavSample(new float[0], sampleRate, channels, bitsPerSample);
                    }
                    return new WavSample(mono, sampleRate, channels, bitsPerSample);
                } else {
                    long skipped = 0;
                    while (skipped < chunkSize) {
                        long s = fis.skip(chunkSize - skipped);
                        if (s <= 0) break;
                        skipped += s;
                    }
                }
            }
            return new WavSample(new float[0], sampleRate, channels, bitsPerSample);
        } catch (Exception e) {
            Log.e(TAG, "Failed to load sample: " + file.getName(), e);
            return new WavSample(new float[0], 44100, 1, 16);
        }
    }

    private static float[] resampleLinear(float[] src, int srcRate, int targetRate) {
        if (srcRate == targetRate) return src;
        double ratio = (double) srcRate / (double) targetRate;
        int targetLen = (int) Math.round(src.length / ratio);
        if (targetLen <= 0) return new float[0];
        float[] out = new float[targetLen];
        for (int i = 0; i < targetLen; i++) {
            double srcPos = i * ratio;
            int i0 = (int) Math.floor(srcPos);
            int i1 = Math.min(i0 + 1, src.length - 1);
            double frac = srcPos - i0;
            float v0 = src[i0];
            float v1 = src[i1];
            out[i] = (float) (v0 * (1.0 - frac) + v1 * frac);
        }
        return out;
    }
    
    // ====== 新增成员变量 ======
private volatile Map<String, float[]> cachedSamples = null;
private volatile String cachedSampleFolderPath = null;
private final Object sampleCacheLock = new Object();

// ====== 新增辅助结构 ======
private static final class RenderJob {
    final float[] sample;
    final int startIdx;
    final int targetLen;
    final int sampleLen;
    final float pitch;
    final float gain;

    RenderJob(float[] sample, int startIdx, int targetLen, float pitch, float gain) {
        this.sample = sample;
        this.startIdx = startIdx;
        this.targetLen = targetLen;
        this.sampleLen = sample.length;
        this.pitch = pitch;
        this.gain = gain;
    }
}

public void clearSampleCache() {
    synchronized (sampleCacheLock) {
        cachedSamples = null;
        cachedSampleFolderPath = null;
    }
}

private Map<String, float[]> getOrBuildSampleCache(File sampleFolder, int targetRate) {
    String folderPath = (sampleFolder == null) ? "" : sampleFolder.getAbsolutePath();

    Map<String, float[]> localCache = cachedSamples;
    if (localCache != null && folderPath.equals(cachedSampleFolderPath)) {
        return localCache;
    }

    synchronized (sampleCacheLock) {
        localCache = cachedSamples;
        if (localCache != null && folderPath.equals(cachedSampleFolderPath)) {
            return localCache;
        }

        Map<String, float[]> tmp = new HashMap<>();

        if (sampleFolder != null && sampleFolder.exists() && sampleFolder.isDirectory()) {
            File[] files = sampleFolder.listFiles();
            if (files != null) {
                for (File f : files) {
                    String name = f.getName();
                    String lower = name.toLowerCase(Locale.US);
                    if (!lower.endsWith(".wav")) continue;

                    String key = name.substring(0, name.length() - 4);

                    WavSample ws = loadWavSampleDetailed(f);
                    if (ws == null || ws.monoData == null || ws.monoData.length == 0) continue;

                    float[] sample = ws.monoData;
                    if (ws.sampleRate != targetRate) {
                        sample = resampleLinear(sample, ws.sampleRate, targetRate);
                    }

                    if (sample != null && sample.length > 0) {
                        tmp.put(key, sample);
                    }
                }
            }
        }

        cachedSamples = tmp;
        cachedSampleFolderPath = folderPath;
        return tmp;
    }
}

    public void renderAudio(List<McNote> events, File sampleFolder, File outputWav) {
    final int targetRate = 44100;

    if (outputWav == null) {
        throw new IllegalArgumentException("outputWav is null");
    }
    if (events == null) {
        events = Collections.emptyList();
    }

    // 1) 样本缓存：只在 sampleFolder 变化时重建
    final Map<String, float[]> samples = getOrBuildSampleCache(sampleFolder, targetRate);
    if (samples == null || samples.isEmpty()) {
        Log.w(TAG, "No samples loaded; abort render.");
        return;
    }

    // 2) 预处理事件为渲染任务，避免热循环里做字符串查表和重复计算
    double maxTime = 0.0;
    for (int i = 0; i < events.size(); i++) {
        McNote e = events.get(i);
        if (e.time > maxTime) maxTime = e.time;
    }

    final int totalSamples = Math.max(1, (int) ((maxTime + 4.0) * targetRate));
    final float[] mixBuffer = new float[totalSamples];

    final int attackSamples = Math.max(1, (int) (targetRate * 6 / 1000.0));
    final int releaseSamples = Math.max(1, (int) (targetRate * 12 / 1000.0));

    final ArrayList<RenderJob> jobs = new ArrayList<>(events.size());
    for (int i = 0; i < events.size(); i++) {
        McNote e = events.get(i);

        float[] sample = samples.get(e.inst);
        if (sample == null || sample.length < 2) continue;

        double pitchD = e.pitch;
        if (pitchD < 0.01) pitchD = 0.01;
        if (pitchD > 16.0) pitchD = 16.0;

        int startIdx = (int) (e.time * targetRate);
        if (startIdx >= totalSamples) continue;

        int targetLen = (int) (sample.length / pitchD);
        if (targetLen <= 0) continue;

        jobs.add(new RenderJob(sample, startIdx, targetLen, (float) pitchD, (float) e.volume));
    }

    if (jobs.isEmpty()) {
        writeWavMono(outputWav, mixBuffer, targetRate);
        return;
    }

    // 3) 分块并行：每个 chunk 只写自己负责的输出区间，避免锁竞争
    final int cpuCount = Math.max(1, Runtime.getRuntime().availableProcessors());
    final int chunkCount = Math.min(cpuCount, jobs.size());
    final int chunkSize = (totalSamples + chunkCount - 1) / chunkCount;

    final ArrayList<ArrayList<RenderJob>> chunkJobs = new ArrayList<>(chunkCount);
    for (int i = 0; i < chunkCount; i++) {
        chunkJobs.add(new ArrayList<RenderJob>());
    }

    for (int i = 0; i < jobs.size(); i++) {
        RenderJob job = jobs.get(i);

        int jobStart = job.startIdx;
        int jobEnd = job.startIdx + job.targetLen; // exclusive

        int firstChunk = Math.max(0, jobStart / chunkSize);
        int lastChunk = Math.min(chunkCount - 1, (jobEnd - 1) / chunkSize);

        for (int c = firstChunk; c <= lastChunk; c++) {
            chunkJobs.get(c).add(job);
        }
    }

    java.util.concurrent.ExecutorService pool =
            java.util.concurrent.Executors.newFixedThreadPool(chunkCount);

    java.util.concurrent.CountDownLatch latch =
            new java.util.concurrent.CountDownLatch(chunkCount);

    for (int chunkId = 0; chunkId < chunkCount; chunkId++) {
        final int cId = chunkId;
        pool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    int chunkStart = cId * chunkSize;
                    int chunkEnd = Math.min(totalSamples, chunkStart + chunkSize);
                    int localLen = Math.max(0, chunkEnd - chunkStart);
                    if (localLen <= 0) return;

                    float[] localBuffer = new float[localLen];
                    ArrayList<RenderJob> list = chunkJobs.get(cId);

                    for (int j = 0; j < list.size(); j++) {
                        RenderJob job = list.get(j);

                        int jobStart = job.startIdx;
                        int jobEnd = job.startIdx + job.targetLen;

                        int segStart = Math.max(jobStart, chunkStart);
                        int segEnd = Math.min(jobEnd, chunkEnd);
                        if (segStart >= segEnd) continue;

                        int localStart = segStart - chunkStart;
                        int localEnd = segEnd - chunkStart;

                        int relStart = segStart - jobStart;
                        int relEnd = segEnd - jobStart;

                        float[] src = job.sample;
                        int srcLen = job.sampleLen;
                        float pitch = job.pitch;
                        float gain = job.gain;

                        int attackEnd = Math.min(job.targetLen, attackSamples);
                        int releaseStart = Math.max(0, job.targetLen - releaseSamples);

                        // -------- attack 段 --------
                        int a0 = Math.max(relStart, 0);
                        int a1 = Math.min(relEnd, attackEnd);
                        if (a0 < a1) {
                            double srcPos = a0 * pitch;
                            for (int pos = a0; pos < a1; pos++) {
                                int idx0 = (int) srcPos;
                                if (idx0 >= srcLen - 1) break;
                                int idx1 = idx0 + 1;
                                float frac = (float) (srcPos - idx0);
                                float val = src[idx0] + (src[idx1] - src[idx0]) * frac;
                                float env = (pos + 1) / (float) attackEnd;
                                localBuffer[(segStart - chunkStart) + (pos - a0)] += val * gain * env;
                                srcPos += pitch;
                            }
                        }

                        // -------- sustain 段 --------
                        int s0 = Math.max(relStart, attackEnd);
                        int s1 = Math.min(relEnd, releaseStart);
                        if (s0 < s1) {
                            double srcPos = s0 * pitch;
                            for (int pos = s0; pos < s1; pos++) {
                                int idx0 = (int) srcPos;
                                if (idx0 >= srcLen - 1) break;
                                int idx1 = idx0 + 1;
                                float frac = (float) (srcPos - idx0);
                                float val = src[idx0] + (src[idx1] - src[idx0]) * frac;
                                localBuffer[(segStart - chunkStart) + (pos - relStart)] += val * gain;
                                srcPos += pitch;
                            }
                        }

                        // -------- release 段 --------
                        int r0 = Math.max(relStart, releaseStart);
                        int r1 = relEnd;
                        if (r0 < r1) {
                            double srcPos = r0 * pitch;
                            for (int pos = r0; pos < r1; pos++) {
                                int idx0 = (int) srcPos;
                                if (idx0 >= srcLen - 1) break;
                                int idx1 = idx0 + 1;
                                float frac = (float) (srcPos - idx0);
                                float val = src[idx0] + (src[idx1] - src[idx0]) * frac;

                                int relPos = pos - releaseStart;
                                float env = 1.0f - (relPos / (float) Math.max(1, releaseSamples));
                                if (env < 0f) env = 0f;

                                localBuffer[(segStart - chunkStart) + (pos - r0)] += val * gain * env;
                                srcPos += pitch;
                            }
                        }
                    }

                    System.arraycopy(localBuffer, 0, mixBuffer, chunkStart, localLen);
                } finally {
                    latch.countDown();
                }
            }
        });
    }

    try {
        latch.await();
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        Log.e(TAG, "Render interrupted", e);
        pool.shutdownNow();
        return;
    } finally {
        pool.shutdown();
    }

    // 4) 峰值归一化
    float peak = 0f;
    for (int i = 0; i < mixBuffer.length; i++) {
        float v = mixBuffer[i];
        if (v < 0f) v = -v;
        if (v > peak) peak = v;
    }

    if (peak > 0.99f) {
        float scale = 0.99f / peak;
        for (int i = 0; i < mixBuffer.length; i++) {
            mixBuffer[i] *= scale;
        }
    }

    // 5) 写出 WAV
    writeWavMono(outputWav, mixBuffer, targetRate);
    Log.d(TAG, "Render finished.");
}

    private void writeWavMono(File file, float[] data, int sampleRate) {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            int channels = 1;
            int bitsPerSample = 16;
            int byteRate = sampleRate * channels * bitsPerSample / 8;
            int blockAlign = channels * bitsPerSample / 8;
            int subchunk2Size = data.length * channels * bitsPerSample / 8;
            int chunkSize = 36 + subchunk2Size;

            byte[] header = new byte[44];
            header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
            header[4] = (byte) (chunkSize & 0xff);
            header[5] = (byte) ((chunkSize >> 8) & 0xff);
            header[6] = (byte) ((chunkSize >> 16) & 0xff);
            header[7] = (byte) ((chunkSize >> 24) & 0xff);
            header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
            header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
            header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0;
            header[20] = 1; header[21] = 0;
            header[22] = (byte) channels; header[23] = 0;
            header[24] = (byte) (sampleRate & 0xff);
            header[25] = (byte) ((sampleRate >> 8) & 0xff);
            header[26] = (byte) ((sampleRate >> 16) & 0xff);
            header[27] = (byte) ((sampleRate >> 24) & 0xff);
            header[28] = (byte) (byteRate & 0xff);
            header[29] = (byte) ((byteRate >> 8) & 0xff);
            header[30] = (byte) ((byteRate >> 16) & 0xff);
            header[31] = (byte) ((byteRate >> 24) & 0xff);
            header[32] = (byte) (blockAlign & 0xff);
            header[33] = 0;
            header[34] = (byte) bitsPerSample;
            header[35] = 0;
            header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
            header[40] = (byte) (subchunk2Size & 0xff);
            header[41] = (byte) ((subchunk2Size >> 8) & 0xff);
            header[42] = (byte) ((subchunk2Size >> 16) & 0xff);
            header[43] = (byte) ((subchunk2Size >> 24) & 0xff);

            fos.write(header, 0, 44);

            byte[] pcm = new byte[data.length * 2];
            int idx = 0;
            for (float f : data) {
                float clamped = Math.max(-1.0f, Math.min(1.0f, f));
                short s = (short) (clamped * 32767);
                pcm[idx++] = (byte) (s & 0xff);
                pcm[idx++] = (byte) ((s >> 8) & 0xff);
            }
            fos.write(pcm);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // =========== 4) 文本导出 ===========
    public String saveTextToString(List<McNote> events) {
        StringBuilder sb = new StringBuilder();
        sb.append("\"\"\"\n")
          .append("Pitch Factor: ").append(globalPitchFactor)
          .append(" | Vol Mode: ").append(volumeMode)
          .append("\n\"\"\"\n");
        
        for (McNote e : events) {
            String line = String.format(Locale.US, "%d %s %.3f %.4f%n", 
                e.tick, e.inst, e.volume, e.pitch);
            sb.append(line);
        }
        
        return sb.toString();
    }
    
    public void saveCommandText(String input, File outFile, String SN, int SO, int MC, boolean ES, java.util.Set<Integer> FB)  {
        MinecraftMusicConverter conv = new MinecraftMusicConverter.Builder()
                .setMode(0)
                .setScoreboardName(SN)
                .setStartOffset(SO)
                .setEnableStacking(ES)
                .setMaxChars(MC)
                .setInputString(input)
                .setForbiddenValues(FB)
                .build();

        conv.convertToFile(outFile);
    }
    
    /**
 * 生成详细的统计信息摘要
 */
private String generateSummary(List<McNote> originalNotes, List<McNote> processedNotes, List<McNote> simplifiedNotes) {
    StringBuilder sb = new StringBuilder();
    sb.append("========== MIDI转换统计报告 ==========\n\n");
    
    // 1. 总体统计
    sb.append("【总体统计】\n");
    sb.append("原始音符数: ").append(originalNotes.size()).append("\n");
    sb.append("处理后音符数: ").append(processedNotes.size()).append("\n");
    sb.append("简化后音符数: ").append(simplifiedNotes.size()).append("\n");
    sb.append("简化率: ").append(String.format(Locale.US, "%.1f%%", 
        (1 - (double) simplifiedNotes.size() / originalNotes.size()) * 100)).append("\n");
    
    // 计算总时长
    if (!simplifiedNotes.isEmpty()) {
        double maxTime = 0;
        for (McNote n : simplifiedNotes) {
            if (n.time > maxTime) maxTime = n.time;
        }
        sb.append("总时长: ").append(String.format(Locale.US, "%.2f", maxTime)).append(" 秒\n");
        sb.append("总Tick数: ").append(simplifiedNotes.get(simplifiedNotes.size() - 1).tick).append("\n");
    }
    sb.append("\n");
    
    // 2. 乐器使用统计
    sb.append("【乐器使用统计】\n");
    Map<String, Integer> instrumentCount = new HashMap<>();
    Map<String, Integer> instrumentTickCount = new HashMap<>();
    Map<Integer, Integer> trackCount = new HashMap<>();
    Map<Integer, Integer> trackTickCount = new HashMap<>();
    
    for (McNote n : simplifiedNotes) {
        // 乐器统计
        instrumentCount.put(n.inst, instrumentCount.getOrDefault(n.inst, 0) + 1);
        instrumentTickCount.put(n.inst, instrumentTickCount.getOrDefault(n.inst, 0) + 1);
        
        // 轨道统计
        trackCount.put(n.trackId, trackCount.getOrDefault(n.trackId, 0) + 1);
        trackTickCount.put(n.trackId, trackTickCount.getOrDefault(n.trackId, 0) + 1);
    }
    
    // 按使用频率排序乐器
    List<Map.Entry<String, Integer>> sortedInstruments = new ArrayList<>(instrumentCount.entrySet());
    sortedInstruments.sort((a, b) -> b.getValue().compareTo(a.getValue()));
    
    sb.append("乐器使用排名 (按音符数量):\n");
    int rank = 1;
    for (Map.Entry<String, Integer> entry : sortedInstruments) {
        double percentage = (double) entry.getValue() / simplifiedNotes.size() * 100;
        sb.append(String.format("  %d. %s: %d 个音符 (%.1f%%)\n", 
            rank++, entry.getKey(), entry.getValue(), percentage));
    }
    sb.append("\n");
    
    // 3. 轨道统计
    sb.append("【MIDI轨道统计】\n");
    List<Map.Entry<Integer, Integer>> sortedTracks = new ArrayList<>(trackCount.entrySet());
    sortedTracks.sort((a, b) -> b.getValue().compareTo(a.getValue()));
    
    for (Map.Entry<Integer, Integer> entry : sortedTracks) {
        double percentage = (double) entry.getValue() / simplifiedNotes.size() * 100;
        sb.append(String.format("  轨道 %d: %d 个音符 (%.1f%%)\n", 
            entry.getKey(), entry.getValue(), percentage));
    }
    sb.append("\n");
    
    // 4. 音量分布统计
    sb.append("【音量分布统计】\n");
    double[] volumeRanges = {0, 0.2, 0.4, 0.6, 0.8, 1.0};
    int[] volumeCounts = new int[volumeRanges.length - 1];
    
    for (McNote n : simplifiedNotes) {
        for (int i = 0; i < volumeRanges.length - 1; i++) {
            if (n.volume >= volumeRanges[i] && n.volume < volumeRanges[i + 1]) {
                volumeCounts[i]++;
                break;
            }
        }
    }
    
    for (int i = 0; i < volumeCounts.length; i++) {
        if (i == volumeCounts.length - 1) {
            sb.append(String.format("  %.0f%%-%.0f%%: %d 个音符\n", 
                volumeRanges[i] * 100, volumeRanges[i + 1] * 100, volumeCounts[i]));
        } else {
            sb.append(String.format("  %.0f%%-%.0f%%: %d 个音符\n", 
                volumeRanges[i] * 100, volumeRanges[i + 1] * 100, volumeCounts[i]));
        }
    }
    sb.append("\n");
    
    // 5. 音高分布统计
    sb.append("【音高分布统计】\n");
    Map<Integer, Integer> pitchOctaveCount = new HashMap<>();
    for (McNote n : simplifiedNotes) {
        if (n.isDrum) continue; // 跳过打击乐
        int octave = n.midiNote / 12;
        pitchOctaveCount.put(octave, pitchOctaveCount.getOrDefault(octave, 0) + 1);
    }
    
    if (!pitchOctaveCount.isEmpty()) {
        List<Map.Entry<Integer, Integer>> sortedOctaves = new ArrayList<>(pitchOctaveCount.entrySet());
        sortedOctaves.sort((a, b) -> a.getKey().compareTo(b.getKey()));
        
        for (Map.Entry<Integer, Integer> entry : sortedOctaves) {
            sb.append(String.format("  第 %d 八度: %d 个音符\n", 
                entry.getKey() - 1, entry.getValue()));
        }
    }
    sb.append("\n");
    
    // 6. 打击乐统计
    int drumCount = 0;
    int melodyCount = 0;
    for (McNote n : simplifiedNotes) {
        if (n.isDrum) drumCount++;
        else melodyCount++;
    }
    sb.append("【类型统计】\n");
    sb.append("旋律音符: ").append(melodyCount).append("\n");
    sb.append("打击乐音符: ").append(drumCount).append("\n");
    sb.append("\n");
    
    // 7. 配置参数
    sb.append("【转换参数】\n");
    sb.append("mcTick: ").append(mcTick).append("\n");
    sb.append("maxSimulNotes: ").append(maxSimulNotes).append("\n");
    sb.append("pitchPrecision: ").append(pitchPrecision).append("\n");
    sb.append("globalPitchFactor: ").append(globalPitchFactor).append("\n");
    sb.append("volumeMode: ").append(volumeMode == 0 ? "MATCH" : "FIXED").append("\n");
    sb.append("simplificationLevel: ").append(simplificationLevel).append("\n");
    sb.append("enableStacking: ").append(EnableStacking).append("\n");
    sb.append("maxChars: ").append(MaxChars).append("\n");
    sb.append("\n");
    
    sb.append("========== 报告结束 ==========");
    
    return sb.toString();
}

    // ======== getter / setter ========
    public void setMidiFile(File midiFile) { this.midiFile = midiFile; }
    public void setSampleFolder(File sampleFolder) { this.sampleFolder = sampleFolder; }
    public void setOutputWav(File outputWav) { this.outputWav = outputWav; }
    public void setOutputTxt(File outputTxt) { this.outputTxt = outputTxt; }

    public void setGlobalPitchFactor(double p) { this.globalPitchFactor = p; }
    public void setMcTick(double t) { this.mcTick = t; }
    public void setVolumeMode(int m) { this.volumeMode = m; }
    public void setMaxSimulNotes(int n) { this.maxSimulNotes = n; }
    public void setSimplificationLevel(int l) { this.simplificationLevel = l; }
    public void setForBiddenScores(java.util.Set<Integer> val) { this.ForbiddenScores = val; }
    
    public void setInstrumentMappings(List<InstrumentDef> map) { this.instrumentMappings = map; }
    public void setMidiStream(InputStream is) { this.midiStream = is; }
}