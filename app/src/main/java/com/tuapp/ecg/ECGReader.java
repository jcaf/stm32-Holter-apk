package com.tuapp.ecg;

import android.util.Log;

import java.io.*;
import java.nio.*;

public class ECGReader {

    /** Lee archivo .ECG como uint16 big-endian y centra restando 'offset' (ej. 32768). */
    public static double[] readECGBigEndianUint16(File f, int offset) {
        Log.d("ECG_DEBUG", "Leyendo archivo ECG: " + f.getAbsolutePath());

        if (f == null || !f.exists()) {
            Log.e("ECG_DEBUG", "❌ Archivo no existe o es nulo");
            return new double[0];
        }

        try {
            long length = f.length();
            Log.d("ECG_DEBUG", "Tamaño del archivo: " + length + " bytes");

            if (length < 2) {
                Log.e("ECG_DEBUG", "❌ Archivo demasiado pequeño para contener muestras válidas");
                return new double[0];
            }

            // Leer todos los bytes
            byte[] all = readAll(f);
            if (all.length != length) {
                Log.w("ECG_DEBUG", "⚠️ Tamaño leído (" + all.length + ") difiere del tamaño en disco (" + length + ")");
            }

            // Convertir a short[] (big-endian)
            ShortBuffer sb = ByteBuffer.wrap(all)
                    .order(ByteOrder.BIG_ENDIAN)
                    .asShortBuffer();

            short[] s = new short[sb.remaining()];
            sb.get(s);
            Log.d("ECG_DEBUG", "Muestras crudas leídas: " + s.length);

            // Convertir a double[] centrado
            double[] y = new double[s.length];
            for (int i = 0; i < s.length; i++) {
                y[i] = (s[i] & 0xFFFF) - offset;
            }

            Log.d("ECG_DEBUG", "✅ Lectura finalizada. Total muestras: " + y.length);
            return y;

        } catch (Exception e) {
            Log.e("ECG_DEBUG", "❌ Error leyendo archivo ECG: " + e.getMessage(), e);
            return new double[0];
        }
    }

    /** Lee completamente un archivo binario en memoria. */
    private static byte[] readAll(File f) throws IOException {
        try (FileInputStream fis = new FileInputStream(f);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {

            byte[] buf = new byte[65536];
            int n;
            while ((n = fis.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }

            return bos.toByteArray();
        }
    }
}
