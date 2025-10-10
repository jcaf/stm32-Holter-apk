package com.tuapp.ecg;

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
import android.widget.Toast;

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
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.components.AxisBase;
import com.github.mikephil.charting.formatter.ValueFormatter;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;

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
        lblInfo    = findViewById(R.id.lblInfo);
        ecgChart   = findViewById(R.id.ecgChart);
        chkMA      = findViewById(R.id.chkMA);
        btnReporte = findViewById(R.id.btnReporte);

        Log.d("ECG_DEBUG", "=== INICIANDO DiagnosticoActivity ===");

//        Button btnSeleccionar = new Button(this);
//        btnSeleccionar.setText("Seleccionar archivo ECG");
//        ((android.widget.LinearLayout) findViewById(R.id.rootLayout)).addView(btnSeleccionar, 0);
//
        Button btnSeleccionar = findViewById(R.id.btnSeleccionarArchivo);


        btnSeleccionar.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(intent, "Selecciona archivo .ECG"), 100);
        });

        chkMA.setOnCheckedChangeListener((v, checked) -> {
            if (signalRaw != null) plot(checked);
        });

        btnReporte.setOnClickListener(v -> {
            if (signalRaw == null) {
                Toast.makeText(this, "Primero selecciona un archivo ECG.", Toast.LENGTH_SHORT).show();
                return;
            }

            // === 1️⃣ Detectar picos R (nuevo detector robusto) ===
            RPeakDetector.Result resultR = RPeakDetector.detectR(signalRaw, FS);

            // === 2️⃣ Leer hora de inicio desde el archivo .TST (si existe) ===
            String horaInicioTST = null;
            try {
                if (tstPath != null) {
                    Log.d("ECG_DEBUG", "Leyendo hora desde: " + tstPath);
                    try (BufferedReader br = new BufferedReader(new FileReader(tstPath))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            if (line.startsWith("OPEN,")) {
                                horaInicioTST = line.substring(5).trim(); // ej. "2025-10-06 10:27:11"
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

            // === 3️⃣ Construir el reporte base ===
            Metrics.Report rep = Metrics.buildReport(
                    resultR,
                    null,                                   // no usamos lista de timestamps explícita
                    new File(ecgPath).getName(),            // nombre del archivo ECG
                    durationSec,
                    horaInicioTST                           // hora base tomada del .TST
            );

            // === 4️⃣ Si hay índice temporal (.TST), asociar timestamps reales ===
            if (timeIndex != null) {
                for (Metrics.Event e : rep.topHigh) {
                    e.timestamp = timeIndex.sampleToDateTimeString(
                            (int) Math.round(e.timeSec * FS)
                    );
                }
                for (Metrics.Event e : rep.topLow) {
                    e.timestamp = timeIndex.sampleToDateTimeString(
                            (int) Math.round(e.timeSec * FS)
                    );
                }
                rep.studyStart = timeIndex.startDisplay;
                Log.d("ECG_DEBUG", "Hora de inicio del TST: " + rep.studyStart);
            }

            // === 5️⃣ Guardar temporalmente el reporte en la caché ===
            String key = "rep_" + System.currentTimeMillis();
            ReportCache.put(key, rep);

            // === 6️⃣ Lanzar ReportActivity con la clave ===
            Intent intent = new Intent(DiagnosticoActivity.this, ReportActivity.class);
            intent.putExtra("reportKey", key);
            startActivity(intent);
        });


        setupChartAesthetics();
    }

    /** ============================
     *   Apariencia inicial
     *  ============================ */
    private void setupChartAesthetics() {
        ecgChart.setNoDataText("Selecciona un archivo ECG para visualizar.");
        ecgChart.setBackgroundColor(Color.rgb(255, 245, 245)); // papel ECG
        ecgChart.setDrawGridBackground(false);
        ecgChart.setTouchEnabled(true);
        ecgChart.setPinchZoom(true);
        ecgChart.getDescription().setEnabled(false);

        // === EJE X: tiempo (s)
        XAxis xAxis = ecgChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setDrawGridLines(true);
        xAxis.setGranularity(0.04f);
        xAxis.setGranularityEnabled(true);
        xAxis.setTextColor(Color.BLACK);
        xAxis.setAxisLineColor(Color.RED);
        xAxis.setAxisLineWidth(1.5f);
        xAxis.setValueFormatter(new ValueFormatter() {
            @Override
            public String getAxisLabel(float value, AxisBase axis) {
                return String.format(Locale.US, "%.1f", value);
            }
        });
        xAxis.setLabelCount(6, true);
        xAxis.setDrawAxisLine(true);
        xAxis.setDrawLabels(true);

// === EJE Y: amplitud (mV)
        YAxis leftAxis = ecgChart.getAxisLeft();
        leftAxis.setDrawGridLines(true);
        leftAxis.setGranularity(0.1f);
        leftAxis.setGranularityEnabled(true);
        leftAxis.setTextColor(Color.BLACK);
        leftAxis.setAxisLineColor(Color.RED);
        leftAxis.setAxisLineWidth(1.5f);
        leftAxis.setAxisMinimum((float) Y_MIN_MV);
        leftAxis.setAxisMaximum((float) Y_MAX_MV);
        leftAxis.setValueFormatter(new ValueFormatter() {
            @Override
            public String getAxisLabel(float value, AxisBase axis) {
                return String.format(Locale.US, "%.1f", value);
            }
        });
        leftAxis.setLabelCount(6, true);
        leftAxis.setDrawAxisLine(true);
        leftAxis.setDrawLabels(true);

// Desactiva eje derecho (no se usa en ECG)
        ecgChart.getAxisRight().setEnabled(false);


        // === Render personalizado: papel ECG ===
        ecgChart.setRenderer(new ECGPaperRenderer(ecgChart, ecgChart.getAnimator(), ecgChart.getViewPortHandler()));

        // Etiquetas descriptivas visibles
        ecgChart.getDescription().setEnabled(true);
        ecgChart.getDescription().setText("Tiempo (s)   |   Amplitud (mV)");
        ecgChart.getDescription().setTextColor(Color.DKGRAY);
        ecgChart.getDescription().setTextSize(12f);
    }


    /** ============================
     *   Lectura del archivo ECG
     *  ============================ */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 100 && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) return;
            try {
                String name = getFileNameFromUri(uri);
                lblArchivo.setText("Archivo: " + name);
                File tmpEcg = new File(getCacheDir(), name);
                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream out = new FileOutputStream(tmpEcg)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                ecgPath = tmpEcg.getAbsolutePath();

                // Intentamos también localizar/copiar el .TST (si el usuario seleccionó desde una carpeta que permita)
                File tstCandidate = new File(tmpEcg.getParent(), name.replace(".ECG", ".TST"));
                if (tstCandidate.exists()) {
                    tstPath = tstCandidate.getAbsolutePath();
                    Log.d("ECG_DEBUG", "✅ .TST encontrado en caché: " + tstPath);
                } else {
                    // Si no está en caché, dejamos tstPath = null — tu flujo puede copiar .TST manualmente si procede.
                    tstPath = null;
                    Log.w("ECG_DEBUG", "⚠️ No se encontró archivo TST correspondiente");
                }

                loadFileAndPlot(tmpEcg);
            } catch (Exception e) {
                lblArchivo.setText("❌ Error cargando el archivo ECG");
                Log.e("ECG_DEBUG", "Error cargando ECG", e);
            }
        }
    }

    /** ============================
     *   Conversión ADC → mV
     *  ============================ */
    private void loadFileAndPlot(File ecgFile) throws Exception {
        Log.d("ECG_DEBUG", "Leyendo archivo ECG: " + ecgFile.getAbsolutePath());
        double[] adcRaw = ECGReader.readECGBigEndianUint16(ecgFile, 0);
        signalRaw = new double[adcRaw.length];
        for (int i = 0; i < adcRaw.length; i++) {
            double val = (adcRaw[i] - ADC_MID) / 2048.0;
            signalRaw[i] = val * 1.5;
        }
        durationSec = signalRaw.length / FS;
        signalMA = Filters.movingAverageCentered(signalRaw, MA_N);

        // parsear .TST desde la copia en caché (si existe)
        timeIndex = (tstPath != null) ? TstIndex.parse(new File(tstPath)) : null;
        if (timeIndex != null) Log.d("ECG_DEBUG", "Hora de inicio del TST: " + timeIndex.startDisplay);
        plot(false);
    }

    /** ============================
     *   Gráfico MPAndroidChart
     *  ============================ */
    private void plot(boolean useMA) {
        double[] y = useMA ? signalMA : signalRaw;
        ArrayList<Entry> entries = new ArrayList<>();
        float t = 0f, dt = (float)(1.0 / FS);
        for (double v : y) { entries.add(new Entry(t, (float) v)); t += dt; }

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
     *  ============================
     */
    static class ECGPaperRenderer extends LineChartRenderer {
        private final Paint thin, thick, bg;
        private final float smallTime = 0.04f;  // 1 cuadro pequeño = 0.04 s
        private final float bigTime   = 0.20f;  // 1 cuadro grande = 0.20 s
        private final float smallMV   = 0.1f;   // 1 cuadro pequeño = 0.1 mV
        private final float bigMV     = 0.5f;   // 1 cuadro grande = 0.5 mV

        public ECGPaperRenderer(LineDataProvider chart,
                                com.github.mikephil.charting.animation.ChartAnimator anim,
                                ViewPortHandler viewPortHandler) {
            super(chart, anim, viewPortHandler);

            thin = new Paint();
            thin.setColor(Color.rgb(255, 210, 210)); // línea fina
            thin.setStrokeWidth(1f);

            thick = new Paint();
            thick.setColor(Color.rgb(255, 120, 120)); // línea gruesa
            thick.setStrokeWidth(2f);

            bg = new Paint();
            bg.setColor(Color.rgb(255, 245, 245)); // fondo
            bg.setStyle(Paint.Style.FILL);
        }

        @Override
        public void drawData(Canvas c) {
            // Fondo ECG
            c.drawRect(mViewPortHandler.getContentRect(), bg);

            // CAST al LineChart para acceder a métodos visuales
            LineChart chart = (LineChart) mChart;

            // Rango visible actual
            float xMin = chart.getLowestVisibleX();
            float xMax = chart.getHighestVisibleX();

            YAxis yAxis = chart.getAxisLeft();
            float yMin = yAxis.mAxisMinimum;
            float yMax = yAxis.mAxisMaximum;

            final com.github.mikephil.charting.utils.Transformer trans =
                    chart.getTransformer(YAxis.AxisDependency.LEFT);

            float[] pts = new float[2];

            // --- Cuadros verticales (tiempo) usando índice entero para evitar drift ---
            float startX = (float) Math.floor(xMin / smallTime) * smallTime;
            int nX = (int) Math.ceil((xMax - startX) / smallTime);
            for (int i = 0; i <= nX; i++) {
                float x = startX + i * smallTime;
                pts[0] = x; pts[1] = yMin;               // transforma un punto cualquiera con ese X
                trans.pointValuesToPixel(pts);
                float xPix = pts[0];
                boolean major = (i % 5 == 0);            // cada 5 cuadros pequeños = 0.20s
                c.drawLine(xPix, mViewPortHandler.contentTop(), xPix, mViewPortHandler.contentBottom(),
                        major ? thick : thin);
            }

            // --- Cuadros horizontales (amplitud) usando índice entero también ---
            float startY = (float) Math.floor(yMin / smallMV) * smallMV;
            int nY = (int) Math.ceil((yMax - startY) / smallMV);
            for (int j = 0; j <= nY; j++) {
                float y = startY + j * smallMV;
                pts[0] = xMin; pts[1] = y;
                trans.pointValuesToPixel(pts);
                float yPix = pts[1];
                boolean major = (j % 5 == 0);            // cada 5 cuadros pequeños = 0.5mV
                c.drawLine(mViewPortHandler.contentLeft(), yPix, mViewPortHandler.contentRight(), yPix,
                        major ? thick : thin);
            }

            // Dibuja la señal ECG encima
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
            double FS = DiagnosticoActivity.FS;
            double seconds = (blk - blk0) * (blockSize / FS)
                    + (sampleIndex - blk * blockSize) / FS;
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(t0 + (long) (seconds * 1000.0));
            return DateFormat.format("yyyy-MM-dd HH:mm:ss", cal).toString();
        }
    }
}
