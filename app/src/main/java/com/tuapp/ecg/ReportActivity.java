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
        txtResumen.setText("Archivo: " + rep.fileName);
        //+++++++++++++++++++++++++++
        if (rep.extended != null) {
            Metrics.ECGStats st = rep.extended;
            String resumenClinico = String.format(Locale.US,
                    "Promedio: %.1f lpm\nMínima: %.1f lpm\nMáxima: %.1f lpm\n\n" +
                            "Taquicardias (>100): %d\nBradicardias (<60): %d\nPausas (>2s): %d\n\n" +
                            "PVC detectadas: %d\nPAC detectadas: %d\n\n" +
                            "Parejas (Couplets): %d\nRachas de 3 (Triplets): %d\n" +
                            "Bigeminia: %d\nTrigeminia: %d",
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
        //+++++++++++++++++++++++++++

        btnGuardar.setOnClickListener(v -> confirmAndSave());
    }

    /** ✅ Muestra eventos en formato detallado con hora o segundos */
    private String formatDetailedEvents(List<Metrics.Event> list, String title) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n").append(title).append("\n");

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

            int y = 40;
            y = drawLine(c, p, y, "Médico: ", edMedico);
            y = drawLine(c, p, y, "Lugar/Centro de salud: ", edLugar);
            y = drawLine(c, p, y, "Fecha: ", edFecha);
            y = drawLine(c, p, y, "Duración del estudio: ", edDuracion);
            y = drawLine(c, p, y, "Paciente: ", edPaciente);
            y = drawLine(c, p, y, "DNI: ", edDni);
            y = drawLine(c, p, y, "Edad: ", edEdad);
            y = drawLine(c, p, y, "Sexo: ", edSexo);
            y += 18;
            y = drawMultiline(c, p, y, "Antecedentes personales:", edAntecedentes);
            y += 18;
            y = drawMultiline(c, p, y, "Resultados:", edResultados);
            y += 18;
            //++++++++++++++++++++++++
            y = drawMultilineRaw(c, p, y, "Resultados clínicos automáticos:", txtClinicos.getText().toString());
            y += 18;


            //++++++++++++++++++++++++

            y = drawMultilineRaw(c, p, y, "Eventos (>100 lpm):", txtEventosHigh.getText().toString());
            y += 18;
            y = drawMultilineRaw(c, p, y, "Eventos (<60 lpm):", txtEventosLow.getText().toString());
            y += 18;
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

        } catch (Exception ex) {
            Log.e("ECG_DEBUG", "Error al guardar PDF", ex);
            Toast.makeText(this, "Error al guardar PDF: " + ex.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

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
        c.drawText(label, 40, y, p);
        y += 18;
        for (String ln : content.split("\n")) {
            c.drawText(ln, 60, y, p);
            y += 16;
        }
        return y;
    }

    private static CharSequence safe(Editable e) {
        return (e == null ? "" : e.toString());
    }

    /** ✅ Crea el URI del PDF en la carpeta Descargas */
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
