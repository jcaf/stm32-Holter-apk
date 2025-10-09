package com.tuapp.ecg;

import java.util.*;

/**
 * Detector robusto de picos R (Pan-Tompkins-ish simplificado).
 *
 * Entradas:
 *  - x: señal ECG en mV (dobles)
 *  - fs: frecuencia de muestreo en Hz (ej. 500.0)
 *
 * Salidas (Result):
 *  - rIndex: índices de muestra donde se detectó cada R
 *  - rrSec: intervalos RR en segundos
 *  - bpm: frecuencia instantánea (60 / RR)
 *
 * Notas:
 *  - Intenté mantener un equilibrio entre robustez frente a ruido y no
 *    introducir mucha latencia/retardo. Los parámetros (ventanas, factores)
 *    están comentados y son fáciles de ajustar.
 */
public class RPeakDetector {

    public static class Result {
        public int[] rIndex;   // índices de R en muestras
        public double[] rrSec; // intervalos RR (s)
        public double[] bpm;   // 60/RR
    }

    public static Result detectR(double[] x, double fs) {
        int n = (x == null) ? 0 : x.length;
        Result r = new Result();

        if (n < 50) { // señal demasiado corta
            r.rIndex = new int[0];
            r.rrSec = new double[0];
            r.bpm = new double[0];
            return r;
        }

        // --- 1) Eliminación de baseline: media móvil larga (≈0.6 s) ---
        // Ventana para baseline: 0.6s (ajustable)
        int baselineWin = (int) Math.max(1, Math.round(0.60 * fs));
        double[] baseline = Filters.movingAverageCentered(x, baselineWin);

        // Señal con baseline eliminado
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            double b = (baseline != null && i < baseline.length) ? baseline[i] : 0.0;
            y[i] = x[i] - b;
        }

        // --- 2) Suavizado leve (media móvil pequeña N=5) ---
        int smoothN = 5;
        double[] s = Filters.movingAverageCentered(y, smoothN);

        // --- 3) Derivada y cuadrado (resaltar pendientes de QRS) ---
        double[] diff2 = new double[n];
        diff2[0] = 0.0;
        for (int i = 1; i < n; i++) {
            double d = s[i] - s[i - 1];
            diff2[i] = d * d; // cuadrado
        }

        // --- 4) Ventana de integración (≈100-120 ms) ---
        int integMs = 110; // ms (puedes probar 80..120)
        int integWin = (int) Math.max(1, Math.round((integMs / 1000.0) * fs));
        double[] integ = Filters.movingAverageCentered(diff2, integWin);

        // --- 5) Umbral adaptativo inicial ---
        // Estadísticas básicas de la señal integrada
        double maxInteg = 0.0;
        double sum = 0.0;
        for (double v : integ) { sum += v; if (v > maxInteg) maxInteg = v; }
        double meanInteg = sum / integ.length;

        // calcular desviación estándar
        double var = 0.0;
        for (double v : integ) var += (v - meanInteg) * (v - meanInteg);
        double stdInteg = Math.sqrt(var / Math.max(1, integ.length - 1));

        // Umbral robusto: media + k * std, con fallback a fracción del máximo
        double thr = meanInteg + 3.0 * stdInteg;
        thr = Math.max(thr, 0.25 * maxInteg); // al menos 25% del máximo

        // Si la señal es muy pequeña, ajustar a valor mínimo absoluto
        if (thr <= 1e-12) thr = 1e-12;

        // --- 6) Detección de candidatos (picos locales sobre la integ) ---
        int refractory = (int) Math.max(1, Math.round(0.20 * fs)); // 200 ms refractario (ajustable)
        List<Integer> cand = new ArrayList<>();
        int i = 1;
        while (i < n - 1) {
            if (integ[i] > thr && integ[i] >= integ[i - 1] && integ[i] >= integ[i + 1]) {
                cand.add(i);
                i += refractory; // saltar ventana refractaria
            } else {
                i++;
            }
        }

        // --- 7) Refinado: para cada candidato, buscar máximo local en la señal suavizada 's' ---
        // buscaremos en +/- searchWindow samples (ej. 50 ms) para posicionar R en la cresta real
        int refineMs = 50;
        int refineWin = (int) Math.max(1, Math.round((refineMs / 1000.0) * fs));
        List<Integer> peaks = new ArrayList<>();
        for (int idx : cand) {
            int a = Math.max(0, idx - refineWin);
            int b = Math.min(n - 1, idx + refineWin);
            int best = idx;
            double bestVal = s[idx];
            for (int k = a + 1; k <= b; k++) {
                if (s[k] > bestVal) {
                    bestVal = s[k];
                    best = k;
                }
            }
            peaks.add(best);
        }

        // --- 8) Eliminar duplicados / picos muy cercanos (mantener el pico mayor) ---
        List<Integer> finalPeaks = new ArrayList<>();
        int minDist = (int) Math.max(1, Math.round(0.25 * fs)); // 250 ms mínimo entre picos (evita double-count)
        Collections.sort(peaks);
        for (int idxPeak : peaks) {
            if (finalPeaks.isEmpty()) {
                finalPeaks.add(idxPeak);
            } else {
                int last = finalPeaks.get(finalPeaks.size() - 1);
                if (idxPeak - last <= minDist) {
                    // están muy juntos: quedarnos con el que tenga mayor amplitud en 's'
                    if (s[idxPeak] > s[last]) {
                        finalPeaks.set(finalPeaks.size() - 1, idxPeak);
                    }
                } else {
                    finalPeaks.add(idxPeak);
                }
            }
        }

        // --- 9) Si no se detectaron picos con el umbral inicial, intentar relajarlo (fallback) ---
        if (finalPeaks.isEmpty()) {
            double thr2 = Math.max(meanInteg + 2.0 * stdInteg, 0.15 * maxInteg);
            i = 1;
            while (i < n - 1) {
                if (integ[i] > thr2 && integ[i] >= integ[i - 1] && integ[i] >= integ[i + 1]) {
                    int a = Math.max(0, i - refineWin);
                    int b = Math.min(n - 1, i + refineWin);
                    int best = i; double bestVal = s[i];
                    for (int k = a + 1; k <= b; k++) if (s[k] > bestVal) { bestVal = s[k]; best = k; }
                    // añadir con la misma lógica de separación
                    if (finalPeaks.isEmpty()) finalPeaks.add(best);
                    else {
                        int last = finalPeaks.get(finalPeaks.size() - 1);
                        if (best - last > minDist) finalPeaks.add(best);
                        else if (s[best] > s[last]) finalPeaks.set(finalPeaks.size() - 1, best);
                    }
                    i += refractory;
                } else i++;
            }
        }

        // --- 10) Resultado: índices, RR y BPM ---
        r.rIndex = finalPeaks.stream().mapToInt(Integer::intValue).toArray();
        if (r.rIndex.length > 1) {
            int m = r.rIndex.length - 1;
            r.rrSec = new double[m];
            r.bpm = new double[m];
            for (int k = 1; k < r.rIndex.length; k++) {
                double rr = (r.rIndex[k] - r.rIndex[k - 1]) / fs;
                if (rr <= 0) rr = 1.0 / fs; // seguridad
                r.rrSec[k - 1] = rr;
                r.bpm[k - 1] = 60.0 / rr;
            }
        } else {
            r.rrSec = new double[0];
            r.bpm = new double[0];
        }

        return r;
    }
}
