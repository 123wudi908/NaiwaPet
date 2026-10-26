package com.naiwa.androidpet.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import com.naiwa.androidpet.R
import com.naiwa.androidpet.animation.*
import com.naiwa.androidpet.companion.*
import com.naiwa.androidpet.data.*
import com.naiwa.androidpet.game.*
import com.naiwa.androidpet.runtime.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class AppPage(val title: String, val subtitle: String) {
    HOME("奶蛙之家","今天也一起，慢慢过。"),
    ACTIONS("动作图鉴","见过的表情，都收在这里。"),
    PLAY("游乐场","一局小游戏，一点好心情。"),
    CARE("一起长大","每一次互动，奶蛙都记得。"),
    SETTINGS("设置","按你的习惯来。")
}

/** Native screens share the actual service settings, approved materials and local memory. */
class CompanionUi(private val activity: Activity, private val preferences: PetPreferences,
    private val startPet: () -> Unit, private val stopPet: () -> Unit, private val controls: () -> Unit,
    private val overlaySettings: () -> Unit, private val notificationSettings: () -> Unit,
    private val export: (String,String) -> Unit, initialPage: String? = null) {
    private val memory = PetStateRepository.get(activity)
    private val sound = FeedbackPlayer(activity)
    private val root = LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(NativeStyle.background) }
    private val header = LinearLayout(activity)
    private val scroll = ScrollView(activity).apply { isFillViewport=true;clipToPadding=false }
    private val body = LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(24),0,dp(24),dp(28)) }
    private val navigation = LinearLayout(activity)
    var page: AppPage = AppPage.entries.firstOrNull { it.name == initialPage } ?: AppPage.HOME
        private set
    private var preview: PetPreview? = null
    private var active = false
    private val values = mutableMapOf<String,TextView>()
    private val discoveries = mutableMapOf<PetAction,TextView>()
    private var startButton: Button? = null
    private var controlsButton: Button? = null
    private var bubble: TextView? = null
    private var currentAction: TextView? = null
    private var sizeLabel: TextView? = null
    private var selectedAction: PetAction = PetAction.IDLE
    private val toggles=mutableMapOf<String,Switch>()
    private var refreshingControls=false
    private var sizeSlider:SeekBar?=null
    private var resizing=false

    init {
        header.orientation=LinearLayout.HORIZONTAL;header.gravity=Gravity.CENTER_VERTICAL
        header.setPadding(dp(24),dp(16),dp(20),dp(16))
        scroll.addView(body)
        navigation.orientation=LinearLayout.HORIZONTAL;navigation.gravity=Gravity.CENTER_VERTICAL
        navigation.setPadding(dp(8),dp(4),dp(8),dp(8));navigation.setBackgroundColor(Color.WHITE)
        root.addView(header);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));root.addView(navigation)
        activity.setContentView(root);NativeStyle.installInsets(activity,root)
        render()
    }

    fun show(page: AppPage) { this.page=page;render() }
    private fun render() {
        preview?.close();preview=null;values.clear();discoveries.clear();toggles.clear();sizeSlider=null
        startButton=null;controlsButton=null;bubble=null;currentAction=null;sizeLabel=null
        body.removeAllViews();header.removeAllViews();navigation.removeAllViews()
        val compactHeader=activity.resources.configuration.fontScale>=1.5f ||
            activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE
        header.setPadding(dp(24),dp(if(compactHeader)8 else 16),dp(20),dp(if(compactHeader)8 else 16))
        val titleBlock = LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
        titleBlock.addView(NativeStyle.text(activity,page.title,if(compactHeader)22f else 26f,true).apply{setPadding(0,0,0,0)})
        if(!compactHeader)titleBlock.addView(NativeStyle.text(activity,page.subtitle,13f).apply{setPadding(0,dp(4),0,0)})
        header.addView(titleBlock,LinearLayout.LayoutParams(0,-2,1f))
        header.addView(iconButton(if (page == AppPage.SETTINGS) IconKind.BACK else IconKind.SETTINGS,
            if (page == AppPage.SETTINGS) "返回奶蛙之家" else "设置") {
            show(if (page == AppPage.SETTINGS) AppPage.HOME else AppPage.SETTINGS)
        },LinearLayout.LayoutParams(dp(48),dp(48)))
        when (page) {
            AppPage.HOME -> home()
            AppPage.ACTIONS -> actions()
            AppPage.PLAY -> games()
            AppPage.CARE -> care()
            AppPage.SETTINGS -> settings()
        }
        for ((destination,label,kind) in listOf(Triple(AppPage.HOME,"家",IconKind.HOME),
            Triple(AppPage.ACTIONS,"图鉴",IconKind.BOOK),Triple(AppPage.PLAY,"游戏",IconKind.PLAY),
            Triple(AppPage.CARE,"养成",IconKind.HEART))) {
            val selected = page == destination
            val item = LinearLayout(activity).apply {
                orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;minimumHeight=dp(60)
                setPadding(dp(4),dp(6),dp(4),dp(4))
                background=RippleDrawable(ColorStateList.valueOf(0x22516E46),
                    android.graphics.drawable.GradientDrawable().apply{
                        setColor(if(selected)NativeStyle.softGreen else Color.WHITE);cornerRadius=dp(16).toFloat()
                    },null)
                isClickable=true;isFocusable=true;isSelected=selected;contentDescription=if(destination==AppPage.PLAY)"游乐场" else label
                addView(UiIcon(activity,kind,if(selected)NativeStyle.accent else NativeStyle.secondary),LinearLayout.LayoutParams(dp(22),dp(22)))
                addView(NativeStyle.text(activity,label,12f,selected).apply { gravity=Gravity.CENTER;setPadding(0,dp(4),0,0) })
                setOnClickListener { show(destination) }
            }
            navigation.addView(item,LinearLayout.LayoutParams(0,-2,1f).apply { marginStart=dp(3);marginEnd=dp(3) })
        }
        refresh();scroll.post { scroll.scrollTo(0,0) }
    }

    private fun home() {
        bind("homeDiagnostic",text("",13f).apply{
            setPadding(dp(12),dp(10),dp(12),dp(10))
            background=NativeStyle.surface(activity,NativeStyle.softGreen)
            accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
        })
        val stage = LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(8),dp(16),dp(12))
            background=NativeStyle.surface(activity,NativeStyle.cream,NativeStyle.Radius.stage)
        }
        val pet = newPreview { message -> bubble?.text=message }
        pet.onLongPress = { show(AppPage.ACTIONS) }
        stage.addView(pet,LinearLayout.LayoutParams(-1,dp(184)))
        bubble = NativeStyle.text(activity,"轻点打招呼，连点有惊喜。",13f).apply { gravity=Gravity.CENTER;minHeight=dp(32) }
            .also { stage.addView(it) }
        body.addView(stage)
        statStrip()
        currentAction=NativeStyle.text(activity,"现在：待机",12f).apply{setPadding(0,dp(4),0,dp(4))}.also { body.addView(it) }
        pet.stateListener = { currentAction?.text=activity.getString(R.string.home_action_now,activity.getString(it.action?.labelResource() ?: R.string.action_idle)) }
        startButton=button("开启桌宠",true) { if(PetRuntime.running)stopPet() else startPet() }
        controlsButton=button("打开操作面板") { controls() }
        text("桌面上：轻点互动 · 长按开面板 · 拖动换位置",12f)
        section("今天想做什么")
        menuRow("打个招呼","奶蛙会用动作和一句话回应你。") { pet.greet() }
        menuRow("去玩一局","打鼓、连击，还有软软的奶泡。") { show(AppPage.PLAY) }
        menuRow("看看养成记忆","心情、亲密度和刚解锁的成就。") { show(AppPage.CARE) }
    }

    private fun actions() {
        val materials=PetMaterials.load(activity)
        val available=PetAction.entries.filter(materials.pool::canManualFormal)
        text("已收录 ${available.size} 个动作。轻点条目看预览，第一次见到会记入图鉴。",14f)
        val pet=newPreview { message -> bubble?.text=message }
        body.addView(pet,LinearLayout.LayoutParams(-1,dp(230)))
        bubble=NativeStyle.text(activity,"选一个动作，看看它会做什么。",13f).also { body.addView(it) }
        currentAction=text("当前预览：待机",17f,true)
        pet.stateListener={ currentAction?.text=activity.getString(R.string.gallery_action_now,activity.getString(it.action?.labelResource() ?: R.string.action_idle)) }
        for ((group,title) in listOf(ActionGroup.BASIC to "基础动作",ActionGroup.FUNNY to "调皮时刻",ActionGroup.EXTENSION to "更多动作")) {
            val actions=available.filter { it.group == group };if(actions.isEmpty())continue
            section(title)
            actions.forEach { action ->
                val name=activity.getString(action.labelResource())
                val label=menuRow(name,actionDescription(action),"预览$name") {
                    selectedAction=action;scroll.smoothScrollTo(0,0);pet.play(action);refresh()
                }
                discoveries[action]=label
            }
        }
        val missing=PetAction.entries.filterNot { it in available }
        if(missing.isNotEmpty()) {
            section("还没收录的动作")
            text("${missing.size} 项缺少合格独立素材，暂不开放："+missing.joinToString("、") { activity.getString(it.labelResource()) }+"。",13f)
        }
    }

    private fun games() {
        for ((index,game) in GameKind.entries.withIndex()) {
            val card=LinearLayout(activity).apply {
                orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(16),dp(20),dp(20))
                background=NativeStyle.surface(activity,if(index==0)NativeStyle.cream else Color.WHITE,NativeStyle.Radius.card)
            }
            card.addView(NativeStyle.text(activity,"${index+1} / ${(game.durationMillis/1000)} 秒",12f,true))
            card.addView(NativeStyle.text(activity,game.title,22f,true))
            card.addView(NativeStyle.text(activity,game.description,14f))
            bind("best.${game.id}",NativeStyle.text(activity,"",13f).also { card.addView(it) })
            card.addView(NativeStyle.button(activity,"去玩${game.title}",index==0) {
                activity.startActivity(GameActivity.intent(activity,game))
            })
            body.addView(card,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(12);bottomMargin=dp(8) })
        }
        text("游戏在独立页面中进行，离开应用会暂停。成绩只在本机保存。",12f)
    }

    private fun care() {
        statStrip()
        bind("count",text("",32f,true))
        bind("discovered",text("",14f))
        bind("lastInteraction",text("",13f))
        section("我们的小成就")
        for ((id,definition) in AchievementCatalog.entries) {
            val row=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL;setPadding(0,dp(12),0,dp(12)) }
            bind("achievement.$id",NativeStyle.text(activity,definition.first,17f,true).also { row.addView(it) })
            row.addView(NativeStyle.text(activity,definition.second,13f));body.addView(row);divider()
        }
        section("最近一起做的事")
        bind("recent",text("",14f))
    }

    private fun settings() {
        section("陪伴方式")
        toggle("自动行为","空闲时眨眼、挥手，偶尔调皮一下。",preferences.automaticBehaviorEnabled(true)) { preferences.setAutomaticBehavior(it) }
        toggle("自动移动","在安全区域左右走；触摸和打开面板时暂停。",preferences.automaticMovementEnabled(false)) { preferences.setAutomaticMovement(it) }
        toggle("音效","简短的本地合成提示音，默认关闭。",preferences.soundEnabled) { preferences.soundEnabled=it;if(it)sound.play() }
        toggle("动画效果","关闭后保留静态角色和互动文字，减少动效。",preferences.animationsEnabled) { preferences.animationsEnabled=it;preview?.refreshSettings() }
        toggle("互动文字","轻点奶蛙时显示简短本地对话。",preferences.feedbackEnabled) { preferences.feedbackEnabled=it }
        section("桌宠尺寸")
        sizeLabel=text("${preferences.sizeDp} dp",17f,true)
        sizeSlider=SeekBar(activity).apply {
            max=140;progress=preferences.sizeDp-80;minimumHeight=dp(48);contentDescription="桌宠尺寸"
            progressTintList=ColorStateList.valueOf(NativeStyle.accent);thumbTintList=ColorStateList.valueOf(NativeStyle.accent)
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar:SeekBar?,value:Int,user:Boolean) { sizeLabel?.text=activity.getString(R.string.size_value,value+80);bar?.contentDescription=activity.getString(R.string.size_accessibility,value+80) }
                override fun onStartTrackingTouch(bar:SeekBar?) { resizing=true }
                override fun onStopTrackingTouch(bar:SeekBar) { resizing=false;preferences.sizeDp=bar.progress+80 }
            })
        }.also { body.addView(it,LinearLayout.LayoutParams(-1,dp(48))) }
        text("80–220 dp，保持角色比例。",12f)
        section("数据管理")
        menuRow("导出养成记忆","保存心情、亲密度、互动、图鉴和最佳成绩。") { export("naiwa-memory.json",memory.exportJson()) }
        menuRow("导出运行日志","排查桌宠异常时使用，只包含本应用的运行事件。") { export("naiwa-lifecycle.txt",PetDiagnostics.read()) }
        menuRow("重置养成数据","清除养成和成绩，保留桌宠位置与设置。") {
            AlertDialog.Builder(activity).setTitle("重置养成记忆？")
                .setMessage("心情、精力、亲密度、互动、图鉴和游戏成绩会回到初始状态。桌宠位置与设置保留。")
                .setNegativeButton("保留",null).setPositiveButton("重置") { _,_ -> memory.reset();refresh() }.show()
        }
        section("权限与运行")
        bind("permission",text("",14f,true))
        button("悬浮窗权限设置") { overlaySettings() }
        bind("notifications",text("",13f))
        button("通知设置") { notificationSettings() }
        button("关闭桌宠") { stopPet() }
        bind("diagnostic",text("",13f))
        section("关于奶蛙")
        @Suppress("DEPRECATION")
        val version=activity.packageManager.getPackageInfo(activity.packageName,0).versionName
        text("版本 $version · 本地保存 · 无需账号",13f)
        text("以你的参考形象制作的个人使用动作。新增姿势是参考衍生；未获准动作不会混入正式图鉴。",12f)
        text("系统可能限制悬浮层或后台服务。若手机限制后台运行，可在系统的应用与电池设置中查看。",12f)
    }

    private fun statStrip() {
        val strip=LinearLayout(activity).apply {
            orientation=if(activity.resources.configuration.fontScale>1.4f)LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            setPadding(0,dp(12),0,dp(4))
        }
        for ((id,label) in listOf("happiness" to "心情","energy" to "精力","affinity" to "亲密度")) {
            val group=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(4),0,dp(4),dp(4)) }
            group.addView(NativeStyle.text(activity,label,12f).apply{setPadding(0,0,0,0)})
            bind(id,NativeStyle.text(activity,"",18f,true).apply{setPadding(0,dp(2),0,0)}.also { group.addView(it) })
            strip.addView(group,if(strip.orientation==LinearLayout.HORIZONTAL)LinearLayout.LayoutParams(0,-2,1f) else LinearLayout.LayoutParams(-1,-2))
        }
        body.addView(strip)
    }

    fun refresh() {
        val s=memory.read()
        val available=PetMaterials.load(activity).pool.policies.keys.count { PetMaterials.load(activity).pool.canManualFormal(it) }
        val formatter=SimpleDateFormat("MM-dd HH:mm",Locale.SIMPLIFIED_CHINESE)
        val diagnostic=PetRuntime.diagnostic ?: preferences.lastDiagnostic
        values.forEach { (key,label) ->
            label.text=when {
                key=="happiness" -> "${s.happiness} / 100"
                key=="energy" -> "${s.energy} / 100"
                key=="affinity" -> "${s.affinity} / 100"
                key=="count" -> "${s.interactionCount} 次互动"
                key=="discovered" -> "已见过 ${s.unlockedActions.size.coerceAtMost(available)} / $available 个动作"
                key=="lastInteraction" -> if(s.lastInteractionTime==0L)"还没打过招呼，去摸摸奶蛙吧。" else "最近互动：${formatter.format(Date(s.lastInteractionTime))}"
                key=="recent" -> if(s.recentInteractions.isEmpty())"这里会留下你们的小日常。" else s.recentInteractions.takeLast(8).asReversed().joinToString("\n") {
                    val name=when(it.type){InteractionType.TAP->"打个招呼";InteractionType.COMBO->"连击逗笑";InteractionType.SWIPE->"晃一晃";InteractionType.DRAG->"挪个位置";InteractionType.GAME->"一起玩游戏"}
                    "${formatter.format(Date(it.at))}  $name"
                }
                key.startsWith("best.") -> "最佳记录：${s.bestScores[key.removePrefix("best.")] ?: 0} 分"
                key.startsWith("achievement.") -> {
                    val id=key.removePrefix("achievement.")
                    "${if(id in s.achievements)"已解锁" else "未解锁"} · ${AchievementCatalog.entries[id]?.first}"
                }
                key=="permission" -> if(Settings.canDrawOverlays(activity))"悬浮窗权限已允许" else "需要悬浮窗权限，才能在应用外陪伴你。"
                key=="notifications" -> if(activity.getSystemService(NotificationManager::class.java).areNotificationsEnabled())"运行通知已允许" else "通知未允许，可在系统活动应用中停止桌宠。"
                key=="diagnostic" -> diagnostic ?: "未检测到运行异常。"
                key=="homeDiagnostic" -> diagnostic ?: ""
                else -> label.text
            }
            if(key=="homeDiagnostic")label.visibility=if(diagnostic.isNullOrBlank())View.GONE else View.VISIBLE
            if(key.startsWith("achievement."))label.setTextColor(if(key.removePrefix("achievement.") in s.achievements)NativeStyle.accent else NativeStyle.secondary)
        }
        discoveries.forEach { (action,label) ->
            label.text=activity.getString(R.string.unlocked_action_description,if(action.directory in s.unlockedActions)"已见过" else "还没见过",actionDescription(action))
        }
        startButton?.text=if(PetRuntime.running)"关闭桌宠" else "开启桌宠"
        startButton?.isSelected=PetRuntime.running
        controlsButton?.isEnabled=PetRuntime.running
        if(!preferences.feedbackEnabled)bubble?.text=""
        refreshingControls=true
        try {
            toggles["自动行为"]?.isChecked=preferences.automaticBehaviorEnabled(true)
            toggles["自动移动"]?.isChecked=preferences.automaticMovementEnabled(false)
            toggles["音效"]?.isChecked=preferences.soundEnabled
            toggles["动画效果"]?.isChecked=preferences.animationsEnabled
            toggles["互动文字"]?.isChecked=preferences.feedbackEnabled
            if(!resizing)sizeSlider?.progress=preferences.sizeDp-80
        }finally{refreshingControls=false}
    }
    fun setActive(value:Boolean){active=value;preview?.setActive(value);if(value){preview?.refreshSettings();refresh()}}
    fun close(){preview?.close();preview=null;sound.close()}

    private fun newPreview(feedback:(String)->Unit):PetPreview=PetPreview(activity,feedback=feedback).also{preview=it;it.setActive(active)}
    private fun text(label:String,size:Float=14f,strong:Boolean=false)=NativeStyle.text(activity,label,size,strong).also{body.addView(it)}
    private fun section(label:String)=text(label,17f,true).apply{setPadding(0,dp(24),0,dp(8))}
    private fun bind(id:String,label:TextView){values[id]=label}
    private fun button(label:String,primary:Boolean=false,action:()->Unit)=NativeStyle.button(activity,label,primary,action).also{body.addView(it)}
    private fun divider(){body.addView(View(activity).apply{setBackgroundColor(NativeStyle.border)},LinearLayout.LayoutParams(-1,dp(1)))}
    private fun toggle(label:String,description:String,value:Boolean,onChange:(Boolean)->Unit){
        val control=Switch(activity).apply{
            text=label;textSize=16f;setTextColor(NativeStyle.ink);minimumHeight=dp(52);isChecked=value
            setPadding(0,dp(8),0,dp(4));setOnCheckedChangeListener{_,checked->if(!refreshingControls)onChange(checked)}
            thumbTintList=ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(NativeStyle.accent,NativeStyle.secondary))
        }
        toggles[label]=control;body.addView(control,LinearLayout.LayoutParams(-1,-2))
        text(description,12f);divider()
    }
    private fun menuRow(title:String,description:String,accessibility:String=title,action:()->Unit):TextView{
        val row=LinearLayout(activity).apply{
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(64)
            setPadding(dp(14),dp(8),dp(12),dp(8));isClickable=true;isFocusable=true;contentDescription=accessibility
            background=RippleDrawable(ColorStateList.valueOf(0x22516E46),NativeStyle.surface(activity,Color.WHITE,16),null)
            setOnClickListener{action()}
        }
        val words=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL}
        words.addView(NativeStyle.text(activity,title,16f,true))
        val detail=NativeStyle.text(activity,description,12f).also{words.addView(it)}
        row.addView(words,LinearLayout.LayoutParams(0,-2,1f))
        row.addView(UiIcon(activity,IconKind.BACK,NativeStyle.secondary).apply{rotation=180f},LinearLayout.LayoutParams(dp(16),dp(16)))
        body.addView(row,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})
        return detail
    }
    private fun iconButton(kind:IconKind,label:String,action:()->Unit)=FrameLayout(activity).apply{
        contentDescription=label;isClickable=true;isFocusable=true
        background=RippleDrawable(ColorStateList.valueOf(0x22516E46),NativeStyle.surface(activity,Color.WHITE,16),null)
        addView(UiIcon(activity,kind),FrameLayout.LayoutParams(dp(22),dp(22),Gravity.CENTER));setOnClickListener{action()}
    }
    private fun actionDescription(action:PetAction)=when(action){
        PetAction.IDLE->"安静地陪在身边";PetAction.BLINK->"眨眨眼，已经看见你了";PetAction.WAVE->"伸出小手打个招呼"
        PetAction.HAPPY->"开心得轻轻跳起来";PetAction.WALK->"慢慢走上几步";PetAction.THINK->"让我认真想一下"
        PetAction.LAUGH->"捧着肚皮笑一会儿";PetAction.DANCE->"跟着自己的节奏摆动";PetAction.FAKE_FALL->"一场小小的戏精表演"
        PetAction.BELLY_DRUM->"左右手轮流敲一敲，跟着肚皮打节拍"
        PetAction.LAUGH_FALL->"笑着笑着，躺倒了";PetAction.LOADING_BRAIN->"脑袋正在慢慢转动";PetAction.FREEZE->"突然愣住一小会儿"
        PetAction.WOBBLE->"晃一晃，再站稳";else->"等待合格独立素材"
    }
    private fun dp(value:Int)=NativeStyle.dp(activity,value)
}
