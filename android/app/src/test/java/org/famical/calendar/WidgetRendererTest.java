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
        c.getSharedPreferences("famical",Context.MODE_PRIVATE).edit().putString("widget_day",date.toString()).putString("widget_lines","종일  가족 여행\n18:30  가족 식사")
            .putString("widget_month",java.time.YearMonth.from(date).toString())
            .putString("widget_month_events","[{\"day\":\"2026-11-17\",\"end_day\":\"2026-11-20\",\"color\":\"blue\"},{\"day\":\"2026-11-18\",\"end_day\":\"2026-11-18\",\"color\":\"rose\"},{\"day\":\"2026-11-19\",\"end_day\":\"2026-11-19\",\"color\":\"violet\",\"visibility\":\"private\"},{\"day\":\"2026-11-24\",\"end_day\":\"2026-11-26\",\"color\":\"sage\"}]").apply();
        FrameLayout parent=new FrameLayout(c);
        View view=WidgetRenderer.create(c,width,height,date,"21:22 갱신").apply(c,parent);
        int w=Math.round(width*c.getResources().getDisplayMetrics().density),h=Math.round(height*c.getResources().getDisplayMetrics().density);
        view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));
        view.layout(0,0,w,h);return view;
    }
    @Test public void largeCalendarFillsWidgetInsteadOfLeavingBlankSpace() {
        Context c=RuntimeEnvironment.getApplication();
        assertNotEquals("A new layout ID must force launcher reinflation after the APK update",R.layout.calendar_widget_grid,WidgetRenderer.create(c,300,480,LocalDate.of(2026,11,17),"갱신").getLayoutId());
        View view=render(300,480,LocalDate.of(2026,11,17));
        View month=view.findViewById(R.id.widget_month);
        assertTrue("Calendar must fill most of the widget",month.getHeight()>view.getHeight()*0.55f);
        TextView first=view.findViewById(R.id.day_0),last=view.findViewById(R.id.day_6);
        View firstCell=view.findViewById(R.id.cell_0),lastCell=view.findViewById(R.id.cell_6);
        assertTrue("Day cells must fill the row",(lastCell.getRight()-firstCell.getLeft())>month.getWidth()*0.94f);
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
    @Test public void markersShowFutureDatesRangesAndMultipleEvents() {
        View view=render(300,480,LocalDate.of(2026,11,17));
        assertEquals(View.VISIBLE,view.findViewById(R.id.marker_16_0).getVisibility());
        assertEquals(View.VISIBLE,view.findViewById(R.id.marker_17_1).getVisibility());
        assertEquals(View.GONE,view.findViewById(R.id.marker_18_1).getVisibility());
        assertEquals(View.VISIBLE,view.findViewById(R.id.marker_25_0).getVisibility());
        assertEquals(View.INVISIBLE,view.findViewById(R.id.markers_26).getVisibility());
        assertEquals("Markers must not move the date baseline",view.findViewById(R.id.day_15).getHeight(),view.findViewById(R.id.day_16).getHeight());
        assertEquals(WidgetEvents.color("blue"),((android.graphics.drawable.ColorDrawable)view.findViewById(R.id.marker_16_0).getBackground()).getColor());
        Context c=RuntimeEnvironment.getApplication();c.getSharedPreferences("famical",Context.MODE_PRIVATE).edit().putBoolean("widget_private",true).apply();
        view=render(300,480,LocalDate.of(2026,11,17));
        assertEquals(View.VISIBLE,view.findViewById(R.id.marker_18_1).getVisibility());
    }
    @Test public void todayHasARedBorderAndRefreshPeriodIsOneHour() throws Exception {
        View view=render(300,480,LocalDate.of(2026,11,17));
        assertTrue("Today must render an actual red border",countColor(view.findViewById(R.id.cell_16),android.graphics.Color.parseColor("#E53935"))>0);
        assertEquals("Other dates must not have the today border",0,countColor(view.findViewById(R.id.cell_15),android.graphics.Color.parseColor("#E53935")));
        try(android.content.res.XmlResourceParser xml=RuntimeEnvironment.getApplication().getResources().getXml(R.xml.calendar_widget)) {
            while(xml.next()!=org.xmlpull.v1.XmlPullParser.START_TAG) { }
            assertEquals(3600000,xml.getAttributeIntValue("http://schemas.android.com/apk/res/android","updatePeriodMillis",0));
        }
    }
    @Test public void fullMonthCacheIsNotTruncatedByTheThreeTodayLines() throws Exception {
        org.json.JSONArray response=new org.json.JSONArray();
        for(int i=0;i<4;i++)response.put(new org.json.JSONObject().put("day","2026-11-17").put("title","today"+i));
        response.put(new org.json.JSONObject().put("day","2026-11-24").put("end_day","2026-11-26").put("title","future"));
        response.put(new org.json.JSONObject().put("day","2026-11-17").put("title","secret").put("visibility","private"));
        WidgetEvents.Snapshot snapshot=WidgetEvents.parse(response.toString(),LocalDate.of(2026,11,17),false);
        assertEquals(3,snapshot.todayLines.split("\n").length);assertFalse(snapshot.todayLines.contains("secret"));
        assertEquals(5,new org.json.JSONArray(snapshot.monthEvents).length());assertFalse(snapshot.monthEvents.contains("title"));
        assertEquals(3,WidgetEvents.monthColors(snapshot.monthEvents,java.time.YearMonth.of(2026,11),false).get(17).size());
        assertEquals(1,WidgetEvents.monthColors(snapshot.monthEvents,java.time.YearMonth.of(2026,11),false).get(26).size());
        assertTrue(WidgetEvents.monthColors(snapshot.monthEvents,java.time.YearMonth.of(2026,11),false).get(27).isEmpty());
    }
    @Test public void crossMonthRangeIsClampedToDisplayedMonth() throws Exception {
        WidgetEvents.Snapshot snapshot=WidgetEvents.parse("[{\"day\":\"2026-10-30\",\"end_day\":\"2026-11-02\",\"title\":\"range\"}]",LocalDate.of(2026,11,17),false);
        java.util.List<java.util.List<Integer>> colors=WidgetEvents.monthColors(snapshot.monthEvents,java.time.YearMonth.of(2026,11),false);
        assertEquals(1,colors.get(1).size());assertEquals(1,colors.get(2).size());assertTrue(colors.get(3).isEmpty());
    }
    private int countColor(View view,int color) {
        Bitmap bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap));
        int count=0;for(int y=0;y<bitmap.getHeight();y++)for(int x=0;x<bitmap.getWidth();x++)if(bitmap.getPixel(x,y)==color)count++;
        bitmap.recycle();return count;
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
