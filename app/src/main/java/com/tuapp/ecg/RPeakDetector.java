package com.tuapp.ecg;

import java.util.*;

/**
 * Detector de picos R simple con suavizado, umbral adaptativo y período refractario.
 */
public class RPeakDetector {

    public static class Result {
        public int[] rIndex;   // índices de R en muestras
        public double[] rrSec; // intervalos RR (s)
        public double[] bpm;   // 60/RR
    }

    /**
     * Detección de picos R.
     * @param x  señal ECG (en mV)
     * @param fs frecuencia de muestreo (Hz)
     */
    public static Result detectR(double[] x, double fs) {
        int n = x.length;
        if (n < 10) return new Result();

        // --- 1. Suavizado leve (media móvil N=7) ---
        double[] s = Filters.movingAverageCentered(x, 7);

        // --- 2. Umbral adaptativo sobre |s| ---
        double[] abs = new double[n];
        double max = 0;
        for (int i = 0; i < n; i++) {
            abs[i] = Math.abs(s[i]);
            if (abs[i] > max) max = abs[i];
        }
        double thr = Math.max(max * 0.4, 0.2);  // 40% del máximo o 0.2 mV
        int refr = (int) (0.20 * fs);           // 200 ms de período refractario

        // --- 3. Búsqueda de máximos locales ---
        List<Integer> idx = new ArrayList<>();
        int i = 1;
        while (i < n - 1) {
            if (abs[i] > thr && abs[i] >= abs[i - 1] && abs[i] >= abs[i + 1]) {
                idx.add(i);
                i += refr;
            } else i++;
        }

        // --- 4. Calcular intervalos RR y BPM ---
        Result r = new Result();
        r.rIndex = idx.stream().mapToInt(Integer::intValue).toArray();
        if (r.rIndex.length > 1) {
            r.rrSec = new double[r.rIndex.length - 1];
            r.bpm = new double[r.rIndex.length - 1];
            for (int k = 1; k < r.rIndex.length; k++) {
                double rr = (r.rIndex[k] - r.rIndex[k - 1]) / fs;
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
