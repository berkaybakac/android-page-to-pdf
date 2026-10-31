package com.boran.pdf;

import android.accessibilityservice.*;
import android.app.KeyguardManager;
import android.content.*;
import android.graphics.*;
import android.hardware.HardwareBuffer;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.util.Log;
import android.view.*;
import android.view.accessibility.*;
import android.widget.*;
import java.io.*;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public class CaptureService extends AccessibilityService {
    public static CaptureService instance;
    static final String ISLER="air.com.fernus.islermobil";
    final Handler main=new Handler(Looper.getMainLooper());
    final ExecutorService io=Executors.newSingleThreadExecutor();
    LinearLayout panel; TextView status; Button startButton,selectButton,closeButton; View selector;
    int requested=3,mode,index,first,stable,session,width,height,rotation;
    boolean running,busy,finishing,isler,destroyed;
    String stopReason,sourcePackage,lastUri="",runName;
    Rect crop,windowBounds; byte[] candidate,previous;
    final Set<String> hashes=new HashSet<>();
    long deadline,started; RasterPdf pdf; File localPdf; FileWriter log;
    SharedPreferences prefs(){return getSharedPreferences("boran",MODE_PRIVATE);}
    int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    @Override protected void onServiceConnected(){instance=this;Log.i("BoranPDF","READY");}
    final Runnable readyActions=()->{
        if(panel==null||running||busy||finishing||!lastUri.isEmpty())return;
        AccessibilityWindowInfo w=sourceWindow();boolean other=w!=null&&w.getRoot()!=null&&!ISLER.equals(String.valueOf(w.getRoot().getPackageName()));
        selectButton.setVisibility(mode==0&&other?View.VISIBLE:View.GONE);
    };
    @Override public void onAccessibilityEvent(AccessibilityEvent event){if(panel!=null&&!running&&!busy&&!finishing&&lastUri.isEmpty()){main.removeCallbacks(readyActions);main.postDelayed(readyActions,200);}}
    @Override public void onInterrupt(){requestStop("Erişilebilirlik izni kesildi");}
    @Override public void onDestroy(){destroyed=true;instance=null;requestStop("Uygulama kapandı");removeSelector();removePanel();if(!busy&&!finishing)io.shutdown();super.onDestroy();}
    public boolean prepare(int count,int method){
        if(running||busy||finishing){Toast.makeText(this,"Önce devam eden çekimi durdurun.",Toast.LENGTH_SHORT).show();return false;}
        requested=count;mode=method;lastUri="";showPanel();showReady();return true;
    }
    Button button(String text,LinearLayout parent){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setTextSize(13);parent.addView(b);return b;}
    void showPanel(){
        if(panel!=null)return;
        panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(8),dp(4),dp(8),dp(4));panel.setBackgroundColor(0xf2e9f3ef);
        status=new TextView(this);status.setTextSize(13);status.setTextColor(Color.rgb(20,50,45));status.setMaxWidth(dp(390));panel.addView(status);
        LinearLayout row=new LinearLayout(this);panel.addView(row);startButton=button("Başlat",row);selectButton=button("İleri düğmesini göster",row);closeButton=button("×",row);
        startButton.setOnClickListener(v->{if(running)requestStop("Kullanıcı durdurdu");else if(!lastUri.isEmpty())openPdf(this,lastUri);else begin();});
        selectButton.setOnClickListener(v->selectPoint());closeButton.setOnClickListener(v->{if(running)requestStop("Kullanıcı durdurdu");else if(!finishing){removeSelector();removePanel();}});
        WindowManager.LayoutParams lp=new WindowManager.LayoutParams(-2,-2,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,PixelFormat.TRANSLUCENT);
        lp.gravity=Gravity.TOP|Gravity.CENTER_HORIZONTAL;lp.y=dp(30);getSystemService(WindowManager.class).addView(panel,lp);
    }
    void showReady(){status.setText("Boran PDF · "+requested+" sayfa · Başlangıç sayfanı aç");startButton.setText("Başlat");startButton.setEnabled(true);selectButton.setVisibility(mode==0?View.VISIBLE:View.GONE);selectButton.setEnabled(true);closeButton.setEnabled(true);}
    void removePanel(){if(panel!=null){getSystemService(WindowManager.class).removeView(panel);panel=null;}}
    void removeSelector(){if(selector!=null){getSystemService(WindowManager.class).removeView(selector);selector=null;}}
    AccessibilityWindowInfo sourceWindow(){
        for(AccessibilityWindowInfo w:getWindows())if(w.getType()==AccessibilityWindowInfo.TYPE_APPLICATION&&(w.isFocused()||w.isActive())){
            AccessibilityNodeInfo r=w.getRoot();if(r!=null&&!getPackageName().equals(String.valueOf(r.getPackageName())))return w;
        }return null;
    }
    AccessibilityNodeInfo find(AccessibilityNodeInfo node,String label){
        if(node==null)return null;if(label.equals(String.valueOf(node.getContentDescription()))||label.equals(String.valueOf(node.getText())))return node;
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo r=find(node.getChild(i),label);if(r!=null)return r;}return null;
    }
    int number(AccessibilityNodeInfo root){
        AccessibilityNodeInfo bar=find(root,"Sayfa Görünümü");if(bar==null)return -1;
        for(int i=0;i<bar.getChildCount();i++){AccessibilityNodeInfo c=bar.getChild(i);if(c==null)continue;String s=String.valueOf(c.getContentDescription());if(s.matches("[0-9]+"))return Integer.parseInt(s);}return -1;
    }
    Rect pageArea(AccessibilityNodeInfo root,Rect win){
        if(!isler)return new Rect(win);AccessibilityNodeInfo bar=find(root,"Sayfa Görünümü");if(bar==null)return null;Rect b=new Rect();bar.getBoundsInScreen(b);return largestPage(root,b.top,win.width());
    }
    boolean empty(CharSequence s){return s==null||s.length()==0;}
    Rect largestPage(AccessibilityNodeInfo node,int bottom,int screenWidth){
        if(node==null)return null;Rect best=null,b=new Rect();node.getBoundsInScreen(b);
        if(node.isClickable()&&empty(node.getContentDescription())&&empty(node.getText())&&b.width()>=screenWidth*.8&&b.height()>screenWidth*.7&&b.bottom<=bottom)best=new Rect(b);
        for(int i=0;i<node.getChildCount();i++){Rect r=largestPage(node.getChild(i),bottom,screenWidth);if(r!=null&&(best==null||r.width()*r.height()>best.width()*best.height()))best=r;}return best;
    }
    void selectPoint(){
        AccessibilityWindowInfo w=sourceWindow();if(w==null){status.setText("Önce kitap uygulamasını aç.");return;}
        AccessibilityNodeInfo root=w.getRoot();String pkg=String.valueOf(root.getPackageName());Rect win=new Rect();w.getBoundsInScreen(win);
        if(pkg.equals("com.android.settings")){status.setText("Önce kitabını aç.");return;}
        FrameLayout cover=new FrameLayout(this){@Override public boolean onTouchEvent(MotionEvent e){
            if(e.getAction()==MotionEvent.ACTION_UP){int x=(int)e.getRawX(),y=(int)e.getRawY();if(!win.contains(x,y))return true;
                prefs().edit().putInt("x."+pkg,x).putInt("y."+pkg,y).putString("window."+pkg,win.flattenToString()).apply();removeSelector();panel.setVisibility(View.VISIBLE);status.setText("Hazır. Başlangıç sayfanda Başlat'a dokun.");}return true;
        }};
        cover.setBackgroundColor(0x18005f4b);Button cancel=new Button(this);cancel.setText("Sonraki sayfa düğmesine dokun · İptal");cancel.setAllCaps(false);
        FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL);bp.topMargin=dp(80);cover.addView(cancel,bp);cancel.setOnClickListener(v->{removeSelector();panel.setVisibility(View.VISIBLE);});
        selector=cover;panel.setVisibility(View.GONE);WindowManager.LayoutParams lp=new WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);getSystemService(WindowManager.class).addView(selector,lp);
    }
    void begin(){
        if(running||busy||finishing)return;
        try{
            AccessibilityWindowInfo w=sourceWindow();if(w==null)throw new IOException("Önce kitap uygulamasını aç.");AccessibilityNodeInfo root=w.getRoot();root.refresh();sourcePackage=String.valueOf(root.getPackageName());
            if(sourcePackage.equals("com.android.settings")||sourcePackage.equals(getPackageName()))throw new IOException("Önce kitabını aç.");
            isler=sourcePackage.equals(ISLER);first=isler?number(root):-1;windowBounds=new Rect();w.getBoundsInScreen(windowBounds);width=windowBounds.width();height=windowBounds.height();rotation=getSystemService(WindowManager.class).getDefaultDisplay().getRotation();crop=pageArea(root,windowBounds);
            if(crop==null||(isler&&first<0))throw new IOException("Kitabı tam ekran, tek sayfa görünümünde aç.");
            if(isler&&find(root,"Basit")!=null)throw new IOException("Çizim çubuğunun altındaki Basit düğmesine dokun.");
            if(mode==0&&!isler&&!windowBounds.flattenToString().equals(prefs().getString("window."+sourcePackage,"")))throw new IOException("Bu uygulama için önce İleri düğmesini göster'e dokun.");
            if(getFilesDir().getUsableSpace()<requested*5_000_000L+100_000_000L)throw new IOException("PDF için yeterli boş alan yok.");
            runName=new SimpleDateFormat("yyyyMMdd-HHmmss-SSS",Locale.US).format(new Date());File dir=new File(getExternalFilesDir(null),"runs");if(!dir.exists()&&!dir.mkdirs())throw new IOException("Kayıt klasörü açılamadı.");
            localPdf=new File(dir,runName+".pdf.part");pdf=new RasterPdf(localPdf);log=new FileWriter(new File(dir,runName+".txt"));log.write("requested="+requested+" package="+sourcePackage+" start="+first+"\n");log.flush();
            index=0;candidate=null;previous=null;stable=0;hashes.clear();stopReason=null;running=true;busy=false;finishing=false;session++;started=SystemClock.elapsedRealtime();deadline=started+30_000;
            selectButton.setVisibility(View.GONE);startButton.setText("Durdur");status.setText("Boran PDF · Hazırlanıyor");poll(session);
        }catch(Exception e){status.setText(e.getMessage());if(!running&&pdf!=null){try{pdf.close();}catch(Exception ignored){}pdf=null;}}
    }
    boolean check(AccessibilityWindowInfo w){
        if(w==null){requestStop("Kitap uygulaması önde değil");return false;}
        AccessibilityNodeInfo root=w.getRoot();if(root==null||!sourcePackage.equals(String.valueOf(root.getPackageName()))){requestStop("Başka uygulamaya geçildi");return false;}
        if(getSystemService(KeyguardManager.class).isKeyguardLocked()){requestStop("Ekran kilitlendi");return false;}
        Rect bounds=new Rect();w.getBoundsInScreen(bounds);if(!bounds.equals(windowBounds)||rotation!=getSystemService(WindowManager.class).getDefaultDisplay().getRotation()){requestStop("Ekran yönü veya boyutu değişti");return false;}
        if(isler&&(find(root,"Kitabı Kapat")!=null||find(root,"Sayfa Görselleri")!=null||find(root,"Basit")!=null)){requestStop("Menü veya çizim çubuğu sayfayı örtüyor");return false;}
        Rect area=pageArea(root,bounds);if(area!=null&&!area.equals(crop)){requestStop("Sayfa yerleşimi değişti");return false;}return true;
    }
    void poll(int token){
        if(token!=session||!running||busy)return;
        if(SystemClock.elapsedRealtime()>deadline){requestStop("Sayfa değişmedi veya yüklenemedi. İlerletme tekrarlanmadı.");return;}
        AccessibilityWindowInfo w=sourceWindow();if(!check(w))return;AccessibilityNodeInfo root=w.getRoot();root.refresh();
        if(isler){int n=number(root),expected=first+index;if(n>expected){requestStop("Sayfa atlandı: "+expected+" yerine "+n);return;}if(n!=expected){main.postDelayed(()->poll(token),600);return;}}
        busy=true;
        takeScreenshotOfWindow(w.getId(),getMainExecutor(),new TakeScreenshotCallback(){
            @Override public void onFailure(int code){
                busy=false;if(code==ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT&&running){main.postDelayed(()->poll(token),700);return;}
                if(!running){finishIfReady();return;}
                requestStop(code==ERROR_TAKE_SCREENSHOT_SECURE_WINDOW?"Bu uygulama ekran yakalamayı engelliyor.":"Ekran alınamadı ("+code+").");
            }
            @Override public void onSuccess(ScreenshotResult result){
                HardwareBuffer buffer=result.getHardwareBuffer();Bitmap hardware=null,image=null;
                try{hardware=Bitmap.wrapHardwareBuffer(buffer,result.getColorSpace());if(hardware==null)throw new IOException("Ekran görüntüsü okunamadı");image=hardware.copy(Bitmap.Config.ARGB_8888,false);}
                catch(Exception e){busy=false;if(running)requestStop(e.getMessage());else finishIfReady();}
                finally{if(hardware!=null)hardware.recycle();buffer.close();}
                if(image==null)return;if(!running||token!=session){image.recycle();busy=false;finishIfReady();return;}
                Bitmap captured=image;io.execute(()->process(captured,token));
            }
        });
    }
    byte[] sample(Bitmap image){
        byte[] values=new byte[120*180];int p=0;
        for(int y=0;y<180;y++)for(int x=0;x<120;x++){int c=image.getPixel(x*image.getWidth()/120,y*image.getHeight()/180);values[p++]=(byte)((Color.red(c)*3+Color.green(c)*6+Color.blue(c))/10);}return values;
    }
    double difference(byte[] a,byte[] b){if(a==null||b==null)return 255;long sum=0;for(int i=0;i<a.length;i++)sum+=Math.abs((a[i]&255)-(b[i]&255));return sum/(double)a.length;}
    void process(Bitmap full,int token){
        Bitmap image=null;
        try{
            if(full.getWidth()!=width||full.getHeight()!=height)throw new IOException("Pencere görüntüsü boyutu değişti.");
            Rect local=new Rect(crop);local.offset(-windowBounds.left,-windowBounds.top);image=Bitmap.createBitmap(full,local.left,local.top,local.width(),local.height());if(image!=full)full.recycle();
            byte[] now=sample(image);double sum=0,squares=0;for(byte v:now){int n=v&255;sum+=n;squares+=n*n;}
            boolean blank=squares/now.length-Math.pow(sum/now.length,2)<4;stable=difference(candidate,now)<.12?stable+1:0;candidate=now;
            boolean accept=stable>=2&&!blank&&difference(previous,now)>.25;Bitmap ready=image;
            main.post(()->{
                if(!running||token!=session){ready.recycle();busy=false;finishIfReady();return;}
                AccessibilityWindowInfo w=sourceWindow();if(!check(w)){ready.recycle();busy=false;finishIfReady();return;}
                if(isler&&number(w.getRoot())!=first+index){ready.recycle();busy=false;stable=0;main.postDelayed(()->poll(token),600);return;}
                if(!accept){ready.recycle();busy=false;main.postDelayed(()->poll(token),600);return;}
                io.execute(()->savePage(ready,now,token));
            });
        }catch(Exception e){if(image!=null&&!image.isRecycled())image.recycle();if(!full.isRecycled())full.recycle();main.post(()->{busy=false;if(running)requestStop(e.getMessage());else finishIfReady();});}
    }
    void savePage(Bitmap image,byte[] sampled,int token){
        String error=null;
        try{
            String hash=Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(sampled));if(!hashes.add(hash))throw new IOException("Daha önce alınan sayfa tekrar göründü.");
            pdf.add(image);index++;previous=sampled;candidate=null;stable=0;log.write("page="+index+" source="+(isler?first+index-1:-1)+" hash="+hash+"\n");log.flush();Log.i("BoranPDF","PAGE "+index+" source="+(isler?first+index-1:-1));
        }catch(Exception e){error=e.getMessage();}finally{image.recycle();}
        String failure=error;main.post(()->{
            busy=false;if(failure!=null){if(running)requestStop(failure);else finishIfReady();return;}
            if(panel!=null)status.setText("Kaydedilen: "+index+" / "+requested+(isler?" · Son: "+(first+index-1):""));
            if(!running||token!=session){finishIfReady();return;}if(index==requested){running=false;finishIfReady();return;}advance(token);
        });
    }
    void advance(int token){
        AccessibilityWindowInfo w=sourceWindow();if(!check(w))return;if(isler&&number(w.getRoot())!=first+index-1){requestStop("Kaydetme sırasında sayfa değişti");return;}
        if(panel!=null)status.setText("Kaydedilen: "+index+" / "+requested+"\n"+(isler?(first+index)+". sayfa hazırlanıyor…":"Sonraki sayfa hazırlanıyor…"));
        Path path=new Path();long duration=70;
        if(mode==0){int x,y;
            if(isler){AccessibilityNodeInfo next=find(find(w.getRoot(),"Sayfa Görünümü"),"İleri");if(next==null||!next.isEnabled()){requestStop("Sonraki sayfa bulunamadı");return;}Rect b=new Rect();next.getBoundsInScreen(b);x=b.centerX();y=b.centerY();}
            else{x=prefs().getInt("x."+sourcePackage,-1);y=prefs().getInt("y."+sourcePackage,-1);}
            if(!windowBounds.contains(x,y)){requestStop("Geçiş noktası ekran dışında");return;}path.moveTo(x,y);
        }else if(mode==1){path.moveTo(windowBounds.left+width*.8f,windowBounds.top+height*.5f);path.lineTo(windowBounds.left+width*.2f,windowBounds.top+height*.5f);duration=350;}
        else{path.moveTo(windowBounds.left+width*.5f,windowBounds.top+height*.8f);path.lineTo(windowBounds.left+width*.5f,windowBounds.top+height*.2f);duration=350;}
        boolean sent=dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path,0,duration)).build(),new GestureResultCallback(){
            @Override public void onCompleted(GestureDescription g){deadline=SystemClock.elapsedRealtime()+30_000;main.postDelayed(()->poll(token),800);}
            @Override public void onCancelled(GestureDescription g){requestStop("Sayfa geçişi iptal edildi. Dokunma tekrarlanmadı.");}
        },main);if(!sent)requestStop("Sayfa geçişi gönderilemedi.");
    }
    void requestStop(String reason){
        if(!running&&!busy)return;running=false;stopReason=reason;finishIfReady();
    }
    void finishIfReady(){
        if(busy||finishing||pdf==null)return;finishing=true;
        if(panel!=null){status.setText("PDF kaydediliyor…");startButton.setEnabled(false);closeButton.setEnabled(false);}
        boolean complete=index==requested&&stopReason==null;
        io.execute(()->{
            String uri="",error=null;
            try{
                if(index>0){
                    pdf.finish();pdf.close();String name="Boran-"+runName+"-"+index+"-sayfa"+(complete?"":"-EKSIK")+".pdf";
                    ContentValues values=new ContentValues();values.put(MediaStore.Downloads.DISPLAY_NAME,name);values.put(MediaStore.Downloads.MIME_TYPE,"application/pdf");values.put(MediaStore.Downloads.RELATIVE_PATH,"Download/Boran PDF");values.put(MediaStore.Downloads.IS_PENDING,1);
                    Uri target=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);if(target==null)throw new IOException("İndirilenler klasörüne kaydedilemedi.");
                    try(InputStream input=new FileInputStream(localPdf);OutputStream output=getContentResolver().openOutputStream(target)){
                        if(output==null)throw new IOException("PDF dosyası açılamadı.");input.transferTo(output);
                    }catch(Exception e){getContentResolver().delete(target,null,null);throw e;}
                    values.clear();values.put(MediaStore.Downloads.IS_PENDING,0);getContentResolver().update(target,values,null,null);uri=target.toString();localPdf.delete();
                }else{pdf.close();localPdf.delete();}
                log.write("result="+(complete?"complete":"incomplete")+" count="+index+" seconds="+((SystemClock.elapsedRealtime()-started)/1000)+" reason="+stopReason+"\n");log.close();
            }catch(Exception e){error=e.getMessage();try{pdf.close();}catch(Exception ignored){}try{log.close();}catch(Exception ignored){}}
            String resultUri=uri,problem=error;
            main.post(()->{
                pdf=null;finishing=false;lastUri=resultUri;String summary=complete?"PDF hazır · "+index+" sayfa":("Kullanıcı durdurdu".equals(stopReason)?"Durduruldu":"İşlem durdu")+" · "+index+" / "+requested+" sayfa kaydedildi";
                if(problem!=null)summary="PDF kaydedilemedi: "+problem;else if(!complete&&stopReason!=null)summary+="\n"+stopReason;
                if(!resultUri.isEmpty())prefs().edit().putString("last_uri",resultUri).putString("last_summary",summary+"\nİndirilenler / Boran PDF").apply();
                Log.i("BoranPDF","RESULT "+summary+" uri="+resultUri);
                if(panel!=null){status.setText(summary);startButton.setEnabled(true);startButton.setText(resultUri.isEmpty()?"Tekrar dene":"Samsung Notes'ta aç");closeButton.setEnabled(true);}
                if(destroyed)io.shutdown();
            });
        });
    }
    public static void openPdf(Context context,String value){
        if(value.isEmpty())return;Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(value),"application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setClipData(ClipData.newRawUri("PDF",Uri.parse(value)));intent.setPackage("com.samsung.android.app.notes");
        try{context.startActivity(intent);if(instance!=null)instance.removePanel();}
        catch(ActivityNotFoundException e){intent.setPackage(null);try{context.startActivity(intent);}catch(ActivityNotFoundException noViewer){Toast.makeText(context,"PDF İndirilenler / Boran PDF klasöründe.",Toast.LENGTH_LONG).show();}}
    }
}
