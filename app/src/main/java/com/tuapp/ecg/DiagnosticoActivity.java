package com.tuapp.ecg;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.widget.*;
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
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.*;

public class DiagnosticoActivity extends AppCompatActivity {

    public static final double FS = 500.0;  // Frecuencia de muestreo
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

        // === BOTÓN PARA SELECCIONAR ARCHIVO ECG ===
        Button btnSeleccionar = new Button(this);
        btnSeleccionar.setText("Seleccionar archivo ECG");
        ((LinearLayout) findViewById(R.id.rootLayout)).addView(btnSeleccionar, 0);

        btnSeleccionar.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(intent, "Selecciona archivo .ECG"), 100);
        });

        chkMA.setOnCheckedChangeListener((v, checked) -> {
            if (signalRaw != null) plot(checked);
        });

        btnReporte.setOnClickListener(v -> generarReporte());

        // === CONFIGURACIÓN VISUAL BASE ===
        setupChartAesthetics();

        // === CONFIGURACIÓN DE INTERACCIÓN Y SCROLL ===
        ecgChart.setTouchEnabled(true);
        ecgChart.setDragEnabled(true);
        ecgChart.setScaleEnabled(true);
        ecgChart.setScaleXEnabled(true);
        ecgChart.setScaleYEnabled(true);
        ecgChart.setPinchZoom(true);
        ecgChart.setDoubleTapToZoomEnabled(true);
        ecgChart.setAutoScaleMinMaxEnabled(true);
        ecgChart.setVerticalScrollBarEnabled(true);
        ecgChart.setHorizontalScrollBarEnabled(true);
        ecgChart.setScrollbarFadingEnabled(false);

        // No hay ScrollView externo — el gráfico maneja sus propios gestos
        ecgChart.setOnTouchListener((v, event) -> false);
    }


    /** Configuración visual del gráfico ECG */
    private void setupChartAesthetics() {
        ecgChart.setNoDataText("Selecciona un archivo ECG para visualizar.");
        ecgChart.setBackgroundColor(Color.rgb(255, 245, 245)); // Fondo tipo papel ECG
        ecgChart.setDrawGridBackground(false);
        ecgChart.setTouchEnabled(true);
        ecgChart.setDragEnabled(true);
        ecgChart.setScaleEnabled(true);
        ecgChart.setScaleXEnabled(true);   // 🔹 nuevo: zoom horizontal independiente
        ecgChart.setScaleYEnabled(true);   // 🔹 nuevo: zoom vertical independiente
        ecgChart.setPinchZoom(true);
        ecgChart.setDoubleTapToZoomEnabled(true);
        ecgChart.setAutoScaleMinMaxEnabled(true);
        ecgChart.setHorizontalScrollBarEnabled(true);
        ecgChart.setVerticalScrollBarEnabled(true);
        ecgChart.setScrollbarFadingEnabled(false);   // 🔹 nuevo
        ecgChart.getDescription().setEnabled(false);


        XAxis xAxis = ecgChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setTextColor(Color.DKGRAY);
        xAxis.setDrawGridLines(false);

        YAxis leftAxis = ecgChart.getAxisLeft();
        leftAxis.setTextColor(Color.DKGRAY);
        leftAxis.setDrawGridLines(false);
        leftAxis.setAxisMinimum((float) (Y_MIN_MV - 0.2f));  // margen inferior
        leftAxis.setAxisMaximum((float) (Y_MAX_MV + 0.2f));  // margen superior

        ecgChart.getAxisRight().setEnabled(false);

        ecgChart.setRenderer(new ECGPaperRenderer(ecgChart, ecgChart.getAnimator(), ecgChart.getViewPortHandler()));
    }

    /** Proceso de reporte (igual que antes) */
    private void generarReporte() {
        if (signalRaw == null) {
            Toast.makeText(this, "Primero selecciona un archivo ECG.", Toast.LENGTH_SHORT).show();
            return;
        }

        RPeakDetector.Result resultR = RPeakDetector.detectR(signalRaw, FS);
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

        Metrics.Report rep = Metrics.buildReport(resultR, null,
                new File(ecgPath).getName(), durationSec, horaInicioTST);

        if (timeIndex != null) {
            for (Metrics.Event e : rep.topHigh)
                e.timestamp = timeIndex.sampleToDateTimeString((int) Math.round(e.timeSec * FS));
            for (Metrics.Event e : rep.topLow)
                e.timestamp = timeIndex.sampleToDateTimeString((int) Math.round(e.timeSec * FS));
            rep.studyStart = timeIndex.startDisplay;
        }

        String key = "rep_" + System.currentTimeMillis();
        ReportCache.put(key, rep);

        Intent intent = new Intent(this, ReportActivity.class);
        intent.putExtra("reportKey", key);
        startActivity(intent);
    }

    /** Lectura del archivo ECG y ploteo */
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

                File tstCandidate = new File(tmpEcg.getParent(), name.replace(".ECG", ".TST"));
                tstPath = tstCandidate.exists() ? tstCandidate.getAbsolutePath() : null;

                loadFileAndPlot(tmpEcg);
            } catch (Exception e) {
                lblArchivo.setText("❌ Error cargando el archivo ECG");
                Log.e("ECG_DEBUG", "Error cargando ECG", e);
            }
        }
    }

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
        timeIndex = (tstPath != null) ? TstIndex.parse(new File(tstPath)) : null;
        plot(false);
    }

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

// Configuración de scroll y zoom independientes
        float totalSegundos = (float) (y.length / FS);
        float ventanaInicial = 5f;   // muestra 5 s por defecto
        ecgChart.setVisibleXRangeMaximum(ventanaInicial);
        ecgChart.setVisibleXRangeMinimum(0.5f);

// Asegura desplazamiento horizontal completo
        ecgChart.moveViewToX(0f);

// Permite escalar el eje Y libremente
        ecgChart.getAxisLeft().setAxisMinimum((float) Y_MIN_MV);
        ecgChart.getAxisLeft().setAxisMaximum((float) Y_MAX_MV);
        ecgChart.setAutoScaleMinMaxEnabled(true);

// Refresca el gráfico
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

    /** Renderizado con grilla ECG + etiqueta “Amplitud (mV)” */
    static class ECGPaperRenderer extends LineChartRenderer {
        private final Paint thin, thick, bg, label;
        private final float smallTime = 0.04f;
        private final float smallMV = 0.1f;

        public ECGPaperRenderer(LineDataProvider chart,
                                com.github.mikephil.charting.animation.ChartAnimator anim,
                                ViewPortHandler viewPortHandler) {
            super(chart, anim, viewPortHandler);
            thin = new Paint(); thin.setColor(Color.rgb(255,210,210)); thin.setStrokeWidth(1f);
            thick = new Paint(); thick.setColor(Color.rgb(255,120,120)); thick.setStrokeWidth(2f);
            bg = new Paint(); bg.setColor(Color.rgb(255,245,245)); bg.setStyle(Paint.Style.FILL);
            label = new Paint(); label.setColor(Color.DKGRAY); label.setTextSize(30f); label.setAntiAlias(true);
        }

        @Override
        public void drawData(Canvas c) {
            c.drawRect(mViewPortHandler.getContentRect(), bg);
            float xMin = mChart.getXChartMin(), xMax = mChart.getXChartMax();
            float yMin = mChart.getYChartMin(), yMax = mChart.getYChartMax();

            for (float x = xMin; x <= xMax; x += smallTime) {
                float[] pts = new float[]{x, 0f};
                mChart.getTransformer(YAxis.AxisDependency.LEFT).pointValuesToPixel(pts);
                Paint p = (Math.abs((x / smallTime) % 5) < 0.001) ? thick : thin;
                c.drawLine(pts[0], mViewPortHandler.contentTop(), pts[0], mViewPortHandler.contentBottom(), p);
            }
            for (float y = yMin; y <= yMax; y += smallMV) {
                float[] pts = new float[]{0f, y};
                mChart.getTransformer(YAxis.AxisDependency.LEFT).pointValuesToPixel(pts);
                Paint p = (Math.abs((y / smallMV) % 5) < 0.001) ? thick : thin;
                c.drawLine(mViewPortHandler.contentLeft(), pts[1], mViewPortHandler.contentRight(), pts[1], p);
            }

            // Etiqueta “Amplitud (mV)” fuera del grid, rotada
            c.save();
            c.rotate(-90, mViewPortHandler.contentLeft() - 50, mViewPortHandler.contentTop());
            c.drawText("Amplitud (mV)", mViewPortHandler.contentLeft() - 70,
                    mViewPortHandler.contentTop() + 100, label);
            c.restore();

            super.drawData(c);
        }
    }

    /** Parser del .TST */
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
            } catch (Exception ignored) {}
            return ti;
        }

        String sampleToDateTimeString(int sampleIndex) {
            if (blockToMillis.isEmpty()) return null;
            int blk = (sampleIndex / blockSize) * blockSize;
            Map.Entry<Integer, Long> e = blockToMillis.floorEntry(blk);
            if (e == null) e = blockToMillis.firstEntry();
            long baseMillis = e.getValue();
            int delta = sampleIndex - e.getKey();
            long t = baseMillis + (long)((delta / FS) * 1000.0);
            return DateFormat.format("HH:mm:ss", new Date(t)).toString();
        }
    }
    // === GUARDAR Y RESTAURAR POSICIÓN AL ROTAR ===
    private float savedLowestX = 0f, savedScaleX = 1f, savedScaleY = 1f;

    @Override
    protected void onPause() {
        super.onPause();
        if (ecgChart != null) {
            savedLowestX = ecgChart.getLowestVisibleX();
            savedScaleX = ecgChart.getViewPortHandler().getScaleX();
            savedScaleY = ecgChart.getViewPortHandler().getScaleY();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ecgChart != null && ecgChart.getData() != null) {
            ecgChart.fitScreen();
            ecgChart.zoom(savedScaleX, savedScaleY, 0f, 0f);
            ecgChart.moveViewToX(savedLowestX);
        }
    }
}  // <-- fin de la clase DiagnosticoActivity
