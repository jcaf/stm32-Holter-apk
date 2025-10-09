package com.tuapp.ecg;

import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Abre directamente DiagnosticoActivity
        Intent intent = new Intent(this, DiagnosticoActivity.class);
        startActivity(intent);

        // Cierra MainActivity para no dejarla en la pila
        finish();
    }
}
