package com.mengying.fqnovel.dto;

/**
 * 设备信息DTO（不可变）
 */
public class DeviceInfo {

    private final String deviceBrand;
    private final String deviceType;
    private final String deviceId;
    private final String installId;
    private final String cdid;
    private final String resolution;
    private final String dpi;
    private final String hostAbi;
    private final String romVersion;
    private final String osVersion;
    private final Integer osApi;
    private final String userAgent;
    private final String cookie;
    private final String aid;
    private final String versionCode;
    private final String versionName;
    private final String updateVersionCode;

    private DeviceInfo(
        String deviceBrand,
        String deviceType,
        String deviceId,
        String installId,
        String cdid,
        String resolution,
        String dpi,
        String hostAbi,
        String romVersion,
        String osVersion,
        Integer osApi,
        String userAgent,
        String cookie,
        String aid,
        String versionCode,
        String versionName,
        String updateVersionCode
    ) {
        this.deviceBrand = deviceBrand;
        this.deviceType = deviceType;
        this.deviceId = deviceId;
        this.installId = installId;
        this.cdid = cdid;
        this.resolution = resolution;
        this.dpi = dpi;
        this.hostAbi = hostAbi;
        this.romVersion = romVersion;
        this.osVersion = osVersion;
        this.osApi = osApi;
        this.userAgent = userAgent;
        this.cookie = cookie;
        this.aid = aid;
        this.versionCode = versionCode;
        this.versionName = versionName;
        this.updateVersionCode = updateVersionCode;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getDeviceBrand() {
        return deviceBrand;
    }

    public String getDeviceType() {
        return deviceType;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getInstallId() {
        return installId;
    }

    public String getCdid() {
        return cdid;
    }

    public String getResolution() {
        return resolution;
    }

    public String getDpi() {
        return dpi;
    }

    public String getHostAbi() {
        return hostAbi;
    }

    public String getRomVersion() {
        return romVersion;
    }

    public String getOsVersion() {
        return osVersion;
    }

    public Integer getOsApi() {
        return osApi;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public String getCookie() {
        return cookie;
    }

    public String getAid() {
        return aid;
    }

    public String getVersionCode() {
        return versionCode;
    }

    public String getVersionName() {
        return versionName;
    }

    public String getUpdateVersionCode() {
        return updateVersionCode;
    }

    public static final class Builder {
        private String deviceBrand;
        private String deviceType;
        private String deviceId;
        private String installId;
        private String cdid;
        private String resolution;
        private String dpi;
        private String hostAbi;
        private String romVersion;
        private String osVersion;
        private Integer osApi;
        private String userAgent;
        private String cookie;
        private String aid;
        private String versionCode;
        private String versionName;
        private String updateVersionCode;

        private Builder() {
        }

        public Builder deviceBrand(String deviceBrand) {
            this.deviceBrand = deviceBrand;
            return this;
        }

        public Builder deviceType(String deviceType) {
            this.deviceType = deviceType;
            return this;
        }

        public Builder deviceId(String deviceId) {
            this.deviceId = deviceId;
            return this;
        }

        public Builder installId(String installId) {
            this.installId = installId;
            return this;
        }

        public Builder cdid(String cdid) {
            this.cdid = cdid;
            return this;
        }

        public Builder resolution(String resolution) {
            this.resolution = resolution;
            return this;
        }

        public Builder dpi(String dpi) {
            this.dpi = dpi;
            return this;
        }

        public Builder hostAbi(String hostAbi) {
            this.hostAbi = hostAbi;
            return this;
        }

        public Builder romVersion(String romVersion) {
            this.romVersion = romVersion;
            return this;
        }

        public Builder osVersion(String osVersion) {
            this.osVersion = osVersion;
            return this;
        }

        public Builder osApi(Integer osApi) {
            this.osApi = osApi;
            return this;
        }

        public Builder userAgent(String userAgent) {
            this.userAgent = userAgent;
            return this;
        }

        public Builder cookie(String cookie) {
            this.cookie = cookie;
            return this;
        }

        public Builder aid(String aid) {
            this.aid = aid;
            return this;
        }

        public Builder versionCode(String versionCode) {
            this.versionCode = versionCode;
            return this;
        }

        public Builder versionName(String versionName) {
            this.versionName = versionName;
            return this;
        }

        public Builder updateVersionCode(String updateVersionCode) {
            this.updateVersionCode = updateVersionCode;
            return this;
        }

        public DeviceInfo build() {
            return new DeviceInfo(
                deviceBrand,
                deviceType,
                deviceId,
                installId,
                cdid,
                resolution,
                dpi,
                hostAbi,
                romVersion,
                osVersion,
                osApi,
                userAgent,
                cookie,
                aid,
                versionCode,
                versionName,
                updateVersionCode
            );
        }
    }
}
