package com.hush.space.core;

import org.json.JSONObject;

/** 一个空间的设备伪装配置。 */
public class DeviceProfile {

    public boolean enabled;
    public String brand = "";
    public String model = "";
    public String manufacturer = "";
    public String device = "";
    public String serial = "";
    public String fingerprint = "";

    public boolean isEmpty() {
        return brand.isEmpty() && model.isEmpty() && manufacturer.isEmpty()
                && device.isEmpty() && serial.isEmpty() && fingerprint.isEmpty();
    }

    public String toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("enabled", enabled);
            o.put("brand", brand);
            o.put("model", model);
            o.put("manufacturer", manufacturer);
            o.put("device", device);
            o.put("serial", serial);
            o.put("fingerprint", fingerprint);
            return o.toString();
        } catch (Throwable t) {
            return "{}";
        }
    }

    public static DeviceProfile fromJson(String json) {
        DeviceProfile p = new DeviceProfile();
        try {
            JSONObject o = new JSONObject(json);
            p.enabled = o.optBoolean("enabled", false);
            p.brand = o.optString("brand", "");
            p.model = o.optString("model", "");
            p.manufacturer = o.optString("manufacturer", "");
            p.device = o.optString("device", "");
            p.serial = o.optString("serial", "");
            p.fingerprint = o.optString("fingerprint", "");
        } catch (Throwable ignored) {
        }
        return p;
    }

    public DeviceProfile copy() {
        DeviceProfile p = new DeviceProfile();
        p.enabled = enabled;
        p.brand = brand;
        p.model = model;
        p.manufacturer = manufacturer;
        p.device = device;
        p.serial = serial;
        p.fingerprint = fingerprint;
        return p;
    }
}
