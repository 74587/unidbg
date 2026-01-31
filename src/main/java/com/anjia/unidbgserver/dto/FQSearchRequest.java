package com.anjia.unidbgserver.dto;

import lombok.Data;

/**
 * FQ搜索请求DTO
 * 补全所有实际API参数，保证与真实接口参数一致
 */
@Data
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
}
