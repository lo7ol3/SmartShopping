package com.example.smartshopping;

import android.content.Context;
import android.content.res.AssetFileDescriptor;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

public class TFLiteModelLoader {

    public static Interpreter loadModel(Context context) throws Exception {

        AssetFileDescriptor afd =
                context.getAssets().openFd("best_model_v2.tflite");

        FileInputStream fis = new FileInputStream(afd.getFileDescriptor());
        FileChannel channel = fis.getChannel();

        long startOffset = afd.getStartOffset();
        long declaredLength = afd.getDeclaredLength();

        MappedByteBuffer buffer =
                channel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);

        Interpreter.Options options = new Interpreter.Options();
        options.setNumThreads(4);

        return new Interpreter(buffer, options);
    }
}
