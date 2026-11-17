package org.famical.calendar;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.File;
import java.io.FileOutputStream;
import java.time.LocalDate;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class WidgetRendererTest {
    private View render(int width,int height,LocalDate date) {
        Context c=RuntimeEnvironment.getApplication();
        c.getSharedPreferences("famical",Context.MODE_PRIVATE).edit().putString("widget_day",date.toString()).putString("widget_lines","종일  가족 여행\n18:30  가족 식사").apply();
        FrameLayout parent=new FrameLayout(c);
        View view=WidgetRenderer.create(c,width,height,date,"21:22 갱신").apply(c,parent);
        int w=Math.round(width*c.getResources().getDisplayMetrics().density),h=Math.round(height*c.getResources().getDisplayMetrics().density);
        view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));
        view.layout(0,0,w,h);return view;
    }
    @Test public void largeCalendarFillsWidgetInsteadOfLeavingBlankSpace() {
        Context c=RuntimeEnvironment.getApplication();
        assertNotEquals("A new layout ID must force launcher reinflation after the APK update",R.layout.calendar_widget,WidgetRenderer.create(c,300,480,LocalDate.of(2026,11,17),"갱신").getLayoutId());
        View view=render(300,480,LocalDate.of(2026,11,17));
        View month=view.findViewById(R.id.widget_month);
        assertTrue("Calendar must fill most of the widget",month.getHeight()>view.getHeight()*0.55f);
        TextView first=view.findViewById(R.id.day_0),last=view.findViewById(R.id.day_6);
        assertTrue("Day cells must fill the row",(last.getRight()-first.getLeft())>month.getWidth()*0.94f);
        assertEquals("1",first.getText().toString());assertEquals("17",((TextView)view.findViewById(R.id.day_16)).getText().toString());
        assertEquals(View.GONE,view.findViewById(R.id.week_5).getVisibility());
    }
    @Test public void resizingGrowsRowsAndKeepsFooterWithinBounds() {
        View small=render(260,300,LocalDate.of(2026,5,17)),large=render(480,600,LocalDate.of(2026,5,17));
        assertTrue(large.findViewById(R.id.day_6).getWidth()>small.findViewById(R.id.day_6).getWidth());
        assertTrue(large.findViewById(R.id.week_0).getHeight()>small.findViewById(R.id.week_0).getHeight());
        assertEquals(View.VISIBLE,small.findViewById(R.id.week_5).getVisibility());
        for(View view:new View[]{small,large}) {
            int[] root=new int[2],footer=new int[2];view.getLocationOnScreen(root);View refresh=view.findViewById(R.id.widget_refresh);refresh.getLocationOnScreen(footer);
            assertTrue("Refresh must stay inside widget",footer[1]+refresh.getHeight()<=root[1]+view.getHeight());
        }
    }
    @Test public void noSpannedStringOrMonospaceAlignmentIsRequired() {
        View view=render(260,260,LocalDate.of(2026,2,17));
        assertEquals(View.GONE,view.findViewById(R.id.widget_events).getVisibility());
        assertEquals(View.GONE,view.findViewById(R.id.week_4).getVisibility());
        assertEquals("28",((TextView)view.findViewById(R.id.day_27)).getText().toString());
    }
    @Test public void renderPreviewAtPhoneAndFoldableSizes() throws Exception {
        String output=System.getProperty("famical.preview.dir",System.getProperty("java.io.tmpdir")+"/famical-widget-previews");
        new File(output).mkdirs();
        for(int[] size:new int[][]{{300,480},{480,400},{260,280}}) {
            View view=render(size[0],size[1],LocalDate.of(2026,11,17));
            Bitmap bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap));
            try(FileOutputStream file=new FileOutputStream(new File(output,"widget-"+size[0]+"x"+size[1]+".png"))) {bitmap.compress(Bitmap.CompressFormat.PNG,100,file);}
        }
    }
}
