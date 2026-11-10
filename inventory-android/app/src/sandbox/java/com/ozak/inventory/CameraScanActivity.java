package com.ozak.inventory;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.hardware.camera2.*;
import android.media.*;
import android.os.*;
import android.util.Size;
import android.view.*;
import android.widget.*;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import java.nio.ByteBuffer;
import java.util.*;

/** Local camera decode only. No images saved, no network, no acceptance claim. */
public final class CameraScanActivity extends Activity {
    private TextureView preview;private TextView status;private HandlerThread worker;private Handler cameraHandler;
    private CameraDevice camera;private CameraCaptureSession session;private ImageReader reader;private Surface surface;
    private Size size;private int orientation,generation;private boolean resumed,opening,completed;private long lastFrame;
    private BarcodeFormat format;
    @Override public void onCreate(Bundle b){super.onCreate(b);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON|WindowManager.LayoutParams.FLAG_SECURE);
        format="QR_CODE".equals(getIntent().getStringExtra("format"))?BarcodeFormat.QR_CODE:BarcodeFormat.DATA_MATRIX;
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(20,32,20,32);root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        status=new TextView(this);status.setText("SYNTHETIC LABEL — "+format+"\nKeep one entire code in view, including its white border. Local read only; server acceptance is separate.");status.setTextSize(18);root.addView(status);
        preview=new TextureView(this);root.addView(preview,new LinearLayout.LayoutParams(-1,0,1));Button cancel=new Button(this);cancel.setText("Cancel scan");cancel.setOnClickListener(v->finish());root.addView(cancel);setContentView(root);
        preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener(){public void onSurfaceTextureAvailable(SurfaceTexture t,int w,int h){open();}public void onSurfaceTextureSizeChanged(SurfaceTexture t,int w,int h){map();}public boolean onSurfaceTextureDestroyed(SurfaceTexture t){return true;}public void onSurfaceTextureUpdated(SurfaceTexture t){}});
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.CAMERA},1);
    }
    @Override protected void onResume(){super.onResume();resumed=true;worker=new HandlerThread("SyntheticCamera");worker.start();cameraHandler=new Handler(worker.getLooper());open();}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){super.onRequestPermissionsResult(request,permissions,grants);if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)open();else status.setText("Camera permission is required. Cancel to return.");}
    private void error(){runOnUiThread(()->{if(resumed)status.setText("Camera could not start. Cancel and retry after checking camera access.");});}
    private void open(){if(!resumed||opening||camera!=null||cameraHandler==null||!preview.isAvailable()||checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)return;
        opening=true;final int ticket=++generation;
        try{CameraManager manager=(CameraManager)getSystemService(CAMERA_SERVICE);String selected=null;CameraCharacteristics characteristics=null;
            for(String id:manager.getCameraIdList()){CameraCharacteristics c=manager.getCameraCharacteristics(id);if(Objects.equals(c.get(CameraCharacteristics.LENS_FACING),CameraCharacteristics.LENS_FACING_BACK)){selected=id;characteristics=c;break;}}
            if(selected==null)throw new IllegalStateException();orientation=characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            Size[] yuv=characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.YUV_420_888);List<Size> textures=Arrays.asList(characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(SurfaceTexture.class));
            long best=Long.MAX_VALUE;for(Size s:yuv){long distance=Math.abs((long)s.getWidth()*s.getHeight()-1280L*720);if(textures.contains(s)&&distance<best){size=s;best=distance;}}
            if(size==null)throw new IllegalStateException();final int[] af=characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            reader=ImageReader.newInstance(size.getWidth(),size.getHeight(),ImageFormat.YUV_420_888,2);reader.setOnImageAvailableListener(r->frame(r,ticket),cameraHandler);
            manager.openCamera(selected,new CameraDevice.StateCallback(){public void onOpened(CameraDevice c){if(!resumed||ticket!=generation){c.close();return;}camera=c;opening=false;
                try{preview.getSurfaceTexture().setDefaultBufferSize(size.getWidth(),size.getHeight());surface=new Surface(preview.getSurfaceTexture());CaptureRequest.Builder request=c.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);request.addTarget(surface);request.addTarget(reader.getSurface());request.set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_ON);if(af!=null)for(int mode:af)if(mode==CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)request.set(CaptureRequest.CONTROL_AF_MODE,mode);
                    c.createCaptureSession(Arrays.asList(surface,reader.getSurface()),new CameraCaptureSession.StateCallback(){public void onConfigured(CameraCaptureSession s){if(!resumed||ticket!=generation){s.close();return;}session=s;try{s.setRepeatingRequest(request.build(),null,cameraHandler);runOnUiThread(()->map());}catch(Exception e){error();}}public void onConfigureFailed(CameraCaptureSession s){error();}},cameraHandler);
                }catch(Exception e){error();}}
                public void onDisconnected(CameraDevice c){c.close();error();}public void onError(CameraDevice c,int code){c.close();error();}},cameraHandler);
        }catch(Exception e){opening=false;error();}
    }
    private void map(){if(size==null||preview.getWidth()==0)return;float w=size.getWidth(),h=size.getHeight(),vw=preview.getWidth(),vh=preview.getHeight();int rotation=(orientation-getWindowManager().getDefaultDisplay().getRotation()*90+360)%360;Matrix toView=new Matrix();toView.postTranslate(-w/2,-h/2);toView.postRotate(rotation);float scale=Math.max(vw/(rotation%180==0?w:h),vh/(rotation%180==0?h:w));toView.postScale(scale,scale);toView.postTranslate(vw/2,vh/2);Matrix base=new Matrix();base.postTranslate(-w/2,-h/2);base.postRotate(orientation);base.postScale(vw/(orientation%180==0?w:h),vh/(orientation%180==0?h:w));base.postTranslate(vw/2,vh/2);float[] source={0,0,w,0,0,h},target=source.clone();base.mapPoints(source);toView.mapPoints(target);Matrix correction=new Matrix();correction.setPolyToPoly(source,0,target,0,3);preview.setTransform(correction);}
    private void frame(ImageReader input,int ticket){Image image=null;try{image=input.acquireLatestImage();if(image==null||completed||!resumed||ticket!=generation)return;long now=SystemClock.elapsedRealtime();if(now-lastFrame<250)return;lastFrame=now;
        int w=image.getWidth(),h=image.getHeight();Image.Plane plane=image.getPlanes()[0];ByteBuffer buffer=plane.getBuffer();byte[] pixels=new byte[w*h];for(int y=0;y<h;y++)for(int x=0;x<w;x++)pixels[y*w+x]=buffer.get(y*plane.getRowStride()+x*plane.getPixelStride());
        LuminanceSource luminance=new PlanarYUVLuminanceSource(pixels,w,h,0,0,w,h,false);Map<DecodeHintType,Object> hints=new EnumMap<>(DecodeHintType.class);hints.put(DecodeHintType.POSSIBLE_FORMATS,Collections.singletonList(format));hints.put(DecodeHintType.TRY_HARDER,true);
        for(int invert=0;invert<2;invert++){try{Result result=new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(invert==0?luminance:luminance.invert())),hints);completed=true;String value=result.getText();runOnUiThread(()->{if(resumed&&ticket==generation){setResult(RESULT_OK,new Intent().putExtra("payload",value).putExtra("format",format.name()));finish();}});return;}catch(ReaderException ignored){}}
    }catch(Exception ignored){}finally{if(image!=null)image.close();}}
    @Override protected void onPause(){resumed=false;generation++;opening=false;if(session!=null){session.close();session=null;}if(camera!=null){camera.close();camera=null;}if(reader!=null){reader.close();reader=null;}if(surface!=null){surface.release();surface=null;}if(worker!=null)worker.quitSafely();super.onPause();}
}
