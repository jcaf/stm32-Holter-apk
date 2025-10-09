package com.tuapp.ecg;

import androidx.documentfile.provider.DocumentFile;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.io.File;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

import android.util.Log;
import android.net.Uri;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.renderer.LineChartRenderer;
import com.github.mikephil.charting.utils.ViewPortHandler;
import com.github.mikephil.charting.interfaces.dataprovider.LineDataProvider;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.*;

public class DiagnosticoActivity extends AppCompatActivity {

    public static final double FS = 500.0;  // Hz
    private static final int MA_N = 9;
    private static final double Y_MIN_MV = -1.5;
    private static final double Y_MAX_MV = +1.5;
    private static final int ADC_MID = 2048;

    TextView lblArchivo, lblInfo;
    LineChart ecgChart;
    CheckBox chkMA;
    Button btnReporte;

    String ecgPath, tstPath;
    double[] signalRaw, signalMA;
    double durationSec;
    TstIndex timeIndex;

    public static void launch(Context c, String ecgAbsPath) {
        Intent i = new Intent(c, DiagnosticoActivity.class);
        i.putExtra("ecg", ecgAbsPath);
        c.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_diagnostico);

        lblArchivo = findViewById(R.id.lblArchivo);
        lblInfo = findViewById(R.id.lblInfo);
        ecgChart = findViewById(R.id.ecgChart);
        chkMA = findViewById(R.id.chkMA);
        btnReporte = findViewById(R.id.btnReporte);

        Log.d("ECG_DEBUG", "=== INICIANDO DiagnosticoActivity ===");

        Button btnSeleccionar = new Button(this);
        btnSeleccionar.setText("Seleccionar archivo ECG");
        ((android.widget.LinearLayout) findViewById(R.id.rootLayout)).addView(btnSeleccionar, 0);

        btnSeleccionar.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(intent, "Selecciona archivo .ECG"), 100);
        });

        chkMA.setOnCheckedChangeListener((v, checked) -> {
            if (signalRaw != null) plot(checked);
        });

        // === Botón "Generar Reporte" ===
        btnReporte.setOnClickListener(v -> {
            if (signalRaw == null) return;

            // === Detectar picos R ===
            RPeakDetector.Result r = RPeakDetector.detectR(signalRaw, FS);

            // === Leer hora de inicio desde el archivo .TST (si existe) ===
            String horaInicioTST = null;
            try {
                if (tstPath != null) {
                    Log.d("ECG_DEBUG", "Leyendo hora desde: " + tstPath);
                    try (BufferedReader br = new BufferedReader(new FileReader(tstPath))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            if (line.startsWith("OPEN,")) {
                                horaInicioTST = line.substring(5).trim();
                                Log.d("ECG_DEBUG", "Hora base detectada: " + horaInicioTST);
                                break;
                            }
                        }
                    }
                } else {
                    Log.d("ECG_DEBUG", "No existe archivo .TST asociado.");
                }
            } catch (Exception e) {
                Log.e("ECG_DEBUG", "Error leyendo hora del .TST", e);
            }

            // === Generar el reporte base ===
            Metrics.Report rep = Metrics.buildReport(
                    r,
                    null,
                    new File(ecgPath).getName(),
                    durationSec,
                    horaInicioTST
            );

            // === Enlazar hora real usando TstIndex ===
            if (tstPath != null) {
                timeIndex = TstIndex.parse(new File(tstPath));
                if (timeIndex != null) {
                    Log.d("ECG_DEBUG", "Hora de inicio del TST: " + timeIndex.startDisplay);
                    for (Metrics.Event e : rep.topHigh) {
                        e.timestamp = timeIndex.sampleToDateTimeString((int) Math.round(e.timeSec * FS));
                    }
                    for (Metrics.Event e : rep.topLow) {
                        e.timestamp = timeIndex.sampleToDateTimeString((int) Math.round(e.timeSec * FS));
                    }
                    rep.studyStart = timeIndex.startDisplay;
                }
            }

            // === Guardar en caché para el ReportActivity ===
            String key = "rep_" + System.currentTimeMillis();
            ReportCache.put(key, rep);

            // === Lanzar ReportActivity ===
            Intent intent = new Intent(DiagnosticoActivity.this, ReportActivity.class);
            intent.putExtra("reportKey", key);
            startActivity(intent);
        });


        setupChartAesthetics();
    }

    /**
     * ============================
     * Apariencia inicial
     * ============================
     */
    private void setupChartAesthetics() {
        ecgChart.setNoDataText("Selecciona un archivo ECG para visualizar.");
        ecgChart.setBackgroundColor(Color.rgb(255, 245, 245)); // papel ECG
        ecgChart.setDrawGridBackground(false);
        ecgChart.setTouchEnabled(true);
        ecgChart.setPinchZoom(true);
        ecgChart.getDescription().setEnabled(false);

        XAxis xAxis = ecgChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setTextColor(Color.DKGRAY);
        xAxis.setDrawGridLines(false);

        YAxis leftAxis = ecgChart.getAxisLeft();
        leftAxis.setTextColor(Color.DKGRAY);
        leftAxis.setDrawGridLines(false);
        leftAxis.setAxisMinimum((float) Y_MIN_MV);
        leftAxis.setAxisMaximum((float) Y_MAX_MV);
        ecgChart.getAxisRight().setEnabled(false);

        // Reemplazamos el renderer estándar por uno que dibuja papel ECG
        ecgChart.setRenderer(new ECGPaperRenderer(ecgChart, ecgChart.getAnimator(), ecgChart.getViewPortHandler()));
    }

    /**
     * ============================
     * Lectura del archivo ECG
     * ============================
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 100 && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) return;
            try {
                String name = getFileNameFromUri(uri);
                lblArchivo.setText("Archivo: " + name);

                // === Copiar ECG seleccionado a caché ===
                File tmpEcg = new File(getCacheDir(), name);
                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream out = new FileOutputStream(tmpEcg)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                Log.d("ECG_DEBUG", "✅ ECG copiado a caché: " + tmpEcg.getAbsolutePath());
                ecgPath = tmpEcg.getAbsolutePath();

                // === Intentar hallar el .TST ===
                String baseName = name.replace(".ECG", "");
                String tstName = baseName + ".TST";
                tstPath = null;

                // 1️⃣ Intentar abrir en misma ubicación (mismo Uri pero extensión .TST)
                try {
                    String tstUriStr = uri.toString().replace(".ECG", ".TST");
                    Uri tstUri = Uri.parse(tstUriStr);
                    try (InputStream in = getContentResolver().openInputStream(tstUri)) {
                        if (in != null) {
                            File tmpTst = new File(getCacheDir(), tstName);
                            try (OutputStream out = new FileOutputStream(tmpTst)) {
                                byte[] buf = new byte[4096];
                                int n;
                                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                            }
                            tstPath = tmpTst.getAbsolutePath();
                            Log.d("ECG_DEBUG", "✅ Archivo TST abierto por derivación de URI");
                        }
                    }
                } catch (Exception e) {
                    Log.w("ECG_DEBUG", "No se pudo abrir .TST por derivación directa");
                }

                // 2️⃣ Intentar abrir desde carpeta pública “Downloads”
                if (tstPath == null) {
                    File downloads = android.os.Environment
                            .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
                    File altTst = new File(downloads, tstName);
                    if (altTst.exists()) {
                        File tmpTst = new File(getCacheDir(), tstName);
                        try (InputStream in = new FileInputStream(altTst);
                             OutputStream out = new FileOutputStream(tmpTst)) {
                            byte[] buf = new byte[4096];
                            int n;
                            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        }
                        tstPath = tmpTst.getAbsolutePath();
                        Log.d("ECG_DEBUG", "✅ Archivo TST encontrado en /Download");
                    }
                }

                if (tstPath == null)
                    Log.w("ECG_DEBUG", "⚠️ No se encontró archivo TST correspondiente");

                loadFileAndPlot(tmpEcg);

            } catch (Exception e) {
                lblArchivo.setText("❌ Error cargando el archivo ECG");
                Log.e("ECG_DEBUG", "Error cargando ECG", e);
            }
        }
    }




    /**
     * ============================
     * Conversión ADC → mV
     * ============================
     */
    private void loadFileAndPlot(File ecgFile) throws Exception {
        double[] adcRaw = ECGReader.readECGBigEndianUint16(ecgFile, 0);
        signalRaw = new double[adcRaw.length];
        for (int i = 0; i < adcRaw.length; i++) {
            double val = (adcRaw[i] - ADC_MID) / 2048.0;
            signalRaw[i] = val * 1.5;
        }
        durationSec = signalRaw.length / FS;
        signalMA = Filters.movingAverageCentered(signalRaw, MA_N);
        timeIndex = (tstPath != null) ? TstIndex.parse(new File(tstPath)) : null;
        plot(false);
    }

    /**
     * ============================
     * Gráfico MPAndroidChart
     * ============================
     */
    private void plot(boolean useMA) {
        double[] y = useMA ? signalMA : signalRaw;
        ArrayList<Entry> entries = new ArrayList<>();
        float t = 0f, dt = (float) (1.0 / FS);
        for (double v : y) {
            entries.add(new Entry(t, (float) v));
            t += dt;
        }

        LineDataSet dataSet = new LineDataSet(entries, useMA ? "ECG (MA=9)" : "ECG crudo");
        dataSet.setColor(Color.BLACK);
        dataSet.setLineWidth(2f);
        dataSet.setDrawCircles(false);
        dataSet.setDrawValues(false);

        ecgChart.setData(new LineData(dataSet));
        ecgChart.invalidate();

        lblInfo.setText(String.format(Locale.US,
                "Fs=%.0f Hz · Duración=%.1fs · MA=%s",
                FS, durationSec, useMA ? "ON (N=9)" : "OFF"));
    }

    private String getFileNameFromUri(Uri uri) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (android.database.Cursor cursor = getContentResolver()
                    .query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) result = cursor.getString(idx);
                }
            }
        }
        if (result == null) result = uri.getPath();
        if (result == null) return "archivo.ecg";
        int cut = result.lastIndexOf('/');
        return (cut != -1) ? result.substring(cut + 1) : result;
    }

    /**
     * ============================
     * Renderer personalizado tipo papel ECG
     * ============================
     */
    static class ECGPaperRenderer extends LineChartRenderer {
        private final Paint thin, thick, bg;
        private final float smallTime = 0.04f;  // s (cuadro pequeño)
        private final float smallMV = 0.1f;     // mV (cuadro pequeño)

        public ECGPaperRenderer(LineDataProvider chart,
                                com.github.mikephil.charting.animation.ChartAnimator anim,
                                ViewPortHandler viewPortHandler) {
            super(chart, anim, viewPortHandler);
            thin = new Paint();
            thin.setColor(Color.rgb(255, 210, 210));
            thin.setStrokeWidth(1f);
            thick = new Paint();
            thick.setColor(Color.rgb(255, 120, 120));
            thick.setStrokeWidth(2f);
            bg = new Paint();
            bg.setColor(Color.rgb(255, 245, 245));
            bg.setStyle(Paint.Style.FILL);
        }

        @Override
        public void drawData(Canvas c) {
            // Fondo rosa tipo papel ECG
            c.drawRect(mViewPortHandler.getContentRect(), bg);

            float xMin = mChart.getXChartMin();
            float xMax = mChart.getXChartMax();
            float yMin = mChart.getYChartMin();
            float yMax = mChart.getYChartMax();

            // Cuadros verticales (tiempo)
            for (float x = xMin; x <= xMax; x += smallTime) {
                float[] pts = new float[]{x, 0f};
                mChart.getTransformer(YAxis.AxisDependency.LEFT).pointValuesToPixel(pts);
                Paint p = (Math.abs((x / smallTime) % 5) < 0.001) ? thick : thin;
                c.drawLine(pts[0], mViewPortHandler.contentTop(), pts[0], mViewPortHandler.contentBottom(), p);
            }

            // Cuadros horizontales (amplitud)
            for (float y = yMin; y <= yMax; y += smallMV) {
                float[] pts = new float[]{0f, y};
                mChart.getTransformer(YAxis.AxisDependency.LEFT).pointValuesToPixel(pts);
                Paint p = (Math.abs((y / smallMV) % 5) < 0.001) ? thick : thin;
                c.drawLine(mViewPortHandler.contentLeft(), pts[1], mViewPortHandler.contentRight(), pts[1], p);
            }

            super.drawData(c);
        }
    }

    /**
     * ============================
     * Parser .TST (timestamps)
     * ============================
     */
    static class TstIndex {
        final TreeMap<Integer, Long> blockToMillis = new TreeMap<>();
        final int blockSize = 1024;
        String startDisplay = null;

        static TstIndex parse(File f) {
            TstIndex ti = new TstIndex();
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    if (line.startsWith("OPEN,")) {
                        String ts = line.substring(5).trim();
                        Date d = fmt.parse(ts);
                        if (d != null) {
                            ti.blockToMillis.put(0, d.getTime());
                            ti.startDisplay = ts;
                        }
                    } else {
                        String[] parts = line.split(",");
                        if (parts.length == 2) {
                            int blk = Integer.parseInt(parts[0]);
                            Date d = fmt.parse(parts[1]);
                            if (d != null) ti.blockToMillis.put(blk, d.getTime());
                        }
                    }
                }
            } catch (Exception ignored) {
            }
            return ti;
        }

        String sampleToDateTimeString(int sampleIndex) {
            if (blockToMillis.isEmpty()) return null;
            int blk = sampleIndex / blockSize;
            Map.Entry<Integer, Long> e = blockToMillis.floorEntry(blk);
            if (e == null) e = blockToMillis.firstEntry();
            int blk0 = e.getKey();
            long t0 = e.getValue();
            double seconds = (blk - blk0) * (blockSize / FS)
                    + (sampleIndex - blk * blockSize) / FS;
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(t0 + (long) (seconds * 1000.0));
            return DateFormat.format("yyyy-MM-dd HH:mm:ss", cal).toString();
        }
    }
}
