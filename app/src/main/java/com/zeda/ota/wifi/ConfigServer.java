package com.zeda.ota;

import fi.iki.elonen.NanoHTTPD;

import java.util.Map;

public class ConfigServer extends NanoHTTPD {

    public interface OnWifiSubmitListener {

        void onWifiSubmit(
                String ssid,
                String password
        );
    }

    private final OnWifiSubmitListener listener;

    public ConfigServer(
            OnWifiSubmitListener listener
    ) {

        super(8080);

        this.listener = listener;
    }

    @Override
    public Response serve(IHTTPSession session) {

        if(Method.POST.equals(session.getMethod())){

            try {

                session.parseBody(null);

                Map<String,String> params =
                        session.getParms();

                String ssid =
                        params.get("ssid");

                String password =
                        params.get("password");

                new Thread(() -> {

                    try {
                        Thread.sleep(2000);
                    } catch (Throwable ignored) {
                    }

                    if (listener != null) {
                        listener.onWifiSubmit(
                                ssid,
                                password
                        );
                    }

                }).start();

                return newFixedLengthResponse(
                        Response.Status.OK,
                        "text/html; charset=utf-8",
                        "<!DOCTYPE html>" +
                                "<html>" +
                                "<head>" +
                                "<meta charset='utf-8'>" +
                                "<meta name='viewport' content='width=device-width, initial-scale=1.0'>" +
                                "<title>WiFi 配置</title>" +
                                "</head>" +
                                "<body style='font-size:26px;text-align:center;padding:60px 24px;font-family:Arial,\"Microsoft YaHei\",sans-serif;'>" +
                                "WiFi 配置已提交<br><br>" +
                                "设备正在连接网络，请稍候...<br><br>" +
                                "<div style='font-size:20px;color:#666;line-height:1.6;'>" +
                                "如果 1 分钟后设备仍未上线，可能是 WiFi 密码错误。<br>" +
                                "批量配网时请保持手机连接 OTA_FACTORY 后重新扫码。<br>" +
                                "单机配网时请重新连接设备热点 OTA_CONFIG_设备ID 后再次扫码。" +
                                "</div>" +
                                "</body>" +
                                "</html>"
                );

            } catch (Exception e) {

                return newFixedLengthResponse(
                        Response.Status.OK,
                        "text/html; charset=utf-8",
                        "<html><head>" +
                                "<meta charset='utf-8'>" +
                                "<meta name='viewport' content='width=device-width, initial-scale=1.0'>" +
                                "</head><body style='font-size:24px;color:red;padding:40px;'>" +
                                "提交失败：" + e.getMessage() +
                                "</body></html>"
                );
            }
        }

        String html =
                "<!DOCTYPE html>" +
                        "<html>" +
                        "<head>" +
                        "<meta charset='utf-8'>" +
                        "<meta name='viewport' content='width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no'>" +
                        "<title>WiFi配置</title>" +
                        "<style>" +
                        "body{margin:0;padding:0;font-family:Arial,'Microsoft YaHei',sans-serif;background:#f5f5f5;}" +
                        ".container{box-sizing:border-box;width:100%;max-width:520px;margin:0 auto;padding:32px 24px;}" +
                        "h2{font-size:32px;text-align:center;margin:20px 0 36px 0;color:#222;}" +
                        "label{display:block;font-size:24px;margin-bottom:12px;color:#333;}" +
                        "input{box-sizing:border-box;width:100%;height:58px;font-size:24px;padding:8px 14px;border:2px solid #ccc;border-radius:10px;background:#fff;margin-bottom:28px;}" +
                        "button{width:100%;height:64px;font-size:26px;border:none;border-radius:12px;background:#1677ff;color:white;}" +
                        ".tip{font-size:18px;text-align:center;color:#666;margin-top:24px;}" +
                        "</style>" +
                        "</head>" +
                        "<body>" +
                        "<div class='container'>" +
                        "<h2>WiFi 配置</h2>" +
                        "<form method='post'>" +
                        "<label>WiFi 名称</label>" +
                        "<input name='ssid' placeholder='请输入 WiFi 名称'>" +
                        "<label>WiFi 密码</label>" +
                        "<input name='password' type='text' placeholder='请输入 WiFi 密码'>" +
                        "<button type='submit'>连接 WiFi</button>" +
                        "</form>" +
                        "<div class='tip'>提交后设备会自动连接网络</div>" +
                        "</div>" +
                        "</body>" +
                        "</html>";

        return newFixedLengthResponse(
                Response.Status.OK,
                "text/html; charset=utf-8",
                html
        );
    }
}