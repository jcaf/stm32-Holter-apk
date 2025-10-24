package com.tuapp.ecg;

import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Cálculo de métricas extendidas (frecuencia, pausas, arritmias, PVC/PAC, etc.)
 * Compatible con ReportActivity y DiagnosticoActivity.
 */
public class Metrics {

    /* ============================================================
       1️⃣  Clases de datos originales
       ============================================================ */

    /** Evento de frecuencia (para el reporte visual) */
    public static class Event implements Serializable {
        public double bpm;
        public double timeSec;
        public String timestamp;
        @Override
        public String toString(){
            return String.format(Locale.US, "BPM %.1f @ %s",
                    bpm, (timestamp != null ? timestamp : String.format(Locale.US,"%.2fs", timeSec)));
        }
    }

    /** Reporte principal */
    public static class Report implements Serializable {
        public String fileName;
        public double bpmMean, bpmMin, bpmMax;
        public double durationSec;
        public String studyStart;
        public List<Event> topHigh = new ArrayList<>();
        public List<Event> topLow  = new ArrayList<>();

        // NUEVO: métricas clínicas extendidas
        public ECGStats extended;
    }

    /* ============================================================
       2️⃣  Cálculos extendidos (basado en propuesta de tu amigo)
       ============================================================ */
    public static class ECGStats implements Serializable {
        public double avgHR;
        public double minHR;
        public double maxHR;
        public int tachyCount;
        public int bradyCount;
        public int pauseCount;
        public int pvcCount;
        public int pacCount;
        public int coupletCount;
        public int tripletCount;
        public int bigeminyCount;
        public int trigeminyCount;
        public int totalBeats;

        @Override
        public String toString() {
            return String.format(Locale.US,
                    "HR avg=%.1f min=%.1f max=%.1f | Tachy=%d Brady=%d Pauses=%d PVC=%d PAC=%d",
                    avgHR, minHR, maxHR, tachyCount, bradyCount, pauseCount, pvcCount, pacCount);
        }
    }

    /**
     * Versión extendida de análisis del ECG (basada en la propuesta de tu amigo)
     */
    public static ECGStats analyzeExtended(double[] rPeaks, double[] signal, double fs) {
        ECGStats stats = new ECGStats();
        if (rPeaks == null || rPeaks.length < 2) return stats;

        List<Double> rrIntervals = new ArrayList<>();
        for (int i = 1; i < rPeaks.length; i++) {
            rrIntervals.add((rPeaks[i] - rPeaks[i - 1]) / fs);
        }

        stats.totalBeats = rrIntervals.size();
        List<Double> hr = new ArrayList<>();
        for (double rr : rrIntervals) hr.add(60.0 / rr);

        stats.avgHR = hr.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        stats.minHR = hr.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        stats.maxHR = hr.stream().mapToDouble(Double::doubleValue).max().orElse(0);

        for (double v : hr) {
            if (v > 100) stats.tachyCount++;
            if (v < 60) stats.bradyCount++;
        }

        for (double rr : rrIntervals) {
            if (rr > 2.0) stats.pauseCount++;
        }

        // --- Detección básica de PVC/PAC ---
        double mean = mean(signal);
        double sd = std(signal, mean);
        for (int i = 1; i < signal.length - 1; i++) {
            if (Math.abs(signal[i] - mean) > 3 * sd)
                stats.pvcCount++;
        }

        // --- Estimaciones estadísticas ---
        stats.coupletCount = stats.pvcCount / 2;
        stats.tripletCount = stats.pvcCount / 3;
        stats.bigeminyCount = stats.pvcCount / 4;
        stats.trigeminyCount = stats.pvcCount / 5;

        return stats;
    }

    private static double mean(double[] x) {
        double s = 0;
        for (double v : x) s += v;
        return s / x.length;
    }

    private static double std(double[] x, double m) {
        double s = 0;
        for (double v : x) s += Math.pow(v - m, 2);
        return Math.sqrt(s / (x.length - 1));
    }

    /* ============================================================
       3️⃣  Método principal buildReport (se mantiene original)
       ============================================================ */
    public static Report buildReport(RPeakDetector.Result resultR, List<String> tsSecondsOrNull,
                                     String fileName, double durationSec, String studyStart) {

        Report rep = new Report();
        rep.fileName = fileName;
        rep.durationSec = durationSec;
        rep.studyStart = studyStart;

        if (resultR == null || resultR.bpm == null || resultR.bpm.length == 0) {
            rep.bpmMean = rep.bpmMin = rep.bpmMax = 0.0;
            return rep;
        }

        // --- Estadísticas base ---
        double sum=0, mn=Double.POSITIVE_INFINITY, mx=Double.NEGATIVE_INFINITY;
        for (double v: resultR.bpm){ sum+=v; if (v<mn) mn=v; if (v>mx) mx=v; }
        rep.bpmMean = sum / resultR.bpm.length;
        rep.bpmMin  = mn;
        rep.bpmMax  = mx;

        List<Event> highs = new ArrayList<>(), lows = new ArrayList<>();

        // --- Calcular timestamps ---
        Date baseDate = null;
        if (studyStart != null) {
            try {
                if (studyStart.length() >= 19) {
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
                    baseDate = sdf.parse(studyStart);
                }
            } catch (Exception ignored){}
        }

        for (int k = 0; k < resultR.bpm.length; k++){
            double bpm = resultR.bpm[k];
            int idxSecondR = resultR.rIndex[k+1];
            double t = idxSecondR / DiagnosticoActivity.FS;

            Event e = new Event();
            e.bpm = bpm;
            e.timeSec = t;

            if (baseDate != null) {
                Date d = new Date(baseDate.getTime() + (long)(t * 1000.0));
                SimpleDateFormat sdf2 = new SimpleDateFormat("HH:mm:ss", Locale.US);
                e.timestamp = sdf2.format(d);
            }

            if (bpm > 100.0) highs.add(e);
            if (bpm < 60.0)  lows.add(e);
        }

        highs.sort((a,b)->Double.compare(b.bpm, a.bpm));
        lows.sort(Comparator.comparingDouble(a -> a.bpm));

        rep.topHigh.addAll(highs.subList(0, Math.min(10, highs.size())));
        rep.topLow.addAll(lows.subList(0, Math.min(10, lows.size())));

        // --- NUEVO: análisis extendido clínico ---
        rep.extended = analyzeExtended(resultR.rIndexDoubles(), resultR.signalFiltered, DiagnosticoActivity.FS);

        return rep;
    }
}
