package com.anjia.unidbgserver.dto;

/**
 * 设备信息DTO
 */
public class DeviceInfo {
    
    /**
     * 设备品牌
     */
    private String deviceBrand;
    
    /**
     * 设备型号
     */
    private String deviceType;
    
    /**
     * 设备ID
     */
    private String deviceId;
    
    /**
     * 安装ID
     */
    private String installId;
    
    /**
     * CDID
     */
    private String cdid;
    
    /**
     * 分辨率
     */
    private String resolution;
    
    /**
     * DPI
     */
    private String dpi;
    
    /**
     * 主机ABI
     */
    private String hostAbi;
    
    /**
     * ROM版本
     */
    private String romVersion;
    
    /**
     * Android版本
     */
    private String osVersion;
    
    /**
     * Android API级别
     */
    private Integer osApi;
    
    /**
     * 用户代理
     */
    private String userAgent;
    
    /**
     * Cookie
     */
    private String cookie;
    
    /**
     * 应用ID
     */
    private String aid;
    
    /**
     * 版本代码
     */
    private String versionCode;
    
    /**
     * 版本名称
     */
    private String versionName;
    
    /**
     * 更新版本代码
     */
    private String updateVersionCode;

    public DeviceInfo() {
    }

    public DeviceInfo(
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

    public void setDeviceBrand(String deviceBrand) {
        this.deviceBrand = deviceBrand;
    }

    public String getDeviceType() {
        return deviceType;
    }

    public void setDeviceType(String deviceType) {
        this.deviceType = deviceType;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getInstallId() {
        return installId;
    }

    public void setInstallId(String installId) {
        this.installId = installId;
    }

    public String getCdid() {
        return cdid;
    }

    public void setCdid(String cdid) {
        this.cdid = cdid;
    }

    public String getResolution() {
        return resolution;
    }

    public void setResolution(String resolution) {
        this.resolution = resolution;
    }

    public String getDpi() {
        return dpi;
    }

    public void setDpi(String dpi) {
        this.dpi = dpi;
    }

    public String getHostAbi() {
        return hostAbi;
    }

    public void setHostAbi(String hostAbi) {
        this.hostAbi = hostAbi;
    }

    public String getRomVersion() {
        return romVersion;
    }

    public void setRomVersion(String romVersion) {
        this.romVersion = romVersion;
    }

    public String getOsVersion() {
        return osVersion;
    }

    public void setOsVersion(String osVersion) {
        this.osVersion = osVersion;
    }

    public Integer getOsApi() {
        return osApi;
    }

    public void setOsApi(Integer osApi) {
        this.osApi = osApi;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public String getCookie() {
        return cookie;
    }

    public void setCookie(String cookie) {
        this.cookie = cookie;
    }

    public String getAid() {
        return aid;
    }

    public void setAid(String aid) {
        this.aid = aid;
    }

    public String getVersionCode() {
        return versionCode;
    }

    public void setVersionCode(String versionCode) {
        this.versionCode = versionCode;
    }

    public String getVersionName() {
        return versionName;
    }

    public void setVersionName(String versionName) {
        this.versionName = versionName;
    }

    public String getUpdateVersionCode() {
        return updateVersionCode;
    }

    public void setUpdateVersionCode(String updateVersionCode) {
        this.updateVersionCode = updateVersionCode;
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
