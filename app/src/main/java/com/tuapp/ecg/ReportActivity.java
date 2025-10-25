package com.tuapp.ecg;

import androidx.documentfile.provider.DocumentFile;

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
import java.util.Locale;

public class ReportActivity extends AppCompatActivity {

    // === DIRECTIVA PARA ELIMINAR ARCHIVOS DESPUÉS DE GUARDAR PDF ===
    private static final boolean AUTO_DELETE_AFTER_PDF = true;

    EditText edMedico, edLugar, edFecha, edDuracion,
            edPaciente, edDni, edEdad, edSexo,
            edAntecedentes, edRecomendaciones, edResultados;
    TextView txtEventosHigh, txtEventosLow, txtResumen, txtClinicos;
    Button btnGuardar;
    Metrics.Report rep;

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
        txtClinicos    = findViewById(R.id.txtClinicos);
        btnGuardar     = findViewById(R.id.btnGuardar);

        try {
            String key = getIntent().getStringExtra("reportKey");
            rep = ReportCache.get(key);
            if (rep == null) {
                Log.e("ECG_DEBUG", "Error: ReportCache devolvió null para key=" + key);
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
                    "Frecuencia media: %.1f LPM\nFrecuencia mínima: %.1f LPM\nFrecuencia máxima: %.1f LPM",
                    rep.bpmMean, rep.bpmMin, rep.bpmMax));

            // Manejo seguro de eventos
            txtEventosHigh.setText(formatDetailedEvents(rep.topHigh, ">100 lpm"));
            txtEventosLow.setText(formatDetailedEvents(rep.topLow, "<60 lpm"));
            txtResumen.setText(""); // no mostrar "Archivo: ..."

            // Métricas clínicas extendidas
            if (rep.extended != null) {
                Metrics.ECGStats st = rep.extended;
                String resumenClinico = String.format(Locale.US,
                        "Frecuencia media: %.1f LPM\nFrecuencia mínima: %.1f LPM\nFrecuencia máxima: %.1f LPM\n" +
                                "Episodios de taquicardia (>100 LPM): %d\n" +
                                "Episodios de bradicardia (<60 LPM): %d\n" +
                                "Pausas (>2s): %d\n" +
                                "PVC detectadas: %d\nPAC detectadas: %d\n" +
                                "Parejas ventriculares: %d\n" +
                                "Rachas de 3 latidos (triple): %d\n" +
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

        } catch (Exception e) {
            Log.e("ECG_DEBUG", "Error inicializando ReportActivity", e);
            Toast.makeText(this, "Error al cargar el reporte.", Toast.LENGTH_LONG).show();
        }

        btnGuardar.setOnClickListener(v -> confirmAndSave());
    }

    private String formatDetailedEvents(java.util.List<Metrics.Event> list, String title) {
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

            // === Encabezado centrado ===
            android.graphics.Paint title = new android.graphics.Paint();
            title.setTextSize(16f);
            title.setColor(Color.BLACK);
            title.setTextAlign(android.graphics.Paint.Align.CENTER);
            c.drawText("REPORTE CLÍNICO - MONITOREO ECG", info.getPageWidth() / 2f, y, title);
            y += 20;
            c.drawText("Derivación: I (config. Einthoven modificada)", info.getPageWidth() / 2f, y, title);
            y += 30;

            // === Datos generales ===
            y = drawLine(c, p, y, "Médico: ", edMedico);
            y = drawLine(c, p, y, "Lugar/Centro de salud: ", edLugar);
            y = drawLine(c, p, y, "Fecha: ", edFecha);
            y = drawLine(c, p, y, "Duración del estudio: ", edDuracion);
            y = drawLine(c, p, y, "Paciente: ", edPaciente);
            y = drawLine(c, p, y, "DNI: ", edDni);
            y = drawLine(c, p, y, "Edad: ", edEdad);
            y = drawLine(c, p, y, "Sexo: ", edSexo);
            y += 20;

            // === Secciones ===
            y = drawMultiline(c, p, y, "Antecedentes personales:", edAntecedentes);
            y += 20;
            y = drawMultilineRaw(c, p, y, "", txtClinicos.getText().toString());
            y += 20;
            y = drawMultilineRaw(c, p, y, "Eventos (>100 lpm):", txtEventosHigh.getText().toString());
            y += 18;
            y = drawMultilineRaw(c, p, y, "Eventos (<60 lpm):", txtEventosLow.getText().toString());
            y += 18;
            y = drawMultiline(c, p, y, "Recomendaciones:", edRecomendaciones);

            pdf.finishPage(page);

            // === Guardar ===
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

            if (AUTO_DELETE_AFTER_PDF) {
                promptFileDeletion();
            }

        } catch (Exception ex) {
            Log.e("ECG_DEBUG", "Error al guardar PDF", ex);
            Toast.makeText(this, "Error al guardar PDF: " + ex.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void promptFileDeletion() {
        new AlertDialog.Builder(this)
                .setTitle("Eliminar archivos originales")
                .setMessage("¿Desea eliminar los archivos .ECG y .TST después de guardar el reporte en PDF?")
                .setPositiveButton("Sí", (dialog, which) -> {
                    String ecgPath = getIntent().getStringExtra("ecgPath");
                    String tstPath = getIntent().getStringExtra("tstPath");
                    String ecgUriStr = getIntent().getStringExtra("ecgUri");

                    int deleted = 0;
                    if (deleteFileUniversal(ecgPath)) deleted++;
                    if (deleteFileUniversal(tstPath)) deleted++;

                    // 🔹 Eliminar también desde el URI SAF (cualquier ubicación)
                    if (ecgUriStr != null) {
                        try {
                            Uri uri = normalizeUri(Uri.parse(ecgUriStr));
                            Log.d("ECG_DEBUG", "Intentando eliminar con SAF: " + uri);

                            boolean ok = false;
                            DocumentFile doc = DocumentFile.fromSingleUri(this, uri);
                            if (doc != null && doc.canWrite() && doc.exists()) {
                                ok = doc.delete();
                                Log.d("ECG_DEBUG", "Eliminado por SAF directo=" + ok + " → " + uri);
                            }

                            if (!ok) {
                                // 🔸 Fallback: intentar eliminar por ruta física
                                String decoded = Uri.decode(uri.toString());

                                // Eliminar prefijos conocidos de SAF
                                decoded = decoded
                                        .replace("content://com.android.providers.downloads.documents/document/raw%3A", "")
                                        .replace("content://com.android.providers.downloads.documents/document/", "")
                                        .replace("file://", "");

                                File f = new File(decoded);
                                if (f.exists()) {
                                    Log.d("ECG_DEBUG", "Intentando eliminar por ruta directa: " + f.getAbsolutePath());
                                    ok = f.delete();

                                    // Si aún no se elimina, forzar con comando rm
                                    if (!ok) {
                                        Process p = Runtime.getRuntime().exec("rm -f \"" + f.getAbsolutePath() + "\"");
                                        p.waitFor();
                                        ok = !f.exists();
                                    }
                                } else {
                                    Log.w("ECG_DEBUG", "Archivo no encontrado para eliminación directa: " + decoded);
                                }
                            }

                            Log.d("ECG_DEBUG", "Eliminado universal=" + ok + " → " + uri);
                            if (ok) deleted++;

                        } catch (Exception e) {
                            Log.e("ECG_DEBUG", "Error al eliminar con SAF: " + ecgUriStr, e);
                        }
                    }




                    Toast.makeText(this, "🗑 Archivos eliminados (" + deleted + ")", Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("No", (dialog, which) ->
                        Toast.makeText(this, "Archivos conservados.", Toast.LENGTH_SHORT).show())
                .show();
    }
    private Uri normalizeUri(Uri uri) {
        try {
            String s = uri.toString();
            if (s.contains("raw%3A") || s.contains("raw:")) {
                s = s.replace("raw%3A", "").replace("raw:", "");
                return Uri.parse(Uri.decode(s));
            }
        } catch (Exception e) {
            Log.w("ECG_DEBUG", "normalizeUri: fallo al normalizar URI", e);
        }
        return uri;
    }


    private boolean deleteFileUniversal(String path) {
        if (path == null || path.trim().isEmpty()) return false;
        try {
            File f = new File(path);
            if (!f.exists()) return false;

            // ✅ Si pertenece al cache interno, basta con delete()
            if (path.startsWith(getCacheDir().getAbsolutePath())) {
                boolean ok = f.delete();
                Log.d("ECG_DEBUG", "deleteFileUniversal: cache interno eliminado=" + ok + " → " + path);
                return ok;
            }

            // Archivos externos o públicos
            if (f.delete()) return true;

            Uri uri = Uri.fromFile(f);
            int res = getContentResolver().delete(uri, null, null);
            if (res > 0) return true;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Uri externalUri = MediaStore.Files.getContentUri("external");
                res = getContentResolver().delete(
                        externalUri,
                        MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                        new String[]{f.getName()});
                if (res > 0) return true;
            }

            Process p = Runtime.getRuntime().exec("rm -f " + path);
            p.waitFor();
            return !f.exists();

        } catch (Exception e) {
            Log.e("ECG_DEBUG", "Error al eliminar archivo: " + path, e);
            return false;
        }
    }


    // === Utilidades gráficas ===
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
        if (uri == null) throw new Exception("No se pudo crear el archivo en Descargas");
        return uri;
    }
}
