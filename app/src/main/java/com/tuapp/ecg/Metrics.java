package com.tuapp.ecg;

import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.*;

/** Cálculo de métricas y resumen de estudio ECG. */
public class Metrics {

    /** Evento de frecuencia anómala */
    public static class Event implements Serializable {
        public double bpm;
        public double timeSec;
        public String timestamp; // hora absoluta (ej: 10:27:35)
    }

    /** Reporte completo generado desde DiagnosticoActivity */
    public static class Report implements Serializable {
        public String fileName;
        public double durationSec;
        public double bpmMean, bpmMax, bpmMin;
        public List<Event> topHigh = new ArrayList<>();
        public List<Event> topLow  = new ArrayList<>();
        public String studyStart; // hora base del archivo .TST
    }

    /**
     * Construye un reporte completo a partir de los resultados del detector.
     * @param resultR resultado del detector de R
     * @param ts lista de timestamps (de .TST) asociados a las muestras
     * @param fileName nombre del archivo
     * @param duration duración total en segundos
     * @param studyStart cadena de la hora inicial, ej. "2025-10-06 10:27:11"
     */
    public static Report buildReport(RPeakDetector.Result resultR,
                                     List<String> ts,
                                     String fileName,
                                     double duration,
                                     String studyStart) {
        Report rep = new Report();
        rep.fileName = fileName;
        rep.durationSec = duration;
        rep.studyStart = studyStart;

        if (resultR == null || resultR.bpm.length == 0) {
            rep.bpmMean = rep.bpmMax = rep.bpmMin = 0;
            return rep;
        }

        // Calcular estadísticas básicas
        double sum = 0, max = -9999, min = 9999;
        for (double b : resultR.bpm) {
            sum += b;
            if (b > max) max = b;
            if (b < min) min = b;
        }
        rep.bpmMean = sum / resultR.bpm.length;
        rep.bpmMax = max;
        rep.bpmMin = min;

        // Generar eventos >100 y <60
        List<Event> highs = new ArrayList<>();
        List<Event> lows  = new ArrayList<>();

        // Obtener base de hora si se proporcionó
        Date baseDate = null;
        try {
            if (studyStart != null && studyStart.length() >= 19) {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
                baseDate = sdf.parse(studyStart);
            }
        } catch (Exception ignored) {}

        for (int i = 0; i < resultR.bpm.length; i++) {
            double bpm = resultR.bpm[i];
            double t = resultR.rIndex[i] / DiagnosticoActivity.FS; // usa frecuencia global de 500Hz

            Event e = new Event();
            e.bpm = bpm;
            e.timeSec = t;

            // Calcular hora absoluta (timestamp)
            if (baseDate != null) {
                Date d = new Date(baseDate.getTime() + (long) (t * 1000));
                SimpleDateFormat sdf2 = new SimpleDateFormat("HH:mm:ss", Locale.US);
                e.timestamp = sdf2.format(d);
            }

            if (bpm > 100) highs.add(e);
            else if (bpm < 60) lows.add(e);
        }

        // Ordenar y limitar top 10
        highs.sort((a, b) -> Double.compare(b.bpm, a.bpm));
        lows.sort(Comparator.comparingDouble(a -> a.bpm));

        int nh = Math.min(10, highs.size());
        int nl = Math.min(10, lows.size());

        rep.topHigh.addAll(highs.subList(0, nh));
        rep.topLow.addAll(lows.subList(0, nl));

        return rep;
    }
}
