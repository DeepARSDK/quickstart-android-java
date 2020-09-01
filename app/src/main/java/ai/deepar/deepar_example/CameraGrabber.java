package ai.deepar.deepar_example;

import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import ai.deepar.ar.CameraResolutionPreset;
import ai.deepar.ar.DeepAR;


/**
 * Created by luka on 19/04/17.
 * This is an example implementation of how the camera frames are fed to the DeepAR SDK. Feel free
 * to use it as is, or modify for your own needs.
 */



public class CameraGrabber
{
    private static final String TAG = CameraGrabber.class.getSimpleName();

    private static final int NUMBER_OF_BUFFERS=2;

    private static int currentCameraDevice = Camera.CameraInfo.CAMERA_FACING_FRONT;

    private int width = 640;
    private int height = 480;
    private CameraResolutionPreset resolutionPreset = CameraResolutionPreset.P640x480;

    private int screenOrientation = 0;

    public CameraGrabber() {
    }

    public CameraGrabber(int cameraDevice) {
        CameraGrabber.currentCameraDevice = cameraDevice;
    }

    public void setFrameReceiver(DeepAR receiver) {
        if (mThread != null) {
            mThread.setFrameReceiver(receiver, currentCameraDevice);
        }
    }

    public void initCamera(CameraGrabberListener listener) {
        if (mThread == null) {
            mThread = new CameraHandlerThread(listener, width, height, screenOrientation);
        }

        synchronized (mThread) {
            mThread.openCamera();
        }
    }


    public void startPreview() {
        if (mThread != null && mThread.camera != null) {
            mThread.camera.startPreview();
        }
    }

    public void stopPreview() {
        if (mThread != null && mThread.camera != null ) {
            mThread.camera.stopPreview();
        }
    }

    public void changeCameraDevice(int cameraDevice) {
        currentCameraDevice = cameraDevice;
        initCamera(new CameraGrabberListener() {
            @Override
            public void onCameraInitialized() {
                startPreview();
            }

            @Override
            public void onCameraError(String errorMsg) {
                Log.e(TAG, errorMsg);
            }
        });

    }

    public int getCurrCameraDevice() {
        return currentCameraDevice;
    }

    public void releaseCamera() {
        if (mThread != null) {
            mThread.releaseCamera();
            mThread = null;
        }
    }

    private CameraHandlerThread mThread = null;

    public CameraResolutionPreset getResolutionPreset() {
        return resolutionPreset;
    }

    public void setResolutionPreset(CameraResolutionPreset resolutionPreset) {

        this.resolutionPreset = resolutionPreset;

        width = this.resolutionPreset.getWidth();
        height = this.resolutionPreset.getHeight();

        if (mThread != null) {
            mThread.reinitCamera(width, height);
        }

    }

    public int getScreenOrientation() {
        return screenOrientation;
    }

    public void setScreenOrientation(int screenOrientation) {
        this.screenOrientation = screenOrientation;
    }
    public Camera getCamera() {
        if (mThread == null) {
            return null;
        }
        return mThread.camera;
    }

    private static class CameraHandlerThread extends HandlerThread {
        Handler mHandler = null;
        public Camera camera;
        public SurfaceTexture surface;
        private DeepAR frameReceiver;
        private ByteBuffer[] buffers;
        private int currentBuffer = 0;
        private CameraGrabberListener listener;
        private int cameraOrientation;
        private int width;
        private  int height;
        private  int screenOrientation;
        CameraHandlerThread(CameraGrabberListener listener, int width, int height, int screenOrientation) {
            super("CameraHandlerThread");

            this.listener = listener;
            this.width = width;
            this.height = height;
            this.screenOrientation = screenOrientation;
            start();
            mHandler = new Handler(getLooper());
        }

        synchronized void notifyCameraOpened() {
            notify();
        }

        synchronized void releaseCamera() {
            if (camera == null) {
                return;
            }
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    camera.stopPreview();
                    camera.setPreviewCallbackWithBuffer(null);
                    camera.release();
                    camera = null;
                    mHandler = null;
                    listener = null;
                    frameReceiver = null;
                    surface = null;
                    buffers = null;
                    quitSafely();
                }
            });
        }

        synchronized void setFrameReceiver(DeepAR receiver, final int cameraDevice) {
            frameReceiver = receiver;
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (camera == null) {
                        return;
                    }
                    camera.setPreviewCallbackWithBuffer(new Camera.PreviewCallback() {
                        public void onPreviewFrame(byte[] data, Camera arg1) {
                            if (frameReceiver != null) {
                                buffers[currentBuffer].put(data);
                                buffers[currentBuffer].position(0);
                                if (frameReceiver != null) {
                                    frameReceiver.receiveFrame(buffers[currentBuffer], width,height, cameraOrientation, cameraDevice == Camera.CameraInfo.CAMERA_FACING_FRONT);
                                }
                                currentBuffer = ( currentBuffer + 1 ) % NUMBER_OF_BUFFERS;
                            }
                            if (camera != null) {
                                camera.addCallbackBuffer(data);
                            }
                        }
                    });
                }
            });
        }

        private void init()  {

            if (camera != null) {
                camera.setPreviewCallbackWithBuffer(null);
                camera.stopPreview();
                camera.release();
                camera = null;
            }

            if (surface == null) {
                surface = new SurfaceTexture(0);
            }

            Camera.CameraInfo info = new Camera.CameraInfo();
            int cameraId = -1;
            int numberOfCameras = Camera.getNumberOfCameras();
            for(int i = 0; i < numberOfCameras; i++)
            {
                Camera.getCameraInfo(i, info);
                if(info.facing == currentCameraDevice) {
                    cameraOrientation = info.orientation;

                    if (currentCameraDevice == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                        cameraOrientation = (info.orientation + screenOrientation) % 360;
                        //cameraOrientation = (360 - cameraOrientation) % 360;
                    } else {
                        cameraOrientation = (info.orientation - screenOrientation + 360) % 360;
                    }
                    cameraId = i;
                    break;
                }
            }

            if(cameraId == -1) {
                if (listener != null) {
                    listener.onCameraError("Camera not found error.");
                }
                return;
            }

            try {
                camera = Camera.open(cameraId);
            } catch (Exception e) {
                // event error
                if (listener != null) {
                    listener.onCameraError("Could not open camera device. Could be used by another process.");
                }
                return;
            }

            Camera.Parameters params = camera.getParameters();


            boolean presetExists = false;
            List<Camera.Size> availableSizes = camera.getParameters().getSupportedPictureSizes();
            for (Camera.Size size : availableSizes) {
                if (size.width == width && size.height == height) {
                    presetExists = true;
                    break;
                }
            }

            if (!presetExists) {
                Log.e(TAG, "Selected resolution preset is not available on this device");
                listener.onCameraError("Selected preset resolution of " + width + "x" + height + " is not supported for this device.");
                return;
            }

            params.setPreviewSize(width, height);
            params.setPictureSize(width, height);
            params.setPictureFormat(PixelFormat.JPEG);
            params.setJpegQuality(90);
            params.setPreviewFormat(ImageFormat.NV21);

            /*
            List<int[]> ranges = params.getSupportedPreviewFpsRange();
            int[] bestRange = {0,0};

            for (int[] range : ranges) {
                if (range[0] > bestRange[0]) {
                    bestRange[0] = range[0];
                    bestRange[1] = range[1];
                }
            }
            params.setPreviewFpsRange(bestRange[0], bestRange[1]);
            */

            camera.setParameters(params);


            buffers = new ByteBuffer[NUMBER_OF_BUFFERS];
            for (int i = 0; i < NUMBER_OF_BUFFERS; i++) {
                buffers[i] = ByteBuffer.allocateDirect(width * height * 3 / 2);
                buffers[i].order(ByteOrder.nativeOrder());
                buffers[i].position(0);
                byte[] buffer = new byte[width * height * 3 / 2];
                camera.addCallbackBuffer(buffer);
            }


            try {
                camera.setPreviewTexture(surface);
            } catch(IOException ioe)  {
                if (listener != null) {
                    listener.onCameraError("Error setting preview texture.");
                }
                return;
            }

            if (frameReceiver != null) {
                setFrameReceiver(frameReceiver, currentCameraDevice);
            }
            if (listener != null) {
                listener.onCameraInitialized();
            }
        }

        void reinitCamera(final int newWidth, final int newHeight) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    camera.stopPreview();
                    camera.setPreviewCallbackWithBuffer(null);
                    camera.release();
                    camera = null;
                    width = newWidth;
                    height = newHeight;
                    init();
                }
            });
        }

        void openCamera() {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    init();
                    notifyCameraOpened();
                }
            });
            try {
                wait();
            }
            catch (InterruptedException e) {
                Log.w(TAG, "wait was interrupted");
            }
        }
    }

};