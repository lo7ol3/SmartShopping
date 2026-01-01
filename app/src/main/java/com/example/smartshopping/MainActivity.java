package com.example.smartshopping;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private PreviewView previewView;
    private OverlayView overlay;
    private TextView statusText;

    private YoloV8Detector detector;
    private ExecutorService cameraExecutor;
    private TextToSpeech tts;

    private long lastSpokenTime = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        overlay = findViewById(R.id.overlay);
        statusText = findViewById(R.id.statusText);

        cameraExecutor = Executors.newSingleThreadExecutor();

        tts = new TextToSpeech(this, s -> tts.setLanguage(Locale.US));

        try {
            detector = new YoloV8Detector(this);
            statusText.setText("YOLOv8 model loaded");
        } catch (Exception e) {
            statusText.setText("Model load failed");
            return;
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(
                    this, new String[]{Manifest.permission.CAMERA}, 1001);
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);

        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis analysis =
                        new ImageAnalysis.Builder()
                                .setBackpressureStrategy(
                                        ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build();

                analysis.setAnalyzer(cameraExecutor, image -> {

                    Bitmap bitmap = ImageUtils.imageToBitmap(image);
                    bitmap = ImageUtils.rotateBitmap(
                            bitmap, image.getImageInfo().getRotationDegrees());

                    if (bitmap == null) {
                        image.close();
                        return;
                    }

                    List<YoloV8Detector.Detection> results =
                            detector.detect(bitmap);

                    List<RectF> boxes = new ArrayList<>();

                    for (YoloV8Detector.Detection d : results) {

                        boxes.add(d.box);

                        long now = System.currentTimeMillis();
                        if (now - lastSpokenTime > 3000) {
                            String spokenLabel = d.label.replace("_", " ");
                            tts.speak(spokenLabel + " detected",
                                    TextToSpeech.QUEUE_FLUSH,
                                    null, null);
                            lastSpokenTime = now;
                        }

                        runOnUiThread(() ->
                                statusText.setText(d.label));
                    }

                    Bitmap finalBitmap = bitmap;
                    runOnUiThread(() ->
                            overlay.setBoxes(
                                    boxes,
                                    finalBitmap.getWidth(),
                                    finalBitmap.getHeight()));

                    image.close();
                });

                provider.unbindAll();
                provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview, analysis);

            } catch (Exception e) {
                runOnUiThread(() ->
                        statusText.setText("Camera error"));
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @Override
    protected void onDestroy() {
        cameraExecutor.shutdown();
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }
}
