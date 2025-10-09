package com.tuapp.ecg;

public class Filters {
    /** Media móvil centrada, N impar (p.ej., 9). Devuelve nueva señal suavizada. */
    public static double[] movingAverageCentered(double[] x, int N) {
        if (N<1 || (N%2)==0) throw new IllegalArgumentException("N debe ser impar");
        int n = x.length, half=N/2;
        double[] y = new double[n];
        double acc=0;
        // inicial
        for (int k=-half;k<=half;k++){
            int idx = clamp(k,0,n-1);
            acc += x[idx];
        }
        y[0] = acc/N;
        for (int i=1;i<n;i++){
            int outIdx = clamp(i-1-half, 0, n-1);
            int inIdx  = clamp(i+half,   0, n-1);
            acc += x[inIdx] - x[outIdx];
            y[i] = acc/N;
        }
        return y;
    }
    private static int clamp(int v,int lo,int hi){ return Math.max(lo, Math.min(hi, v)); }
}
