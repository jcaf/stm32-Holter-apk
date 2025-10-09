package com.tuapp.ecg;

public class Filters {

    /**
     * Filtro de media móvil centrada.
     * Si la ventana N es mayor que el tamaño de la señal, devuelve una copia segura.
     */
    public static double[] movingAverageCentered(double[] x, int N) {
        int n = (x != null) ? x.length : 0;
        if (n == 0 || N < 1) return new double[0];

        // Limitar tamaño de ventana si es mayor que la señal
        if (N > n) N = n;
        int half = N / 2;
        double[] y = new double[n];

        for (int i = 0; i < n; i++) {
            int start = Math.max(0, i - half);
            int end   = Math.min(n - 1, i + half);
            double sum = 0;
            for (int j = start; j <= end; j++) {
                sum += x[j];
            }
            y[i] = sum / (end - start + 1);
        }

        return y;
    }

    /**
     * Filtro pasa altos simple: elimina componente DC por resta del promedio.
     */
    public static double[] removeDC(double[] x) {
        int n = (x != null) ? x.length : 0;
        if (n == 0) return new double[0];

        double mean = 0;
        for (double v : x) mean += v;
        mean /= n;

        double[] y = new double[n];
        for (int i = 0; i < n; i++) y[i] = x[i] - mean;
        return y;
    }
}
