package dev.amenhancer.module.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import dev.amenhancer.module.CurrentSongDetails
import dev.amenhancer.module.hook.CurrentLyricsSourceStatus
import dev.amenhancer.module.hook.EmbeddedMlKit
import dev.amenhancer.module.hook.TcrrryLyricsHistory
import com.tcrrry.desktoplyrics.SecretStorage
import com.tcrrry.desktoplyrics.SupplementTranslation
import com.tcrrry.desktoplyrics.TranslationApiProfiles
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import android.text.InputType
import java.util.Locale

/** The dark capsule controls used by the standalone Desktop Lyrics settings. */
internal object TcrrryLyricsSettingsUi {
    private const val RED = 0xFFFA2D48.toInt()
    private const val WHITE = 0xFFF7F7FA.toInt()
    private const val MUTED = 0xFFADB2C1.toInt()
    private const val DIM = 0xFF7F8798.toInt()
    private val SOURCES = listOf<String?>(null, "QQ音乐", "网易云音乐", "LRCLIB")

    fun render(activity: Activity, parent: LinearLayout, song: CurrentSongDetails?, refreshPage: () -> Unit) {
        val id = song?.appleMusicId ?: 0L
        val selected = CurrentLyricsSourceStatus.selectedSource(activity, id)
        val applied = CurrentLyricsSourceStatus.appliedSource(activity, id)
        val provider = applied?.takeIf { it.startsWith("desktop-lyrics:") }?.substringAfter(':') ?: selected
        val offset = provider?.let { CurrentLyricsSourceStatus.offsetMs(activity, id, it) } ?: 0
        parent.setBackgroundColor(0xFF090909.toInt())
        parent.setPadding(dp(activity, 18), dp(activity, 16), dp(activity, 18), dp(activity, 28))

        parent.addView(card(activity).apply {
            addView(title(activity, song?.title ?: "暂无播放歌曲", 19f))
            addView(label(activity, song?.artist.orEmpty(), 13f, MUTED))
            addView(chip(activity, CurrentLyricsSourceStatus.description(activity, id), selected = true).apply {
                setOnClickListener { refreshPage() }
            }, fullMargin(activity, 12))
        })

        parent.addView(card(activity).apply {
            addView(title(activity, "歌词源", 18f))
            addView(label(activity, "切换来源，或在下方重新匹配当前来源。", 12f, MUTED))
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(activity, 12), 0, 0)
                addView(chip(activity, selected ?: "自动匹配", selected = true).apply {
                    setOnClickListener { if (id > 0L) showSourcePicker(activity, id, selected, refreshPage) }
                }, LinearLayout.LayoutParams(0, dp(activity, 46), 1f))
                addView(chip(activity, "切换来源  ›").apply {
                    setOnClickListener { if (id > 0L) showSourcePicker(activity, id, selected, refreshPage) }
                }, LinearLayout.LayoutParams(dp(activity, 116), dp(activity, 46)).apply {
                    marginStart = dp(activity, 8)
                })
            })
            addView(action(activity, "重新匹配当前来源", emphasized = true) {
                if (!CurrentLyricsSourceStatus.excludeCurrentRecord(activity, id)) {
                    toast(activity, "当前来源还没有可跳过的匹配记录")
                } else {
                    if (CurrentLyricsSourceStatus.refresh(id)) toast(activity, "正在寻找下一个匹配版本")
                    else toast(activity, "请重新打开歌词页后生效")
                    refreshPage()
                }
            }, fullMargin(activity, 12))
            addView(action(activity, "恢复自动匹配") {
                if (id > 0L) {
                    CurrentLyricsSourceStatus.resetMatching(activity, id)
                    CurrentLyricsSourceStatus.refresh(id)
                    refreshPage()
                }
            }, fullMargin(activity, 8))
            addView(action(activity, "匹配历史与预览") { showHistory(activity, id, song, refreshPage) }, fullMargin(activity, 8))
        }, fullMargin(activity, 12))

        renderTranslationSettings(activity, parent, id, refreshPage)

        parent.addView(card(activity).apply {
            addView(LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(title(activity, "歌词偏移", 18f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val value = label(activity, formatOffset(offset), 13f, WHITE)
                addView(value)
                addView(View(activity), LinearLayout.LayoutParams(0, 1))
                val bar = SeekBar(activity).apply {
                    max = 100
                    progress = offset / 100 + 50
                    progressTintList = ColorStateList.valueOf(RED)
                    progressBackgroundTintList = ColorStateList.valueOf(0xFF39404F.toInt())
                    thumbTintList = ColorStateList.valueOf(WHITE)
                    isEnabled = id > 0L && provider in SOURCES
                    splitTrack = false
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                            if (fromUser) value.text = formatOffset((progress - 50) * 100)
                        }
                        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                        override fun onStopTrackingTouch(seekBar: SeekBar?) {
                            val source = provider ?: return
                            val next = ((seekBar?.progress ?: 50) - 50) * 100
                            CurrentLyricsSourceStatus.selectSource(activity, id, source)
                            CurrentLyricsSourceStatus.setOffsetMs(activity, id, source, next)
                            if (next != offset) CurrentLyricsSourceStatus.refresh(id)
                            refreshPage()
                        }
                    })
                }
                // Keep the value beside the title while the slider gets its own row.
                setTag(bar)
            })
            val slider = (getChildAt(childCount - 1) as LinearLayout).tag as SeekBar
            addView(slider, fullMargin(activity, 8))
            addView(LinearLayout(activity).apply {
                addView(label(activity, "延后 5 秒", 11f, DIM), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(label(activity, "提前 5 秒", 11f, DIM))
            })
            addView(label(activity, "每首歌、每个歌词源独立保存。正数让歌词更早显示。", 12f, MUTED), fullMargin(activity, 8))
            addView(action(activity, "重置当前来源偏移") {
                if (id > 0L && provider != null) {
                    CurrentLyricsSourceStatus.setOffsetMs(activity, id, provider, 0)
                    CurrentLyricsSourceStatus.refresh(id)
                    refreshPage()
                }
            }, fullMargin(activity, 10))
        }, fullMargin(activity, 12))

        parent.alpha = 0f
        parent.translationY = dp(activity, 8).toFloat()
        parent.animate().alpha(1f).translationY(0f).setDuration(220L).start()
        val initial = CurrentLyricsSourceStatus.description(activity, id)
        parent.postDelayed(object : Runnable {
            override fun run() {
                if (!parent.isAttachedToWindow) return
                if (CurrentLyricsSourceStatus.description(activity, id) != initial) refreshPage()
                else parent.postDelayed(this, 1_000L)
            }
        }, 1_000L)
    }

    private fun renderTranslationSettings(
        activity: Activity,
        parent: LinearLayout,
        id: Long,
        refreshPage: () -> Unit,
    ) {
        val prefs = activity.getSharedPreferences("supplement_translation", Context.MODE_PRIVATE)
        val mode = prefs.getString("mode", "off") ?: "off"
        parent.addView(card(activity).apply {
            addView(title(activity, "补充翻译", 18f))
            addView(label(activity, "平台译文优先，仅对缺少译文的句子补译。", 12f, MUTED), fullMargin(activity, 5))
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(activity, 12), 0, 0)
                listOf("off" to "关闭", "offline" to "离线机翻", "api" to "自定义 API").forEachIndexed { index, (value, caption) ->
                    addView(chip(activity, caption, selected = mode == value).apply {
                        setOnClickListener {
                            if (mode != value) {
                                prefs.edit().putString("mode", value).apply()
                                CurrentLyricsSourceStatus.refresh(id)
                                refreshPage()
                            }
                        }
                    }, LinearLayout.LayoutParams(0, dp(activity, 43), 1f).apply {
                        if (index > 0) marginStart = dp(activity, 5)
                    })
                }
            })
            if (mode == "offline") renderOfflineControls(activity, this, id)
            if (mode == "api") renderApiControls(activity, this, id, refreshPage)
            CurrentLyricsSourceStatus.translationStatus(activity, id).takeIf(String::isNotBlank)?.let { status ->
                addView(label(activity, status, 12f, MUTED), fullMargin(activity, 12))
            }
            addView(action(activity, "清理补充译文缓存") {
                val folder = java.io.File(activity.cacheDir, "translations")
                folder.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
                toast(activity, "补充译文缓存已清理")
                CurrentLyricsSourceStatus.refresh(id)
            }, fullMargin(activity, 10))
        }, fullMargin(activity, 12))
    }

    private fun renderApiControls(activity: Activity, card: LinearLayout, id: Long, refreshPage: () -> Unit) {
        val prefs = activity.getSharedPreferences("supplement_translation", Context.MODE_PRIVATE)
        val profile = TranslationApiProfiles.find(activity, prefs.getString("active_api_profile", null))
        card.addView(action(activity, "${profile.label}  ⌄") {
            showApiProfilePicker(activity, id, refreshPage)
        }, fullMargin(activity, 12))
        val endpoint = field(activity, "HTTPS 服务地址", prefs.getString(TranslationApiProfiles.endpointKey(profile.id), profile.defaultEndpoint).orEmpty())
        val model = field(activity, "模型名称", prefs.getString(TranslationApiProfiles.modelKey(profile.id), profile.defaultModel).orEmpty())
        val secret = SecretStorage(activity, profile.id)
        val key = field(activity, if (secret.read().isBlank()) "API Key" else "API Key 已保存，留空表示不更改", "").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        listOf(endpoint, model, key).forEach { card.addView(it, fullMargin(activity, 8)) }
        val status = label(activity, "", 12f, MUTED)
        card.addView(action(activity, "保存并验证连接", emphasized = true) {
            val address = endpoint.text.toString().trim()
            val modelName = model.text.toString().trim()
            if (!address.startsWith("https://") || modelName.isBlank()) {
                status.text = "请填写 HTTPS 地址和模型名称"
                return@action
            }
            val newKey = key.text.toString().trim()
            runCatching { if (newKey.isNotBlank()) secret.save(newKey) }
                .onFailure { status.text = "密钥保存失败：${it.message.orEmpty().take(80)}"; return@action }
            val savedKey = if (newKey.isNotBlank()) newKey else secret.read()
            if (savedKey.isBlank()) { status.text = "请填写 API Key"; return@action }
            prefs.edit().putString("active_api_profile", profile.id)
                .putString(TranslationApiProfiles.endpointKey(profile.id), address)
                .putString(TranslationApiProfiles.modelKey(profile.id), modelName).apply()
            key.setText("")
            status.text = "正在验证翻译服务…"
            Thread({
                val outcome = runCatching { SupplementTranslation(activity.applicationContext).testApi(address, modelName, savedKey) }
                activity.runOnUiThread {
                    if (!activity.isFinishing) {
                        status.text = outcome.fold(
                            onSuccess = { "连接成功 · 测试译文：$it" },
                            onFailure = { "连接失败：${it.message.orEmpty().take(150)}" },
                        )
                        if (outcome.isSuccess) CurrentLyricsSourceStatus.refresh(id)
                    }
                }
            }, "tcrrry-api-test").apply { isDaemon = true }.start()
        }, fullMargin(activity, 10))
        card.addView(action(activity, "删除当前 API Key") {
            secret.save("")
            key.setText("")
            status.text = "已删除当前服务的密钥"
        }, fullMargin(activity, 8))
        card.addView(action(activity, "管理当前 API 配置") {
            showApiProfileManagement(activity, profile.id, refreshPage)
        }, fullMargin(activity, 8))
        card.addView(status, fullMargin(activity, 8))
    }

    private fun showApiProfileManagement(activity: Activity, profileId: String, refreshPage: () -> Unit) {
        val profile = TranslationApiProfiles.find(activity, profileId)
        val body = card(activity)
        body.addView(title(activity, profile.label, 19f))
        val dialog = AlertDialog.Builder(activity).setView(body).create()
        body.addView(action(activity, "重命名") {
            dialog.dismiss()
            val input = field(activity, "服务名称", profile.label)
            AlertDialog.Builder(activity).setTitle("重命名 API 服务").setView(input)
                .setPositiveButton("保存") { _, _ ->
                    TranslationApiProfiles.rename(activity, profileId, input.text.toString())
                    refreshPage()
                }.setNegativeButton("取消", null).show()
        }, fullMargin(activity, 10))
        body.addView(action(activity, "删除此服务及其密钥") {
            dialog.dismiss()
            AlertDialog.Builder(activity).setTitle("删除 API 配置")
                .setMessage("将删除 ${profile.label} 的地址、模型和本机密钥。")
                .setPositiveButton("删除") { _, _ ->
                    TranslationApiProfiles.remove(activity, profileId)
                    refreshPage()
                }.setNegativeButton("取消", null).show()
        }, fullMargin(activity, 8))
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun showApiProfilePicker(activity: Activity, id: Long, refreshPage: () -> Unit) {
        val body = card(activity)
        body.addView(title(activity, "选择翻译 API", 19f))
        val prefs = activity.getSharedPreferences("supplement_translation", Context.MODE_PRIVATE)
        val dialog = AlertDialog.Builder(activity).setView(body).create()
        TranslationApiProfiles.all(activity).forEach { profile ->
            body.addView(action(activity, profile.label,
                emphasized = prefs.getString("active_api_profile", null) == profile.id) {
                prefs.edit().putString("active_api_profile", profile.id).apply()
                dialog.dismiss()
                refreshPage()
            }, fullMargin(activity, 8))
        }
        body.addView(action(activity, "＋ 添加自定义 API") {
            dialog.dismiss()
            val input = field(activity, "服务名称")
            AlertDialog.Builder(activity).setTitle("添加 API 服务").setView(input)
                .setPositiveButton("添加") { _, _ ->
                    val added = TranslationApiProfiles.add(activity, input.text.toString())
                    prefs.edit().putString("active_api_profile", added.id).apply()
                    refreshPage()
                }.setNegativeButton("取消", null).show()
        }, fullMargin(activity, 10))
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun renderOfflineControls(activity: Activity, card: LinearLayout, id: Long) {
        runCatching { EmbeddedMlKit.initialize(activity) }
            .onFailure { card.addView(label(activity, "离线模型初始化失败：${it.message.orEmpty().take(100)}", 12f, MUTED)) }
        card.addView(label(activity,
            "按文字判断日语、韩语等语言；其他语言可手动指定。请下载源语言和中文模型。", 12f, MUTED), fullMargin(activity, 12))
        val translationPrefs = activity.getSharedPreferences("supplement_translation", Context.MODE_PRIVATE)
        val selected = translationPrefs.getString("offline_source_language", "auto").orEmpty()
        val selectedName = if (selected == "auto") "自动判断" else
            Locale.forLanguageTag(selected).getDisplayLanguage(Locale.SIMPLIFIED_CHINESE)
        card.addView(action(activity, "歌词原语言：$selectedName") {
            val codes = listOf("auto") + TranslateLanguage.getAllLanguages().sorted()
            val names = codes.map { code -> if (code == "auto") "自动判断" else
                "${Locale.forLanguageTag(code).getDisplayLanguage(Locale.SIMPLIFIED_CHINESE)} · $code" }
            AlertDialog.Builder(activity).setTitle("选择歌词原语言")
                .setItems(names.toTypedArray()) { _, which ->
                    translationPrefs.edit().putString("offline_source_language", codes[which]).apply()
                    CurrentLyricsSourceStatus.refresh(id)
                    renderOfflineControls(activity, card.apply { removeAllViews() }, id)
                }.show()
        }, fullMargin(activity, 8))
        val progress = ProgressBar(activity).apply {
            visibility = View.GONE
            indeterminateTintList = ColorStateList.valueOf(RED)
        }
        card.addView(progress, fullMargin(activity, 8))
        val modelList = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val search = field(activity, "搜索语言，例如日语、韩语、法语")
        card.addView(search, fullMargin(activity, 10))
        card.addView(action(activity, "刷新语言包状态") { refreshModels(activity, id, modelList, search, progress) }, fullMargin(activity, 8))
        val testStatus = label(activity, "", 12f, MUTED)
        card.addView(action(activity, "验证离线翻译") {
            runCatching { EmbeddedMlKit.initialize(activity) }
                .onFailure { testStatus.text = "初始化失败：${it.message.orEmpty().take(120)}"; return@action }
            testStatus.text = "正在验证翻译模型…"
            val translator = Translation.getClient(TranslatorOptions.Builder()
                .setSourceLanguage("en").setTargetLanguage("zh").build())
            translator.translate("Hello, world")
                .addOnCompleteListener { translated ->
                    translator.close()
                    testStatus.text = if (translated.isSuccessful) "离线翻译可用：${translated.result}"
                        else "离线翻译失败：${translated.exception?.message.orEmpty().take(160)}"
                }
        }, fullMargin(activity, 8))
        card.addView(testStatus, fullMargin(activity, 8))
        card.addView(modelList, fullMargin(activity, 8))
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                refreshModels(activity, id, modelList, search, progress)
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        refreshModels(activity, id, modelList, search, progress)
    }

    private fun refreshModels(
        activity: Activity, id: Long, list: LinearLayout, search: EditText, progress: ProgressBar,
    ) {
        val manager = RemoteModelManager.getInstance()
        progress.visibility = View.VISIBLE
        manager.getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { installed ->
                if (activity.isFinishing) return@addOnSuccessListener
                progress.visibility = View.GONE
                list.removeAllViews()
                val ready = installed.map { it.language }.toSet()
                val codes = listOf("en", "ja", "ko", "zh") + TranslateLanguage.getAllLanguages()
                    .filterNot { it in setOf("en", "ja", "ko", "zh") }.sorted()
                val query = search.text.toString().trim()
                codes.filter { code ->
                    query.isNotBlank() && (code.contains(query, true) ||
                        Locale.forLanguageTag(code).getDisplayLanguage(Locale.SIMPLIFIED_CHINESE).contains(query, true)) ||
                        query.isBlank() && code in setOf("en", "ja", "ko", "zh")
                }.forEach { code ->
                    val needed = setOf(code, "zh").filter { it != "en" }
                    val installedNow = ready.containsAll(needed)
                    val name = Locale.forLanguageTag(code).getDisplayLanguage(Locale.SIMPLIFIED_CHINESE)
                    list.addView(action(activity, "$name · $code  ${if (installedNow) "已下载" else "下载"}") {
                        if (installedNow) {
                            val removable = if (code == "en") "zh" else code
                            showModelDelete(activity, id, removable) { refreshModels(activity, id, list, search, progress) }
                        } else showModelDownload(activity, id, needed) { refreshModels(activity, id, list, search, progress) }
                    }, fullMargin(activity, 7))
                }
            }.addOnFailureListener {
                progress.visibility = View.GONE
                toast(activity, "无法读取离线语言包状态")
            }
    }

    private fun showModelDownload(activity: Activity, id: Long, codes: List<String>, done: () -> Unit) {
        AlertDialog.Builder(activity).setTitle("下载离线语言包")
            .setMessage("将下载 ${codes.joinToString("、") { Locale.forLanguageTag(it).getDisplayLanguage(Locale.SIMPLIFIED_CHINESE) }} 模型。")
            .setPositiveButton("允许当前网络") { _, _ -> downloadModels(activity, id, codes, false, done) }
            .setNeutralButton("仅 Wi-Fi") { _, _ -> downloadModels(activity, id, codes, true, done) }
            .setNegativeButton("取消", null).show()
    }

    private fun downloadModels(activity: Activity, id: Long, codes: List<String>, wifiOnly: Boolean, done: () -> Unit) {
        toast(activity, "语言包下载已开始")
        val conditions = DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()
        val manager = RemoteModelManager.getInstance()
        val tasks = codes.map { manager.download(TranslateRemoteModel.Builder(it).build(), conditions) }
        com.google.android.gms.tasks.Tasks.whenAll(tasks).addOnCompleteListener { result ->
            if (result.isSuccessful) {
                toast(activity, "语言包已就绪")
                CurrentLyricsSourceStatus.refresh(id)
            } else {
                val details = tasks.mapIndexedNotNull { index, task ->
                    task.exception?.let { error -> "${codes[index]}：${error.javaClass.simpleName} ${error.message.orEmpty().take(180)}" }
                }.joinToString("\n").ifBlank { result.exception?.message.orEmpty() }
                android.util.Log.e("TcrrryLyrics", "offline model download failed: $details", result.exception)
                AlertDialog.Builder(activity).setTitle("语言包下载失败")
                    .setMessage(details.ifBlank { "下载失败，原因未知" }).setPositiveButton("知道了", null).show()
            }
            done()
        }
    }

    private fun showModelDelete(activity: Activity, id: Long, code: String, done: () -> Unit) {
        AlertDialog.Builder(activity).setTitle("删除离线语言包")
            .setMessage("删除 ${Locale.forLanguageTag(code).getDisplayLanguage(Locale.SIMPLIFIED_CHINESE)} 模型？")
            .setPositiveButton("删除") { _, _ ->
                RemoteModelManager.getInstance().deleteDownloadedModel(TranslateRemoteModel.Builder(code).build())
                    .addOnCompleteListener {
                        toast(activity, if (it.isSuccessful) "语言包已删除" else "删除失败")
                        if (it.isSuccessful) CurrentLyricsSourceStatus.refresh(id)
                        done()
                    }
            }.setNegativeButton("取消", null).show()
    }

    private fun field(activity: Activity, hint: String, value: String = "") = EditText(activity).apply {
        this.hint = hint
        setText(value)
        setTextColor(WHITE)
        setHintTextColor(DIM)
        textSize = 14f
        setSingleLine(true)
        setPadding(dp(activity, 14), dp(activity, 9), dp(activity, 14), dp(activity, 9))
        background = shape(activity, 0xFF27272B.toInt(), 18, 0x1FFFFFFF)
    }

    private fun showSourcePicker(activity: Activity, id: Long, selected: String?, refreshPage: () -> Unit) {
        val body = card(activity)
        body.addView(title(activity, "选择歌词源", 20f))
        body.addView(label(activity, "歌词仍由 Apple Music 播放页显示", 12f, MUTED))
        val dialog = AlertDialog.Builder(activity).setView(body).create()
        SOURCES.forEach { source ->
            val name = source ?: "自动匹配"
            body.addView(action(activity, name, emphasized = source == selected) {
                CurrentLyricsSourceStatus.selectSource(activity, id, source)
                val started = CurrentLyricsSourceStatus.refresh(id)
                dialog.dismiss()
                toast(activity, if (started) "正在从$name 查找歌词" else "请重新打开歌词页后生效")
                refreshPage()
            }, fullMargin(activity, 9))
        }
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun showHistory(activity: Activity, id: Long, song: CurrentSongDetails?, refreshPage: () -> Unit) {
        val history = TcrrryLyricsHistory.entries(activity, id).asReversed()
        if (history.isEmpty()) { toast(activity, "还没有匹配历史"); return }
        val body = card(activity).apply {
            addView(title(activity, "匹配历史", 20f))
            addView(label(activity, "${song?.title.orEmpty()} · 选择版本可查看歌词预览", 12f, MUTED))
        }
        val scroll = android.widget.ScrollView(activity).apply { addView(body) }
        val dialog = AlertDialog.Builder(activity).setView(scroll).create()
        history.forEach { result ->
            body.addView(action(activity, "${result.source} · ${result.title.ifBlank { song?.title.orEmpty() }}") {
                dialog.dismiss()
                val preview = result.lyrics.lineSequence().take(16).joinToString("\n")
                val detail = card(activity).apply {
                    addView(title(activity, result.title.ifBlank { song?.title.orEmpty() }, 19f))
                    addView(label(activity,
                        "${result.artist} · ${result.durationMs / 1000} 秒 · " +
                            (if (result.wordLyrics.isNotBlank()) "逐字" else "逐行") +
                            (if (result.translatedLyrics.isNotBlank()) " · 含译文" else ""), 12f, MUTED))
                    addView(label(activity, preview, 13f, WHITE), fullMargin(activity, 12))
                }
                val detailDialog = AlertDialog.Builder(activity).setView(android.widget.ScrollView(activity).apply { addView(detail) }).create()
                detail.addView(action(activity, "选用此版本", emphasized = true) {
                    TcrrryLyricsHistory.select(activity, id, result)
                    CurrentLyricsSourceStatus.refresh(id)
                    detailDialog.dismiss()
                    refreshPage()
                }, fullMargin(activity, 12))
                detailDialog.show()
                detailDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            }, fullMargin(activity, 8))
        }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun card(activity: Activity) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(activity, 18), dp(activity, 18), dp(activity, 18), dp(activity, 18))
        background = shape(activity, 0xFF1B1B1F.toInt(), 28, 0x24FFFFFF)
    }

    private fun title(activity: Activity, value: String, size: Float) = label(activity, value, size, WHITE).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun label(activity: Activity, value: String, size: Float, color: Int) = TextView(activity).apply {
        text = value
        textSize = size
        setTextColor(color)
        setSingleLine(false)
    }

    private fun chip(activity: Activity, value: String, selected: Boolean = false) = TextView(activity).apply {
        text = value
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(if (selected) RED else WHITE)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(activity, 12), dp(activity, 8), dp(activity, 12), dp(activity, 8))
        background = shape(activity, if (selected) 0xFF302026.toInt() else 0xFF27272B.toInt(), 18, if (selected) 0x66FA2D48 else 0x1FFFFFFF)
    }

    private fun action(activity: Activity, value: String, emphasized: Boolean = false, click: () -> Unit) =
        chip(activity, value, emphasized).apply {
            minimumHeight = dp(activity, 46)
            if (emphasized) { setTextColor(WHITE); background = shape(activity, RED, 18, RED) }
            isClickable = true
            isFocusable = true
            setOnClickListener { click() }
        }

    private fun shape(activity: Activity, color: Int, radius: Int, stroke: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(activity, radius).toFloat()
        setStroke(dp(activity, 1), stroke)
    }

    private fun fullMargin(activity: Activity, top: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(activity, top) }

    private fun formatOffset(value: Int): String = String.format(Locale.US, "%+.1fs", value / 1000f)
    private fun dp(activity: Activity, value: Int) = (activity.resources.displayMetrics.density * value + 0.5f).toInt()
    private fun toast(activity: Activity, message: String) = Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
}
