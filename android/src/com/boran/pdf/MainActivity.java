package com.boran.pdf;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;

public class MainActivity extends Activity {
    static final int INK=0xff193b34, GREEN=0xff126552;
    TextView status,result,hint; Button permission,last; EditText count; Spinner mode; SharedPreferences prefs;
    int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    TextView label(String value,int size,LinearLayout parent){
        TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(INK);t.setPadding(0,dp(10),0,dp(10));parent.addView(t);return t;
    }
    Button button(String text,LinearLayout parent){
        Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setTextSize(17);b.setTextColor(Color.WHITE);android.graphics.drawable.GradientDrawable background=new android.graphics.drawable.GradientDrawable();background.setColor(GREEN);background.setCornerRadius(dp(14));b.setBackground(background);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(60));lp.topMargin=dp(12);parent.addView(b,lp);return b;
    }
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);prefs=getSharedPreferences("boran",MODE_PRIVATE);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(0xfff6f8f5);
        LinearLayout outer=new LinearLayout(this);outer.setGravity(Gravity.TOP|Gravity.CENTER_HORIZONTAL);scroll.addView(outer);
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(dp(24),dp(24),dp(24),dp(24));
        outer.addView(page,new LinearLayout.LayoutParams(Math.min(dp(560),getResources().getDisplayMetrics().widthPixels),-2));
        outer.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());v.setPadding(0,bars.top+dp(16),0,bars.bottom+dp(16));return insets;});
        label("Boran PDF",34,page);label("Sayfalarını tek PDF'de topla.",19,page);status=label("",16,page);
        permission=button("İzni aç",page);permission.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Ekran yakalama izni")
            .setMessage("Boran PDF, Başlat'a dokunduğunda açık uygulamanın sayfalarını görüntüleyip kaydeder ve sayfaları otomatik ilerletir. Görüntüler tablette işlenir. İzni istediğin zaman Erişilebilirlik ayarlarından kapatabilirsin.")
            .setPositiveButton("Ayarlara git",(d,w)->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))).setNegativeButton("Vazgeç",null).show());
        label("Sayfa sayısı",18,page);count=new EditText(this);count.setInputType(InputType.TYPE_CLASS_NUMBER);count.setSingleLine(true);count.setTextSize(28);count.setSelectAllOnFocus(true);count.setText(String.valueOf(prefs.getInt("count",3)));page.addView(count);
        label("Sayfa geçişi",18,page);mode=new Spinner(this);mode.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Düğmeyle (İşler ve diğerleri)","Sola kaydırarak","Yukarı kaydırarak"}));mode.setSelection(prefs.getInt("mode",0));page.addView(mode);
        button("İçeriği aç",page).setOnClickListener(v->{
            if(CaptureService.instance==null){new AlertDialog.Builder(this).setMessage("Ayarlar → Erişilebilirlik → Yüklü uygulamalar → Boran PDF iznini aç.").setPositiveButton("Tamam",null).show();return;}
            try{int n=Integer.parseInt(count.getText().toString());if(n<1||n>2000)throw new NumberFormatException();prefs.edit().putInt("count",n).putInt("mode",mode.getSelectedItemPosition()).apply();
                ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(count.getWindowToken(),0);
                if(CaptureService.instance.prepare(n,mode.getSelectedItemPosition()))moveTaskToBack(true);
            }catch(NumberFormatException e){count.setError("1–2000 arasında bir sayı gir");}
        });
        label("Kaydetmek istediğin içeriği aç, ardından Başlat’a dokun.",17,page);
        TextView help=label("Nasıl kullanılır?",16,page);help.setTextColor(GREEN);
        hint=label("Sadece İşler’de değil, başka uygulamalarda da kullanabilirsin.\nAçtığın kitap, ders notu veya slaytları tek PDF’de toplayabilirsin. Bazı uygulamalar ekran görüntüsü alınmasını engellediği için çalışmayabilir.\n\n1. Sayfa sayısını gir. Başlangıç sayfan da dahildir.\n2. İçeriği aç düğmesine dokun, başlangıç sayfanı aç.\n3. Ekranda beliren Başlat'a dokun ve çekimin bitmesini bekle.\n4. Samsung Notes'ta aç → Nota ekle.\n\nİşler: düğmeyle geçişi seç; ileri düğmesi otomatik bulunur. Kitabı tek sayfa, tam ekran aç; çizim çubuğunu küçült.\n\nDiğer uygulamalar: düğmeyle ilerliyorsa İleri düğmesini göster'e dokunup ileri düğmesine bir kez dokun. Kaydırarak ilerliyorsa uygun kaydırma yönünü seç.",16,page);
        hint.setVisibility(View.GONE);help.setOnClickListener(v->hint.setVisibility(hint.getVisibility()==View.GONE?View.VISIBLE:View.GONE));
        result=label("",15,page);last=button("Son PDF'yi aç",page);last.setOnClickListener(v->CaptureService.openPdf(this,prefs.getString("last_uri","")));
        label("PDF'lerin: Dosyalarım → İndirilenler → Boran PDF",14,page);
        TextView motto=new TextView(this);
        motto.setText("İlk 100’e gireceklerin\nPDF uygulaması.");motto.setTextSize(21);motto.setTextColor(INK);
        motto.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));
        motto.setLineSpacing(dp(3),1);motto.setPadding(dp(22),dp(20),dp(22),dp(20));
        android.graphics.drawable.GradientDrawable card=new android.graphics.drawable.GradientDrawable();
        card.setColor(0xffe6efe7);card.setCornerRadius(dp(18));card.setStroke(dp(1),0xffd1dfd3);motto.setBackground(card);
        LinearLayout.LayoutParams mottoLayout=new LinearLayout.LayoutParams(-1,-2);mottoLayout.topMargin=dp(24);page.addView(motto,mottoLayout);
        setContentView(scroll);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }
    final android.os.Handler refresh=new android.os.Handler(android.os.Looper.getMainLooper());
    final Runnable refreshStatus=()->updateStatus();
    @Override public void onResume(){super.onResume();updateStatus();}
    @Override public void onPause(){refresh.removeCallbacks(refreshStatus);super.onPause();}
    void updateStatus(){boolean enabled=CaptureService.instance!=null;status.setText(enabled?"Hazır":"Başlamak için ekran yakalama iznini aç.");permission.setVisibility(enabled?View.GONE:View.VISIBLE);String uri=prefs.getString("last_uri","");last.setVisibility(uri.isEmpty()?View.GONE:View.VISIBLE);result.setText(prefs.getString("last_summary",""));refresh.removeCallbacks(refreshStatus);refresh.postDelayed(refreshStatus,1000);}
}
