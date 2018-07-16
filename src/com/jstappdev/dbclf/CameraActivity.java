package com.jstappdev.dbclf;

import android.Manifest;
import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Fragment;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.TransitionDrawable;
import android.hardware.Camera;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.Image.Plane;
import android.media.ImageReader;
import android.media.ImageReader.OnImageAvailableListener;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.support.v4.content.FileProvider;
import android.text.Html;
import android.text.SpannableString;
import android.text.method.LinkMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.util.Linkify;
import android.util.Size;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import com.github.mikephil.charting.charts.PieChart;
import com.github.mikephil.charting.data.PieData;
import com.github.mikephil.charting.data.PieDataSet;
import com.github.mikephil.charting.data.PieEntry;
import com.github.mikephil.charting.utils.ColorTemplate;
import com.jstappdev.dbclf.env.ImageUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;


public abstract class CameraActivity extends Activity
        implements OnImageAvailableListener, Camera.PreviewCallback {

    static final int PICK_IMAGE = 100;
    private static final int PERMISSIONS_REQUEST = 1;
    private static final String PERMISSION_CAMERA = Manifest.permission.CAMERA;
    private static final String PERMISSION_STORAGE = Manifest.permission.READ_EXTERNAL_STORAGE;

    private Handler handler;
    private HandlerThread handlerThread;
    private boolean isProcessingFrame = false;
    private byte[][] yuvBytes = new byte[3][];
    private int[] rgbBytes = null;
    private int yRowStride;

    protected int previewWidth = 0;
    protected int previewHeight = 0;

    private Runnable postInferenceCallback;
    private Runnable imageConverter;

    TextView resultsView;
    PieChart mChart;

    AtomicBoolean snapShot = new AtomicBoolean(false);
    boolean continuousInference = false;
    boolean imageSet = false;

    ImageButton cameraButton, shareButton;
    ToggleButton continuousInferenceButton;
    ImageView imageViewFromGallery;
    RelativeLayout firstTimeInstructionsTopLayer;
    ProgressBar progressBar;

    static private final int[] CHART_COLORS = {Color.rgb(114, 147, 203),
            Color.rgb(225, 151, 76), Color.rgb(132, 186, 91), Color.TRANSPARENT};
    private boolean useCamera2API;

    protected ClassifierActivity.InferenceTask inferenceTask;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(null);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_camera);

        firstTimeInstructionsTopLayer = findViewById(R.id.firstTimeInstructionsTopLayer);

        // this will show @drawable/ic_first_time_instructions.xml SVG picture
        if (isFirstTime()) firstTimeInstructionsTopLayer.setVisibility(View.INVISIBLE);

        imageViewFromGallery = findViewById(R.id.imageView);
        resultsView = findViewById(R.id.results);
        mChart = findViewById(R.id.chart);
        progressBar = findViewById(R.id.progressBar);

        setupButtons();
        setupPieChart();

        if (hasPermission()) setFragment();
        else requestPermission();

        // Get intent, action and MIME type
        Intent intent = getIntent();
        String action = intent.getAction();
        String type = intent.getType();

        if (Intent.ACTION_SEND.equals(action) && type != null) {
            if (type.startsWith("image/")) {
                handleSendImage(intent); // Handle single image being sent
            }
        }
    }

    private void setupButtons() {
        continuousInferenceButton = findViewById(R.id.continuousInferenceButton);
        cameraButton = findViewById(R.id.cameraButton);
        shareButton = findViewById(R.id.shareButton);
        cameraButton.setEnabled(false);
        shareButton.setEnabled(false);
        shareButton.setVisibility(View.GONE);

        cameraButton.setOnClickListener(v -> {
            final View pnlFlash = findViewById(R.id.pnlFlash);

            cameraButton.setEnabled(false);
            snapShot.set(true);
            imageSet = false;
            updateResults(null);

            imageViewFromGallery.setVisibility(View.GONE);
            continuousInferenceButton.setChecked(false);

            // show flash animation
            pnlFlash.setVisibility(View.VISIBLE);
            AlphaAnimation fade = new AlphaAnimation(1, 0);
            fade.setDuration(500);
            fade.setAnimationListener(new Animation.AnimationListener() {
                @Override
                public void onAnimationStart(Animation animation) {
                }

                @Override
                public void onAnimationEnd(Animation anim) {
                    pnlFlash.setVisibility(View.GONE);
                }

                @Override
                public void onAnimationRepeat(Animation animation) {

                }
            });

            pnlFlash.startAnimation(fade);
        });

        continuousInferenceButton.setOnCheckedChangeListener((buttonView, isChecked) -> {
            imageViewFromGallery.setVisibility(View.GONE);
            continuousInference = isChecked;

            if (!continuousInference)
                if (inferenceTask != null)
                    inferenceTask.cancel(true);

            //if (imageSet)
            cameraButton.setEnabled(true);

            imageSet = false;

            if (handler != null)
                handler.post(() -> updateResults(null));

            readyForNextImage();
        });
    }

    abstract void handleSendImage(Intent intent);

    private boolean isFirstTime() {
        final SharedPreferences preferences = getPreferences(MODE_PRIVATE);
        boolean ranBefore = preferences.getBoolean("RanBefore", false);
        if (!ranBefore) {
            SharedPreferences.Editor editor = preferences.edit();
            editor.putBoolean("RanBefore", true);
            editor.apply();
            firstTimeInstructionsTopLayer.setVisibility(View.VISIBLE);
            firstTimeInstructionsTopLayer.setOnTouchListener((View v, MotionEvent event) -> {
                firstTimeInstructionsTopLayer.setVisibility(View.INVISIBLE);
                return false;
            });
        }
        return ranBefore;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        final MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.mainmenu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_about:
                final AlertDialog.Builder builder1 = new AlertDialog.Builder(CameraActivity.this);

                final SpannableString s = new SpannableString(Html.fromHtml(getString(R.string.about_message)));
                Linkify.addLinks(s, Linkify.WEB_URLS);

                builder1.setMessage(s);
                builder1.setCancelable(true);

                builder1.setPositiveButton(
                        "Ok.",
                        (dialog, id) -> dialog.cancel());

                final AlertDialog infoDialog = builder1.create();
                infoDialog.show();

                ((TextView) infoDialog.findViewById(android.R.id.message)).
                        setMovementMethod(LinkMovementMethod.getInstance());
                break;
            case R.id.pick_image:
                if (inferenceTask != null)
                    inferenceTask.cancel(true);

                final Intent i = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
                continuousInferenceButton.setChecked(false);
                startActivityForResult(i, PICK_IMAGE);
                break;
            case R.id.action_exit:
                finishAndRemoveTask();
                break;
            default:
                break;
        }

        return true;
    }

    protected int[] getRgbBytes() {
        if (imageConverter != null)
            imageConverter.run();

        return rgbBytes;
    }

    /**
     * Callback for android.hardware.Camera API
     */
    @Override
    public void onPreviewFrame(final byte[] bytes, final Camera camera) {
        if (isProcessingFrame) {
            //LOGGER.w("Dropping frame!");
            return;
        }

        try {
            // Initialize the storage bitmaps once when the resolution is known.
            if (rgbBytes == null) {
                Camera.Size previewSize = camera.getParameters().getPreviewSize();
                previewHeight = previewSize.height;
                previewWidth = previewSize.width;
                rgbBytes = new int[previewWidth * previewHeight];
                onPreviewSizeChosen(new Size(previewSize.width, previewSize.height), 90);
            }
        } catch (final Exception e) {
            //LOGGER.e(e, "Exception!");
            return;
        }

        isProcessingFrame = true;
        yuvBytes[0] = bytes;
        yRowStride = previewWidth;

        imageConverter =
                () -> ImageUtils.convertYUV420SPToARGB8888(bytes, previewWidth, previewHeight, rgbBytes);

        postInferenceCallback =
                () -> {
                    camera.addCallbackBuffer(bytes);
                    isProcessingFrame = false;
                };
        processImage();
    }

    /**
     * Callback for Camera2 API
     */
    @Override
    public void onImageAvailable(final ImageReader reader) {
        //We need to wait until we have some size from onPreviewSizeChosen
        if (previewWidth == 0 || previewHeight == 0) {
            return;
        }
        if (rgbBytes == null) {
            rgbBytes = new int[previewWidth * previewHeight];
        }
        try {
            final Image image = reader.acquireLatestImage();

            if (image == null) {
                return;
            }

            if (isProcessingFrame) {
                image.close();
                return;
            }
            isProcessingFrame = true;
            final Plane[] planes = image.getPlanes();
            fillBytes(planes, yuvBytes);
            yRowStride = planes[0].getRowStride();
            final int uvRowStride = planes[1].getRowStride();
            final int uvPixelStride = planes[1].getPixelStride();

            imageConverter = () -> ImageUtils.convertYUV420ToARGB8888(
                    yuvBytes[0],
                    yuvBytes[1],
                    yuvBytes[2],
                    previewWidth,
                    previewHeight,
                    yRowStride,
                    uvRowStride,
                    uvPixelStride,
                    rgbBytes);

            postInferenceCallback = () -> {
                image.close();
                isProcessingFrame = false;
            };

            processImage();
        } catch (final Exception e) {
            return;
        }
    }

    @Override
    public synchronized void onStart() {
        super.onStart();
    }

    @Override
    public synchronized void onResume() {
        super.onResume();

        snapShot.set(false);

        handlerThread = new HandlerThread("inference");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());

        final ActionBar actionBar = getActionBar();
        if (actionBar != null)
            actionBar.setDisplayOptions(ActionBar.DISPLAY_SHOW_HOME
                    | ActionBar.DISPLAY_SHOW_TITLE | ActionBar.DISPLAY_SHOW_CUSTOM);

        if (!imageSet) cameraButton.setEnabled(true);
    }

    private void setupPieChart() {
        mChart.getDescription().setEnabled(false);
        mChart.setUsePercentValues(true);
        mChart.setTouchEnabled(false);

        mChart.setCenterTextTypeface(Typeface.createFromAsset(getAssets(), "OpenSans-Regular.ttf"));
        mChart.setCenterText(generateCenterSpannableText());
        mChart.setExtraOffsets(14, 0.f, 14, 0.f);
        mChart.setHoleRadius(80);
        mChart.setHoleColor(Color.TRANSPARENT);
        mChart.setCenterTextSizePixels(23);
        mChart.setHovered(true);
        mChart.setDrawMarkers(false);
        mChart.setDrawCenterText(true);
        mChart.setRotationEnabled(false);
        mChart.setHighlightPerTapEnabled(false);
        mChart.getLegend().setEnabled(false);
        mChart.setAlpha(0.9f);

        // display unknown slice
        final ArrayList<PieEntry> entries = new ArrayList<>();
        // set unknown slice to transparent
        entries.add(new PieEntry(100, ""));
        final PieDataSet set = new PieDataSet(entries, "");
        set.setColor(R.color.transparent);
        set.setDrawValues(false);

        final PieData data = new PieData(set);
        mChart.setData(data);
    }

    private SpannableString generateCenterSpannableText() {
        final SpannableString s = new SpannableString("Center dog here\nkeep camera stable");
        s.setSpan(new RelativeSizeSpan(1.5f), 0, 15, 0);
        s.setSpan(new StyleSpan(Typeface.NORMAL), 15, s.length() - 15, 0);
        s.setSpan(new ForegroundColorSpan(ColorTemplate.getHoloBlue()), 0, 15, 0);

        s.setSpan(new StyleSpan(Typeface.ITALIC), s.length() - 18, s.length(), 0);
        s.setSpan(new ForegroundColorSpan(ColorTemplate.getHoloBlue()), s.length() - 18, s.length(), 0);
        return s;
    }

    @Override
    public synchronized void onPause() {
        snapShot.set(false);
        cameraButton.setEnabled(false);

        handlerThread.quitSafely();
        try {
            handlerThread.join();
            handlerThread = null;
            handler = null;
        } catch (final InterruptedException e) {
        }

        super.onPause();
    }

    @Override
    public synchronized void onStop() {
        super.onStop();
    }

    @Override
    public synchronized void onDestroy() {
        super.onDestroy();
    }

    protected synchronized void runInBackground(final Runnable r) {
        if (handler != null) {
            handler.post(r);
        }
    }

    @Override
    public void onRequestPermissionsResult(
            final int requestCode, final String[] permissions, final int[] grantResults) {
        if (requestCode == PERMISSIONS_REQUEST) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED
                    && grantResults[1] == PackageManager.PERMISSION_GRANTED) {
                setFragment();
            } else {
                requestPermission();
            }
        }
    }

    private boolean hasPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return checkSelfPermission(PERMISSION_CAMERA) == PackageManager.PERMISSION_GRANTED &&
                    checkSelfPermission(PERMISSION_STORAGE) == PackageManager.PERMISSION_GRANTED;
        } else {
            return true;
        }
    }

    private void requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (shouldShowRequestPermissionRationale(PERMISSION_CAMERA) ||
                    shouldShowRequestPermissionRationale(PERMISSION_STORAGE)) {
                Toast.makeText(CameraActivity.this,
                        "Camera AND read external storage permission are required for this app", Toast.LENGTH_LONG).show();
            }
            requestPermissions(new String[]{PERMISSION_CAMERA, PERMISSION_STORAGE}, PERMISSIONS_REQUEST);
        }
    }

    // Returns true if the device supports the required hardware level, or better.
    private boolean isHardwareLevelSupported(
            CameraCharacteristics characteristics, int requiredLevel) {
        int deviceLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
        if (deviceLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
            return requiredLevel == deviceLevel;
        }
        // deviceLevel is not LEGACY, can use numerical sort
        return requiredLevel <= deviceLevel;
    }

    private String chooseCamera() {
        final CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            for (final String cameraId : manager.getCameraIdList()) {
                final CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

                // We don't use a front facing camera in this sample.
                final Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue;
                }

                final StreamConfigurationMap map =
                        characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

                if (map == null) {
                    continue;
                }

                // Fallback to camera1 API for internal cameras that don't have full support.
                // This should help with legacy situations where using the camera2 API causes
                // distorted or otherwise broken previews.
                useCamera2API = (facing == CameraCharacteristics.LENS_FACING_EXTERNAL)
                        || isHardwareLevelSupported(characteristics,
                        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL);
                //LOGGER.i("Camera API lv2?: %s", useCamera2API);
                return cameraId;
            }
        } catch (CameraAccessException e) {
            //LOGGER.e(e, "Not allowed to access camera");
        }

        return null;
    }

    protected void setFragment() {
        String cameraId = chooseCamera();
        if (cameraId == null) {
            Toast.makeText(this, "No Camera Detected", Toast.LENGTH_SHORT).show();
            finish();
        }

        Fragment fragment;
        if (useCamera2API) {
            CameraConnectionFragment camera2Fragment =
                    CameraConnectionFragment.newInstance(
                            new CameraConnectionFragment.ConnectionCallback() {
                                @Override
                                public void onPreviewSizeChosen(final Size size, final int rotation) {
                                    previewHeight = size.getHeight();
                                    previewWidth = size.getWidth();
                                    CameraActivity.this.onPreviewSizeChosen(size, rotation);
                                }
                            },
                            this,
                            getLayoutId(),
                            getDesiredPreviewFrameSize());

            camera2Fragment.setCamera(cameraId);
            fragment = camera2Fragment;
        } else {
            fragment =
                    new LegacyCameraConnectionFragment(this, getLayoutId(), getDesiredPreviewFrameSize());
        }

        //fragment.setRetainInstance(false);
        getFragmentManager()
                .beginTransaction()
                .replace(R.id.container, fragment)
                .commit();
    }

    protected void fillBytes(final Plane[] planes, final byte[][] yuvBytes) {
        // Because of the variable row stride it's not possible to know in
        // advance the actual necessary dimensions of the yuv planes.
        for (int i = 0; i < planes.length; ++i) {
            final ByteBuffer buffer = planes[i].getBuffer();
            if (yuvBytes[i] == null) {
                //LOGGER.d("Initializing buffer %d at size %d", i, buffer.capacity());
                yuvBytes[i] = new byte[buffer.capacity()];
            }
            buffer.get(yuvBytes[i]);
        }
    }

    protected void readyForNextImage() {
        // sometimes this will be uninitialized, for whatever reason
        if (postInferenceCallback != null) {
            postInferenceCallback.run();
        } else isProcessingFrame = false;

    }

    protected int getScreenOrientation() {
        switch (getWindowManager().getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_270:
                return 270;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_90:
                return 90;
            default:
                return 0;
        }
    }

    protected abstract void processImage();

    protected abstract void onPreviewSizeChosen(final Size size, final int rotation);

    protected abstract int getLayoutId();

    protected abstract Size getDesiredPreviewFrameSize();

    protected abstract void initClassifier();

    void updateResults(List<Classifier.Recognition> results) {
        updateResultsView(results);
        updatePieChart(results);
    }

    // update results on our custom textview
    void updateResultsView(List<Classifier.Recognition> results) {
        final StringBuilder sb = new StringBuilder();

        if (results != null) {
            for (final Classifier.Recognition recog : results) {
                final String text = String.format(Locale.getDefault(), "%s: %d %%\n",
                        recog.getTitle(), Math.round(recog.getConfidence() * 100));
                sb.append(text);
            }
        } else sb.append("");

        final String finalText = sb.toString();
        runOnUiThread(() -> resultsView.setText(finalText));
    }

    void updatePieChart(List<Classifier.Recognition> results) {
        final ArrayList<PieEntry> entries = new ArrayList<>();
        float sum = 0;

        if (results != null)
            for (int i = 0; i < results.size(); i++) {
                sum += results.get(i).getConfidence();

                //todo: maybe add icons from drawable
                PieEntry entry = new PieEntry(results.get(i).getConfidence() * 100, results.get(i).getTitle());
                entries.add(entry);
            }

        // add unknown slice
        final float unknown = 1 - sum;
        entries.add(new PieEntry(unknown * 100, ""));

        //calculate center of slice
        final float offset = entries.get(0).getValue() * 3.6f / 2;
        // calculate the next angle
        final float end = 270f - (entries.get(0).getValue() * 3.6f - offset);

        final PieDataSet set = new PieDataSet(entries, "");

        if (entries.size() > 2)
            set.setSliceSpace(3f);

        // set slice colors
        final ArrayList<Integer> sliceColors = new ArrayList<>();

        for (int c : CHART_COLORS)
            sliceColors.add(c);

        if (entries.size() > 0)
            sliceColors.set(entries.size() - 1, R.color.transparent);

        set.setColors(sliceColors);
        set.setDrawValues(false);

        final PieData data = new PieData(set);
        mChart.setData(data);

        //rotate to center of first slice
        mChart.setRotationAngle(end);
        mChart.setEntryLabelTextSize(16);
        runOnUiThread(() -> mChart.invalidate());
    }

    protected void setImage(Bitmap image) {
        final int transitionTime = 1000;
        imageSet = true;

        cameraButton.setEnabled(false);
        imageViewFromGallery.setImageBitmap(image);
        imageViewFromGallery.setVisibility(View.VISIBLE);

        final TransitionDrawable transition = (TransitionDrawable) imageViewFromGallery.getBackground();
        transition.startTransition(transitionTime);

        setupShareButton();

        // fade out image on click
        final AlphaAnimation fade = new AlphaAnimation(1, 0);
        fade.setDuration(transitionTime);

        fade.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {
                if (inferenceTask != null)
                    inferenceTask.cancel(true);

                imageViewFromGallery.setClickable(false);
                runInBackground(() -> updateResults(null));
                transition.reverseTransition(transitionTime);
                imageViewFromGallery.setVisibility(View.GONE);
                shareButton.setVisibility(View.GONE);
            }

            @Override
            public void onAnimationEnd(Animation anim) {
                progressBar.setVisibility(View.GONE);
                imageSet = false;
                snapShot.set(false);
                cameraButton.setEnabled(true);
                readyForNextImage();
            }

            @Override
            public void onAnimationRepeat(Animation animation) {

            }
        });

        imageViewFromGallery.setVisibility(View.VISIBLE);
        imageViewFromGallery.setOnClickListener(v -> imageViewFromGallery.startAnimation(fade));

    }


    public Bitmap takeScreenshot() {
        View rootView = findViewById(android.R.id.content).getRootView();
        rootView.setDrawingCacheEnabled(true);
        return rootView.getDrawingCache();
    }

    // https://stackoverflow.com/questions/3733988/screen-capture-in-android
    protected void setupShareButton() {
        shareButton.setVisibility(View.VISIBLE);
        shareButton.setEnabled(true);

        // https://stackoverflow.com/questions/9049143/android-share-intent-for-a-bitmap-is-it-possible-not-to-save-it-prior-sharing
        shareButton.setOnClickListener(v -> {
            shareButton.setVisibility(View.GONE);
            // save bitmap to cache directory
            try {
                File cachePath = new File(getCacheDir(), "images");
                cachePath.mkdirs(); // don't forget to make the directory
                FileOutputStream stream = new FileOutputStream(cachePath + "/image.png"); // overwrites this image every time
                takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, stream);
                stream.close();
            } catch (IOException e) {
                //e.printStackTrace();
            }

            // sharebutton was removed temporarily for screenshot
            shareButton.setVisibility(View.VISIBLE);

            File imagePath = new File(getCacheDir(), "images");
            File newFile = new File(imagePath, "image.png");
            Uri contentUri = FileProvider.getUriForFile(this, "com.jstappdev.dbclf.fileprovider", newFile);

            if (contentUri != null) {
                Intent shareIntent = new Intent();
                shareIntent.setAction(Intent.ACTION_SEND);
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); // temp permission for receiving app to read this file
                shareIntent.setDataAndType(contentUri, getContentResolver().getType(contentUri));
                shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
                startActivity(Intent.createChooser(shareIntent, getString(R.string.share_with)));
            }
        });
    }
}