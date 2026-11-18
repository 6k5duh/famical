package org.famical.calendar;

import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;

/** NAS-only HTTPS transport. Never follows a redirect with the session cookie. */
class CalendarApi {
    final String server, cookie;
    String csrf;
    private final java.util.function.BooleanSupplier active;
    CalendarApi(String server,String cookie,java.util.function.BooleanSupplier active) {
        this.server=ServerAddress.normalize(server);this.cookie=cookie;this.active=active;
    }
    JSONObject request(String path,String method,JSONObject body) throws Exception {
        if(!active.getAsBoolean())throw new IOException("로그인 또는 연동 설정이 변경되었습니다.");
        HttpURLConnection conn=(HttpURLConnection)new URL(server+"api/"+path).openConnection();
        conn.setInstanceFollowRedirects(false);conn.setConnectTimeout(8000);conn.setReadTimeout(12000);
        conn.setRequestMethod(method);conn.setRequestProperty("Cookie",cookie);
        conn.setRequestProperty("Accept","application/json");
        try {
            if(body!=null){
                conn.setDoOutput(true);conn.setRequestProperty("Content-Type","application/json; charset=utf-8");
                conn.setRequestProperty("X-CSRF-Token",csrf==null?"":csrf);
                try(java.io.OutputStream out=conn.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}
            }
            int code=conn.getResponseCode();
            InputStream stream=code>=400?conn.getErrorStream():conn.getInputStream();
            ByteArrayOutputStream buffer=new ByteArrayOutputStream();
            if(stream!=null)try(InputStream in=stream){byte[] bytes=new byte[4096];int count;while((count=in.read(bytes))!=-1){buffer.write(bytes,0,count);if(buffer.size()>8_000_000)throw new IOException("일정 데이터가 너무 많습니다.");}}
            JSONObject result;
            try { result=new JSONObject(buffer.toString("UTF-8")); }
            catch(org.json.JSONException e){throw new IOException("NAS 웹앱 v1.3.0 이상으로 업데이트해 주세요.");}
            if(code<200||code>=300)throw new IOException(result.optString("error","NAS 연결 오류")+" (HTTP "+code+")");
            if(!active.getAsBoolean())throw new IOException("로그인 또는 연동 설정이 변경되었습니다.");
            return result;
        } finally {conn.disconnect();}
    }
    JSONObject me() throws Exception {JSONObject me=request("me","GET",null);csrf=me.getString("csrf");return me;}
}
