package com.tuapp.ecg;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.util.Log;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.interfaces.dataprovider.LineDataProvider;
import com.github.mikephil.charting.listener.ChartTouchListener;
import com.github.mikephil.charting.listener.OnChartGestureListener;
import com.github.mikephil.charting.renderer.LineChartRenderer;
import com.github.mikephil.charting.utils.ViewPortHandler;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public class DiagnosticoActivity extends AppCompatActivity {

    // ==== CONST ====
    public static final double FS = 500.0;
    private static final int MA_N = 9;
    private static final double Y_MIN_MV = -1.5;
    private static final double Y_MAX_MV = +1.5;
    private static final int ADC_MID = 2048;

    // ==== UI ====
    TextView lblArchivo, lblInfo;
    LineChart ecgChart;
    CheckBox chkMA;
    Button btnReporte;
    SeekBar scrollBar, zoomBar;

    // ==== DATA ====
    String ecgPath, tstPath;
    double[] signalRaw, signalMA;
    double durationSec;
    TstIndex timeIndex;

    // ==== STATE ====
    private float savedLowestX = 0f, savedScaleX = 1f, savedScaleY = 1f;
    private boolean userScrollingBar = false;
    private boolean isAdjustingZoom = false;

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
        scrollBar  = findViewById(R.id.scrollBar);
        zoomBar    = findViewById(R.id.zoomBar);

        Log.d("ECG_DEBUG", "=== INICIANDO DiagnosticoActivity ===");

        // Botón dinámico (opcional) para seleccionar ECG
        LinearLayout root = findViewById(R.id.rootLayout);
        if (root != null) {
            Button btnSeleccionar = new Button(this);
            btnSeleccionar.setText("Seleccionar archivo ECG");
            root.addView(btnSeleccionar, 0);
            btnSeleccionar.setOnClickListener(v -> {
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("*/*");
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(Intent.createChooser(intent, "Selecciona archivo .ECG"), 100);
            });
        }

        chkMA.setOnCheckedChangeListener((v, checked) -> {
            if (signalRaw != null) plot(checked);
        });

        btnReporte.setOnClickListener(v -> generarReporte());

        setupChartAesthetics();
        setupScrollBar();
        setupZoomBar();
    }

    // ====== Chart & Gestos ======
    private void setupChartAesthetics() {
        ecgChart.setNoDataText("Selecciona un archivo ECG para visualizar.");
        ecgChart.setBackgroundColor(Color.rgb(255, 245, 245));
        ecgChart.setDrawGridBackground(false);
        ecgChart.setTouchEnabled(true);
        ecgChart.setDragEnabled(true);
        ecgChart.setScaleEnabled(true);
        ecgChart.setScaleXEnabled(true);
        ecgChart.setScaleYEnabled(true);
        ecgChart.setPinchZoom(true);
        ecgChart.setDoubleTapToZoomEnabled(true);
        ecgChart.setAutoScaleMinMaxEnabled(true);

        XAxis xAxis = ecgChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setTextColor(Color.DKGRAY);
        xAxis.setDrawGridLines(false);

        YAxis leftAxis = ecgChart.getAxisLeft();
        leftAxis.setTextColor(Color.DKGRAY);
        leftAxis.setDrawGridLines(false);
        leftAxis.setAxisMinimum((float)(Y_MIN_MV - 0.2f));
        leftAxis.setAxisMaximum((float)(Y_MAX_MV + 0.2f));
        ecgChart.getAxisRight().setEnabled(false);

        ecgChart.setRenderer(new ECGPaperRenderer(ecgChart, ecgChart.getAnimator(), ecgChart.getViewPortHandler()));

        // Sincronizar barra de scroll con gesto táctil
        ecgChart.setOnChartGestureListener(new OnChartGestureListener() {
            @Override public void onChartGestureStart(android.view.MotionEvent me, ChartTouchListener.ChartGesture g) {}
            @Override public void onChartGestureEnd(android.view.MotionEvent me, ChartTouchListener.ChartGesture g) {}
            @Override public void onChartLongPressed(android.view.MotionEvent me) {}
            @Override public void onChartDoubleTapped(android.view.MotionEvent me) {}
            @Override public void onChartSingleTapped(android.view.MotionEvent me) {}
            @Override public void onChartFling(android.view.MotionEvent me1, android.view.MotionEvent me2, float vx, float vy) {}
            @Override public void onChartScale(android.view.MotionEvent me, float sx, float sy) {}
            @Override
            public void onChartTranslate(android.view.MotionEvent me, float dX, float dY) {
                if (!userScrollingBar && !isAdjustingZoom && ecgChart.getData() != null && scrollBar != null) {
                    float totalRange = ecgChart.getData().getXMax(); // X arranca en 0
                    float visibleMin = ecgChart.getLowestVisibleX();
                    float visibleRange = ecgChart.getVisibleXRange();
                    float denom = (totalRange - visibleRange);
                    if (denom > 0) {
                        float progress = (visibleMin / denom) * 100f;
                        scrollBar.setProgress((int)Math.max(0, Math.min(100, progress)));
                    } else {
                        scrollBar.setProgress(0);
                    }
                }
            }
        });
    }

    // ====== Barra de Scroll (gris) ======
    private void setupScrollBar() {
        if (scrollBar == null) return;
        scrollBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar s) { userScrollingBar = true; }
            @Override public void onStopTrackingTouch(SeekBar s) { userScrollingBar = false; }
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (!fromUser || ecgChart.getData() == null) return;
                float totalRange = ecgChart.getData().getXMax();
                float visibleRange = ecgChart.getVisibleXRange();
                float denom = (totalRange - visibleRange);
                if (denom > 0) {
                    float targetX = (progress / 100f) * denom;
                    ecgChart.moveViewToX(targetX);
                } else {
                    ecgChart.moveViewToX(0f);
                }
            }
        });
    }

    // ====== Barra de Zoom (azul) — solo eje X ======
    private void setupZoomBar() {
        if (zoomBar == null || ecgChart == null) return;

        zoomBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar s) { isAdjustingZoom = true; }
            @Override public void onStopTrackingTouch(SeekBar s) { isAdjustingZoom = false; }

            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (!fromUser || ecgChart.getData() == null) return;

                isAdjustingZoom = true;

                // Ventana deseada (segundos)
                float zoomMin = 1f;   // más cerca: 1 s visible
                float zoomMax = 10f;  // más lejos: 10 s visibles
                float rango   = zoomMax - zoomMin;
                float ventana = zoomMax - (progress / 100f) * rango; // [1..10] s

                float totalRange = ecgChart.getData().getXMax(); // X arranca en 0
                if (ventana > totalRange) ventana = Math.max(0.5f, totalRange);
                ventana = Math.max(0.5f, ventana);

                // Escala actual y deseada
                float currentVisible = ecgChart.getVisibleXRange();
                if (currentVisible <= 0f) currentVisible = ventana;
                float currentScaleX = ecgChart.getViewPortHandler().getScaleX();
                if (currentScaleX <= 0f) currentScaleX = totalRange / currentVisible;

                float desiredScaleX = (ventana > 0f) ? (totalRange / ventana) : currentScaleX;
                float factorX = desiredScaleX / currentScaleX;

                if (factorX > 0f && !Float.isInfinite(factorX) && !Float.isNaN(factorX)) {
                    ViewPortHandler h = ecgChart.getViewPortHandler();
                    float px = h.contentLeft() + h.contentWidth() / 2f;
                    float py = h.contentTop() + h.contentHeight() / 2f;
                    ecgChart.zoom(factorX, 1f, px, py);
                }

                // Límites permisibles (para gestos)
                ecgChart.setVisibleXRangeMinimum(0.5f);
                ecgChart.setVisibleXRangeMaximum(zoomMax);

                // Re-sincronizar barra de scroll tras el zoom
                float visibleMin = ecgChart.getLowestVisibleX();
                float visibleRange = ecgChart.getVisibleXRange();
                float denom = (totalRange - visibleRange);
                if (scrollBar != null) {
                    if (denom > 0) {
                        float p = (visibleMin / denom) * 100f;
                        scrollBar.setProgress((int)Math.max(0, Math.min(100, p)));
                    } else {
                        scrollBar.setProgress(0);
                    }
                }

                ecgChart.invalidate();
                isAdjustingZoom = false;
            }
        });
    }

    // ====== LECTURA Y PLOTEO ======
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

    // ====== Plot ======
    private void plot(boolean useMA) {
        double[] y = useMA ? signalMA : signalRaw;
        ArrayList<Entry> entries = new ArrayList<>();
        float t = 0f, dt = (float)(1.0 / FS);
        for (double v : y) { entries.add(new Entry(t, (float)v)); t += dt; }

        LineDataSet dataSet = new LineDataSet(entries, useMA ? "ECG (MA=9)" : "ECG crudo");
        dataSet.setColor(Color.BLACK);
        dataSet.setLineWidth(2f);
        dataSet.setDrawCircles(false);
        dataSet.setDrawValues(false);

        ecgChart.setData(new LineData(dataSet));

        // Ventana inicial + estado barras
        ecgChart.setVisibleXRangeMaximum(5f);
        ecgChart.setVisibleXRangeMinimum(0.5f);
        ecgChart.moveViewToX(0f);

        if (scrollBar != null) scrollBar.setProgress(0);
        if (zoomBar != null)  zoomBar.setProgress(50);

        lblInfo.setText(String.format(Locale.US,
                "Fs=%.0f Hz · Duración=%.1fs · MA=%s",
                FS, durationSec, useMA ? "ON (N=9)" : "OFF"));

        ecgChart.invalidate();
    }

    private void generarReporte() {
        // Deja tu implementación original aquí si ya la tenías.
        Toast.makeText(this, "Generando reporte...", Toast.LENGTH_SHORT).show();
    }

    // ====== Render Papel ECG ======
    static class ECGPaperRenderer extends LineChartRenderer {
        private final Paint thin, thick, bg;
        private final float smallTime = 0.04f; // 40 ms
        private final float smallMV   = 0.1f;  // 0.1 mV

        public ECGPaperRenderer(LineDataProvider chart,
                                com.github.mikephil.charting.animation.ChartAnimator anim,
                                ViewPortHandler handler) {
            super(chart, anim, handler);
            thin = new Paint();  thin.setColor(Color.rgb(255,210,210)); thin.setStrokeWidth(1f);
            thick = new Paint(); thick.setColor(Color.rgb(255,120,120)); thick.setStrokeWidth(2f);
            bg = new Paint();    bg.setColor(Color.rgb(255,245,245));   bg.setStyle(Paint.Style.FILL);
        }

        @Override
        public void drawData(Canvas c) {
            c.drawRect(mViewPortHandler.getContentRect(), bg);

            float xMin = mChart.getXChartMin(), xMax = mChart.getXChartMax();
            float yMin = mChart.getYChartMin(), yMax = mChart.getYChartMax();

            // Verticales (tiempo)
            for (float x = xMin; x <= xMax; x += smallTime) {
                float[] pts = new float[]{x, 0f};
                mChart.getTransformer(YAxis.AxisDependency.LEFT).pointValuesToPixel(pts);
                Paint p = (Math.abs((x / smallTime) % 5) < 0.001) ? thick : thin;
                c.drawLine(pts[0], mViewPortHandler.contentTop(), pts[0], mViewPortHandler.contentBottom(), p);
            }
            // Horizontales (mV)
            for (float y = yMin; y <= yMax; y += smallMV) {
                float[] pts = new float[]{0f, y};
                mChart.getTransformer(YAxis.AxisDependency.LEFT).pointValuesToPixel(pts);
                Paint p = (Math.abs((y / smallMV) % 5) < 0.001) ? thick : thin;
                c.drawLine(mViewPortHandler.contentLeft(), pts[1], mViewPortHandler.contentRight(), pts[1], p);
            }

            super.drawData(c);
        }
    }

    // ====== .TST (timestamps por bloques) ======
    static class TstIndex {
        final TreeMap<Integer, Long> blockToMillis = new TreeMap<>();
        final int blockSize = 1024;
        String startDisplay = null;

        static TstIndex parse(File f) {
            // Si ya tenías un parser funcional, reintégralo aquí.
            // Placeholder mínimo para no romper la lógica:
            TstIndex ti = new TstIndex();
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("OPEN,")) {
                        String ts = line.substring(5).trim();
                        Date d = fmt.parse(ts);
                        if (d != null) {
                            ti.blockToMillis.put(0, d.getTime());
                            ti.startDisplay = ts;
                        }
                    }
                    // puedes completar más si tu .TST tiene índices por bloque
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

    // ====== Guardar/restaurar vista (rotación, etc.) ======
    @Override
    protected void onPause() {
        super.onPause();
        if (ecgChart != null) {
            savedLowestX = ecgChart.getLowestVisibleX();
            savedScaleX  = ecgChart.getViewPortHandler().getScaleX();
            savedScaleY  = ecgChart.getViewPortHandler().getScaleY();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ecgChart != null && ecgChart.getData() != null) {
            ViewPortHandler h = ecgChart.getViewPortHandler();
            float px = h.contentLeft() + h.contentWidth() / 2f;
            float py = h.contentTop() + h.contentHeight() / 2f;

            float curScaleX = h.getScaleX();
            float curScaleY = h.getScaleY();
            if (curScaleX > 0f && curScaleY > 0f) {
                ecgChart.zoom(savedScaleX / curScaleX, savedScaleY / curScaleY, px, py);
            }
            ecgChart.moveViewToX(savedLowestX);
            ecgChart.invalidate();
        }
    }
}
