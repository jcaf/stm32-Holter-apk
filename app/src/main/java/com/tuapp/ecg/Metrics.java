package com.tuapp.ecg;

import android.util.Log;
import java.util.ArrayList;
import java.util.List;

public class Metrics {

    // === Clase de estadísticas extendidas ===
    public static class ECGStats {
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
    }

    // === Estructuras ===
    public static class Event {
        public double bpm;
        public double timeSec;
        public String timestamp;
    }

    public static class Report {
        public String fileName;
        public double bpmMean;
        public double bpmMin;
        public double bpmMax;
        public double durationSec;
        public String studyStart;
        public List<Event> topHigh = new ArrayList<>();
        public List<Event> topLow = new ArrayList<>();
        public ECGStats extended;
    }

    // === Cálculo del reporte principal ===
    public static Report buildReport(RPeakDetector.Result resultR,
                                     double[] signal,
                                     String fileName,
                                     double durationSec,
                                     String studyStart) {

        Report rep = new Report();
        rep.fileName = fileName;
        rep.durationSec = durationSec;
        rep.studyStart = studyStart;

        if (resultR == null || resultR.bpm == null || resultR.bpm.length == 0) {
            Log.w("ECG_DEBUG", "buildReport(): resultado RPeak vacío o nulo");
            return rep;
        }

        double sum = 0;
        rep.bpmMax = Double.NEGATIVE_INFINITY;
        rep.bpmMin = Double.POSITIVE_INFINITY;

        for (int i = 0; i < resultR.bpm.length; i++) {
            double bpm = resultR.bpm[i];
            sum += bpm;
            if (bpm > rep.bpmMax) rep.bpmMax = bpm;
            if (bpm < rep.bpmMin) rep.bpmMin = bpm;

            Event ev = new Event();
            ev.bpm = bpm;

            // ✅ Calculamos tiempo en segundos a partir del índice
            if (resultR.rIndex != null && i < resultR.rIndex.length) {
                ev.timeSec = resultR.rIndex[i] / DiagnosticoActivity.FS;
            } else {
                ev.timeSec = i / DiagnosticoActivity.FS;
            }

            if (bpm > 100) rep.topHigh.add(ev);
            if (bpm < 60) rep.topLow.add(ev);
        }

        rep.bpmMean = sum / resultR.bpm.length;

        // ✅ Generar estadísticas extendidas si hay señal
        try {
            double[] signalUsed = (signal != null) ? signal : resultR.signalFiltered;
            if (signalUsed != null && signalUsed.length > 0) {
                rep.extended = analyzeExtended(toDoubleArray(resultR.rIndex), signalUsed, DiagnosticoActivity.FS);
            } else {
                Log.w("ECG_DEBUG", "buildReport(): señal nula o vacía, no se genera análisis extendido");
            }
        } catch (Exception e) {
            Log.e("ECG_DEBUG", "Error generando métricas extendidas", e);
        }

        return rep;
    }

    // === Análisis extendido de parámetros clínicos ===
    public static ECGStats analyzeExtended(double[] rPeaks, double[] signal, double fs) {
        ECGStats stats = new ECGStats();

        if (rPeaks == null || rPeaks.length < 2) {
            Log.w("ECG_DEBUG", "analyzeExtended(): rPeaks nulo o insuficiente, devolviendo vacíos");
            return stats;
        }
        if (signal == null || signal.length == 0) {
            Log.w("ECG_DEBUG", "analyzeExtended(): señal nula o vacía, devolviendo vacíos");
            return stats;
        }

        try {
            // --- RR intervals ---
            List<Double> rrIntervals = new ArrayList<>();
            for (int i = 1; i < rPeaks.length; i++) {
                rrIntervals.add((rPeaks[i] - rPeaks[i - 1]) / fs);
            }
            stats.totalBeats = rrIntervals.size();

            // --- HR instantánea ---
            List<Double> hr = new ArrayList<>();
            for (double rr : rrIntervals) hr.add(60.0 / rr);

            stats.avgHR = hr.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            stats.minHR = hr.stream().mapToDouble(Double::doubleValue).min().orElse(0);
            stats.maxHR = hr.stream().mapToDouble(Double::doubleValue).max().orElse(0);

            // --- Eventos clínicos ---
            for (double v : hr) {
                if (v > 100) stats.tachyCount++;
                if (v < 60) stats.bradyCount++;
            }

            for (double rr : rrIntervals) {
                if (rr > 2.0) stats.pauseCount++;
            }

            // --- PVC / PAC detección simplificada ---
            double mean = mean(signal);
            double sd = std(signal, mean);
            for (int i = 1; i < signal.length - 1; i++) {
                double diff = Math.abs(signal[i] - mean);
                if (diff > 3 * sd) stats.pvcCount++;
                else if (diff > 2 * sd) stats.pacCount++;
            }

            // --- Estimaciones de patrones ---
            stats.coupletCount   = stats.pvcCount / 2;
            stats.tripletCount   = stats.pvcCount / 3;
            stats.bigeminyCount  = stats.pvcCount / 4;
            stats.trigeminyCount = stats.pvcCount / 5;

        } catch (Exception e) {
            Log.e("ECG_DEBUG", "Error en analyzeExtended()", e);
        }

        return stats;
    }

    // === Funciones auxiliares ===
    private static double mean(double[] x) {
        if (x == null || x.length == 0) return 0;
        double s = 0;
        for (double v : x) s += v;
        return s / x.length;
    }

    private static double std(double[] x, double m) {
        if (x == null || x.length < 2) return 0;
        double s = 0;
        for (double v : x) s += Math.pow(v - m, 2);
        return Math.sqrt(s / (x.length - 1));
    }

    // === Conversión de int[] a double[] ===
    public static double[] toDoubleArray(int[] arr) {
        if (arr == null) return new double[0];
        double[] d = new double[arr.length];
        for (int i = 0; i < arr.length; i++) d[i] = arr[i];
        return d;
    }
}
