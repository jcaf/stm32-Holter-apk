package com.tuapp.ecg;

import android.content.ContentValues;
import android.graphics.Color;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.Editable;
import android.util.Log;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ReportActivity extends AppCompatActivity {

    EditText edMedico, edLugar, edFecha, edDuracion,
            edPaciente, edDni, edEdad, edSexo,
            edAntecedentes, edRecomendaciones, edResultados;
    TextView txtEventosHigh, txtEventosLow, txtResumen;
    Button btnGuardar;
    Metrics.Report rep;
    TextView txtClinicos;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_report);

        edMedico = findViewById(R.id.edMedico);
        edLugar  = findViewById(R.id.edLugar);
        edFecha  = findViewById(R.id.edFecha);
        edDuracion = findViewById(R.id.edDuracion);
        edPaciente = findViewById(R.id.edPaciente);
        edDni      = findViewById(R.id.edDni);
        edEdad     = findViewById(R.id.edEdad);
        edSexo     = findViewById(R.id.edSexo);
        edAntecedentes = findViewById(R.id.edAntecedentes);
        edRecomendaciones = findViewById(R.id.edRecomendaciones);
        edResultados = findViewById(R.id.edResultados);
        txtEventosHigh = findViewById(R.id.txtEventosHigh);
        txtEventosLow  = findViewById(R.id.txtEventosLow);
        txtResumen     = findViewById(R.id.txtResumen);
        txtClinicos = findViewById(R.id.txtClinicos);
        btnGuardar     = findViewById(R.id.btnGuardar);

        // Recuperar el reporte desde la caché temporal
        String key = getIntent().getStringExtra("reportKey");
        rep = ReportCache.get(key);
        if (rep == null) {
            Toast.makeText(this, "Error: no se pudo cargar el reporte.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        String fecha = (rep.studyStart != null)
                ? rep.studyStart
                : new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date());

        edFecha.setText(fecha);
        edDuracion.setText(String.format(Locale.US, "%.1f s", rep.durationSec));
        edResultados.setText(String.format(Locale.US,
                "Frecuencia cardíaca promedio: %.1f lpm\nMáxima: %.1f lpm\nMínima: %.1f lpm",
                rep.bpmMean, rep.bpmMax, rep.bpmMin));

        // ✅ Mostrar eventos con hora absoluta si existe
        txtEventosHigh.setText(formatDetailedEvents(rep.topHigh, ">100 lpm"));
        txtEventosLow.setText(formatDetailedEvents(rep.topLow, "<60 lpm"));

        // Eliminamos el texto redundante de resumen en la vista Android
        txtResumen.setText("");

        // ✅ Resultados clínicos
        if (rep.extended != null) {
            Metrics.ECGStats st = rep.extended;
            String resumenClinico = String.format(Locale.US,
                    "Frecuencia media: %.1f LPM\n" +
                            "Frecuencia mínima: %.1f LPM\n" +
                            "Frecuencia máxima: %.1f LPM\n" +
                            "Episodios de taquicardia (>100 LPM): %d\n" +
                            "Episodios de bradicardia (<60 LPM): %d\n" +
                            "Pausas (>2s): %d\n" +
                            "PVC detectadas: %d\n" +
                            "PAC detectadas: %d\n" +
                            "Parejas ventriculares: %d\n" +
                            "Rachas de 3 latidos (triple): %d\n" +
                            "Bigeminia: %d\n" +
                            "Trigeminia: %d",
                    st.avgHR, st.minHR, st.maxHR,
                    st.tachyCount, st.bradyCount, st.pauseCount,
                    st.pvcCount, st.pacCount,
                    st.coupletCount, st.tripletCount,
                    st.bigeminyCount, st.trigeminyCount
            );
            txtClinicos.setText(resumenClinico);
        } else {
            txtClinicos.setText("(No se calcularon métricas clínicas extendidas)");
        }

        btnGuardar.setOnClickListener(v -> confirmAndSave());
    }

    /** ✅ Muestra eventos en formato detallado */
    private String formatDetailedEvents(List<Metrics.Event> list, String title) {
        StringBuilder sb = new StringBuilder();
        sb.append(title).append("\n");
        if (list == null || list.isEmpty()) {
            sb.append("(sin eventos)\n");
            return sb.toString();
        }
        for (int i = 0; i < list.size(); i++) {
            Metrics.Event e = list.get(i);
            String tiempo = (e.timestamp != null && !e.timestamp.isEmpty())
                    ? e.timestamp
                    : String.format(Locale.US, "%.2f s", e.timeSec);
            sb.append(String.format(Locale.US, "%2d) %.2f lpm  @ %s\n", i + 1, e.bpm, tiempo));
        }
        return sb.toString();
    }

    private void confirmAndSave() {
        new AlertDialog.Builder(this)
                .setMessage("¿Desea guardar el reporte en PDF?")
                .setPositiveButton("Sí", (d, w) -> savePdf())
                .setNegativeButton("No", null)
                .show();
    }

    private void savePdf() {
        try {
            PdfDocument pdf = new PdfDocument();
            PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(595, 842, 1).create(); // A4
            PdfDocument.Page page = pdf.startPage(info);
            android.graphics.Canvas c = page.getCanvas();
            android.graphics.Paint p = new android.graphics.Paint();
            p.setTextSize(12f);
            p.setColor(Color.BLACK);

            int y = 50;

            // === Encabezado centrado ===
            android.graphics.Paint title = new android.graphics.Paint(p);
            title.setTextSize(16f);
            title.setFakeBoldText(true);
            title.setTextAlign(android.graphics.Paint.Align.CENTER);
            c.drawText("REPORTE CLÍNICO - MONITOREO ECG", info.getPageWidth() / 2f, y, title);
            y += 25;
            c.drawText("Derivación: I (config. Einthoven modificada)", info.getPageWidth() / 2f, y, title);
            y += 40;

            // === Datos del paciente ===
            y = drawLine(c, p, y, "Médico: ", edMedico);
            y = drawLine(c, p, y, "Lugar/Centro de salud: ", edLugar);
            y = drawLine(c, p, y, "Fecha: ", edFecha);
            y = drawLine(c, p, y, "Duración del estudio: ", edDuracion);
            y = drawLine(c, p, y, "Paciente: ", edPaciente);
            y = drawLine(c, p, y, "DNI: ", edDni);
            y = drawLine(c, p, y, "Edad: ", edEdad);
            y = drawLine(c, p, y, "Sexo: ", edSexo);
            y += 20;
            y = drawMultiline(c, p, y, "Antecedentes personales:", edAntecedentes);
            y += 25;

            // === Resultados clínicos ===
            y = drawMultilineRaw(c, p, y, "", txtClinicos.getText().toString());
            y += 25;

            // === Eventos ===
            y = drawMultilineRaw(c, p, y, "Eventos (>100 lpm):", txtEventosHigh.getText().toString());
            y += 20;
            y = drawMultilineRaw(c, p, y, "Eventos (<60 lpm):", txtEventosLow.getText().toString());
            y += 20;

            // === Recomendaciones ===
            y = drawMultiline(c, p, y, "Recomendaciones:", edRecomendaciones);

            pdf.finishPage(page);

            String name = "ReporteECG_" +
                    new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".pdf";
            Uri uri = createDownloadUri(name);

            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (os == null) throw new Exception("No se pudo abrir el archivo para escritura");
                pdf.writeTo(os);
                os.flush();
            }

            pdf.close();
            Toast.makeText(this, "✅ PDF guardado en Descargas: " + name, Toast.LENGTH_LONG).show();

            // === Eliminación opcional de archivos ECG/TST ===
            final boolean AUTO_DELETE_AFTER_PDF = true; // <— Activa o desactiva la función globalmente

            if (AUTO_DELETE_AFTER_PDF) {
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Eliminar archivos originales")
                        .setMessage("¿Desea eliminar los archivos .ECG y .TST después de guardar el reporte en PDF?")
                        .setPositiveButton("Sí", (dialog, which) -> {
                            try {
                                int deleted = 0;

                                // Archivos originales pasados por Intent
                                String ecgPath = getIntent().getStringExtra("ecgPath");
                                String tstPath = getIntent().getStringExtra("tstPath");
                                if (ecgPath != null) {
                                    File fEcg = new File(ecgPath);
                                    if (fEcg.exists() && fEcg.delete()) deleted++;
                                }
                                if (tstPath != null) {
                                    File fTst = new File(tstPath);
                                    if (fTst.exists() && fTst.delete()) deleted++;
                                }

                                // Archivos en caché
                                File cacheDir = getCacheDir();
                                if (cacheDir != null && cacheDir.isDirectory()) {
                                    File[] files = cacheDir.listFiles();
                                    if (files != null) {
                                        for (File f : files) {
                                            String n = f.getName().toUpperCase(Locale.US);
                                            if (n.endsWith(".ECG") || n.endsWith(".TST")) {
                                                if (f.delete()) deleted++;
                                            }
                                        }
                                    }
                                }

                                Toast.makeText(this,
                                        "🗑 Archivos .ECG/.TST eliminados (" + deleted + ")", Toast.LENGTH_LONG).show();
                                Log.i("ECG_DEBUG", "Auto-delete tras PDF: eliminados " + deleted + " archivos.");

                            } catch (Exception e) {
                                Log.e("ECG_DEBUG", "Error eliminando archivos ECG/TST", e);
                                Toast.makeText(this, "⚠️ Error al eliminar archivos", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton("No", (dialog, which) ->
                                Toast.makeText(this, "Archivos conservados.", Toast.LENGTH_SHORT).show())
                        .show();
            }

        } catch (Exception ex) {
            Log.e("ECG_DEBUG", "Error al guardar PDF", ex);
            Toast.makeText(this, "Error al guardar PDF: " + ex.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // === Utilidades de dibujo en PDF ===
    private int drawLine(android.graphics.Canvas c, android.graphics.Paint p, int y, String label, EditText ed) {
        c.drawText(label + safe(ed.getText()), 40, y, p);
        return y + 18;
    }

    private int drawMultiline(android.graphics.Canvas c, android.graphics.Paint p, int y, String label, EditText ed) {
        c.drawText(label, 40, y, p);
        y += 18;
        for (String ln : safe(ed.getText()).toString().split("\n")) {
            c.drawText(ln, 60, y, p);
            y += 16;
        }
        return y;
    }

    private int drawMultilineRaw(android.graphics.Canvas c, android.graphics.Paint p, int y, String label, String content) {
        if (label != null && !label.isEmpty()) {
            c.drawText(label, 40, y, p);
            y += 18;
        }
        for (String ln : content.split("\n")) {
            c.drawText(ln, 60, y, p);
            y += 16;
        }
        return y;
    }

    private static CharSequence safe(Editable e) {
        return (e == null ? "" : e.toString());
    }

    private Uri createDownloadUri(String displayName) throws Exception {
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf");
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/");

        Uri uri;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        } else {
            File downloads = android.os.Environment
                    .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
            File file = new File(downloads, displayName);
            uri = Uri.fromFile(file);
        }

        if (uri == null)
            throw new Exception("No se pudo crear el archivo en Descargas");

        return uri;
    }
}
