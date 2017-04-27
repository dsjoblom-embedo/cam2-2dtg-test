/*
 * Copyright 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.camera2basic;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Fragment;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.*;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.v13.app.FragmentCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.*;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.Toast;
import decoding.twodtg.com.twodtgdecodelib2.BarcodeDecoder;
import decoding.twodtg.com.twodtgdecodelib2.BarcodeDecoderLibrary;
import decoding.twodtg.com.twodtgdecodelib2.BarcodeDecoderOptions;
import decoding.twodtg.com.twodtgdecodelib2.DecoderException;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/////////////////////////////////////////////////////////
//  2dtg lib
/////////////////////////////////////////////////////////

public class Camera2BasicFragment extends Fragment
        implements View.OnClickListener, FragmentCompat.OnRequestPermissionsResultCallback, View.OnTouchListener {

    /**
     * Conversion from screen rotation to JPEG orientation.
     */
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    private static final int REQUEST_CAMERA_PERMISSION = 1;
    private static final String FRAGMENT_DIALOG = "dialog";

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    /**
     * Tag for the {@link Log}.
     */
    private static final String TAG = "Camera2BasicFragment";

    /**
     * Camera state: Showing camera preview.
     */
    private static final int STATE_PREVIEW = 0;

    /**
     * Camera state: Waiting for the focus to be locked.
     */
    private static final int STATE_WAITING_LOCK = 1;

    /**
     * Camera state: Waiting for the exposure to be precapture state.
     */
    private static final int STATE_WAITING_PRECAPTURE = 2;

    /**
     * Camera state: Waiting for the exposure state to be something other than precapture.
     */
    private static final int STATE_WAITING_NON_PRECAPTURE = 3;

    /**
     * Camera state: Picture was taken.
     */
    private static final int STATE_PICTURE_TAKEN = 4;

    /**
     * Max preview width that is guaranteed by Camera2 API
     */
    private static final int MAX_PREVIEW_WIDTH = 1920;

    /**
     * Max preview height that is guaranteed by Camera2 API
     */
    private static final int MAX_PREVIEW_HEIGHT = 1080;

    /**
     * {@link TextureView.SurfaceTextureListener} handles several lifecycle events on a
     * {@link TextureView}.
     */
    private final TextureView.SurfaceTextureListener mSurfaceTextureListener
            = new TextureView.SurfaceTextureListener() {

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
            openCamera(width, height);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
            configureTransform(width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture texture) {
        }

    };

    /**
     * ID of the current {@link CameraDevice}.
     */
    private String mCameraId;

    /**
     * An {@link AutoFitTextureView} for camera preview.
     */
    private AutoFitTextureView mTextureView;

    /**
     * A {@link CameraCaptureSession } for camera preview.
     */
    private CameraCaptureSession mCaptureSession;

    /**
     * A reference to the opened {@link CameraDevice}.
     */
    private CameraDevice mCameraDevice;

    // TODO: configure the decoder? (if necessary)
    private BarcodeDecoder decoder;

    /**
     * The {@link android.util.Size} of camera preview.
     */
    private Size mPreviewSize;

    /**
     * {@link CameraDevice.StateCallback} is called when {@link CameraDevice} changes its state.
     */
    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            // This method is called when the camera is opened.  We start camera preview here.
            mCameraOpenCloseLock.release();
            mCameraDevice = cameraDevice;
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
            Activity activity = getActivity();
            if (null != activity) {
                activity.finish();
            }
        }

    };

    /**
     * An additional thread for running tasks that shouldn't block the UI.
     */
    private HandlerThread mBackgroundThread;

    /**
     * A {@link Handler} for running tasks in the background.
     */
    private Handler mBackgroundHandler;

    /**
     * An {@link ImageReader} that handles still image capture.
     */
    private ImageReader mImageReader;

    /**
     * This a callback object for the {@link ImageReader}. "onImageAvailable" will be called when a
     * still image is ready to be saved.
     */
    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener;

    {
        mOnImageAvailableListener = new ImageReader.OnImageAvailableListener() {

            @Override
            public void onImageAvailable(ImageReader reader) {
                // convert Image to Bitmap
                Image img = null;
                try {
                    img = reader.acquireLatestImage();
                    if ( img == null)
                        return; // no image available

                    Image.Plane[] planes = img.getPlanes();
                    ByteBuffer buffer = planes[0].getBuffer();
                    byte[] b = new byte[buffer.remaining()];
                    buffer.get(b);
                    Bitmap bitmap = BitmapFactory.decodeByteArray(b, 0, b.length);
                    if (bitmap == null)
                        throw new NullPointerException("failed to convert Image to Bitmap");
                    Bitmap bitmap1 = Utils.rotateBitmap( bitmap, 90 );
                    Bitmap bitmap2 = Utils.rotateBitmap( bitmap, 180 );
                    Bitmap bitmap3 = Utils.rotateBitmap( bitmap, 270 );
/*

                    Utils.writeBitmap( bitmap, new File(getActivity().getExternalFilesDir(null), "pic0.jpg") );
                    Utils.writeBitmap( bitmap1, new File(getActivity().getExternalFilesDir(null), "pic1.jpg") );
                    Utils.writeBitmap( bitmap2, new File(getActivity().getExternalFilesDir(null), "pic2.jpg") );
                    Utils.writeBitmap( bitmap3, new File(getActivity().getExternalFilesDir(null), "pic3.jpg") );
*/

                    // try to decode bitmap
                    try {
                        System.out.println( "blah" );

                        if (decoder.DecodeBitmap(bitmap) ||decoder.DecodeBitmap(bitmap1) ||decoder.DecodeBitmap(bitmap2) ||decoder.DecodeBitmap(bitmap3)) {
                            Utils.ErrorDialog.newInstance( "decode succeeded" + decoder.GetDecodeResult(Charset.defaultCharset() ) ).show(getChildFragmentManager(), FRAGMENT_DIALOG);
                            //showToast("decode succeeded" + decoder.GetDecodeResult(Charset.defaultCharset()));
                        } else {
                            //showToast("decode failed" + decoder.GetDecodeResult(Charset.defaultCharset()));
                            // do nothing, decode did not succeed
                            //showToast("decode failed, img size: " + bitmap.getWidth() + "x" + bitmap.getHeight() );
                        }
                    } catch ( Exception e) {
                        Utils.ErrorDialog.newInstance(e.getMessage())
                                         .show(getChildFragmentManager(), FRAGMENT_DIALOG);
                    }
                }
                catch ( Exception e ) {
                    Utils.ErrorDialog.newInstance(e.getMessage())
                                     .show(getChildFragmentManager(), FRAGMENT_DIALOG);
                }
                finally {
                    if (img != null)
                        img.close();
                }
            }
        };
    }

    /**
     * {@link CaptureRequest.Builder} for the camera preview
     */
    private CaptureRequest.Builder mPreviewRequestBuilder;

    /**
     * {@link CaptureRequest} generated by {@link #mPreviewRequestBuilder}
     */
    private CaptureRequest mPreviewRequest;

    /**
     * The current state of camera state for taking pictures.
     *
     * @see #mCaptureCallback
     */
    private int mState = STATE_PREVIEW;

    /**
     * A {@link Semaphore} to prevent the app from exiting before closing the camera.
     */
    private Semaphore mCameraOpenCloseLock = new Semaphore(1);

    /**
     * Whether the current camera device supports Flash or not.
     */
    private boolean mFlashSupported;

    /**
     * Orientation of the camera sensor
     */
    private int mSensorOrientation;

    /**
     * A {@link CameraCaptureSession.CaptureCallback} that handles events related to JPEG capture.
     */
    private CameraCaptureSession.CaptureCallback mCaptureCallback
            = new CameraCaptureSession.CaptureCallback() {

        private void process(CaptureResult result) {
            switch (mState) {
                case STATE_PREVIEW: {
                    // We have nothing to do when the camera preview is working normally.
                    //captureStillPicture();
                    break;
                }
            }
        }

        @Override
        public void onCaptureProgressed(@NonNull CameraCaptureSession session,
                                        @NonNull CaptureRequest request,
                                        @NonNull CaptureResult partialResult) {
            process(partialResult);
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                       @NonNull CaptureRequest request,
                                       @NonNull TotalCaptureResult result) {
            process(result);
        }

    };

    /**
     * Shows a {@link Toast} on the UI thread.
     *
     * @param text The message to show
     */
    private void showToast(final String text) {
        final Activity activity = getActivity();
        if (activity != null) {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    public static Camera2BasicFragment newInstance() {
        return new Camera2BasicFragment();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_camera2_basic, container, false);
    }

    private CheckBox flash;

    @Override
    public void onViewCreated(final View view, Bundle savedInstanceState) {
        view.findViewById(R.id.picture).setOnClickListener(this);
        view.findViewById(R.id.info).setOnClickListener(this);

        flash = (CheckBox)view.findViewById(R.id.flash);
        flash.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton cb, boolean checked) {
                // TODO implement
            }
        });

        mTextureView = (AutoFitTextureView) view.findViewById(R.id.texture);

        try {
            decoder = BarcodeDecoder.CreateDecoderForImages( getActivity().getApplicationContext(), BarcodeDecoder.BarcodeType.DMATRIX);
        } catch (DecoderException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
    }

    @Override
    public void onResume() {
        super.onResume();
        startBackgroundThread();

        // When the screen is turned off and turned back on, the SurfaceTexture is already
        // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
        // a camera and start preview from here (otherwise, we wait until the surface is ready in
        // the SurfaceTextureListener).
        if (mTextureView.isAvailable()) {
            openCamera(mTextureView.getWidth(), mTextureView.getHeight());
        } else {
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
    }

    @Override
    public void onPause() {
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    private void requestCameraPermission() {
        if (FragmentCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)) {
            new Utils.ConfirmationDialog().show(getChildFragmentManager(), FRAGMENT_DIALOG);
        } else {
            FragmentCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA},
                    REQUEST_CAMERA_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length != 1 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Utils.ErrorDialog.newInstance(getString(R.string.request_permission))
                        .show(getChildFragmentManager(), FRAGMENT_DIALOG);
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    /**
     * Sets up member variables related to camera.
     *
     * @param width  The width of available size for camera preview
     * @param height The height of available size for camera preview
     */
    private void setUpCameraOutputs(int width, int height) {
        Activity activity = getActivity();
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String cameraId : manager.getCameraIdList()) {
                CameraCharacteristics characteristics
                        = manager.getCameraCharacteristics(cameraId);

                // We don't use a front facing camera in this sample.
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue;
                }

                StreamConfigurationMap map = characteristics.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) {
                    continue;
                }

                // For still image captures, we use the largest available size.
                Size largest = Collections.max(
                        Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)),
                        new Utils.CompareSizesByArea());

                // Find out if we need to swap dimension to get the preview size relative to sensor
                // coordinate.
                int displayRotation = activity.getWindowManager().getDefaultDisplay().getRotation();
                //noinspection ConstantConditions
                mSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                boolean swappedDimensions = false;
                switch (displayRotation) {
                    case Surface.ROTATION_0:
                    case Surface.ROTATION_180:
                        if (mSensorOrientation == 90 || mSensorOrientation == 270) {
                            swappedDimensions = true;
                        }
                        break;
                    case Surface.ROTATION_90:
                    case Surface.ROTATION_270:
                        if (mSensorOrientation == 0 || mSensorOrientation == 180) {
                            swappedDimensions = true;
                        }
                        break;
                    default:
                        Log.e(TAG, "Display rotation is invalid: " + displayRotation);
                }

                Point displaySize = new Point();
                activity.getWindowManager().getDefaultDisplay().getSize(displaySize);
                int rotatedPreviewWidth = width;
                int rotatedPreviewHeight = height;
                int maxPreviewWidth = displaySize.x;
                int maxPreviewHeight = displaySize.y;

                if (swappedDimensions) {
                    rotatedPreviewWidth = height;
                    rotatedPreviewHeight = width;
                    maxPreviewWidth = displaySize.y;
                    maxPreviewHeight = displaySize.x;
                }

                if (maxPreviewWidth > MAX_PREVIEW_WIDTH) {
                    maxPreviewWidth = MAX_PREVIEW_WIDTH;
                }

                if (maxPreviewHeight > MAX_PREVIEW_HEIGHT) {
                    maxPreviewHeight = MAX_PREVIEW_HEIGHT;
                }

                // Danger, W.R.! Attempting to use too large a preview size could  exceed the camera
                // bus' bandwidth limitation, resulting in gorgeous previews but the storage of
                // garbage capture data.
                mPreviewSize = Utils.chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class),
                        rotatedPreviewWidth, rotatedPreviewHeight, maxPreviewWidth,
                        maxPreviewHeight, largest);

                // make image reader read 640x480 (hardcoded)
                mImageReader = ImageReader.newInstance( 640, 480,  ImageFormat.JPEG, /*maxImages*/1);
                mImageReader.setOnImageAvailableListener( mOnImageAvailableListener, mBackgroundHandler);

                // We fit the aspect ratio of TextureView to the size of preview we picked.
                int orientation = getResources().getConfiguration().orientation;
                if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    mTextureView.setAspectRatio(
                            mPreviewSize.getWidth(), mPreviewSize.getHeight());
                } else {
                    mTextureView.setAspectRatio(
                            mPreviewSize.getHeight(), mPreviewSize.getWidth());
                }

                // Check if the flash is supported.
                Boolean available = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                mFlashSupported = available == null ? false : available;

                mCameraId = cameraId;

                BarcodeDecoderLibrary lib = new BarcodeDecoderLibrary();
                boolean activated = lib.isActivated( getActivity() );
                System.out.println( "barcode decoder library activated: " + activated );
                SetOptions();

                // setup pinch zoom
                mTextureView.setOnTouchListener( this);
                //setupZoomHandler(characteristics);
                return;
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (NullPointerException e) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            Utils.ErrorDialog.newInstance(getString(R.string.camera_error))
                    .show(getChildFragmentManager(), FRAGMENT_DIALOG);
        }
    }

    /**
     * Opens the camera specified by {@link Camera2BasicFragment#mCameraId}.
     */
    private void openCamera(int width, int height) {
        if (ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermission();
            return;
        }
        setUpCameraOutputs(width, height);
        configureTransform(width, height);
        Activity activity = getActivity();
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            manager.openCamera(mCameraId, mStateCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
        }
    }

    /**
     * Closes the current {@link CameraDevice}.
     */
    private void closeCamera() {
        try {
            mCameraOpenCloseLock.acquire();
            if (null != mCaptureSession) {
                mCaptureSession.close();
                mCaptureSession = null;
            }
            if (null != mCameraDevice) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
            if (null != mImageReader) {
                mImageReader.close();
                mImageReader = null;
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        } finally {
            mCameraOpenCloseLock.release();
        }
    }

    /**
     * Starts a background thread and its {@link Handler}.
     */
    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    /**
     * Stops the background thread and its {@link Handler}.
     */
    private void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Creates a new {@link CameraCaptureSession} for camera preview.
     */
    private void createCameraPreviewSession() {
        try {
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            assert texture != null;

            // We configure the size of default buffer to be the size of camera preview we want.
            texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());

            // This is the output Surface we need to start preview.
            Surface surface = new Surface(texture);

            // We set up a CaptureRequest.Builder with the output Surface.
            mPreviewRequestBuilder
                    = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mPreviewRequestBuilder.addTarget(surface);
            mPreviewRequestBuilder.addTarget(mImageReader.getSurface());

            // Here, we create a CameraCaptureSession for camera preview.
            mCameraDevice.createCaptureSession(Arrays.asList(surface, mImageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                            // The camera is already closed
                            if (null == mCameraDevice) {
                                return;
                            }

                            // When the session is ready, we start displaying the preview.
                            mCaptureSession = cameraCaptureSession;
                            try {
                                // Auto focus should be continuous for camera preview.
                                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                // Flash is automatically enabled when necessary.
                                setAutoFlash(mPreviewRequestBuilder);

                                // Finally, we start displaying the camera preview.
                                mPreviewRequest = mPreviewRequestBuilder.build();
                                mCaptureSession.setRepeatingRequest(mPreviewRequest,
                                        mCaptureCallback, mBackgroundHandler);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(
                                @NonNull CameraCaptureSession cameraCaptureSession) {
                            showToast("Failed");
                        }
                    }, null
            );
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Configures the necessary {@link android.graphics.Matrix} transformation to `mTextureView`.
     * This method should be called after the camera preview size is determined in
     * setUpCameraOutputs and also the size of `mTextureView` is fixed.
     *
     * @param viewWidth  The width of `mTextureView`
     * @param viewHeight The height of `mTextureView`
     */
    private void configureTransform(int viewWidth, int viewHeight) {
        Activity activity = getActivity();
        if (null == mTextureView || null == mPreviewSize || null == activity) {
            return;
        }
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / mPreviewSize.getHeight(),
                    (float) viewWidth / mPreviewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }
        mTextureView.setTransform(matrix);
    }

    /**
     * Run the precapture sequence for capturing a still image. This method should be called when
     * we get a response in {@link #mCaptureCallback} from {@link #lockFocus()}.
     */
    private void runPrecaptureSequence() {
        try {
            // This is how to tell the camera to trigger.
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            // Tell #mCaptureCallback to wait for the precapture sequence to be set.
            mState = STATE_WAITING_PRECAPTURE;
            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback,
                    mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Retrieves the JPEG orientation from the specified screen rotation.
     *
     * @param rotation The screen rotation.
     * @return The JPEG orientation (one of 0, 90, 270, and 360)
     */
    private int getOrientation(int rotation) {
        // Sensor orientation is 90 for most devices, or 270 for some devices (eg. Nexus 5X)
        // We have to take that into account and rotate JPEG properly.
        // For devices with orientation of 90, we simply return our mapping from ORIENTATIONS.
        // For devices with orientation of 270, we need to rotate the JPEG 180 degrees.
        return (ORIENTATIONS.get(rotation) + mSensorOrientation + 270) % 360;
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.picture: {
                //takePicture();
                // TODO: remove (replace with scan button, with preview not initially available)
                break;
            }
            case R.id.info: {
                Activity activity = getActivity();
                if (null != activity) {
                    new AlertDialog.Builder(activity)
                            .setMessage(R.string.intro_message)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                }
                break;
            }
        }
    }

    private void setAutoFlash(CaptureRequest.Builder requestBuilder) {
        if (mFlashSupported) {
            requestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
        }
    }

    private void SetOptions()
    {
        try
        {
            BarcodeDecoderOptions opt = BarcodeDecoder.Options();
            opt.DataMatrix_FilterMode = getDataMatrixFilterMode();
            opt.DataMatrix_LabelMode = getDataMatrixLabelMode();
            opt.DataMatrix_SpeedMode = getDataMatrixSpeedMode();
            opt.QRCode_CellColor = getQRCodeCellColor();
        }
        catch(Exception e)
        {
            //Failed("SetOptions", e);
        }
    }

    private boolean DMatrix()
    {
        return getPreference("DMatrix", true);
    }

    private int getDataMatrixFilterMode() throws DecoderException
    {
        String str = getPreference("FilterMode");
        if(str == null) return BarcodeDecoderOptions.DataMatrixFilterMode.FM_AUTO;
        if(str.equals("FM_AUTO")) return BarcodeDecoderOptions.DataMatrixFilterMode.FM_AUTO;
        if(str.equals("FM_SHARPMASK")) return BarcodeDecoderOptions.DataMatrixFilterMode.FM_SHARPMASK;
        if(str.equals("FM_BWR")) return BarcodeDecoderOptions.DataMatrixFilterMode.FM_BWR;
        if(str.equals("FM_SM_BWR")) return BarcodeDecoderOptions.DataMatrixFilterMode.FM_SM_BWR;
        throw new DecoderException("Error option");
    }

    private int getDataMatrixSpeedMode() throws DecoderException
    {
        String str = getPreference("SpeedMode");
        if(str == null) return  BarcodeDecoderOptions.DataMatrixSpeeMode.DMSP_REGULAR;
        if(str.equals("SP_REGULAR")) return  BarcodeDecoderOptions.DataMatrixSpeeMode.DMSP_REGULAR;
        if(str.equals("SP_ULTIMATE")) return  BarcodeDecoderOptions.DataMatrixSpeeMode.DMSP_ULTIMATE;
        if(str.equals("SP_EXPRESS")) return  BarcodeDecoderOptions.DataMatrixSpeeMode.DMSP_EXPRESS;
        throw new DecoderException("Error option");
    }

    private int getDataMatrixLabelMode() throws DecoderException
    {
        String str = getPreference("LabelMode");
        if(str == null) return  BarcodeDecoderOptions.DataMatrixLabelMode.LM_REG_DOT;
        if(str.equals("LM_REGULAR")) return  BarcodeDecoderOptions.DataMatrixLabelMode.LM_REGULAR;
        if(str.equals("LM_REG_DOT")) return  BarcodeDecoderOptions.DataMatrixLabelMode.LM_REG_DOT;
        throw new DecoderException("Error option");
    }

    private int getQRCodeCellColor() throws DecoderException
    {
        String str = getPreference("QRCellColor");
        if(str == null) return BarcodeDecoderOptions.QRCodeCellColor.QRCL_BLACKONWHITE;
        if(str.equals("CL_BLACKONWHITE"))
            return BarcodeDecoderOptions.QRCodeCellColor.QRCL_BLACKONWHITE;
        if(str.equals("CL_WHITEONBLACK"))
            return BarcodeDecoderOptions.QRCodeCellColor.QRCL_WHITEONBLACK;
        if(str.equals("CL_ANY")) return BarcodeDecoderOptions.QRCodeCellColor.QRCL_ANY;
        throw new DecoderException("Error option");
    }

    private String getPreference(String key)
    {
        SharedPreferences SP = PreferenceManager.getDefaultSharedPreferences( getActivity().getBaseContext() );
        String str = SP.getString(key, "");
        if(str != null && str.length() == 0) return null;
        return str;
    }

    private Boolean getPreference(String key, boolean def)
    {
        try
        {
            SharedPreferences SP = PreferenceManager.getDefaultSharedPreferences( getActivity().getBaseContext() );
            return SP.getBoolean(key, def);
        }
        catch(Exception e)
        {
            //Failed("get key", e);
            return def;
        }
    }

    public float finger_spacing = 0;
    public int zoom_level = 7;

    public boolean onTouch(View v, MotionEvent event) {
        try {
            Activity activity = getActivity();
            CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(mCameraId);
            float maxzoom = (characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM))*10;

            Rect m = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            int action = event.getAction();
            float current_finger_spacing;

            if (event.getPointerCount() > 1) {
                // Multi touch logic
                current_finger_spacing = getFingerSpacing(event);

                if(finger_spacing != 0){
                    if(current_finger_spacing > finger_spacing && maxzoom > zoom_level){
                        zoom_level++;

                    }
                    else if (current_finger_spacing < finger_spacing && zoom_level > 1){
                        zoom_level--;

                    }
                    int minW = (int) (m.width() / maxzoom);
                    int minH = (int) (m.height() / maxzoom);
                    int difW = m.width() - minW;
                    int difH = m.height() - minH;
                    int cropW = difW /100 *(int)zoom_level;
                    int cropH = difH /100 *(int)zoom_level;
                    cropW -= cropW & 3;
                    cropH -= cropH & 3;
                    Rect zoom = new Rect(cropW, cropH, m.width() - cropW, m.height() - cropH);
                    mPreviewRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, zoom);
                }
                finger_spacing = current_finger_spacing;
            }
            else{
                if (action == MotionEvent.ACTION_UP) {
                    //single touch logic
                }
            }

            try {
                mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(), mCaptureCallback,
                        null);
            }
            catch (CameraAccessException e) {
                e.printStackTrace();
            }
            catch (NullPointerException ex)
            {
                ex.printStackTrace();
            }
        }
        catch (CameraAccessException e)
        {
            throw new RuntimeException("can not access camera.", e);
        }

        return true;
    }

    //Determine the space between the first two fingers
    @SuppressWarnings("deprecation")
    private float getFingerSpacing(MotionEvent event) {

        float x = event.getX(0) - event.getX(1);
        float y = event.getY(0) - event.getY(1);
        return (float) Math.sqrt(x * x + y * y);
    }
}
