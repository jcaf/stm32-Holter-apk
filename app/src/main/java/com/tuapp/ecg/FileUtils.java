package com.tuapp.ecg;

import android.content.Context;
import android.net.Uri;
import java.io.*;
import java.util.*;

public class FileUtils {

    /** Busca recursivamente el .ECG más reciente debajo de root (ej. /sdcard). */
    public static File findLatestECG(File root) {
        List<File> found = new ArrayList<>();
        walk(root, found);
        if (found.isEmpty()) return null;
        found.sort((a,b) -> Long.compare(b.lastModified(), a.lastModified()));
        return found.get(0);
    }

    private static void walk(File dir, List<File> out) {
        if (dir==null || !dir.exists()) return;
        File[] ls = dir.listFiles();
        if (ls==null) return;
        for (File f: ls) {
            if (f.isDirectory()) walk(f, out);
            else if (f.getName().toUpperCase().endsWith(".ECG")) out.add(f);
        }
    }

    /** Copia un Uri (selector de archivos) a un archivo temporal y devuelve su ruta. */
    public static String getPathFromUri(Context ctx, Uri uri) {
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            // FIX: Cambiar la extensión del archivo temporal a ".ecg"
            // para que pase la validación de nombre en DiagnosticoActivity.java
            File tmp = File.createTempFile("pick",".ecg", ctx.getCacheDir());
            try (OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return tmp.getAbsolutePath();
        } catch (Exception e) {
            // Log para depuración en caso de fallo de acceso/copia
            System.err.println("Error al obtener path desde Uri: " + e.getMessage());
            return null;
        }
    }
}
