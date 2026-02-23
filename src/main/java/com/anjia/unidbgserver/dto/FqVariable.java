package com.anjia.unidbgserver.dto;

import com.anjia.unidbgserver.config.FQApiProperties;
import com.anjia.unidbgserver.utils.Texts;

/**
 * FQNovel API 变量配置
 * 对应 Rust 中的 FqVariable 结构
 */
public class FqVariable {
    private static final String VALUE_ZERO = "0";
    private static final String VALUE_ONE = "1";
    private static final String OS_ANDROID = "android";
    private static final String NETWORK_WIFI = "wifi";
    private static final String CHANNEL_GOOGLE_PLAY = "googleplay";
    private static final String APP_NOVEL = "novelapp";
    private static final String SSMIX_A = "a";
    private static final String LANGUAGE_ZH = "zh";
    private static final String DRAGON_DEVICE_PHONE = "phone";
    private static final String DEFAULT_OS_API = "32";
    private static final String DEFAULT_OS_VERSION = "13";

    /**
     * 安装ID
     */
    private String installId;

    /**
     * 服务端设备ID
     */
    private String serverDeviceId;

    /**
     * 应用ID
     */
    private String aid;

    /**
     * 更新版本代码
     */
    private String updateVersionCode;

    // 新增完整的API参数
    private String keyRegisterTs;
    private String deviceId;
    private String ac;
    private String channel;
    private String appName;
    private String versionCode;
    private String versionName;
    private String devicePlatform;
    private String os;
    private String ssmix;
    private String deviceType;
    private String deviceBrand;
    private String language;
    private String osApi;
    private String osVersion;
    private String manifestVersionCode;
    private String resolution;
    private String dpi;
    private String rticket;
    private String hostAbi;
    private String dragonDeviceType;
    private String pvPlayer;
    private String complianceStatus;
    private String needPersonalRecommend;
    private String playerSoLoad;
    private String isAndroidPadScreen;
    private String romVersion;
    private String cdid;

    /**
     * 从配置构造
     */
    public FqVariable(FQApiProperties fqApiProperties) {
        FQApiProperties.Device device = requireRuntimeDevice(fqApiProperties);
        this.installId = requireText(device.getInstallId(), "fq.api.device.install-id");
        this.serverDeviceId = requireText(device.getDeviceId(), "fq.api.device.device-id");
        this.aid = requireText(device.getAid(), "fq.api.device.aid");
        this.updateVersionCode = requireText(device.getUpdateVersionCode(), "fq.api.device.update-version-code");
        initializeFromConfig(device);
    }

    /**
     * 从配置初始化
     */
    private void initializeFromConfig(FQApiProperties.Device device) {
        String versionCode = device.getVersionCode();

        this.keyRegisterTs = VALUE_ZERO;
        this.deviceId = device.getDeviceId();
        this.ac = NETWORK_WIFI;
        this.channel = CHANNEL_GOOGLE_PLAY;
        this.appName = APP_NOVEL;
        this.versionCode = versionCode;
        this.versionName = device.getVersionName();
        this.devicePlatform = OS_ANDROID;
        this.os = OS_ANDROID;
        this.ssmix = SSMIX_A;
        this.deviceType = device.getDeviceType();
        this.deviceBrand = device.getDeviceBrand();
        this.language = LANGUAGE_ZH;
        this.osApi = Texts.defaultIfBlank(device.getOsApi(), DEFAULT_OS_API);
        this.osVersion = Texts.defaultIfBlank(device.getOsVersion(), DEFAULT_OS_VERSION);
        this.manifestVersionCode = versionCode;
        this.resolution = device.getResolution();
        this.dpi = device.getDpi();
        this.rticket = ""; // 使用时间戳需要在请求时动态生成
        this.hostAbi = device.getHostAbi();
        this.dragonDeviceType = DRAGON_DEVICE_PHONE;
        this.pvPlayer = versionCode;
        this.complianceStatus = VALUE_ZERO;
        this.needPersonalRecommend = VALUE_ONE;
        this.playerSoLoad = VALUE_ONE;
        this.isAndroidPadScreen = VALUE_ZERO;
        this.romVersion = device.getRomVersion();
        this.cdid = device.getCdid();
    }

    private static String requireText(String value, String fieldName) {
        if (!Texts.hasText(value)) {
            throw new IllegalStateException("缺少设备配置字段: " + fieldName);
        }
        return Texts.trimToEmpty(value);
    }

    private static FQApiProperties.Device requireRuntimeDevice(FQApiProperties fqApiProperties) {
        FQApiProperties.RuntimeProfile runtimeProfile = fqApiProperties == null ? null : fqApiProperties.getRuntimeProfile();
        FQApiProperties.Device device = runtimeProfile == null ? null : runtimeProfile.getDevice();
        if (device == null) {
            throw new IllegalStateException("缺少设备配置：fq.api.device");
        }
        return device;
    }

    public String getInstallId() {
        return installId;
    }

    public void setInstallId(String installId) {
        this.installId = installId;
    }

    public String getServerDeviceId() {
        return serverDeviceId;
    }

    public void setServerDeviceId(String serverDeviceId) {
        this.serverDeviceId = serverDeviceId;
    }

    public String getAid() {
        return aid;
    }

    public void setAid(String aid) {
        this.aid = aid;
    }

    public String getUpdateVersionCode() {
        return updateVersionCode;
    }

    public void setUpdateVersionCode(String updateVersionCode) {
        this.updateVersionCode = updateVersionCode;
    }

    public String getKeyRegisterTs() {
        return keyRegisterTs;
    }

    public void setKeyRegisterTs(String keyRegisterTs) {
        this.keyRegisterTs = keyRegisterTs;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getAc() {
        return ac;
    }

    public void setAc(String ac) {
        this.ac = ac;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
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

    public String getDevicePlatform() {
        return devicePlatform;
    }

    public void setDevicePlatform(String devicePlatform) {
        this.devicePlatform = devicePlatform;
    }

    public String getOs() {
        return os;
    }

    public void setOs(String os) {
        this.os = os;
    }

    public String getSsmix() {
        return ssmix;
    }

    public void setSsmix(String ssmix) {
        this.ssmix = ssmix;
    }

    public String getDeviceType() {
        return deviceType;
    }

    public void setDeviceType(String deviceType) {
        this.deviceType = deviceType;
    }

    public String getDeviceBrand() {
        return deviceBrand;
    }

    public void setDeviceBrand(String deviceBrand) {
        this.deviceBrand = deviceBrand;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getOsApi() {
        return osApi;
    }

    public void setOsApi(String osApi) {
        this.osApi = osApi;
    }

    public String getOsVersion() {
        return osVersion;
    }

    public void setOsVersion(String osVersion) {
        this.osVersion = osVersion;
    }

    public String getManifestVersionCode() {
        return manifestVersionCode;
    }

    public void setManifestVersionCode(String manifestVersionCode) {
        this.manifestVersionCode = manifestVersionCode;
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

    public String getRticket() {
        return rticket;
    }

    public void setRticket(String rticket) {
        this.rticket = rticket;
    }

    public String getHostAbi() {
        return hostAbi;
    }

    public void setHostAbi(String hostAbi) {
        this.hostAbi = hostAbi;
    }

    public String getDragonDeviceType() {
        return dragonDeviceType;
    }

    public void setDragonDeviceType(String dragonDeviceType) {
        this.dragonDeviceType = dragonDeviceType;
    }

    public String getPvPlayer() {
        return pvPlayer;
    }

    public void setPvPlayer(String pvPlayer) {
        this.pvPlayer = pvPlayer;
    }

    public String getComplianceStatus() {
        return complianceStatus;
    }

    public void setComplianceStatus(String complianceStatus) {
        this.complianceStatus = complianceStatus;
    }

    public String getNeedPersonalRecommend() {
        return needPersonalRecommend;
    }

    public void setNeedPersonalRecommend(String needPersonalRecommend) {
        this.needPersonalRecommend = needPersonalRecommend;
    }

    public String getPlayerSoLoad() {
        return playerSoLoad;
    }

    public void setPlayerSoLoad(String playerSoLoad) {
        this.playerSoLoad = playerSoLoad;
    }

    public String getIsAndroidPadScreen() {
        return isAndroidPadScreen;
    }

    public void setIsAndroidPadScreen(String isAndroidPadScreen) {
        this.isAndroidPadScreen = isAndroidPadScreen;
    }

    public String getRomVersion() {
        return romVersion;
    }

    public void setRomVersion(String romVersion) {
        this.romVersion = romVersion;
    }

    public String getCdid() {
        return cdid;
    }

    public void setCdid(String cdid) {
        this.cdid = cdid;
    }
}
