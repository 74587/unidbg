package com.mengying.fqnovel.dto;

/**
 * FQ搜索请求DTO
 * 补全所有实际API参数，保证与真实接口参数一致
 */
public class FQSearchRequest {

    // 搜索关键词
    private String query;

    // 分页偏移量
    private Integer offset;

    // 每页数量
    private Integer count;

    // passback参数 (与offset数值相同)
    private Integer passback;

    // 搜索类型 1综合 2听书 3书籍 4社区 5全文 6用户 8漫画 11短剧 13买书
    private Integer tabType;

    // 搜索ID (用于二次搜索)
    private String searchId;

    // 书架搜索计划
    private Integer bookshelfSearchPlan;

    // 是否来自推荐系统
    private Boolean fromRs;

    // 用户是否登录
    private Integer userIsLogin;

    // 书店标签
    private Integer bookstoreTab;

    // 搜索来源
    private Integer searchSource;

    // 点击内容类型
    private String clickedContent;

    // 搜索来源ID
    private String searchSourceId;

    // 是否使用lynx
    private Boolean useLynx;

    // 是否使用纠错
    private Boolean useCorrect;

    // 标签页名称
    private String tabName;

    // 是否首次进入搜索
    private Boolean isFirstEnterSearch;

    // 客户端AB测试信息（JSON字符串，需URL编码）
    private String clientAbInfo;

    // 新增以下字段，补全所有抓包参数，建议从设备/环境/session动态生成

    // 搜索页面停留时长
    private Integer lastSearchPageInterval;

    // 每行字数
    private Integer lineWordsNum;

    // 最近消费间隔
    private Integer lastConsumeInterval;

    // pad显示封面
    private Integer padColumnCover;

    // 用于设备唯一标识
    private String klinkEgdi;

    // 会话相关
    private String normalSessionId;
    private String coldStartSessionId;

    // 充电状态
    private Integer charging;

    // 屏幕亮度
    private Integer screenBrightness;

    // 电池百分比
    private Integer batteryPct;

    // 下载速度
    private Integer downSpeed;

    // 系统暗色模式
    private Integer sysDarkMode;

    // app暗色模式
    private Integer appDarkMode;

    // 字体缩放
    private Integer fontScale;

    // 是否Pad屏幕
    private Integer isAndroidPadScreen;

    // 网络类型
    private Integer networkType;

    // ROM版本
    private String romVersion;

    // 当前音量
    private Integer currentVolume;

    // 设备唯一标识
    private String cdid;

    // 是否需要个性化推荐
    private Integer needPersonalRecommend;

    // 播放器so加载
    private Integer playerSoLoad;

    // 性别
    private Integer gender;

    // 合规状态
    private Integer complianceStatus;

    // HAR状态
    private Integer harStatus;

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public Integer getOffset() {
        return offset;
    }

    public void setOffset(Integer offset) {
        this.offset = offset;
    }

    public Integer getCount() {
        return count;
    }

    public void setCount(Integer count) {
        this.count = count;
    }

    public Integer getPassback() {
        return passback;
    }

    public void setPassback(Integer passback) {
        this.passback = passback;
    }

    public Integer getTabType() {
        return tabType;
    }

    public void setTabType(Integer tabType) {
        this.tabType = tabType;
    }

    public String getSearchId() {
        return searchId;
    }

    public void setSearchId(String searchId) {
        this.searchId = searchId;
    }

    public Integer getBookshelfSearchPlan() {
        return bookshelfSearchPlan;
    }

    public void setBookshelfSearchPlan(Integer bookshelfSearchPlan) {
        this.bookshelfSearchPlan = bookshelfSearchPlan;
    }

    public Boolean getFromRs() {
        return fromRs;
    }

    public void setFromRs(Boolean fromRs) {
        this.fromRs = fromRs;
    }

    public Integer getUserIsLogin() {
        return userIsLogin;
    }

    public void setUserIsLogin(Integer userIsLogin) {
        this.userIsLogin = userIsLogin;
    }

    public Integer getBookstoreTab() {
        return bookstoreTab;
    }

    public void setBookstoreTab(Integer bookstoreTab) {
        this.bookstoreTab = bookstoreTab;
    }

    public Integer getSearchSource() {
        return searchSource;
    }

    public void setSearchSource(Integer searchSource) {
        this.searchSource = searchSource;
    }

    public String getClickedContent() {
        return clickedContent;
    }

    public void setClickedContent(String clickedContent) {
        this.clickedContent = clickedContent;
    }

    public String getSearchSourceId() {
        return searchSourceId;
    }

    public void setSearchSourceId(String searchSourceId) {
        this.searchSourceId = searchSourceId;
    }

    public Boolean getUseLynx() {
        return useLynx;
    }

    public void setUseLynx(Boolean useLynx) {
        this.useLynx = useLynx;
    }

    public Boolean getUseCorrect() {
        return useCorrect;
    }

    public void setUseCorrect(Boolean useCorrect) {
        this.useCorrect = useCorrect;
    }

    public String getTabName() {
        return tabName;
    }

    public void setTabName(String tabName) {
        this.tabName = tabName;
    }

    public Boolean getIsFirstEnterSearch() {
        return isFirstEnterSearch;
    }

    public void setIsFirstEnterSearch(Boolean isFirstEnterSearch) {
        this.isFirstEnterSearch = isFirstEnterSearch;
    }

    public String getClientAbInfo() {
        return clientAbInfo;
    }

    public void setClientAbInfo(String clientAbInfo) {
        this.clientAbInfo = clientAbInfo;
    }

    public Integer getLastSearchPageInterval() {
        return lastSearchPageInterval;
    }

    public void setLastSearchPageInterval(Integer lastSearchPageInterval) {
        this.lastSearchPageInterval = lastSearchPageInterval;
    }

    public Integer getLineWordsNum() {
        return lineWordsNum;
    }

    public void setLineWordsNum(Integer lineWordsNum) {
        this.lineWordsNum = lineWordsNum;
    }

    public Integer getLastConsumeInterval() {
        return lastConsumeInterval;
    }

    public void setLastConsumeInterval(Integer lastConsumeInterval) {
        this.lastConsumeInterval = lastConsumeInterval;
    }

    public Integer getPadColumnCover() {
        return padColumnCover;
    }

    public void setPadColumnCover(Integer padColumnCover) {
        this.padColumnCover = padColumnCover;
    }

    public String getKlinkEgdi() {
        return klinkEgdi;
    }

    public void setKlinkEgdi(String klinkEgdi) {
        this.klinkEgdi = klinkEgdi;
    }

    public String getNormalSessionId() {
        return normalSessionId;
    }

    public void setNormalSessionId(String normalSessionId) {
        this.normalSessionId = normalSessionId;
    }

    public String getColdStartSessionId() {
        return coldStartSessionId;
    }

    public void setColdStartSessionId(String coldStartSessionId) {
        this.coldStartSessionId = coldStartSessionId;
    }

    public Integer getCharging() {
        return charging;
    }

    public void setCharging(Integer charging) {
        this.charging = charging;
    }

    public Integer getScreenBrightness() {
        return screenBrightness;
    }

    public void setScreenBrightness(Integer screenBrightness) {
        this.screenBrightness = screenBrightness;
    }

    public Integer getBatteryPct() {
        return batteryPct;
    }

    public void setBatteryPct(Integer batteryPct) {
        this.batteryPct = batteryPct;
    }

    public Integer getDownSpeed() {
        return downSpeed;
    }

    public void setDownSpeed(Integer downSpeed) {
        this.downSpeed = downSpeed;
    }

    public Integer getSysDarkMode() {
        return sysDarkMode;
    }

    public void setSysDarkMode(Integer sysDarkMode) {
        this.sysDarkMode = sysDarkMode;
    }

    public Integer getAppDarkMode() {
        return appDarkMode;
    }

    public void setAppDarkMode(Integer appDarkMode) {
        this.appDarkMode = appDarkMode;
    }

    public Integer getFontScale() {
        return fontScale;
    }

    public void setFontScale(Integer fontScale) {
        this.fontScale = fontScale;
    }

    public Integer getIsAndroidPadScreen() {
        return isAndroidPadScreen;
    }

    public void setIsAndroidPadScreen(Integer isAndroidPadScreen) {
        this.isAndroidPadScreen = isAndroidPadScreen;
    }

    public Integer getNetworkType() {
        return networkType;
    }

    public void setNetworkType(Integer networkType) {
        this.networkType = networkType;
    }

    public String getRomVersion() {
        return romVersion;
    }

    public void setRomVersion(String romVersion) {
        this.romVersion = romVersion;
    }

    public Integer getCurrentVolume() {
        return currentVolume;
    }

    public void setCurrentVolume(Integer currentVolume) {
        this.currentVolume = currentVolume;
    }

    public String getCdid() {
        return cdid;
    }

    public void setCdid(String cdid) {
        this.cdid = cdid;
    }

    public Integer getNeedPersonalRecommend() {
        return needPersonalRecommend;
    }

    public void setNeedPersonalRecommend(Integer needPersonalRecommend) {
        this.needPersonalRecommend = needPersonalRecommend;
    }

    public Integer getPlayerSoLoad() {
        return playerSoLoad;
    }

    public void setPlayerSoLoad(Integer playerSoLoad) {
        this.playerSoLoad = playerSoLoad;
    }

    public Integer getGender() {
        return gender;
    }

    public void setGender(Integer gender) {
        this.gender = gender;
    }

    public Integer getComplianceStatus() {
        return complianceStatus;
    }

    public void setComplianceStatus(Integer complianceStatus) {
        this.complianceStatus = complianceStatus;
    }

    public Integer getHarStatus() {
        return harStatus;
    }

    public void setHarStatus(Integer harStatus) {
        this.harStatus = harStatus;
    }
}
