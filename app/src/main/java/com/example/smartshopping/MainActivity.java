package com.example.smartshopping;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.media.Image;
import android.os.Bundle;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import org.tensorflow.lite.Interpreter;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final int CAMERA_REQUEST = 1001;

    private PreviewView previewView;
    private TextView statusText;

    private Interpreter interpreter;
    private YoloDetector detector;

    private ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        statusText = findViewById(R.id.statusText);

        previewView.setImplementationMode(
                PreviewView.ImplementationMode.COMPATIBLE
        );

        try {
            interpreter = TFLiteModelLoader.loadModel(this);
            detector = new YoloDetector(this, interpreter);
            statusText.setText("Model ready");
        } catch (Exception e) {
            statusText.setText("Model load failed");
            return;
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.CAMERA},
                    CAMERA_REQUEST
            );
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

                analysis.setAnalyzer(executor, image -> {
                    Bitmap bitmap = imageToBitmap(image);
                    String result = detector.detect(bitmap);
                    runOnUiThread(() -> statusText.setText(result));
                    image.close();
                });

                provider.unbindAll();
                provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis
                );

            } catch (Exception e) {
                statusText.setText("Camera error");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private Bitmap imageToBitmap(ImageProxy image) {

        Image img = image.getImage();
        ByteBuffer y = img.getPlanes()[0].getBuffer();
        ByteBuffer u = img.getPlanes()[1].getBuffer();
        ByteBuffer v = img.getPlanes()[2].getBuffer();

        int ySize = y.remaining();
        int uSize = u.remaining();
        int vSize = v.remaining();

        byte[] nv21 = new byte[ySize + uSize + vSize];

        y.get(nv21, 0, ySize);
        v.get(nv21, ySize, vSize);
        u.get(nv21, ySize + vSize, uSize);

        YuvImage yuv = new YuvImage(
                nv21, ImageFormat.NV21,
                image.getWidth(), image.getHeight(), null);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuv.compressToJpeg(
                new Rect(0, 0, image.getWidth(), image.getHeight()), 90, out);

        byte[] bytes = out.toByteArray();
        Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);

        Matrix rotate = new Matrix();
        rotate.postRotate(image.getImageInfo().getRotationDegrees());

        return Bitmap.createBitmap(
                bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), rotate, true);
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, @NonNull String[] permissions,
            @NonNull int[] grantResults) {

        if (requestCode == CAMERA_REQUEST &&
                grantResults.length > 0 &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        }
    }
}
