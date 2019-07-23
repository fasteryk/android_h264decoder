package com.testapp.h264decoder;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Environment;
import android.support.constraint.ConstraintLayout;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.RelativeLayout;

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements SurfaceHolder.Callback, ImageReader.OnImageAvailableListener {
    private final String TAG = "H264 Decode";

    private final String MP4_FILE =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES) + "/r4.mp4";
    private final String DUMP_FILE_DIR =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES).toString();

    private DecodeThread mH264DecodeThread = null;
    private VideoView mVideoView = null;
    private RelativeLayout mMainLayout = null;
    private ImageReader mVideoFrame = null;
    private Paint mPaint;

    private int videoHeight, videoWidth;
    private int frameCounter = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!OpenCVLoader.initDebug()) {
            Log.e(TAG, "Failed to initialize opencv");
        }

        setContentView(R.layout.activity_main);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        mMainLayout = (RelativeLayout)findViewById(R.id.main_layout);

        mVideoFrame = ImageReader.newInstance(1/*don't need it*/ , 1/*don't need it*/,
                                                ImageFormat.YUV_420_888, 2);
        mVideoFrame.setOnImageAvailableListener(this, null);

        mPaint = new Paint();
        mPaint.setColor(Color.RED);
        mPaint.setTextSize(40);

        int read_permission = ContextCompat.checkSelfPermission(this,
                Manifest.permission.READ_EXTERNAL_STORAGE);
        int write_permission =  ContextCompat.checkSelfPermission(this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE);

        List<String> listPermissionsNeeded = new ArrayList<>();

        if (read_permission != PackageManager.PERMISSION_GRANTED)
            listPermissionsNeeded.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        if (write_permission != PackageManager.PERMISSION_GRANTED)
            listPermissionsNeeded.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);

        if (!listPermissionsNeeded.isEmpty()) {
            requestPermissions(
                    listPermissionsNeeded.toArray(new String[listPermissionsNeeded.size()]),
                    1);
        } else {
            if (getVideoSize())
                createSurfaceView();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions, int[] grantResults) {
        if  (requestCode == 1) {
            if (grantResults.length == 2 && grantResults[0] == PackageManager.PERMISSION_GRANTED &&
                    grantResults[1] == PackageManager.PERMISSION_GRANTED) {
                if (getVideoSize())
                    createSurfaceView();
            }
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUI();
        }
    }

    private void hideSystemUI() {
        // Enables regular immersive mode.
        // For "lean back" mode, remove SYSTEM_UI_FLAG_IMMERSIVE.
        // Or for "sticky immersive," replace it with SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                                View.SYSTEM_UI_FLAG_FULLSCREEN |
                                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void createSurfaceView() {
        RelativeLayout.LayoutParams layout = new RelativeLayout.LayoutParams(
                                                    RelativeLayout.LayoutParams.WRAP_CONTENT,
                                                    RelativeLayout.LayoutParams.MATCH_PARENT);
        layout.addRule(RelativeLayout.CENTER_HORIZONTAL);

        mVideoView = new VideoView(this, videoWidth, videoHeight);
        mVideoView.setLayoutParams(layout);
        mVideoView.getHolder().setFormat(PixelFormat.RGBA_8888);
        mVideoView.getHolder().addCallback(this);

        mMainLayout.addView(mVideoView);
    }

    private boolean getVideoSize() {
        MediaExtractor extractor;
        extractor = new MediaExtractor();

        try {
            extractor.setDataSource(MP4_FILE);

            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime.startsWith("video/")) {
                    videoWidth = format.getInteger(MediaFormat.KEY_WIDTH);
                    videoHeight = format.getInteger(MediaFormat.KEY_HEIGHT);
                    return true;
                }
            }
        } catch (Exception ex) {
            Log.e(TAG, ex.toString());
        }

        return false;
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {

    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (mH264DecodeThread == null) {
            mH264DecodeThread = new DecodeThread(mVideoFrame.getSurface()); //new DecodeThread(holder.getSurface());
            mH264DecodeThread.start();
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.d(TAG, "surface destroyed");
        if (mH264DecodeThread != null) {
            mH264DecodeThread.interrupt();
        }
    }

    @Override
    public void onImageAvailable(ImageReader reader) {
        try {
            Image image = reader.acquireLatestImage();

            final int width = image.getWidth(), height = image.getHeight();
            byte[] writeBuffer = new byte[width*height*3/2];

            Image.Plane planes[] = image.getPlanes();

            int off = 0;

            int rowStride = planes[0].getRowStride();
            ByteBuffer buff = planes[0].getBuffer();

            long startTicks, endTicks;

            startTicks = Core.getTickCount();

            for (int i = 0; i < height; i++) {
                buff.position(i*rowStride);
                buff.get(writeBuffer, off, width);
                off += width;
            }

            for (int c = 1; c <= 2; c++) {
                buff = planes[c].getBuffer();
                for (int i = 0; i < height/2; i++) {
                    //buff.position(i * rowStride);
                    for (int j = 0; j < width; j += 2) {
                        writeBuffer[off++] = buff.get(i*rowStride + j);
                    }
                }
            }

            endTicks = Core.getTickCount();
            String copyTime = String.format("copy: %.2f us", (float)(endTicks - startTicks)*1000000/Core.getTickFrequency());

            Mat yuvFrame = new Mat(height + (height/2), width, CvType.CV_8UC1);
            yuvFrame.put(0, 0, writeBuffer);

            Mat rgbFrame = new Mat();
            startTicks = Core.getTickCount();
            Imgproc.cvtColor(yuvFrame, rgbFrame, Imgproc.COLOR_YUV420p2BGRA);
            endTicks = Core.getTickCount();
            String cvtTime = String.format("color format: %.2f us", (float)(endTicks - startTicks)*1000000/Core.getTickFrequency());

            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(rgbFrame, bitmap);

            Canvas canvas = mVideoView.getHolder().lockCanvas();

            //canvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR);
            canvas.drawBitmap(bitmap, new Rect(0,0, bitmap.getWidth(), bitmap.getHeight()),
                    new Rect(0, 0, canvas.getWidth(), canvas.getHeight()), null);

            canvas.drawText(copyTime, 40, 40, mPaint);
            canvas.drawText(cvtTime, 40, 80, mPaint);

            mVideoView.getHolder().unlockCanvasAndPost(canvas);

            if (image != null)
                image.close();
        } catch (Exception e) {
            Log.d(TAG, e.getMessage());
        }
    }

    private void showSupportedColorFormat(MediaCodecInfo.CodecCapabilities caps) {
        System.out.print("supported color format: ");
        for (int c : caps.colorFormats) {
            System.out.print(c + "\t");
        }
        System.out.println();
    }

    private class DecodeThread extends Thread {
        private MediaCodec decoder;
        private MediaExtractor extractor;
        private Surface surface;

        public DecodeThread(Surface surface) {
            this.surface = surface;
        }

        @Override
        public void run() {
            try {
                extractor = new MediaExtractor();
                extractor.setDataSource(MP4_FILE);

                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    MediaFormat format = extractor.getTrackFormat(i);
                    format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
                    String mime = format.getString(MediaFormat.KEY_MIME);
                    if (mime.startsWith("video/")) {
                        extractor.selectTrack(i);
                        decoder = MediaCodec.createDecoderByType(mime);
                        decoder.configure(format, surface, null, 0);
                        showSupportedColorFormat(decoder.getCodecInfo().getCapabilitiesForType(mime));
                        break;
                    }
                }

                decoder.start();

                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                boolean isEOS = false;
                long startMs = System.currentTimeMillis();

                while (!Thread.interrupted()) {
                    if (!isEOS) {
                        int inIndex = decoder.dequeueInputBuffer(10000);
                        if (inIndex >= 0) {
                            ByteBuffer buffer = decoder.getInputBuffer(inIndex);
                            int sampleSize = extractor.readSampleData(buffer, 0);
                            if (sampleSize < 0) {
                                // We shouldn't stop the playback at this point, just pass the EOS
                                // flag to decoder, we will get it again from the
                                // dequeueOutputBuffer
                                Log.d("DecodeActivity", "InputBuffer BUFFER_FLAG_END_OF_STREAM");
                                decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                isEOS = true;
                            } else {
                                decoder.queueInputBuffer(inIndex, 0, sampleSize, extractor.getSampleTime(), 0);
                                extractor.advance();
                            }
                        }
                    }

                    int outIndex = decoder.dequeueOutputBuffer(info, 10000);
                    switch (outIndex) {
                        case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                            Log.d("DecodeActivity", "New format " + decoder.getOutputFormat().toString());
                            break;
                        case MediaCodec.INFO_TRY_AGAIN_LATER:
                            Log.d("DecodeActivity", "dequeueOutputBuffer timed out!");
                            break;
                        default:
                            if (outIndex < 0)
                                break;

                            //ByteBuffer buffer = decoder.getOutputBuffer(outIndex);
                            //Log.v("DecodeThread", "buffer size " + buffer.remaining());

                            // We use a very simple clock to keep the video FPS, or the video
                            // playback will be too fast
                            while (info.presentationTimeUs / 1000 > System.currentTimeMillis() - startMs) {
                                try {
                                    sleep(10);
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                    break;
                                }
                            }
                            decoder.releaseOutputBuffer(outIndex, true);
                            break;
                    }

                    // All decoded frames have been rendered, we can stop playing now
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.d("DecodeActivity", "OutputBuffer BUFFER_FLAG_END_OF_STREAM");
                        break;
                    }
                }

                decoder.stop();
                decoder.release();
                extractor.release();

            } catch (Exception ex) {
                Log.e("Playback thread", ex.toString());
            }
        }
    }
}
