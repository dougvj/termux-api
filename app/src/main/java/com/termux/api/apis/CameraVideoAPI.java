package com.termux.api.apis;

import android.content.Context;
import android.content.Intent;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.MediaRecorder;
import android.media.MediaCodecInfo;
// import android.media.EncoderProfiles;
import android.os.Looper;
import android.util.Size;
import android.view.Surface;
import android.view.WindowManager;

import java.util.Timer;
import java.util.TimerTask;

import com.termux.api.TermuxApiReceiver;
import com.termux.api.util.ResultReturner;
import com.termux.shared.errors.Error;
import com.termux.shared.file.FileUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.file.TermuxFileUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

class CameraVideoAPIParameters {
    public final File outputFile;
    public final int cameraId;
    public final Integer duration;
    public final Integer fps;
    public final Integer bitrate;
    public final Integer resolutionY;
    public final int autoExposureMode;
    public final int autoFocusMode;
    public final int autoWhiteBalanceMode;
    public final int encoder;
    public final int encoderProfile;
    public final int encoderLevel;
    public final Integer orientation;

    private static int parseEncoder(String encoder) {
        if (encoder == null) {
            return MediaRecorder.VideoEncoder.DEFAULT;
        }
        switch (encoder) {
            case "default":
                return MediaRecorder.VideoEncoder.DEFAULT;
            case "h264":
                return MediaRecorder.VideoEncoder.H264;
            case "h263":
                return MediaRecorder.VideoEncoder.H263;
            case "vp8":
                return MediaRecorder.VideoEncoder.VP8;
            /*case "vp9":
                return MediaRecorder.VideoEncoder.VP9;*/
            case "hevc":
                return MediaRecorder.VideoEncoder.HEVC;
            /*case "av1":
                return MediaRecorder.VideoEncoder.AV1;*/
            default:
                throw new RuntimeException("Invalid encoder: " + encoder);
        }
    }

    public CameraVideoAPIParameters(
          Intent intent) {
        final String filePath = intent.getStringExtra("file");
        // Get canonical path of videoFilePath
        String videoFilePath = TermuxFileUtils.getCanonicalPath(filePath, null, true);
        String videoDirPath = FileUtils.getFileDirname(videoFilePath);
        if (filePath == null || filePath.isEmpty()) {
            throw new RuntimeException("Missing file path");
        }

        // If workingDirectory is not a directory, or is not readable or writable, then just return
        // Creation of missing directory and setting of read, write and execute permissions are only done if workingDirectory is
        // under allowed termux working directory paths.
        // We try to set execute permissions, but ignore if they are missing, since only read and write permissions are required
        // for working directories.
        Error error = TermuxFileUtils.validateDirectoryFileExistenceAndPermissions("video directory", videoDirPath,
                true, true, true,
                false, true);
        if (error != null) {
            throw new RuntimeException(error.getErrorLogString());
        }
        this.outputFile = new File(videoFilePath);
        final String cameraId = Objects.toString(intent.getStringExtra("cameraId"), "0");
        this.cameraId = Integer.parseInt(cameraId);
        final String durationStr = intent.getStringExtra("duration");
        this.duration = durationStr == null ? 5 : Integer.parseInt(durationStr);
        final String bitrateStr = intent.getStringExtra("bitrate");
        this.bitrate = bitrateStr == null ? 5000000 : Integer.parseInt(bitrateStr);
        final String resolutionYStr = intent.getStringExtra("resolutionY");
        this.resolutionY = resolutionYStr == null ? null : Integer.parseInt(resolutionYStr);
        final String fpsStr = intent.getStringExtra("fps");
        this.fps = fpsStr == null ? 30 : Integer.parseInt(fpsStr);
        final String encoderStr = intent.getStringExtra("encoder");
        this.encoder = encoderStr == null ? MediaRecorder.VideoEncoder.H264 : parseEncoder(encoderStr);
        final String encoderProfileStr = intent.getStringExtra("encoderProfile");
        // this.encoderProfile = encoderProfileStr == null ? EncoderProfiles.VideoProfile.YUV_420 : Integer.parseInt(encoderProfileStr);
        this.encoderProfile = encoderProfileStr == null ? 1 : Integer.parseInt(encoderProfileStr);
        final String encoderLevelStr = intent.getStringExtra("encoderLevel");
        this.encoderLevel = encoderLevelStr == null ? MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline : Integer.parseInt(encoderLevelStr);
        final String autoExposureStr = intent.getStringExtra("autoExposure");
        this.autoExposureMode = autoExposureStr == null ? CameraMetadata.CONTROL_AE_MODE_ON : Integer.parseInt(autoExposureStr);
        final String autoFocusStr = intent.getStringExtra("autoFocus");
        this.autoFocusMode = autoFocusStr == null ? CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO : Integer.parseInt(autoFocusStr);
        final String autoWhiteBalanceStr = intent.getStringExtra("autoWhiteBalance");
        this.autoWhiteBalanceMode = autoWhiteBalanceStr == null ? CameraMetadata.CONTROL_AWB_MODE_AUTO : Integer.parseInt(autoWhiteBalanceStr);
        final String orientationStr = intent.getStringExtra("orientation");
        this.orientation = orientationStr == null ? null : Integer.parseInt(orientationStr);
        if (this.orientation != null) {
          switch (this.orientation) {
              case 0:
              case 90:
              case 180:
              case 270:
                  break;
              default:
                  throw new RuntimeException("Invalid orientation: " + this.orientation);
          }
        }

    }

    public String toString() {
        return "CameraVideoAPIParameters{" +
                "outputFile=" + outputFile +
                ", duration=" + duration +
                ", fps=" + fps +
                ", bitrate=" + bitrate +
                ", resolutionY=" + resolutionY +
                ", autoExposureMode=" + autoExposureMode +
                ", autoFocusMode=" + autoFocusMode +
                ", autoWhiteBalanceMode=" + autoWhiteBalanceMode +
                ", encoder=" + encoder +
                ", encoderProfile=" + encoderProfile +
                ", encoderLevel=" + encoderLevel +
                '}';
    }
}

public class CameraVideoAPI {

    private static final String LOG_TAG = "CameraVideoAPI";

    public static void onReceive(TermuxApiReceiver apiReceiver, final Context context, Intent intent) {
        Logger.logDebug(LOG_TAG, "onReceive");
        ResultReturner.returnData(apiReceiver, intent, stdout -> {
            try {
                final CameraVideoAPIParameters params = new CameraVideoAPIParameters(intent);
                Logger.logInfo(LOG_TAG, "CameraVideoAPIParameters: " + params.toString());
                takeVideo(stdout, context, params);
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Error taking video", e);
                stdout.println("ERROR: " + e.getMessage());
            }
        });
    }

    private static void reportError(PrintWriter stdout, String message) {
        Logger.logError(LOG_TAG, message);
        stdout.println("ERROR: " + message);
    }

    private static void takeVideo(final PrintWriter stdout, final Context context, final CameraVideoAPIParameters params) {
        try {
            final CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);

            Looper.prepare();
            final Looper looper = Looper.myLooper();
            //noinspection MissingPermission
            manager.openCamera(Integer.toString(params.cameraId), new CameraDevice.StateCallback() {
                @Override
                public void onOpened(final CameraDevice camera) {
                    try {
                        proceedWithOpenedCamera(context, manager, camera, looper, stdout, params);
                    } catch (Exception e) {
                        Logger.logStackTraceWithMessage(LOG_TAG, "Error proceeding with opened camera", e);
                        reportError(stdout, "Error proceeding with opened camera: " + e.getMessage());
                        closeCamera(camera, looper, null);
                    }
                }
                @Override
                public void onDisconnected(CameraDevice camera) {
                    Logger.logInfo(LOG_TAG, "onDisconnected() from camera");
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    reportError(stdout, "Error opening camera: " + error);
                    closeCamera(camera, looper, null);
                }
            }, null);

            Looper.loop();
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Error getting camera", e);
            reportError(stdout, "Error getting camera: " + e.getMessage());
        }
    }

    static void proceedWithOpenedCamera(final Context context, final CameraManager manager, final CameraDevice camera, final Looper looper, final PrintWriter stdout, CameraVideoAPIParameters params) throws CameraAccessException, IOException {
        final CameraCharacteristics characteristics = manager.getCameraCharacteristics(camera.getId());

        int autoExposureMode = CameraMetadata.CONTROL_AE_MODE_OFF;
        for (int supportedMode : characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)) {
            if (supportedMode == params.autoExposureMode) {
                autoExposureMode = supportedMode;
            }
        }
        final int autoExposureModeFinal = autoExposureMode;

        // Use closest available size
        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Comparator<Size> bySize = (lhs, rhs) -> {
            // Cast to ensure multiplications won't overflow:
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());
        };
        List<Size> sizes = Arrays.asList(map.getOutputSizes(ImageFormat.JPEG));
        Size which;
        if (params.resolutionY == null) {
          which = Collections.max(sizes, bySize);
        } else {
            which = Collections.min(sizes, (lhs, rhs) -> {
                int lhsDiff = Math.abs(lhs.getHeight() - params.resolutionY);
                int rhsDiff = Math.abs(rhs.getHeight() - params.resolutionY);
                return Integer.compare(lhsDiff, rhsDiff);
            });
        }
        final MediaRecorder recorder = new MediaRecorder();
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        final String extension = params.outputFile.getName().substring(params.outputFile.getName().lastIndexOf('.') + 1);
        if (extension.equals("mp4"))
          recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        else if (extension.equals("3gp"))
          recorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
        else if (extension.equals("webm"))
          recorder.setOutputFormat(MediaRecorder.OutputFormat.WEBM);
        else if (extension.equals("ts"))
          recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_2_TS);
        else
          throw new RuntimeException("Unknown extension: " + extension);
        Logger.logInfo(LOG_TAG, "Using extension: " + extension);
        recorder.setMaxDuration(params.duration * 1000);
        recorder.setOutputFile(params.outputFile.getAbsolutePath());
        recorder.setVideoEncodingBitRate(params.bitrate);
        recorder.setVideoFrameRate(params.fps);
        recorder.setCaptureRate(params.fps);
        recorder.setVideoSize(which.getWidth(), which.getHeight());
        if (params.orientation != null) {
          recorder.setOrientationHint(params.orientation);
        }
        recorder.setVideoEncoder(params.encoder);
        recorder.setVideoEncodingProfileLevel(params.encoderProfile, params.encoderLevel);
        //recorder.setVideoEncodingProfileLevel(MediaRecorder.VideoEncoder.DEFAULT, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
        // Open the mic for recording
        // recorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
        // recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        try {
          recorder.prepare();
        } catch (Exception e) {
          Logger.logStackTraceWithMessage(LOG_TAG, "Error preparing recorder", e);
          reportError(stdout, "Error preparing recorder: " + e.getMessage());
          closeCamera(camera, looper, recorder);
          return;
        }
        Surface surface = recorder.getSurface();
        camera.createCaptureSession(Collections.singletonList(surface), new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(CameraCaptureSession session) {
                try {
                    CaptureRequest.Builder builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                    builder.addTarget(surface);
                    builder.set(CaptureRequest.CONTROL_AE_MODE, autoExposureModeFinal);
                    session.setRepeatingRequest(builder.build(), null, null);
                    recorder.setOnErrorListener((mr, what, extra) -> {
                        reportError(stdout, "MediaRecorder error: " + what + " " + extra);
                        closeCamera(camera, looper, recorder);
                    });
                    recorder.setOnInfoListener((mr, what, extra) -> {
                        Logger.logInfo(LOG_TAG, "MediaRecorder info: " + what + " " + extra);
                        if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                            recorder.stop();
                            surface.release();
                            session.close();
                            closeCamera(camera, looper, recorder);
                        }
                    });
                    recorder.start();
                    Logger.logInfo(LOG_TAG, "Recording video to " + params.outputFile.getAbsolutePath());
                } catch (CameraAccessException e) {
                    Logger.logStackTraceWithMessage(LOG_TAG, "Error creating capture request", e);
                    session.close();
                    closeCamera(camera, looper, recorder);
                }
            }

            @Override
            public void onConfigureFailed(CameraCaptureSession session) {
                Logger.logInfo(LOG_TAG, "onConfigureFailed() for camera capture session");
                closeCamera(camera, looper, recorder);
            }
        }, null);
    }


    static void closeCamera(CameraDevice camera, Looper looper, MediaRecorder recorder) {
        try {
            camera.close();
        } catch (RuntimeException e) {
            Logger.logInfo(LOG_TAG, "Exception closing camera: " + e.getMessage());
        }
        if (looper != null) looper.quit();
        if (recorder != null) recorder.release();
    }

}
