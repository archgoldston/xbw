package com.xbw.tv.ui.search

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.xbw.tv.R
import com.xbw.tv.XbwApplication
import com.xbw.tv.data.net.HttpFetcher
import com.xbw.tv.data.net.YikmParser
import com.xbw.tv.data.repo.GameRepository
import com.xbw.tv.databinding.ActivitySearchBinding
import com.xbw.tv.ui.common.Nav
import com.xbw.tv.ui.lobby.GameCardAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 搜索页：GET /search?name=关键词（实测无分页，一次返回全部结果）。
 *
 * TV 输入适配：
 *  - 进页面焦点落在输入框 → 直接弹系统 IME（蓝牙键盘/遥控器都能输入）；
 *  - IME 的"搜索"动作与「搜索」按钮等效；
 *  - 结果复用 GameCardAdapter，A 键直接进游戏。
 */
class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding
    private lateinit var adapter: GameCardAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = GameCardAdapter(onClick = { item -> Nav.openGame(this, item) })
        binding.rvResults.layoutManager = GridLayoutManager(this, spanCount())
        // 同大厅：item 动画与焦点动效冲突会崩，TV 网格直接关掉
        binding.rvResults.itemAnimator = null
        binding.rvResults.adapter = adapter

        binding.etKeyword.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER ||
                event?.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                // 实体键盘的按下/抬起都消费，但只提交一次；IME 动作通常没有 KeyEvent。
                if (event == null ||
                    (event.action == KeyEvent.ACTION_UP && !event.isCanceled)
                ) doSearch()
                true
            } else false
        }
        // 保留手柄 B 返回；其他按键交给 EditText，保留选字、光标及复制粘贴快捷键。
        binding.etKeyword.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BUTTON_B) {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) finish()
                true
            } else false
        }
        binding.btnDoSearch.setOnClickListener { doSearch() }

        // 使用正常可编辑的文本框，不清空 keyListener，也不接管长按菜单。
        binding.etKeyword.showSoftInputOnFocus = true
        binding.etKeyword.setOnClickListener { showSystemKeyboard() }
        binding.etKeyword.requestFocus()
        if (savedInstanceState == null && !intent.hasExtra("extra_keyword")) {
            showSystemKeyboard()
        }

        // 自动化/调试入口：am start --es extra_keyword xxx 直接出结果，绕开输入法
        applySeedKeyword()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applySeedKeyword()
    }

    /** 自动化/调试入口：am start --es extra_keyword xxx 直接出结果，绕开 TV 输入法 */
    private fun applySeedKeyword() {
        intent?.getStringExtra("extra_keyword")?.let {
            binding.etKeyword.setText(it)
            doSearch()
        }
    }

    private fun showSystemKeyboard() {
        if (binding.etKeyword.requestFocus()) {
            // AndroidX 会处理初次进入页面时窗口尚未获焦的情况，无须延时反复抢焦点。
            WindowCompat.getInsetsController(window, binding.etKeyword)
                .show(WindowInsetsCompat.Type.ime())
        }
    }

    private fun spanCount(): Int {
        val width = resources.displayMetrics.widthPixels
        val cardW = (148 + 16) * resources.displayMetrics.density
        return (width / cardW).toInt().coerceIn(4, 6)
    }

    private fun doSearch() {
        val kw = binding.etKeyword.text.toString().trim()
        if (kw.isEmpty()) return
        WindowCompat.getInsetsController(window, binding.etKeyword)
            .hide(WindowInsetsCompat.Type.ime())
        binding.progress.visibility = android.view.View.VISIBLE
        binding.emptyText.visibility = android.view.View.GONE
        binding.statusLine.text = getString(R.string.search_loading)

        lifecycleScope.launch {
            val repo = XbwApplication.repository(application)
            // 先出结果：官方站 + 本地已缓存的插件 gamelist（不联网，秒回）
            runCatching { repo.searchEx(kw) }
                .onSuccess { render(it, kw) }
                .onFailure { e -> renderError(e) }
            // 后台补拉还没缓存过的第三方源（联网，不阻塞本次结果）；补到了就静默重搜一次，
            // 这样"新加的源 / 从没进过的分类"在第一次搜索后也能被搜到
            launch(Dispatchers.IO) {
                val warmed = runCatching { repo.warmPluginSources(application) }.getOrDefault(false)
                if (!warmed) return@launch
                // 用户已经改了关键词就别覆盖当前结果
                val stillSame = withContext(Dispatchers.Main) {
                    binding.etKeyword.text.toString().trim() == kw
                }
                if (stillSame) {
                    runCatching { repo.searchEx(kw) }
                        .onSuccess { r ->
                            if (r.items.isNotEmpty()) withContext(Dispatchers.Main) { render(r, kw) }
                        }
                }
            }
        }
    }

    private fun render(r: GameRepository.LoadResult, kw: String) {
        binding.progress.visibility = android.view.View.GONE
        adapter.submitList(r.items)
        if (r.items.isEmpty()) {
            binding.emptyText.visibility = android.view.View.VISIBLE
            binding.statusLine.text = ""
        } else {
            binding.statusLine.text = getString(R.string.search_result_fmt, r.items.size, kw)
            // 结果出来后焦点进列表第一项
            binding.rvResults.post {
                binding.rvResults.layoutManager?.findViewByPosition(0)?.requestFocus()
            }
        }
    }

    private fun renderError(e: Throwable) {
        binding.progress.visibility = android.view.View.GONE
        val msg = when (e) {
            is HttpFetcher.FetchException -> getString(R.string.error_network) + "：" + e.message
            is YikmParser.ParseException -> getString(R.string.error_site_changed) + "：" + e.message
            else -> e.message ?: getString(R.string.error_network)
        }
        binding.statusLine.text = msg
        binding.emptyText.visibility = android.view.View.VISIBLE
    }

}
