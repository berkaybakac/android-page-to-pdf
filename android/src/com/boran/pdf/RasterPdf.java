package com.boran.pdf;

import android.graphics.Bitmap;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.DeflaterOutputStream;

/** Image-only, lossless PDF. Holds only one bitmap, regardless of page count. */
final class RasterPdf implements AutoCloseable {
    private final RandomAccessFile out;
    private final ArrayList<Long> offsets=new ArrayList<>();
    private final ArrayList<Integer> pages=new ArrayList<>();
    private int next=3;
    private boolean finished,broken;
    RasterPdf(File file) throws IOException {
        out=new RandomAccessFile(file,"rw");out.setLength(0);
        offsets.add(0L);offsets.add(0L);offsets.add(0L);text("%PDF-1.4\n% Boran PDF\n");
    }
    private void text(String value) throws IOException {out.write(value.getBytes(StandardCharsets.US_ASCII));}
    private void object(int id) throws IOException {
        while(offsets.size()<=id)offsets.add(0L);
        offsets.set(id,out.getFilePointer());text(id+" 0 obj\n");
    }
    void add(Bitmap bitmap) throws IOException {
        if(finished)throw new IOException("PDF kapandı");
        int width=bitmap.getWidth(),height=bitmap.getHeight();
        ByteArrayOutputStream compressed=new ByteArrayOutputStream();
        try(DeflaterOutputStream z=new DeflaterOutputStream(compressed)) {
            int[] row=new int[width];byte[] rgb=new byte[width*3];
            for(int y=0;y<height;y++) {
                bitmap.getPixels(row,0,width,0,y,width,1);
                for(int x=0;x<width;x++) {int p=row[x];rgb[3*x]=(byte)(p>>16);rgb[3*x+1]=(byte)(p>>8);rgb[3*x+2]=(byte)p;}
                z.write(rgb);
            }
        }
        broken=true; // Never publish a PDF if a page write was interrupted.
        int page=next++,content=next++,image=next++;
        String w=String.format(Locale.US,"%.3f",width*.375),h=String.format(Locale.US,"%.3f",height*.375);
        object(page);text("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 "+w+" "+h+"] /Resources << /XObject << /Im "+image+" 0 R >> >> /Contents "+content+" 0 R >>\nendobj\n");
        byte[] draw=("q "+w+" 0 0 "+h+" 0 0 cm /Im Do Q\n").getBytes(StandardCharsets.US_ASCII);
        object(content);text("<< /Length "+draw.length+" >>\nstream\n");out.write(draw);text("endstream\nendobj\n");
        object(image);text("<< /Type /XObject /Subtype /Image /Width "+width+" /Height "+height+" /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode /Length "+compressed.size()+" >>\nstream\n");
        out.write(compressed.toByteArray());text("\nendstream\nendobj\n");out.getFD().sync();pages.add(page);broken=false;
    }
    void finish() throws IOException {
        if(finished)return;
        if(broken)throw new IOException("Sayfa diske yazılamadı; eksik dosya yayımlanmadı.");
        object(1);text("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        object(2);text("<< /Type /Pages /Count "+pages.size()+" /Kids [");
        for(int p:pages)text(p+" 0 R ");text("] >>\nendobj\n");
        long xref=out.getFilePointer();text("xref\n0 "+next+"\n0000000000 65535 f \n");
        for(int i=1;i<next;i++)text(String.format(Locale.US,"%010d 00000 n \n",offsets.get(i)));
        text("trailer\n<< /Size "+next+" /Root 1 0 R >>\nstartxref\n"+xref+"\n%%EOF\n");out.getFD().sync();finished=true;
    }
    @Override public void close() throws IOException {out.close();}
}
