package com.macropilot.app.factory

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import org.json.JSONArray
import org.json.JSONObject

data class PopularAppTask(
    val taskId: String,
    val instruction: String,
    val category: String,
    val riskLevel: String = "LOW",
    val requiresLogin: Boolean = false,
    val requiresPayment: Boolean = false,
    val stopBeforeSubmit: Boolean = true,
    val expectedEvidence: List<String> = emptyList()
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("taskId", taskId)
            .put("instruction", instruction)
            .put("category", category)
            .put("riskLevel", riskLevel)
            .put("requiresLogin", requiresLogin)
            .put("requiresPayment", requiresPayment)
            .put("stopBeforeSubmit", stopBeforeSubmit)
            .put("expectedEvidence", JSONArray(expectedEvidence))
    }
}

data class PopularAppProfile(
    val packageName: String,
    val appName: String,
    val aliases: List<String> = emptyList(),
    val tasks: List<PopularAppTask>
) {
    fun packageCandidates(): List<String> = (listOf(packageName) + aliases).distinct()

    fun toJson(
        resolvedPackage: String? = null,
        installed: Boolean? = null,
        launchable: Boolean? = null,
        tasksPerApp: Int = tasks.size
    ): JSONObject {
        return JSONObject()
            .put("packageName", packageName)
            .put("resolvedPackage", resolvedPackage ?: JSONObject.NULL)
            .put("appName", appName)
            .put("aliases", JSONArray(aliases))
            .put("installed", installed ?: JSONObject.NULL)
            .put("launchable", launchable ?: JSONObject.NULL)
            .put("taskCount", tasks.take(tasksPerApp).size)
            .put("tasks", JSONArray(tasks.take(tasksPerApp).map { it.toJson() }))
    }
}

object PopularAppTaskCatalog {
    private fun t(
        id: String,
        instruction: String,
        category: String,
        riskLevel: String = "LOW",
        requiresLogin: Boolean = false,
        requiresPayment: Boolean = false,
        stopBeforeSubmit: Boolean = true,
        evidence: List<String> = emptyList()
    ) = PopularAppTask(
        taskId = id,
        instruction = instruction,
        category = category,
        riskLevel = riskLevel,
        requiresLogin = requiresLogin,
        requiresPayment = requiresPayment,
        stopBeforeSubmit = stopBeforeSubmit,
        expectedEvidence = evidence
    )

    fun defaultProfiles(): List<PopularAppProfile> = listOf(
        PopularAppProfile(
            packageName = "tv.danmaku.bili",
            appName = "哔哩哔哩",
            tasks = listOf(
                t("bili_search_open_first_video", "在哔哩哔哩搜索王老菊并打开第一个视频，必须验证已经进入视频播放页，不能只停在搜索结果页。", "search_open", evidence = listOf("搜索结果", "第一个视频", "播放页")),
                t("bili_following_tab", "打开哔哩哔哩底部动态或关注页，读取关注加号、动态列表和可点击入口。", "bottom_tab_probe", evidence = listOf("动态", "关注", "加号")),
                t("bili_mall_categories", "打开哔哩哔哩会员购页，进入全部分类或分类入口，然后返回会员购首页。", "graphical_entry_probe", evidence = listOf("会员购", "全部分类")),
                t("bili_mine_game_center", "打开哔哩哔哩我的页，进入游戏中心，记录游戏中心页面的主要图形化入口。", "child_page_probe", evidence = listOf("我的", "游戏中心")),
                t("bili_mine_services", "打开哔哩哔哩我的页，进入我的服务区域，读取其中至少三个可点击服务入口。", "child_page_probe", evidence = listOf("我的服务")),
                t("bili_search_stop_result", "在哔哩哔哩搜索猫和老鼠，停在搜索结果页并记录视频、用户、番剧等结果分区。", "search", evidence = listOf("猫和老鼠", "搜索结果")),
                t("bili_video_comments_probe", "在哔哩哔哩打开一个视频并打开评论区，只读取评论输入框和评论列表，不发表评论。", "comment_probe", stopBeforeSubmit = true, evidence = listOf("评论", "输入框")),
                t("bili_top_icons_probe", "在哔哩哔哩首页读取顶部小图标入口，比如搜索、消息、历史或扫一扫，逐个打开安全入口后返回。", "top_icon_probe", evidence = listOf("顶部入口")),
                t("bili_side_video_actions", "在哔哩哔哩视频播放页识别点赞、投币、收藏、分享等侧边或底部操作区，只记录位置不点击提交类动作。", "video_action_probe", evidence = listOf("点赞", "收藏")),
                t("bili_settings_probe", "打开哔哩哔哩我的页中的设置入口，读取设置页面列表后返回。", "settings_probe", evidence = listOf("设置"))
            )
        ),
        PopularAppProfile(
            packageName = "com.xingin.xhs",
            appName = "小红书",
            tasks = listOf(
                t("xhs_search_keyword", "在小红书搜索露营装备，停在综合结果页并记录笔记、商品、用户分区。", "search", evidence = listOf("露营装备", "搜索结果")),
                t("xhs_open_first_note", "在小红书搜索咖啡店并打开第一条笔记，验证已经进入笔记详情页。", "search_open", evidence = listOf("笔记详情")),
                t("xhs_publish_text_draft", "在小红书进入发布入口，创建文字草稿，内容写我是AI，但不要点击最终发布。", "draft_create", riskLevel = "MEDIUM", stopBeforeSubmit = true, evidence = listOf("发布", "草稿", "我是AI")),
                t("xhs_bottom_tabs", "依次打开小红书底部首页、购物、消息、我等分屏，记录每个页面的主要可点击入口。", "bottom_tab_probe", requiresLogin = false, evidence = listOf("底部标签")),
                t("xhs_mine_page_probe", "打开小红书我的页，读取收藏、赞过、笔记、设置等入口。", "profile_probe", evidence = listOf("我的", "收藏")),
                t("xhs_top_search_camera", "在小红书首页识别顶部搜索和相机或扫一扫等图形化入口，打开安全入口后返回。", "top_icon_probe", evidence = listOf("搜索", "相机")),
                t("xhs_note_comment_box", "打开小红书任意笔记，识别评论输入框和点赞收藏按钮，不发送评论。", "comment_probe", stopBeforeSubmit = true, evidence = listOf("评论", "点赞")),
                t("xhs_shop_category", "打开小红书购物页或商品入口，进入一个分类页并记录子页面 UI。", "shopping_probe", evidence = listOf("购物", "分类")),
                t("xhs_message_login_state", "打开小红书消息页，识别是否需要登录或消息列表入口。", "message_probe", requiresLogin = true, evidence = listOf("消息")),
                t("xhs_settings_probe", "打开小红书我的页中的设置入口，读取账号与隐私相关设置项后返回。", "settings_probe", evidence = listOf("设置"))
            )
        ),
        PopularAppProfile(
            packageName = "com.ss.android.ugc.aweme",
            appName = "抖音",
            tasks = listOf(
                t("douyin_search_keyword", "在抖音搜索王老菊，停在搜索结果页并记录用户、视频、综合分区。", "search", evidence = listOf("王老菊", "搜索结果")),
                t("douyin_open_first_video", "在抖音搜索猫咪并打开第一个视频，验证已经进入视频播放页。", "search_open", evidence = listOf("播放页")),
                t("douyin_publish_video_draft", "进入抖音发布入口，准备文字为我是AI的视频发布流程，但不要点击最终发布或上传。", "draft_create", riskLevel = "MEDIUM", stopBeforeSubmit = true, evidence = listOf("发布", "我是AI")),
                t("douyin_bottom_tabs", "依次打开抖音首页、朋友、消息、我等底部分屏，记录每个页面的主要入口。", "bottom_tab_probe", evidence = listOf("底部标签")),
                t("douyin_side_actions", "在抖音视频播放页识别右侧点赞、评论、收藏、分享按钮，打开评论区但不发表评论。", "video_action_probe", stopBeforeSubmit = true, evidence = listOf("点赞", "评论")),
                t("douyin_live_entry_probe", "在抖音首页识别直播或同城等入口，打开安全入口后返回。", "graphical_entry_probe", evidence = listOf("直播", "同城")),
                t("douyin_mine_settings", "打开抖音我的页，进入设置或三横菜单，读取主要菜单项后返回。", "settings_probe", evidence = listOf("我的", "设置")),
                t("douyin_shop_probe", "打开抖音商城或购物入口，进入分类页并记录顶部搜索和分类图标。", "shopping_probe", evidence = listOf("商城", "分类")),
                t("douyin_message_probe", "打开抖音消息页，识别登录状态、消息列表或好友入口。", "message_probe", requiresLogin = true, evidence = listOf("消息")),
                t("douyin_comment_draft", "打开抖音视频评论区，在评论框输入我是AI测试但不要发送。", "comment_draft", riskLevel = "MEDIUM", stopBeforeSubmit = true, evidence = listOf("评论框", "我是AI测试"))
            )
        ),
        PopularAppProfile(
            packageName = "com.hupu.games",
            appName = "虎扑",
            tasks = listOf(
                t("hupu_close_ads_home", "打开虎扑，先处理开屏广告和弹窗，再回到虎扑首页读取底部导航。", "ad_dialog_probe", evidence = listOf("首页", "底部导航")),
                t("hupu_search_team", "在虎扑搜索湖人，停在搜索结果页并记录帖子、用户、专区分区。", "search", evidence = listOf("湖人", "搜索结果")),
                t("hupu_enter_forum", "打开虎扑社区或专区入口，进入一个篮球相关专区并记录发帖入口。", "forum_probe", evidence = listOf("专区", "发帖")),
                t("hupu_post_draft", "在虎扑进入发帖入口，若需要选择专区就读取小字提示并选择安全专区，写草稿我是AI测试但不要最终发布。", "draft_create", riskLevel = "MEDIUM", requiresLogin = true, stopBeforeSubmit = true, evidence = listOf("发帖", "专区", "草稿")),
                t("hupu_dialog_small_text", "在虎扑触发或检测提示弹窗，重点读取小字提示内容，比如必须选择专区、登录、权限提示。", "dialog_probe", evidence = listOf("提示", "小字")),
                t("hupu_bottom_tabs", "依次打开虎扑底部首页、社区、赛事、我的等分屏，记录每页主要入口。", "bottom_tab_probe", evidence = listOf("底部标签")),
                t("hupu_news_detail", "打开虎扑首页第一条资讯详情，记录评论入口和返回逻辑，不发表评论。", "detail_probe", evidence = listOf("详情", "评论")),
                t("hupu_top_icons", "读取虎扑首页顶部加号、搜索、消息等小图标入口，打开安全入口后返回。", "top_icon_probe", evidence = listOf("加号", "搜索")),
                t("hupu_profile_probe", "打开虎扑我的页，读取登录、设置、收藏或历史入口。", "profile_probe", requiresLogin = true, evidence = listOf("我的", "设置")),
                t("hupu_settings_probe", "打开虎扑设置页，读取清理缓存、账号、安全、通知等设置项后返回。", "settings_probe", evidence = listOf("设置"))
            )
        ),
        PopularAppProfile(
            packageName = "com.sina.weibo",
            appName = "微博",
            tasks = listOf(
                t("weibo_search_hot", "在微博搜索王老菊，停在综合结果页并记录用户、微博、视频分区。", "search", evidence = listOf("王老菊", "搜索结果")),
                t("weibo_hot_search", "打开微博热搜榜，读取前几个热搜词和页面入口。", "feed_probe", evidence = listOf("热搜")),
                t("weibo_post_draft", "进入微博发布入口，写一条内容为我是AI测试的草稿，但不要点击发送或发布。", "draft_create", riskLevel = "MEDIUM", requiresLogin = true, stopBeforeSubmit = true, evidence = listOf("发布", "草稿")),
                t("weibo_bottom_tabs", "依次打开微博底部首页、视频、发现、消息、我等分屏并保存 UI。", "bottom_tab_probe", evidence = listOf("底部标签")),
                t("weibo_open_first_post", "在微博首页打开第一条微博详情，识别评论、转发、点赞按钮，不执行互动。", "detail_probe", evidence = listOf("微博详情", "评论")),
                t("weibo_comment_draft", "打开微博评论区，在评论框输入我是AI测试但不要发送。", "comment_draft", riskLevel = "MEDIUM", requiresLogin = true, stopBeforeSubmit = true, evidence = listOf("评论框")),
                t("weibo_profile_settings", "打开微博我的页，进入设置入口并读取主要设置项。", "settings_probe", evidence = listOf("我的", "设置")),
                t("weibo_top_icons", "识别微博顶部扫一扫、加号、搜索或消息入口，打开安全入口后返回。", "top_icon_probe", evidence = listOf("顶部入口")),
                t("weibo_video_tab", "打开微博视频页，识别视频流侧边互动 UI，不点击发布或关注。", "video_action_probe", evidence = listOf("视频")),
                t("weibo_message_probe", "打开微博消息页，识别登录状态和消息列表入口。", "message_probe", requiresLogin = true, evidence = listOf("消息"))
            )
        ),
        PopularAppProfile(
            packageName = "com.tencent.mobileqq",
            appName = "QQ",
            tasks = listOf(
                t("qq_login_state", "打开 QQ，识别快速登录、手机号登录、协议勾选小圈圈等入口，不输入密码。", "login_probe", requiresLogin = true, evidence = listOf("快速登录", "协议")),
                t("qq_bottom_tabs", "在 QQ 已登录或可见主页状态下，依次读取消息、联系人、动态等底部分屏。", "bottom_tab_probe", requiresLogin = true, evidence = listOf("消息", "联系人")),
                t("qq_search_contact", "在 QQ 搜索框搜索文件，停在结果页并记录联系人、群聊、聊天记录分区。", "search", requiresLogin = true, evidence = listOf("搜索")),
                t("qq_plus_menu", "打开 QQ 顶部加号菜单，读取扫一扫、加好友、创建群聊等入口后返回。", "top_icon_probe", requiresLogin = true, evidence = listOf("加号")),
                t("qq_settings_probe", "打开 QQ 头像或我的入口，进入设置页并读取账号、隐私、通用等设置项。", "settings_probe", requiresLogin = true, evidence = listOf("设置")),
                t("qq_chat_input_probe", "打开 QQ 任意安全聊天入口或文件助手，识别输入框、表情、加号、发送按钮，不发送消息。", "chat_probe", requiresLogin = true, stopBeforeSubmit = true, evidence = listOf("输入框", "发送")),
                t("qq_dynamic_page", "打开 QQ 动态页，读取小世界、游戏、空间等图形化入口。", "graphical_entry_probe", requiresLogin = true, evidence = listOf("动态")),
                t("qq_contacts_subpages", "打开 QQ 联系人页，切换好友、群聊、设备等子页面并记录 UI。", "child_page_probe", requiresLogin = true, evidence = listOf("联系人")),
                t("qq_message_search", "打开 QQ 消息页顶部搜索或分类入口，记录搜索框和可点击筛选项。", "search_probe", requiresLogin = true, evidence = listOf("消息搜索")),
                t("qq_login_blocker_report", "如果 QQ 未登录，停止并报告登录 blocker，必须截图保存协议勾选和快速登录入口位置。", "blocker_report", requiresLogin = true, evidence = listOf("登录 blocker"))
            )
        ),
        PopularAppProfile(
            packageName = "com.tencent.mm",
            appName = "微信",
            tasks = listOf(
                t("wechat_bottom_tabs_snapshot", "打开微信，依次保存底部微信、通讯录、发现、我四个页面 UI 图谱。", "bottom_tab_probe", requiresLogin = true, evidence = listOf("微信", "通讯录", "发现", "我")),
                t("wechat_search_filehelper", "在微信搜索文件传输助手，打开结果但不要发送消息。", "search_open", requiresLogin = true, evidence = listOf("文件传输助手")),
                t("wechat_chat_input_probe", "打开微信安全聊天页，识别输入框、加号、表情、发送按钮，不发送消息。", "chat_probe", requiresLogin = true, stopBeforeSubmit = true, evidence = listOf("输入框", "发送")),
                t("wechat_moments_private_draft", "进入微信发现页朋友圈入口，准备仅自己可见朋友圈草稿但不要点击发表。", "draft_create", riskLevel = "MEDIUM", requiresLogin = true, stopBeforeSubmit = true, evidence = listOf("朋友圈", "仅自己可见")),
                t("wechat_discover_entries", "打开微信发现页，读取朋友圈、视频号、扫一扫、小程序等入口。", "child_page_probe", requiresLogin = true, evidence = listOf("发现")),
                t("wechat_contacts_probe", "打开微信通讯录页，读取新的朋友、群聊、标签、公众号等入口。", "child_page_probe", requiresLogin = true, evidence = listOf("通讯录")),
                t("wechat_me_settings", "打开微信我页，进入设置并读取账号、安全、通用、隐私等设置项。", "settings_probe", requiresLogin = true, evidence = listOf("设置")),
                t("wechat_plus_menu", "打开微信右上角加号菜单，读取发起群聊、添加朋友、扫一扫、收付款入口，不进入支付确认。", "top_icon_probe", requiresLogin = true, evidence = listOf("加号")),
                t("wechat_pay_probe", "打开微信支付或服务入口，只读取收付款、钱包、账单入口，不输入密码不付款。", "safe_payment_probe", riskLevel = "HIGH", requiresLogin = true, requiresPayment = true, stopBeforeSubmit = true, evidence = listOf("支付", "钱包")),
                t("wechat_miniprogram_search", "打开微信小程序入口或搜索小程序，记录搜索框和结果列表，不授权敏感权限。", "search_probe", requiresLogin = true, evidence = listOf("小程序"))
            )
        ),
        PopularAppProfile(
            packageName = "com.sankuai.meituan",
            appName = "美团",
            tasks = listOf(
                t("meituan_search_food", "在美团搜索奶茶，停在结果页并记录店铺、商品、筛选入口。", "search", evidence = listOf("奶茶", "结果")),
                t("meituan_home_icons", "打开美团首页，逐个识别外卖、酒店、电影、打车、买菜等图形化入口。", "graphical_entry_probe", evidence = listOf("首页图标")),
                t("meituan_takeout_category", "进入美团外卖入口，打开一个分类页并记录顶部搜索、筛选、购物车入口，不下单。", "shopping_probe", stopBeforeSubmit = true, evidence = listOf("外卖", "分类")),
                t("meituan_bottom_tabs", "依次打开美团底部首页、消息、订单、我的等分屏并记录 UI。", "bottom_tab_probe", evidence = listOf("底部标签")),
                t("meituan_shop_detail", "在美团搜索结果打开第一个店铺详情，识别菜单、评价、商家、购物车，不提交订单。", "detail_probe", stopBeforeSubmit = true, evidence = listOf("店铺", "购物车")),
                t("meituan_order_page", "打开美团订单页，识别登录状态和订单筛选入口，不查看敏感详情。", "order_probe", requiresLogin = true, evidence = listOf("订单")),
                t("meituan_mine_settings", "打开美团我的页，进入设置或客服入口并记录设置项。", "settings_probe", evidence = listOf("我的", "设置")),
                t("meituan_top_search_scan", "识别美团顶部搜索、扫一扫、消息等小图标入口，安全打开后返回。", "top_icon_probe", evidence = listOf("搜索", "扫一扫")),
                t("meituan_coupon_probe", "打开美团红包或优惠券入口，读取可点击 UI，不领券不支付。", "promotion_probe", evidence = listOf("优惠券")),
                t("meituan_map_location_probe", "打开美团定位或附近入口，识别地图、地址、附近商家 UI，不修改地址。", "location_probe", evidence = listOf("附近", "地址"))
            )
        ),
        PopularAppProfile(
            packageName = "com.dianping.v1",
            appName = "大众点评",
            tasks = listOf(
                t("dianping_search_restaurant", "在大众点评搜索火锅，停在结果页并记录榜单、筛选、距离入口。", "search", evidence = listOf("火锅", "筛选")),
                t("dianping_home_icons", "打开大众点评首页，识别美食、休闲娱乐、丽人、酒店等图形化入口。", "graphical_entry_probe", evidence = listOf("首页图标")),
                t("dianping_open_first_shop", "搜索咖啡并打开第一个商户详情，读取评价、团购、地址、电话入口，不下单。", "detail_probe", stopBeforeSubmit = true, evidence = listOf("商户详情")),
                t("dianping_bottom_tabs", "依次打开大众点评底部首页、逛逛、消息、我的等分屏并记录 UI。", "bottom_tab_probe", evidence = listOf("底部标签")),
                t("dianping_city_category", "打开城市或分类入口，切换一个安全分类并记录子页面 UI。", "child_page_probe", evidence = listOf("分类")),
                t("dianping_review_draft", "进入大众点评写评价入口，识别评分和文本框，但不要提交评价。", "draft_create", riskLevel = "MEDIUM", requiresLogin = true, stopBeforeSubmit = true, evidence = listOf("写评价")),
                t("dianping_mine_settings", "打开大众点评我的页，读取收藏、足迹、设置等入口。", "profile_probe", requiresLogin = true, evidence = listOf("我的")),
                t("dianping_top_search_scan", "识别大众点评顶部搜索、扫一扫、消息等入口，打开安全入口后返回。", "top_icon_probe", evidence = listOf("顶部入口")),
                t("dianping_coupon_probe", "打开大众点评优惠券或团购券入口，读取 UI，不购买不支付。", "promotion_probe", stopBeforeSubmit = true, evidence = listOf("优惠")),
                t("dianping_map_nearby", "打开大众点评附近或地图相关入口，记录商户地图和列表切换 UI。", "location_probe", evidence = listOf("附近", "地图"))
            )
        ),
        PopularAppProfile(
            packageName = "com.taobao.taobao",
            appName = "淘宝",
            tasks = listOf(
                t("taobao_search_item", "在淘宝搜索机械键盘，停在商品结果页并记录筛选、店铺、价格入口。", "search", evidence = listOf("机械键盘", "商品")),
                t("taobao_home_icons", "打开淘宝首页，识别天猫超市、聚划算、淘宝直播、充值等图形化入口。", "graphical_entry_probe", evidence = listOf("首页图标")),
                t("taobao_open_item_detail", "搜索杯子并打开第一个商品详情，读取规格、评价、店铺、购物车入口，不下单。", "detail_probe", stopBeforeSubmit = true, evidence = listOf("商品详情")),
                t("taobao_bottom_tabs", "依次打开淘宝底部首页、逛逛、消息、购物车、我的淘宝分屏并记录 UI。", "bottom_tab_probe", evidence = listOf("底部标签")),
                t("taobao_cart_probe", "打开淘宝购物车，识别登录状态、商品列表和结算按钮，不点击结算。", "cart_probe", requiresLogin = true, stopBeforeSubmit = true, evidence = listOf("购物车", "结算")),
                t("taobao_mine_orders", "打开我的淘宝，读取订单、收藏、足迹、红包卡券入口。", "profile_probe", requiresLogin = true, evidence = listOf("我的淘宝", "订单")),
                t("taobao_top_scan_camera", "识别淘宝顶部扫一扫、拍照搜、消息等小图标入口，安全打开后返回。", "top_icon_probe", evidence = listOf("扫一扫")),
                t("taobao_category_probe", "打开淘宝分类或频道入口，进入一个子分类并记录子页面 UI。", "child_page_probe", evidence = listOf("分类")),
                t("taobao_customer_service", "打开淘宝客服或消息入口，识别输入框但不发送消息。", "message_probe", requiresLogin = true, stopBeforeSubmit = true, evidence = listOf("客服", "输入框")),
                t("taobao_payment_guard", "打开淘宝商品购买流程只到规格选择或确认订单前，不能点击提交订单或支付。", "safe_payment_probe", riskLevel = "HIGH", requiresLogin = true, requiresPayment = true, stopBeforeSubmit = true, evidence = listOf("确认订单"))
            )
        ),
        PopularAppProfile(
            packageName = "com.jingdong.app.mall",
            appName = "京东",
            tasks = listOf(
                t("jd_search_item", "在京东搜索耳机，停在商品结果页并记录筛选、店铺、价格入口。", "search", evidence = listOf("耳机", "商品")),
                t("jd_home_icons", "打开京东首页，识别秒杀、超市、充值、看病购药等图形化入口。", "graphical_entry_probe", evidence = listOf("首页图标")),
                t("jd_open_item_detail", "搜索鼠标并打开第一个商品详情，读取评价、规格、店铺、购物车入口，不下单。", "detail_probe", stopBeforeSubmit = true, evidence = listOf("商品详情")),
                t("jd_bottom_tabs", "依次打开京东底部首页、分类、购物车、我的等分屏并记录 UI。", "bottom_tab_probe", evidence = listOf("底部标签")),
                t("jd_category_page", "打开京东分类页，进入一个二级分类并记录子页面 UI。", "child_page_probe", evidence = listOf("分类")),
                t("jd_cart_probe", "打开京东购物车，识别登录状态、商品列表和去结算按钮，不点击结算。", "cart_probe", requiresLogin = true, stopBeforeSubmit = true, evidence = listOf("购物车")),
                t("jd_mine_orders", "打开京东我的页，读取订单、优惠券、收藏、设置入口。", "profile_probe", requiresLogin = true, evidence = listOf("我的", "订单")),
                t("jd_top_scan_message", "识别京东顶部搜索、扫一扫、消息等小图标入口，安全打开后返回。", "top_icon_probe", evidence = listOf("扫一扫")),
                t("jd_coupon_probe", "打开京东优惠券或领券中心入口，读取 UI，不领券不支付。", "promotion_probe", evidence = listOf("优惠券")),
                t("jd_payment_guard", "打开京东购买流程只到规格选择或确认订单前，不能点击提交订单或支付。", "safe_payment_probe", riskLevel = "HIGH", requiresLogin = true, requiresPayment = true, stopBeforeSubmit = true, evidence = listOf("确认订单"))
            )
        ),
        PopularAppProfile(
            packageName = "com.xunmeng.pinduoduo",
            appName = "拼多多",
            tasks = listOf(
                t("pdd_search_item", "在拼多多搜索纸巾，停在结果页并记录筛选、店铺、价格入口。", "search", evidence = listOf("纸巾", "结果")),
                t("pdd_home_icons", "打开拼多多首页，识别限时秒杀、多多买菜、充值等图形化入口。", "graphical_entry_probe", evidence = listOf("首页图标")),
                t("pdd_open_item_detail", "搜索水杯并打开第一个商品详情，读取拼单、评价、店铺、客服入口，不下单。", "detail_probe", stopBeforeSubmit = true, evidence = listOf("商品详情")),
                t("pdd_bottom_tabs", "依次打开拼多多底部首页、推荐、搜索、聊天、个人中心等分屏并记录 UI。", "bottom_tab_probe", evidence = listOf("底部标签")),
                t("pdd_category_probe", "打开拼多多分类或搜索推荐入口，进入一个分类并记录子页面 UI。", "child_page_probe", evidence = listOf("分类")),
                t("pdd_cart_order_probe", "打开拼多多购物车或订单入口，识别结算、待付款按钮，不点击提交或支付。", "cart_probe", requiresLogin = true, stopBeforeSubmit = true, evidence = listOf("订单", "结算")),
                t("pdd_mine_probe", "打开拼多多个人中心，读取订单、优惠券、收藏、设置入口。", "profile_probe", requiresLogin = true, evidence = listOf("个人中心")),
                t("pdd_top_scan_message", "识别拼多多顶部搜索、消息、扫一扫等小图标入口，安全打开后返回。", "top_icon_probe", evidence = listOf("顶部入口")),
                t("pdd_coupon_probe", "打开拼多多优惠券或红包入口，读取 UI，不领取需要支付的权益。", "promotion_probe", evidence = listOf("红包")),
                t("pdd_payment_guard", "打开拼多多购买流程只到规格选择或确认订单前，不能点击免拼、提交订单或支付。", "safe_payment_probe", riskLevel = "HIGH", requiresLogin = true, requiresPayment = true, stopBeforeSubmit = true, evidence = listOf("确认订单"))
            )
        ),
        PopularAppProfile(
            packageName = "com.zhihu.android",
            appName = "知乎",
            tasks = listOf(
                t("zhihu_search_question", "在知乎搜索人工智能，停在综合结果页并记录回答、文章、用户分区。", "search", evidence = listOf("人工智能", "搜索结果")),
                t("zhihu_open_first_answer", "在知乎搜索 Kotlin 并打开第一个回答或问题详情，读取回答和评论入口。", "search_open", evidence = listOf("问题详情", "回答")),
                t("zhihu_write_draft", "进入知乎回答或想法发布入口，写草稿我是AI测试但不要发布。", "draft_create", riskLevel = "MEDIUM", requiresLogin = true, stopBeforeSubmit = true, evidence = listOf("草稿")),
                t("zhihu_bottom_tabs", "依次打开知乎底部首页、会员、消息、我的等分屏并记录 UI。", "bottom_tab_probe", evidence = listOf("底部标签")),
                t("zhihu_hot_list", "打开知乎热榜，读取榜单条目和分区入口。", "feed_probe", evidence = listOf("热榜")),
                t("zhihu_comment_draft", "打开知乎回答评论区，在评论框输入我是AI测试但不要发送。", "comment_draft", riskLevel = "MEDIUM", requiresLogin = true, stopBeforeSubmit = true, evidence = listOf("评论框")),
                t("zhihu_mine_settings", "打开知乎我的页，进入设置并读取账号、隐私、通知等设置项。", "settings_probe", evidence = listOf("设置")),
                t("zhihu_top_icons", "识别知乎顶部搜索、创作、消息等入口，安全打开后返回。", "top_icon_probe", evidence = listOf("顶部入口")),
                t("zhihu_follow_page", "打开知乎关注页或推荐页，记录列表和筛选 UI。", "feed_probe", evidence = listOf("关注")),
                t("zhihu_profile_login_blocker", "如果知乎未登录，停止并报告登录 blocker，保存登录按钮和协议提示位置。", "blocker_report", requiresLogin = true, evidence = listOf("登录 blocker"))
            )
        ),
        PopularAppProfile(
            packageName = "com.netease.cloudmusic",
            appName = "网易云音乐",
            tasks = listOf(
                t("music_search_song", "在网易云音乐搜索周杰伦，停在歌曲结果页并记录单曲、歌单、用户分区。", "search", evidence = listOf("周杰伦", "搜索结果")),
                t("music_open_first_song", "搜索晴天并打开第一首歌曲播放页，验证进入播放界面。", "search_open", evidence = listOf("播放页")),
                t("music_bottom_tabs", "依次打开网易云音乐底部发现、播客、我的、关注等分屏并记录 UI。", "bottom_tab_probe", evidence = listOf("底部标签")),
                t("music_playlist_probe", "打开一个歌单详情，读取播放全部、收藏、评论、歌曲列表入口，不执行付费动作。", "detail_probe", evidence = listOf("歌单")),
                t("music_comment_draft", "打开歌曲评论区，在评论框输入我是AI测试但不要发送。", "comment_draft", riskLevel = "MEDIUM", requiresLogin = true, stopBeforeSubmit = true, evidence = listOf("评论框")),
                t("music_mine_downloads", "打开网易云我的页，读取本地、最近播放、下载、收藏入口。", "profile_probe", requiresLogin = true, evidence = listOf("我的")),
                t("music_top_icons", "识别网易云顶部搜索、听歌识曲、扫一扫、消息入口，安全打开后返回。", "top_icon_probe", evidence = listOf("顶部入口")),
                t("music_settings_probe", "打开网易云设置页，读取账号、播放、通知、隐私等设置项。", "settings_probe", evidence = listOf("设置")),
                t("music_podcast_page", "打开播客页，进入一个频道或节目详情并记录子页面 UI。", "child_page_probe", evidence = listOf("播客")),
                t("music_vip_guard", "打开会员或付费音乐入口，只读取 UI，不购买不支付。", "safe_payment_probe", riskLevel = "HIGH", requiresPayment = true, stopBeforeSubmit = true, evidence = listOf("会员"))
            )
        ),
        PopularAppProfile(
            packageName = "com.baidu.BaiduMap",
            appName = "百度地图",
            aliases = listOf("com.baidu.baidumap"),
            tasks = listOf(
                t("baidumap_search_place", "在百度地图搜索上海站，停在地点结果页并记录路线、收藏、电话入口。", "search", evidence = listOf("上海站", "地点")),
                t("baidumap_route_probe", "在百度地图打开路线规划入口，设置目的地为人民广场但不要开始导航。", "route_probe", stopBeforeSubmit = true, evidence = listOf("路线", "人民广场")),
                t("baidumap_home_icons", "打开百度地图首页，识别路线、公交地铁、打车、周边等图形化入口。", "graphical_entry_probe", evidence = listOf("首页图标")),
                t("baidumap_bottom_or_layer", "打开百度地图底部或侧边功能层，记录附近、出行、我的等入口。", "bottom_tab_probe", evidence = listOf("附近", "我的")),
                t("baidumap_nearby_food", "在百度地图搜索附近咖啡，读取附近商户列表和筛选入口。", "search", evidence = listOf("附近咖啡")),
                t("baidumap_place_detail", "打开一个地点详情，读取地址、评价、营业时间、导航按钮，不开始导航。", "detail_probe", stopBeforeSubmit = true, evidence = listOf("地点详情")),
                t("baidumap_mine_settings", "打开百度地图我的页，读取收藏、足迹、设置入口。", "profile_probe", requiresLogin = true, evidence = listOf("我的")),
                t("baidumap_top_search_voice", "识别百度地图顶部搜索、语音、扫一扫等小图标入口，安全打开后返回。", "top_icon_probe", evidence = listOf("搜索", "语音")),
                t("baidumap_transit_probe", "打开公交地铁入口，查询附近公交或地铁页面并记录 UI。", "transit_probe", evidence = listOf("公交", "地铁")),
                t("baidumap_taxi_guard", "打开百度地图打车入口，只读取起终点和车型 UI，不叫车不支付。", "safe_payment_probe", riskLevel = "HIGH", requiresPayment = true, stopBeforeSubmit = true, evidence = listOf("打车"))
            )
        ),
        PopularAppProfile(
            packageName = "com.autonavi.minimap",
            appName = "高德地图",
            tasks = listOf(
                t("amap_search_place", "在高德地图搜索上海站，停在地点结果页并记录路线、收藏、电话入口。", "search", evidence = listOf("上海站", "地点")),
                t("amap_route_probe", "在高德地图打开路线规划入口，设置目的地为人民广场但不要开始导航。", "route_probe", stopBeforeSubmit = true, evidence = listOf("路线")),
                t("amap_home_icons", "打开高德地图首页，识别路线、打车、公交地铁、附近等图形化入口。", "graphical_entry_probe", evidence = listOf("首页图标")),
                t("amap_nearby_food", "在高德地图搜索附近咖啡，读取附近商户列表和筛选入口。", "search", evidence = listOf("附近咖啡")),
                t("amap_place_detail", "打开一个地点详情，读取地址、评价、营业时间、导航按钮，不开始导航。", "detail_probe", stopBeforeSubmit = true, evidence = listOf("地点详情")),
                t("amap_bottom_layer", "打开高德地图底部或侧边功能层，记录消息、我的、工具箱等入口。", "bottom_tab_probe", evidence = listOf("工具箱")),
                t("amap_mine_settings", "打开高德地图我的页，读取收藏、足迹、设置入口。", "profile_probe", requiresLogin = true, evidence = listOf("我的")),
                t("amap_top_scan_voice", "识别高德地图顶部搜索、语音、扫一扫等入口，安全打开后返回。", "top_icon_probe", evidence = listOf("搜索")),
                t("amap_transit_probe", "打开高德地图公交地铁入口，记录线路查询和附近站点 UI。", "transit_probe", evidence = listOf("公交", "地铁")),
                t("amap_taxi_guard", "打开高德地图打车入口，只读取起终点和车型 UI，不叫车不支付。", "safe_payment_probe", riskLevel = "HIGH", requiresPayment = true, stopBeforeSubmit = true, evidence = listOf("打车"))
            )
        ),
        PopularAppProfile(
            packageName = "com.eg.android.AlipayGphone",
            appName = "支付宝",
            aliases = listOf("com.eg.android.alipaygphone"),
            tasks = listOf(
                t("alipay_home_icons", "打开支付宝首页，识别扫一扫、收付款、出行、卡包、生活缴费等图形化入口，不进入付款确认。", "graphical_entry_probe", riskLevel = "HIGH", requiresPayment = true, stopBeforeSubmit = true, evidence = listOf("扫一扫", "收付款")),
                t("alipay_search_service", "在支付宝搜索地铁，停在服务结果页并记录小程序、服务、生活号分区。", "search", evidence = listOf("地铁", "搜索结果")),
                t("alipay_scan_probe", "打开支付宝扫一扫入口，只识别扫码页面和返回按钮，不扫码不授权。", "safe_payment_probe", riskLevel = "HIGH", requiresPayment = true, stopBeforeSubmit = true, evidence = listOf("扫一扫")),
                t("alipay_pay_guard", "打开支付宝收付款入口，只读取付款码页面状态，不截图付款码内容，不输入密码不付款。", "safe_payment_probe", riskLevel = "HIGH", requiresPayment = true, stopBeforeSubmit = true, evidence = listOf("收付款")),
                t("alipay_bottom_tabs", "依次打开支付宝首页、理财、消息、我的等分屏并记录 UI，不做交易。", "bottom_tab_probe", riskLevel = "MEDIUM", evidence = listOf("底部标签")),
                t("alipay_mine_settings", "打开支付宝我的页，进入设置并读取账号、安全、支付设置入口，不修改设置。", "settings_probe", riskLevel = "HIGH", requiresLogin = true, requiresPayment = true, stopBeforeSubmit = true, evidence = listOf("设置")),
                t("alipay_bill_probe", "打开支付宝账单或交易记录入口，只识别页面结构，不进入具体敏感记录。", "finance_probe", riskLevel = "HIGH", requiresLogin = true, requiresPayment = true, stopBeforeSubmit = true, evidence = listOf("账单")),
                t("alipay_card_coupon", "打开支付宝卡包或优惠券入口，读取列表 UI，不领取需要支付的权益。", "promotion_probe", requiresLogin = true, evidence = listOf("卡包")),
                t("alipay_city_service", "打开支付宝市民中心或生活服务入口，记录子页面 UI。", "child_page_probe", evidence = listOf("市民中心")),
                t("alipay_password_guard", "如果出现支付密码、刷脸、授权确认页面，立即停止并报告 blocker。", "blocker_report", riskLevel = "HIGH", requiresLogin = true, requiresPayment = true, stopBeforeSubmit = true, evidence = listOf("密码 blocker"))
            )
        ),
        PopularAppProfile(
            packageName = "com.unionpay",
            appName = "云闪付",
            tasks = listOf(
                t("unionpay_home_icons", "打开云闪付首页，识别扫一扫、收付款、乘车码、信用卡等入口，不进入付款确认。", "graphical_entry_probe", riskLevel = "HIGH", requiresPayment = true, stopBeforeSubmit = true, evidence = listOf("扫一扫", "收付款")),
                t("unionpay_search_service", "在云闪付搜索优惠券，停在结果页并记录服务入口。", "search", evidence = listOf("优惠券")),
                t("unionpay_pay_guard", "打开云闪付收付款入口，只读取页面结构，不展示或保存付款码内容，不输入密码。", "safe_payment_probe", riskLevel = "HIGH", requiresPayment = true, stopBeforeSubmit = true, evidence = listOf("收付款")),
                t("unionpay_scan_probe", "打开云闪付扫一扫入口，只识别扫码 UI 和返回按钮，不扫码不授权。", "safe_payment_probe", riskLevel = "HIGH", requiresPayment = true, stopBeforeSubmit = true, evidence = listOf("扫一扫")),
                t("unionpay_bottom_tabs", "依次打开云闪付首页、财富、生活、我的等分屏并记录 UI。", "bottom_tab_probe", riskLevel = "MEDIUM", evidence = listOf("底部标签")),
                t("unionpay_mine_settings", "打开云闪付我的页，进入设置并读取账号、安全、支付设置入口，不修改设置。", "settings_probe", riskLevel = "HIGH", requiresLogin = true, requiresPayment = true, stopBeforeSubmit = true, evidence = listOf("设置")),
                t("unionpay_coupon_probe", "打开云闪付优惠券或活动入口，读取 UI，不领取需要支付的权益。", "promotion_probe", evidence = listOf("优惠券")),
                t("unionpay_bill_probe", "打开云闪付账单或交易记录入口，只识别页面结构，不进入具体敏感记录。", "finance_probe", riskLevel = "HIGH", requiresLogin = true, requiresPayment = true, stopBeforeSubmit = true, evidence = listOf("账单")),
                t("unionpay_card_probe", "打开银行卡管理入口，只记录入口状态，不查看完整卡号不解绑。", "finance_probe", riskLevel = "HIGH", requiresLogin = true, requiresPayment = true, stopBeforeSubmit = true, evidence = listOf("银行卡")),
                t("unionpay_password_guard", "如果出现支付密码、刷脸、授权确认页面，立即停止并报告 blocker。", "blocker_report", riskLevel = "HIGH", requiresLogin = true, requiresPayment = true, stopBeforeSubmit = true, evidence = listOf("密码 blocker"))
            )
        ),
        PopularAppProfile(
            packageName = "com.hexin.plat.android",
            appName = "同花顺",
            tasks = listOf(
                t("hexin_search_stock", "在同花顺搜索贵州茅台，停在股票详情页并记录盘口、K线、资讯入口，不交易。", "search", riskLevel = "MEDIUM", evidence = listOf("贵州茅台", "股票详情")),
                t("hexin_market_tabs", "打开同花顺行情页，切换沪深、港股、美股或自选等分区并记录 UI。", "bottom_tab_probe", riskLevel = "MEDIUM", evidence = listOf("行情")),
                t("hexin_optional_stock", "打开同花顺自选页，识别添加自选和列表入口，不买卖股票。", "profile_probe", riskLevel = "MEDIUM", requiresLogin = true, stopBeforeSubmit = true, evidence = listOf("自选")),
                t("hexin_stock_detail_kline", "打开任意股票详情，切换分时、日K、资讯、公告等子页面并记录 UI。", "child_page_probe", riskLevel = "MEDIUM", evidence = listOf("K线")),
                t("hexin_trade_guard", "打开同花顺交易入口只识别登录或券商选择页面，不输入密码不交易。", "safe_payment_probe", riskLevel = "HIGH", requiresLogin = true, requiresPayment = true, stopBeforeSubmit = true, evidence = listOf("交易")),
                t("hexin_news_probe", "打开同花顺资讯页，读取热门资讯和搜索入口。", "feed_probe", riskLevel = "MEDIUM", evidence = listOf("资讯")),
                t("hexin_mine_settings", "打开同花顺我的页，进入设置并读取账号、安全、消息设置入口，不修改设置。", "settings_probe", riskLevel = "MEDIUM", requiresLogin = true, evidence = listOf("设置")),
                t("hexin_top_search", "识别同花顺顶部搜索、消息、刷新等小图标入口，安全打开后返回。", "top_icon_probe", riskLevel = "MEDIUM", evidence = listOf("搜索")),
                t("hexin_fund_probe", "打开基金或理财入口，只读取产品列表 UI，不申购不支付。", "finance_probe", riskLevel = "HIGH", requiresPayment = true, stopBeforeSubmit = true, evidence = listOf("基金")),
                t("hexin_password_guard", "如果出现交易密码、资金密码或券商授权页面，立即停止并报告 blocker。", "blocker_report", riskLevel = "HIGH", requiresLogin = true, requiresPayment = true, stopBeforeSubmit = true, evidence = listOf("密码 blocker"))
            )
        ),
        PopularAppProfile(
            packageName = "com.alibaba.android.rimet",
            appName = "钉钉",
            tasks = listOf(
                t("dingtalk_login_state", "打开钉钉，识别登录状态、手机号登录、扫码登录和协议提示，不输入密码。", "login_probe", requiresLogin = true, evidence = listOf("登录")),
                t("dingtalk_bottom_tabs", "在钉钉主页依次打开消息、文档、工作台、通讯录、我的等分屏并记录 UI。", "bottom_tab_probe", requiresLogin = true, evidence = listOf("底部标签")),
                t("dingtalk_search_contact", "在钉钉搜索框搜索测试，停在结果页并记录联系人、群聊、文档分区。", "search", requiresLogin = true, evidence = listOf("搜索")),
                t("dingtalk_workbench_probe", "打开钉钉工作台，读取考勤、审批、日程、会议等图形化入口，不提交申请。", "graphical_entry_probe", requiresLogin = true, evidence = listOf("工作台")),
                t("dingtalk_plus_menu", "打开钉钉顶部加号菜单，读取发起群聊、扫一扫、添加好友等入口后返回。", "top_icon_probe", requiresLogin = true, evidence = listOf("加号")),
                t("dingtalk_chat_input_probe", "打开钉钉安全聊天入口，识别输入框、加号、发送按钮，不发送消息。", "chat_probe", requiresLogin = true, stopBeforeSubmit = true, evidence = listOf("输入框")),
                t("dingtalk_calendar_probe", "打开钉钉日程或会议入口，记录日历和新建按钮，不创建会议。", "child_page_probe", requiresLogin = true, evidence = listOf("日程")),
                t("dingtalk_mine_settings", "打开钉钉我的页，进入设置并读取账号、安全、通用等设置项。", "settings_probe", requiresLogin = true, evidence = listOf("设置")),
                t("dingtalk_approval_draft", "打开钉钉审批入口，识别请假或报销表单字段，但不要提交审批。", "draft_create", riskLevel = "MEDIUM", requiresLogin = true, stopBeforeSubmit = true, evidence = listOf("审批")),
                t("dingtalk_login_blocker_report", "如果钉钉未登录，停止并报告登录 blocker，保存登录入口和协议提示位置。", "blocker_report", requiresLogin = true, evidence = listOf("登录 blocker"))
            )
        ),
        PopularAppProfile(
            packageName = "com.baidu.tieba",
            appName = "Baidu Tieba",
            tasks = listOf(
                t("tieba_search_topic", "Open Baidu Tieba, search for 人工智能, stop on the search results page, and record forum, post, and user entries.", "search", evidence = listOf("人工智能", "search results")),
                t("tieba_home_icons", "Open Baidu Tieba home, read top search, message, check-in, plus, or other graphical entries, safely open one entry and return.", "top_icon_probe", evidence = listOf("search", "message", "check-in")),
                t("tieba_bottom_tabs", "Open Baidu Tieba bottom tabs one by one, including home, forums, messages, and mine, and save each page UI.", "bottom_tab_probe", evidence = listOf("bottom tabs")),
                t("tieba_open_first_post", "Open the first post detail from Baidu Tieba home or search results, read comment entry and floor list, and do not post a comment.", "detail_probe", stopBeforeSubmit = true, evidence = listOf("post detail", "comment")),
                t("tieba_post_draft", "Enter Baidu Tieba post composer, write draft text 我是AI测试, but do not publish.", "draft_create", riskLevel = "MEDIUM", requiresLogin = true, stopBeforeSubmit = true, evidence = listOf("post", "draft")),
                t("tieba_comment_draft", "Open a Baidu Tieba post comment box, input 我是AI测试, but do not send.", "comment_draft", riskLevel = "MEDIUM", requiresLogin = true, stopBeforeSubmit = true, evidence = listOf("comment box")),
                t("tieba_forum_probe", "Enter a Tieba forum home page, read follow, post, featured, and post list subpage UI, and do not follow.", "child_page_probe", stopBeforeSubmit = true, evidence = listOf("forum home")),
                t("tieba_mine_settings", "Open Baidu Tieba mine page, enter settings, read account, privacy, notification settings, then return.", "settings_probe", requiresLogin = true, evidence = listOf("mine", "settings")),
                t("tieba_message_probe", "Open Baidu Tieba messages page, identify login state, notification list, and private message entry, without opening sensitive private messages.", "message_probe", requiresLogin = true, evidence = listOf("messages")),
                t("tieba_login_blocker_report", "If Baidu Tieba is not logged in, stop and report login blocker, saving login button and agreement prompt position.", "blocker_report", requiresLogin = true, evidence = listOf("login blocker"))
            )
        ),
        PopularAppProfile(
            packageName = "com.coolapk.market",
            appName = "Coolapk",
            tasks = listOf(
                t("coolapk_search_app", "Open Coolapk, search for MacroDroid, stop on the results page, and record app, post, and user sections.", "search", evidence = listOf("MacroDroid", "search results")),
                t("coolapk_home_icons", "Open Coolapk home, identify top search, plus, messages, scan, and other small icon entries, safely open one and return.", "top_icon_probe", evidence = listOf("search", "plus", "messages")),
                t("coolapk_bottom_tabs", "Open Coolapk bottom tabs one by one, including home, apps, cool pics, messages, and mine, and save each page UI.", "bottom_tab_probe", evidence = listOf("bottom tabs")),
                t("coolapk_open_first_feed", "Open the first Coolapk feed or post detail, read like, comment, and share entries, and do not interact.", "detail_probe", stopBeforeSubmit = true, evidence = listOf("feed detail", "comment")),
                t("coolapk_post_draft", "Enter Coolapk publish entry, write draft text 我是AI测试, but do not publish.", "draft_create", riskLevel = "MEDIUM", requiresLogin = true, stopBeforeSubmit = true, evidence = listOf("publish", "draft")),
                t("coolapk_comment_draft", "Open a Coolapk post comment box, input 我是AI测试, but do not send.", "comment_draft", riskLevel = "MEDIUM", requiresLogin = true, stopBeforeSubmit = true, evidence = listOf("comment box")),
                t("coolapk_app_detail_probe", "Open any Coolapk app detail page, read download, rating, comments, and version info entries, and do not install.", "detail_probe", stopBeforeSubmit = true, evidence = listOf("app detail")),
                t("coolapk_mine_settings", "Open Coolapk mine page, enter settings, read account, privacy, notification settings, then return.", "settings_probe", requiresLogin = true, evidence = listOf("mine", "settings")),
                t("coolapk_message_probe", "Open Coolapk messages page, identify login state, notification list, and private message entry, without opening sensitive private messages.", "message_probe", requiresLogin = true, evidence = listOf("messages")),
                t("coolapk_login_blocker_report", "If Coolapk is not logged in, stop and report login blocker, saving login button and agreement prompt position.", "blocker_report", requiresLogin = true, evidence = listOf("login blocker"))
            )
        )
    ).map { it.withThirdRoundTasks() }

    private fun PopularAppProfile.withThirdRoundTasks(): PopularAppProfile {
        val extras = when (packageName) {
            "com.tencent.mm" -> listOf(
                t("wechat_filehelper_message_draft", "打开微信文件传输助手或安全聊天入口，在输入框写入我是AI测试但不要发送，记录输入框、发送按钮和草稿状态。", "chat_probe", requiresLogin = true, stopBeforeSubmit = true, evidence = listOf("文件传输助手", "输入框", "草稿")),
                t("wechat_moments_camera_entry", "进入微信朋友圈，打开右上角相机或发布入口，识别拍照、相册、文字入口后返回，不发表。", "top_icon_probe", riskLevel = "MEDIUM", requiresLogin = true, stopBeforeSubmit = true, evidence = listOf("朋友圈", "相机", "文字")),
                t("wechat_channels_probe", "打开微信发现页的视频号入口，读取推荐、关注、搜索和视频互动按钮，不点赞不关注。", "video_action_probe", requiresLogin = true, stopBeforeSubmit = true, evidence = listOf("视频号", "关注", "搜索")),
                t("wechat_public_account_search", "在微信搜索公众号或文章关键词人工智能，停在搜索结果页并记录文章、公众号、小程序分区。", "search", requiresLogin = true, evidence = listOf("人工智能", "公众号", "搜索结果")),
                t("wechat_group_chat_probe", "打开微信通讯录里的群聊入口，读取群聊列表或登录状态，不进入敏感聊天内容。", "child_page_probe", requiresLogin = true, evidence = listOf("群聊", "通讯录")),
                t("wechat_favorites_probe", "打开微信收藏入口，读取笔记、文件、图片等分类入口，不删除不分享。", "profile_probe", requiresLogin = true, evidence = listOf("收藏", "分类")),
                t("wechat_mini_program_recent", "打开微信小程序最近使用页，读取搜索框、最近使用和发现入口，不授权敏感权限。", "child_page_probe", requiresLogin = true, evidence = listOf("小程序", "最近使用")),
                t("wechat_service_notifications", "打开微信服务通知或消息列表，识别通知列表和搜索入口，不点开敏感账单详情。", "message_probe", requiresLogin = true, evidence = listOf("服务通知", "消息")),
                t("wechat_privacy_settings_probe", "打开微信设置里的朋友权限或隐私入口，读取朋友圈权限、黑名单、添加方式等设置项，不修改。", "settings_probe", requiresLogin = true, evidence = listOf("隐私", "朋友权限")),
                t("wechat_scan_guard", "打开微信扫一扫入口，只识别扫码界面、相册和返回按钮，不扫码不授权。", "safe_payment_probe", riskLevel = "HIGH", requiresLogin = true, requiresPayment = true, stopBeforeSubmit = true, evidence = listOf("扫一扫"))
            )
            "com.sina.weibo" -> listOf(
                t("weibo_search_news_topic", "在微博搜索人工智能新闻，停在综合结果页并记录实时、用户、视频、话题分区。", "search", evidence = listOf("人工智能新闻", "搜索结果")),
                t("weibo_super_topic_probe", "打开微博超话入口，搜索或进入一个公开超话，读取帖子列表和发帖入口，不发帖。", "feed_probe", requiresLogin = true, stopBeforeSubmit = true, evidence = listOf("超话", "发帖")),
                t("weibo_like_button_probe", "打开微博详情页，识别点赞、转发、评论、收藏按钮位置和状态，不点击互动。", "detail_probe", stopBeforeSubmit = true, evidence = listOf("点赞", "转发", "评论")),
                t("weibo_follow_button_probe", "打开一个微博用户主页，识别关注按钮、私信入口和主页分区，不点击关注。", "profile_probe", stopBeforeSubmit = true, evidence = listOf("用户主页", "关注")),
                t("weibo_image_post_draft", "进入微博发布入口，准备带图片入口的微博草稿，文本写我是AI测试，但不要发布或上传敏感图片。", "draft_create", riskLevel = "MEDIUM", requiresLogin = true, stopBeforeSubmit = true, evidence = listOf("发微博", "图片", "草稿")),
                t("weibo_topic_post_draft", "进入微博话题发布入口，写带话题的草稿 #AI测试# 我是AI测试，但不要发布。", "draft_create", riskLevel = "MEDIUM", requiresLogin = true, stopBeforeSubmit = true, evidence = listOf("话题", "草稿")),
                t("weibo_collects_probe", "打开微博我的收藏或赞过入口，读取列表和筛选项，不删除不转发。", "profile_probe", requiresLogin = true, evidence = listOf("收藏", "赞过")),
                t("weibo_notification_probe", "打开微博通知页，读取评论、赞、粉丝等分区，不点开敏感私信。", "message_probe", requiresLogin = true, evidence = listOf("通知", "评论", "赞")),
                t("weibo_live_probe", "打开微博直播或视频入口，识别直播间列表、搜索和互动按钮，不发送弹幕不关注。", "video_action_probe", stopBeforeSubmit = true, evidence = listOf("直播", "视频")),
                t("weibo_account_security_probe", "打开微博设置里的账号与安全入口，读取登录保护、隐私、黑名单等设置项，不修改。", "settings_probe", requiresLogin = true, evidence = listOf("账号与安全", "隐私"))
            )
            "com.sankuai.meituan" -> listOf(
                t("meituan_takeout_cart_pre_submit", "在美团外卖搜索盖饭，打开一家店铺并把流程推进到菜品选择或购物车查看，不提交订单不付款。", "cart_probe", riskLevel = "MEDIUM", stopBeforeSubmit = true, evidence = listOf("外卖", "购物车", "提交订单")),
                t("meituan_coupon_center", "打开美团红包卡券或领券中心，读取可领券列表和使用门槛，不领取需要支付的权益。", "promotion_probe", evidence = listOf("红包", "优惠券", "门槛")),
                t("meituan_hotel_search", "在美团搜索上海酒店，停在酒店列表页并记录日期、位置、筛选、价格入口，不预订。", "search", stopBeforeSubmit = true, evidence = listOf("上海酒店", "筛选", "价格")),
                t("meituan_movie_ticket_probe", "打开美团电影入口，选择一部电影进入影院或场次页，读取选座入口但不选座不付款。", "child_page_probe", riskLevel = "MEDIUM", stopBeforeSubmit = true, evidence = listOf("电影", "影院", "选座")),
                t("meituan_groupbuy_detail", "搜索火锅团购并打开一个团购详情，读取套餐、评价、购买按钮和商家地址，不购买。", "detail_probe", stopBeforeSubmit = true, evidence = listOf("团购", "套餐", "购买")),
                t("meituan_grocery_probe", "打开美团买菜或闪购入口，进入一个分类页并记录商品卡片、购物车和配送地址，不下单。", "shopping_probe", stopBeforeSubmit = true, evidence = listOf("买菜", "分类", "购物车")),
                t("meituan_delivery_address_guard", "打开美团收货地址或定位入口，读取地址列表和新增按钮，不新增不修改地址。", "location_probe", requiresLogin = true, stopBeforeSubmit = true, evidence = listOf("收货地址", "新增")),
                t("meituan_customer_service_draft", "打开美团客服或帮助中心，识别输入框和问题分类，不发送咨询。", "message_probe", requiresLogin = true, stopBeforeSubmit = true, evidence = listOf("客服", "输入框")),
                t("meituan_bike_or_ride_guard", "打开美团打车或骑行入口，只读取起终点、车型或扫码入口，不叫车不扫码不支付。", "safe_payment_probe", riskLevel = "HIGH", requiresPayment = true, stopBeforeSubmit = true, evidence = listOf("打车", "骑行")),
                t("meituan_membership_probe", "打开美团会员或省钱卡入口，读取权益、开通按钮和价格信息，不开通不支付。", "promotion_probe", riskLevel = "MEDIUM", stopBeforeSubmit = true, evidence = listOf("会员", "省钱卡"))
            )
            "com.xunmeng.pinduoduo" -> listOf(
                t("pdd_orchard_water_probe", "打开拼多多多多果园或浇水入口，读取水滴、浇水按钮和任务列表，不实际消耗水滴。", "child_page_probe", riskLevel = "MEDIUM", requiresLogin = true, stopBeforeSubmit = true, evidence = listOf("多多果园", "浇水", "水滴")),
                t("pdd_duoduo_farm_probe", "打开拼多多多多农场或牧场入口，读取任务、领取奖励和按钮状态，不领取需要支付的权益。", "child_page_probe", requiresLogin = true, stopBeforeSubmit = true, evidence = listOf("农场", "任务")),
                t("pdd_coupon_center_probe", "打开拼多多红包、优惠券或百亿补贴入口，读取券列表和使用条件，不下单。", "promotion_probe", evidence = listOf("红包", "优惠券", "百亿补贴")),
                t("pdd_group_buy_pre_submit", "搜索纸巾并打开商品详情，识别单独购买、拼单、规格选择和确认页入口，不提交订单。", "order_probe", riskLevel = "MEDIUM", requiresLogin = true, stopBeforeSubmit = true, evidence = listOf("拼单", "规格", "提交订单")),
                t("pdd_order_status_probe", "打开拼多多个人中心的订单区域，读取待付款、待发货、待收货、售后入口，不查看敏感订单详情。", "order_probe", requiresLogin = true, evidence = listOf("待付款", "待发货", "售后")),
                t("pdd_chat_customer_service", "打开拼多多客服或店铺客服入口，识别输入框和快捷问题，不发送消息。", "message_probe", requiresLogin = true, stopBeforeSubmit = true, evidence = listOf("客服", "输入框")),
                t("pdd_bargain_activity_probe", "打开拼多多砍价、现金大转盘或活动入口，读取按钮和任务列表，不分享不邀请。", "promotion_probe", riskLevel = "MEDIUM", requiresLogin = true, stopBeforeSubmit = true, evidence = listOf("砍价", "活动", "邀请")),
                t("pdd_buy_vegetables_probe", "打开多多买菜入口，进入一个分类并记录自提点、商品列表和购物车，不下单。", "shopping_probe", stopBeforeSubmit = true, evidence = listOf("多多买菜", "自提点", "购物车")),
                t("pdd_favorite_store_probe", "打开商品详情或店铺页，识别收藏店铺、关注、客服入口，不点击关注收藏。", "detail_probe", stopBeforeSubmit = true, evidence = listOf("店铺", "收藏", "关注")),
                t("pdd_settings_privacy_probe", "打开拼多多设置页，读取账号安全、隐私、免密支付等入口，不修改设置。", "settings_probe", requiresLogin = true, evidence = listOf("设置", "隐私", "免密支付"))
            )
            "com.jingdong.app.mall" -> listOf(
                t("jd_coupon_center_deep", "打开京东领券中心或优惠券入口，读取可领券、品类券、店铺券列表和使用门槛，不下单。", "promotion_probe", evidence = listOf("领券", "优惠券", "门槛")),
                t("jd_seckill_probe", "打开京东秒杀入口，进入一个商品列表并记录倒计时、筛选和商品卡片，不抢购不下单。", "shopping_probe", stopBeforeSubmit = true, evidence = listOf("秒杀", "倒计时")),
                t("jd_add_to_cart_pre_submit", "搜索抽纸并打开商品详情，识别规格、加入购物车、去结算按钮，不提交订单。", "cart_probe", riskLevel = "MEDIUM", requiresLogin = true, stopBeforeSubmit = true, evidence = listOf("加入购物车", "去结算")),
                t("jd_compare_price_probe", "在京东商品详情页读取比价、评价、问答和店铺入口，不关注不购买。", "detail_probe", stopBeforeSubmit = true, evidence = listOf("评价", "问答", "店铺")),
                t("jd_logistics_probe", "打开京东我的订单或物流入口，读取物流列表和筛选项，不查看敏感订单详情。", "order_probe", requiresLogin = true, evidence = listOf("物流", "订单")),
                t("jd_after_sales_probe", "打开京东售后或退换入口，读取申请售后按钮和规则，不提交申请。", "order_probe", requiresLogin = true, stopBeforeSubmit = true, evidence = listOf("售后", "退换")),
                t("jd_jingxi_or_grocery_probe", "打开京东超市、买菜或京喜入口，进入一个分类页并记录商品、购物车、配送入口，不下单。", "shopping_probe", stopBeforeSubmit = true, evidence = listOf("超市", "买菜", "购物车")),
                t("jd_customer_service_draft", "打开京东客服或店铺客服入口，识别输入框和快捷问题，不发送消息。", "message_probe", requiresLogin = true, stopBeforeSubmit = true, evidence = listOf("客服", "输入框")),
                t("jd_membership_guard", "打开京东 PLUS 会员入口，读取权益、开通按钮和价格，不开通不支付。", "promotion_probe", riskLevel = "MEDIUM", stopBeforeSubmit = true, evidence = listOf("PLUS", "权益")),
                t("jd_address_settings_guard", "打开京东收货地址或账号设置入口，读取地址列表和新增按钮，不新增不修改。", "settings_probe", requiresLogin = true, stopBeforeSubmit = true, evidence = listOf("收货地址", "新增"))
            )
            "com.taobao.taobao" -> listOf(
                t("taobao_coupon_center_deep", "打开淘宝红包卡券或领券中心，读取店铺券、品类券和使用门槛，不下单。", "promotion_probe", evidence = listOf("红包", "优惠券", "门槛")),
                t("taobao_live_probe", "打开淘宝直播入口，进入一个直播列表或直播间，识别商品橱窗和评论入口，不发言不购买。", "video_action_probe", stopBeforeSubmit = true, evidence = listOf("淘宝直播", "商品橱窗")),
                t("taobao_add_to_cart_pre_submit", "搜索抽纸并打开商品详情，识别规格选择、加入购物车和结算入口，不提交订单。", "cart_probe", riskLevel = "MEDIUM", requiresLogin = true, stopBeforeSubmit = true, evidence = listOf("加入购物车", "结算")),
                t("taobao_store_home_probe", "打开淘宝商品详情中的店铺页，读取首页、宝贝、客服、关注按钮，不关注不购买。", "detail_probe", stopBeforeSubmit = true, evidence = listOf("店铺", "客服", "关注")),
                t("taobao_logistics_probe", "打开淘宝我的订单或物流入口，读取待付款、待发货、待收货、退款售后，不查看敏感订单详情。", "order_probe", requiresLogin = true, evidence = listOf("订单", "物流", "售后")),
                t("taobao_after_sales_probe", "打开淘宝退款售后入口，识别申请售后按钮和原因列表，不提交申请。", "order_probe", requiresLogin = true, stopBeforeSubmit = true, evidence = listOf("退款售后", "申请")),
                t("taobao_grocery_or_tmall_supermarket", "打开天猫超市或淘菜菜入口，进入一个分类页并记录购物车和配送入口，不下单。", "shopping_probe", stopBeforeSubmit = true, evidence = listOf("天猫超市", "分类", "购物车")),
                t("taobao_customer_service_draft", "打开淘宝客服或店铺客服入口，识别输入框和快捷问题，不发送消息。", "message_probe", requiresLogin = true, stopBeforeSubmit = true, evidence = listOf("客服", "输入框")),
                t("taobao_member_center_guard", "打开淘宝会员、88VIP 或省钱卡入口，读取权益和开通按钮，不开通不支付。", "promotion_probe", riskLevel = "MEDIUM", stopBeforeSubmit = true, evidence = listOf("会员", "88VIP", "权益")),
                t("taobao_address_settings_guard", "打开淘宝收货地址或账号设置入口，读取地址列表和新增按钮，不新增不修改。", "settings_probe", requiresLogin = true, stopBeforeSubmit = true, evidence = listOf("收货地址", "新增"))
            )
            "com.xingin.xhs" -> listOf(
                t("xhs_recipe_search_open", "在小红书搜索低脂早餐并打开第一条笔记，读取配图、正文、收藏、评论入口，不互动。", "search_open", evidence = listOf("低脂早餐", "笔记详情")),
                t("xhs_travel_plan_search", "在小红书搜索杭州两日游攻略，停在结果页并记录筛选、笔记、用户和地点入口。", "search", evidence = listOf("杭州两日游", "筛选")),
                t("xhs_note_favorite_probe", "打开小红书笔记详情，识别收藏、点赞、评论、分享按钮和状态，不点击互动。", "detail_probe", stopBeforeSubmit = true, evidence = listOf("收藏", "点赞", "评论")),
                t("xhs_follow_creator_probe", "打开小红书笔记作者主页，识别关注按钮、私信、笔记列表，不点击关注。", "profile_probe", stopBeforeSubmit = true, evidence = listOf("作者主页", "关注")),
                t("xhs_comment_draft_ai", "打开小红书笔记评论区，在评论框输入我是AI测试但不要发送。", "comment_draft", riskLevel = "MEDIUM", requiresLogin = true, stopBeforeSubmit = true, evidence = listOf("评论框", "我是AI测试")),
                t("xhs_shop_item_detail", "打开小红书购物或商品卡片，进入商品详情并读取规格、券、店铺、购物车入口，不下单。", "detail_probe", stopBeforeSubmit = true, evidence = listOf("商品详情", "规格", "购物车")),
                t("xhs_coupon_probe", "打开小红书购物优惠券或活动入口，读取券列表和使用门槛，不领需要支付权益。", "promotion_probe", evidence = listOf("优惠券", "门槛")),
                t("xhs_publish_photo_draft", "进入小红书发布入口，准备图文笔记草稿，文本写我是AI测试，但不要发布。", "draft_create", riskLevel = "MEDIUM", requiresLogin = true, stopBeforeSubmit = true, evidence = listOf("发布", "图文", "草稿")),
                t("xhs_nearby_local_probe", "打开小红书附近或本地生活入口，读取地点、榜单和筛选 UI，不预约不购买。", "location_probe", evidence = listOf("附近", "地点", "筛选")),
                t("xhs_account_privacy_probe", "打开小红书设置里的隐私或账号安全入口，读取可见范围、黑名单、通知设置，不修改。", "settings_probe", requiresLogin = true, evidence = listOf("隐私", "账号安全"))
            )
            "com.ss.android.ugc.aweme" -> listOf(
                t("douyin_search_food_shop", "在抖音搜索附近美食，停在结果页并记录视频、团购、用户、地点分区。", "search", evidence = listOf("附近美食", "团购")),
                t("douyin_user_profile_probe", "打开抖音搜索结果里的一个用户主页，识别关注、私信、作品列表，不点击关注。", "profile_probe", stopBeforeSubmit = true, evidence = listOf("用户主页", "关注")),
                t("douyin_like_collect_probe", "在抖音视频播放页识别点赞、收藏、评论、分享按钮和状态，不执行互动。", "video_action_probe", stopBeforeSubmit = true, evidence = listOf("点赞", "收藏", "评论")),
                t("douyin_share_panel_probe", "打开抖音视频分享入口后读取分享面板和复制链接入口，随后返回，不真正分享。", "video_action_probe", riskLevel = "MEDIUM", stopBeforeSubmit = true, evidence = listOf("分享", "面板")),
                t("douyin_local_life_coupon", "打开抖音团购或本地生活商品详情，读取套餐、券、购买按钮，不购买不支付。", "shopping_probe", stopBeforeSubmit = true, evidence = listOf("团购", "套餐", "购买")),
                t("douyin_shop_item_detail", "打开抖音商城搜索耳机并进入商品详情，读取规格、评价、店铺、购物车入口，不下单。", "detail_probe", stopBeforeSubmit = true, evidence = listOf("商品详情", "规格", "购物车")),
                t("douyin_publish_photo_draft", "进入抖音发布入口，准备图文或视频草稿，文案写我是AI测试，但不要发布或上传。", "draft_create", riskLevel = "MEDIUM", requiresLogin = true, stopBeforeSubmit = true, evidence = listOf("发布", "草稿", "我是AI测试")),
                t("douyin_history_favorites_probe", "打开抖音我的页，读取收藏、观看历史、喜欢列表入口，不删除不转发。", "profile_probe", requiresLogin = true, evidence = listOf("收藏", "观看历史")),
                t("douyin_privacy_settings_probe", "打开抖音设置里的隐私和账号安全入口，读取权限、黑名单、登录设备，不修改。", "settings_probe", requiresLogin = true, evidence = listOf("隐私", "账号安全")),
                t("douyin_live_room_probe", "打开抖音直播入口或直播间，读取关注、评论框、礼物按钮，不发言不送礼。", "video_action_probe", riskLevel = "MEDIUM", stopBeforeSubmit = true, evidence = listOf("直播", "评论框", "礼物"))
            )
            "com.hexin.plat.android" -> listOf(
                t("hexin_search_etf", "在同花顺搜索沪深300ETF，停在 ETF 详情页并记录行情、K线、资讯入口，不交易。", "search", riskLevel = "MEDIUM", evidence = listOf("沪深300ETF", "详情")),
                t("hexin_sector_board_probe", "打开同花顺板块或行业入口，切换热门行业、概念、涨跌幅列表并记录 UI，不交易。", "child_page_probe", riskLevel = "MEDIUM", evidence = listOf("板块", "行业", "涨跌幅")),
                t("hexin_stock_screener_probe", "打开同花顺选股或条件选股入口，读取条件列表和结果页，不保存策略不交易。", "finance_probe", riskLevel = "MEDIUM", stopBeforeSubmit = true, evidence = listOf("选股", "条件")),
                t("hexin_watchlist_add_probe", "打开股票详情里的加自选入口，识别加自选按钮和登录提示，不真正添加。", "profile_probe", riskLevel = "MEDIUM", requiresLogin = true, stopBeforeSubmit = true, evidence = listOf("加自选", "登录")),
                t("hexin_kline_indicator_probe", "打开股票 K 线页面，切换分时、日K、周K、指标入口并记录 UI，不交易。", "child_page_probe", riskLevel = "MEDIUM", evidence = listOf("分时", "日K", "指标")),
                t("hexin_news_detail_probe", "打开同花顺资讯详情，读取正文、相关股票、评论入口，不发表评论。", "detail_probe", riskLevel = "MEDIUM", stopBeforeSubmit = true, evidence = listOf("资讯详情", "相关股票")),
                t("hexin_alert_draft_probe", "打开股价提醒或预警入口，识别条件输入和保存按钮，不创建提醒。", "draft_create", riskLevel = "MEDIUM", requiresLogin = true, stopBeforeSubmit = true, evidence = listOf("股价提醒", "保存")),
                t("hexin_simulation_trade_guard", "打开模拟炒股或交易入口，只识别买入卖出页面结构，不输入数量不提交。", "safe_payment_probe", riskLevel = "HIGH", requiresLogin = true, requiresPayment = true, stopBeforeSubmit = true, evidence = listOf("买入", "卖出")),
                t("hexin_account_assets_guard", "打开资产或持仓入口时只识别是否需要登录、券商和密码页面，立即停止不输入密码。", "blocker_report", riskLevel = "HIGH", requiresLogin = true, requiresPayment = true, stopBeforeSubmit = true, evidence = listOf("资产", "密码 blocker")),
                t("hexin_calendar_events_probe", "打开同花顺财经日历或新股日历入口，读取事件列表、筛选和提醒按钮，不创建提醒。", "feed_probe", riskLevel = "MEDIUM", evidence = listOf("财经日历", "事件"))
            )
            else -> emptyList()
        }
        val merged = (tasks + extras).distinctBy { it.taskId }
        return copy(tasks = merged.fillToTwenty())
    }

    private fun List<PopularAppTask>.fillToTwenty(): List<PopularAppTask> {
        if (size >= 20) return this
        val existingIds = map { it.taskId }.toMutableSet()
        val seeds = listOf(
            t("auto_search_result_probe", "打开当前 App，搜索一个常见关键词，停在结果页并记录搜索框、结果列表、筛选、分区或排序入口。", "search", evidence = listOf("搜索", "结果")),
            t("auto_bottom_tabs_deep_probe", "依次打开当前 App 底部每个分屏页面，保存每个页面的可点击入口、输入框、图形化按钮和主要列表。", "bottom_tab_probe", evidence = listOf("底部", "分屏")),
            t("auto_top_icons_deep_probe", "识别当前 App 顶部加号、搜索、消息、扫一扫、设置等小图标，安全打开可读入口后返回并记录子页面 UI。", "top_icon_probe", evidence = listOf("顶部", "图标")),
            t("auto_first_detail_probe", "从首页或搜索结果打开第一个普通内容或商品详情页，读取详情页按钮、评论、分享、收藏或店铺入口，不执行提交动作。", "detail_probe", stopBeforeSubmit = true, evidence = listOf("详情", "评论")),
            t("auto_comment_box_probe", "打开一个公开内容的评论区，识别评论输入框、发送按钮和评论列表；如需输入，只写草稿不发送。", "comment_draft", riskLevel = "MEDIUM", requiresLogin = true, stopBeforeSubmit = true, evidence = listOf("评论", "输入框")),
            t("auto_publish_entry_draft_probe", "查找发布、发帖、创作或加号入口，进入编辑器后识别标题、正文、图片、发布按钮；只写测试草稿不发布。", "draft_create", riskLevel = "MEDIUM", requiresLogin = true, stopBeforeSubmit = true, evidence = listOf("发布", "草稿")),
            t("auto_profile_page_probe", "打开我的、个人中心或账户页，记录订单、收藏、历史、关注、设置等入口和登录状态。", "profile_probe", requiresLogin = true, evidence = listOf("我的", "设置")),
            t("auto_settings_privacy_probe", "进入设置或账户安全页，读取隐私、通知、账号安全、黑名单等入口，不修改任何设置。", "settings_probe", requiresLogin = true, evidence = listOf("设置", "隐私")),
            t("auto_message_page_probe", "打开消息、通知或私信页，识别登录状态、通知列表、搜索或会话入口，不发送消息。", "message_probe", requiresLogin = true, stopBeforeSubmit = true, evidence = listOf("消息", "通知")),
            t("auto_payment_guard_probe", "尝试定位购物车、订单、会员、支付、交易或密码相关入口，只记录页面结构和阻断原因，不提交订单、不支付、不输入密码。", "safe_payment_probe", riskLevel = "HIGH", requiresLogin = true, requiresPayment = true, stopBeforeSubmit = true, evidence = listOf("支付", "订单", "密码"))
        )
        val out = toMutableList()
        var round = 1
        while (out.size < 20) {
            for (seed in seeds) {
                if (out.size >= 20) break
                val id = "${seed.taskId}_$round"
                if (existingIds.add(id)) {
                    out += seed.copy(taskId = id)
                }
            }
            round++
        }
        return out
    }

    fun resolveProfiles(
        context: Context,
        packageFilter: List<String>,
        limitApps: Int,
        tasksPerApp: Int
    ): List<ResolvedPopularAppProfile> {
        val installed = installedPackages(context)
        val wanted = packageFilter.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val profiles = defaultProfiles()
            .filter { profile ->
                wanted.isEmpty() || profile.packageCandidates().any { it in wanted }
            }
            .take(limitApps.coerceIn(1, 50))
        return profiles.map { profile ->
            val resolved = profile.packageCandidates().firstOrNull { candidate -> installed.containsKey(candidate) }
            val appInfo = resolved?.let { installed[it] }
            ResolvedPopularAppProfile(
                profile = profile,
                resolvedPackage = resolved,
                installed = resolved != null,
                launchable = appInfo?.launchable == true,
                label = appInfo?.label ?: profile.appName,
                tasks = profile.tasks.take(tasksPerApp.coerceIn(1, 20))
            )
        }
    }

    fun catalogJson(
        context: Context,
        packageFilter: List<String> = emptyList(),
        limitApps: Int = 20,
        tasksPerApp: Int = 10
    ): JSONObject {
        val resolved = resolveProfiles(context, packageFilter, limitApps, tasksPerApp)
        return JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "popular_app_task_catalog")
            .put("appCount", resolved.size)
            .put("tasksPerApp", tasksPerApp.coerceIn(1, 20))
            .put("totalTaskCount", resolved.sumOf { it.tasks.size })
            .put("apps", JSONArray(resolved.map { it.toJson(tasksPerApp) }))
            .put("safetyPolicy", JSONObject()
                .put("adbManualTargetUiControl", false)
                .put("paymentPasswordInputAllowed", false)
                .put("submitOrderAllowed", false)
                .put("publishOrCommentSubmitAllowedByDefault", false)
                .put("draftOnlyUnlessExplicitInstruction", true))
            .put("createdAt", System.currentTimeMillis())
    }

    private fun installedPackages(context: Context): Map<String, InstalledAppInfo> {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launcherByPackage = pm.queryIntentActivities(launcherIntent, 0)
            .groupBy { it.activityInfo.packageName }
            .mapValues { (_, activities) -> activities.firstOrNull() }
        val installedByPackage = pm.getInstalledPackages(0).associateBy { it.packageName }
        return (installedByPackage.keys + launcherByPackage.keys)
            .distinct()
            .mapNotNull { packageName ->
                val info = installedByPackage[packageName]
                    ?: runCatching { pm.getPackageInfo(packageName, 0) }.getOrNull()
                val appInfo = info?.applicationInfo
                    ?: launcherByPackage[packageName]?.activityInfo?.applicationInfo
                    ?: return@mapNotNull null
                if ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0) return@mapNotNull null
                val label = runCatching { appInfo.loadLabel(pm).toString() }.getOrDefault(packageName)
                val launchable = launcherByPackage.containsKey(packageName) || pm.getLaunchIntentForPackage(packageName) != null
                packageName to InstalledAppInfo(label = label, launchable = launchable)
            }
            .toMap()
    }
}

data class ResolvedPopularAppProfile(
    val profile: PopularAppProfile,
    val resolvedPackage: String?,
    val installed: Boolean,
    val launchable: Boolean,
    val label: String,
    val tasks: List<PopularAppTask>
) {
    fun toJson(tasksPerApp: Int = tasks.size): JSONObject {
        return profile.toJson(
            resolvedPackage = resolvedPackage,
            installed = installed,
            launchable = launchable,
            tasksPerApp = tasksPerApp
        )
            .put("label", label)
            .put("selectedTasks", JSONArray(tasks.map { it.toJson() }))
    }
}

private data class InstalledAppInfo(
    val label: String,
    val launchable: Boolean
)
