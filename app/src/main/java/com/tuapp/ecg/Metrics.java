package com.tuapp.ecg;

import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Cálculo de métricas y generación del objeto Report que se muestra en ReportActivity.
 *
 * buildReport(...) devuelve:
 *  - estadísticas básicas (bpm mean/min/max)
 *  - topHigh: hasta 10 eventos >100 lpm
 *  - topLow: hasta 10 eventos <60 lpm
 *
 * Para cada evento se guarda:
 *  - bpm
 *  - timeSec (tiempo relativo en segundos desde inicio del archivo)
 *  - timestamp (si se proporciona studyStart se calcula la hora; además
 *    DiagnosticoActivity puede sobrescribir con TstIndex para mayor precisión)
 */
public class Metrics {

    /** Evento de frecuencia (se usa en el reporte). */
    public static class Event implements Serializable {
        public double bpm;
        public double timeSec;         // tiempo relativo del latido (s)
        public String timestamp;       // hora real (si .TST disponible), ej. "2025-10-06 10:27:31"
        @Override public String toString(){
            return String.format(Locale.US, "BPM %.1f @ %s",
                    bpm, (timestamp!=null? timestamp : String.format(Locale.US,"%.2fs", timeSec)));
        }
    }

    /** Objeto Reporte que pasamos entre Activities (Serializable para Intent). */
    public static class Report implements Serializable {
        public String fileName;
        public double bpmMean, bpmMin, bpmMax;
        public double durationSec;
        public String studyStart;         // "OPEN" del .TST si existe
        public List<Event> topHigh = new ArrayList<>();
        public List<Event> topLow  = new ArrayList<>();
    }

    /**
     * Construye el reporte a partir de la detección de R.
     * @param resultR resultado del detector de R
     * @param tsSecondsOrNull (no usado aquí, queda para futuras mejoras)
     * @param fileName nombre de archivo
     * @param durationSec duración total en segundos
     * @param studyStart hora OPEN del TST (ej. "2025-10-06 10:27:11") — opcional
     */
    public static Report buildReport(RPeakDetector.Result resultR, List<String> tsSecondsOrNull,
                                     String fileName, double durationSec, String studyStart){
        Report rep = new Report();
        rep.fileName = fileName;
        rep.durationSec = durationSec;
        rep.studyStart = studyStart;

        if (resultR == null || resultR.bpm == null || resultR.bpm.length == 0) {
            rep.bpmMean = rep.bpmMin = rep.bpmMax = 0.0;
            return rep;
        }

        // Estadísticas
        double sum=0, mn=Double.POSITIVE_INFINITY, mx=Double.NEGATIVE_INFINITY;
        for (double v: resultR.bpm){ sum+=v; if (v<mn) mn=v; if (v>mx) mx=v; }
        rep.bpmMean = sum / resultR.bpm.length;
        rep.bpmMin  = mn;
        rep.bpmMax  = mx;

        List<Event> highs = new ArrayList<>(), lows = new ArrayList<>();

        // Si tenemos un studyStart (string "yyyy-MM-dd HH:mm:ss"), usar como base para timestamps
        Date baseDate = null;
        if (studyStart != null) {
            try {
                if (studyStart.length() >= 19) {
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
                    baseDate = sdf.parse(studyStart);
                }
            } catch (Exception ignored){}
        }

        // Ojo: resultR.bpm.length == resultR.rIndex.length - 1
        for (int k = 0; k < resultR.bpm.length; k++){
            double bpm = resultR.bpm[k];
            // Tiempo asociado: instante del segundo R del intervalo -> rIndex[k+1]
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

        // ordenar y acotar top10
        highs.sort((a,b)->Double.compare(b.bpm, a.bpm)); // descendente por bpm
        lows.sort(Comparator.comparingDouble(a -> a.bpm)); // ascendente por bpm

        rep.topHigh.addAll(highs.subList(0, Math.min(10, highs.size())));
        rep.topLow.addAll(lows.subList(0, Math.min(10, lows.size())));

        return rep;
    }
}
